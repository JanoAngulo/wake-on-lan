#include "PipeServer.h"

#include <sddl.h>

#include <optional>

namespace wake {

// NTSTATUS values we invent when Windows never got to answer.
static constexpr uint32_t kStatusTimeout = 0xC00000B5;     // STATUS_IO_TIMEOUT
static constexpr uint32_t kStatusBadRequest = 0xC000000D;  // STATUS_INVALID_PARAMETER
// An 8-byte write into a 4096-byte pipe buffer completes at once; this only guards a broken client.
static constexpr DWORD kWriteWaitMs = 1000;

static void Wipe(std::wstring& s) {
    if (!s.empty()) SecureZeroMemory(&s[0], s.size() * sizeof(wchar_t));
    s.clear();
}

PipeServer::PipeServer(PipeServerOptions options) : options_(options) {}

PipeServer::~PipeServer() {
    Stop();
}

bool PipeServer::Stopping() const {
    return WaitForSingleObject(stopEvent_, 0) == WAIT_OBJECT_0;
}

HRESULT PipeServer::Start(IUnlockSink* sink, const std::wstring& lockedUserSid) {
    Stop();
    if (!sink || lockedUserSid.empty()) return E_INVALIDARG;
    sink_ = sink;

    ioEvent_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    stopEvent_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    {
        std::lock_guard<std::mutex> lock(resultMutex_);
        resultEvent_ = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    }
    if (!ioEvent_ || !stopEvent_ || !resultEvent_) {
        HRESULT hr = HRESULT_FROM_WIN32(GetLastError());
        Stop();
        return hr;
    }

    // Owner SYSTEM; DACL: SYSTEM full control, the locked user read+write. No one else can open it.
    std::wstring sddl = options_.systemOwner ? L"O:SY" : L"";
    sddl += L"D:(A;;GA;;;SY)(A;;GRGW;;;" + lockedUserSid + L")";
    SECURITY_ATTRIBUTES sa{};
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = FALSE;
    if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1,
                                                              &sa.lpSecurityDescriptor, nullptr)) {
        HRESULT hr = HRESULT_FROM_WIN32(GetLastError());
        Stop();
        return hr;
    }

    // FILE_FLAG_FIRST_PIPE_INSTANCE: if anyone already created this name, fail cleanly instead of
    // joining their pipe object.
    pipe_ = CreateNamedPipeW(options_.name,
                             PIPE_ACCESS_DUPLEX | FILE_FLAG_OVERLAPPED | FILE_FLAG_FIRST_PIPE_INSTANCE,
                             PIPE_TYPE_MESSAGE | PIPE_READMODE_MESSAGE | PIPE_REJECT_REMOTE_CLIENTS,
                             1, 64, 4096, 0, &sa);
    DWORD createErr = GetLastError();
    LocalFree(sa.lpSecurityDescriptor);
    if (pipe_ == INVALID_HANDLE_VALUE) {
        Stop();
        return HRESULT_FROM_WIN32(createErr);
    }

    thread_ = std::thread(&PipeServer::ThreadMain, this);
    return S_OK;
}

void PipeServer::Stop() {
    // The thread notices stopEvent_ in every wait, cancels its own pending I/O and waits for the
    // cancellation, so join() is bounded. No CancelIoEx here: it could cancel the "provider closed"
    // reply the thread writes on its way out.
    if (stopEvent_) SetEvent(stopEvent_);
    if (thread_.joinable()) thread_.join();

    // Closing (not disconnecting) the instance keeps an unread reply readable by the client.
    if (pipe_ != INVALID_HANDLE_VALUE) {
        CloseHandle(pipe_);
        pipe_ = INVALID_HANDLE_VALUE;
    }
    if (ioEvent_) {
        CloseHandle(ioEvent_);
        ioEvent_ = nullptr;
    }
    if (stopEvent_) {
        CloseHandle(stopEvent_);
        stopEvent_ = nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(resultMutex_);
        if (resultEvent_) {
            CloseHandle(resultEvent_);
            resultEvent_ = nullptr;
        }
        resultPosted_ = false;
        submitted_ = false;
    }
    requestPending_ = false;
    SecureZeroMemory(readBuf_, sizeof(readBuf_));
    SecureZeroMemory(drainBuf_, sizeof(drainBuf_));
    sink_ = nullptr;
}

void PipeServer::PostResult(uint32_t status, uint32_t substatus) {
    std::lock_guard<std::mutex> lock(resultMutex_);
    status_ = status;
    substatus_ = substatus;
    resultPosted_ = true;
    if (resultEvent_) SetEvent(resultEvent_);
}

void PipeServer::MarkSubmitted() {
    std::lock_guard<std::mutex> lock(resultMutex_);
    submitted_ = true;
}

void PipeServer::ThreadMain() {
    // This thread lives in LogonUI's process; no exception may escape it.
    try {
        RunLoop();
    } catch (...) {
    }
}

void PipeServer::PrepareIo() {
    ZeroMemory(&ov_, sizeof(ov_));
    ov_.hEvent = ioEvent_;
    ResetEvent(ioEvent_);
}

// Finishes the overlapped operation just started on ov_. On return nothing is pending on ov_ or its
// buffer: a stop or timeout cancels the operation and waits for the cancellation to complete.
PipeServer::Io PipeServer::Await(BOOL completedNow, DWORD startError, DWORD* bytes, DWORD timeoutMs,
                                 bool stopAware) {
    *bytes = 0;
    lastIoError_ = 0;
    if (completedNow) {
        GetOverlappedResult(pipe_, &ov_, bytes, FALSE);
        return Io::Done;
    }
    if (startError != ERROR_IO_PENDING) {
        lastIoError_ = startError;
        return Io::Failed;
    }

    HANDLE waits[2] = {ioEvent_, stopEvent_};
    DWORD w = WaitForMultipleObjects(stopAware ? 2 : 1, waits, FALSE, timeoutMs);
    if (w == WAIT_OBJECT_0) {
        if (GetOverlappedResult(pipe_, &ov_, bytes, FALSE)) return Io::Done;
        lastIoError_ = GetLastError();
        return Io::Failed;
    }

    CancelIoEx(pipe_, &ov_);
    GetOverlappedResult(pipe_, &ov_, bytes, TRUE);  // returns once the cancelled IRP is complete
    return Io::Interrupted;
}

void PipeServer::RunLoop() {
    while (!Stopping()) {
        PrepareIo();
        BOOL ok = ConnectNamedPipe(pipe_, &ov_);
        DWORD err = ok ? 0 : GetLastError();
        bool connected = false;
        if (!ok && err == ERROR_PIPE_CONNECTED) {
            connected = true;  // a client connected between CreateNamedPipe/Disconnect and now
        } else {
            DWORD bytes = 0;
            Io r = Await(ok, err, &bytes, INFINITE, true);
            if (r == Io::Interrupted) break;
            connected = (r == Io::Done);
        }

        if (connected && !Stopping()) ServeOneClient();
        // On stop, leave the instance for Stop() to close: closing keeps a just-written reply
        // readable, disconnecting would discard it.
        if (Stopping()) break;
        DisconnectNamedPipe(pipe_);
        if (!connected) WaitForSingleObject(stopEvent_, 100);  // don't spin on a failing connect
    }
}

void PipeServer::ServeOneClient() {
    PrepareIo();
    DWORD read = 0;
    BOOL ok = ReadFile(pipe_, readBuf_, sizeof(readBuf_), &read, &ov_);
    Io r = Await(ok, ok ? 0 : GetLastError(), &read, options_.requestWaitMs, true);

    std::optional<std::wstring> parsed;
    if (r == Io::Done) parsed = ParseRequest(readBuf_, read);
    SecureZeroMemory(readBuf_, sizeof(readBuf_));
    // Interrupted: stop, or a client that connected and said nothing. Failed: the client went away.
    // ERROR_MORE_DATA (a message larger than any valid request) is answered as a bad request.
    if (r == Io::Interrupted) return;
    if (r == Io::Failed && lastIoError_ != ERROR_MORE_DATA) return;

    uint32_t status = kStatusBadRequest;
    uint32_t substatus = 0;
    bool stopping = false;
    if (parsed) {
        status = kStatusTimeout;
        {
            std::lock_guard<std::mutex> lock(resultMutex_);
            resultPosted_ = false;
            submitted_ = false;
            ResetEvent(resultEvent_);
        }
        requestPending_ = true;
        bool accepted = sink_ && sink_->OnUnlockRequest(*parsed);
        Wipe(*parsed);

        if (accepted) {
            HANDLE waits[2] = {resultEvent_, stopEvent_};
            DWORD w = WaitForMultipleObjects(2, waits, FALSE, options_.resultWaitMs);
            if (w == WAIT_OBJECT_0) {
                std::lock_guard<std::mutex> lock(resultMutex_);
                status = status_;
                substatus = substatus_;
            } else if (w == WAIT_OBJECT_0 + 1) {
                // LogonUI is closing the provider (UnAdvise / release). Do not touch the credential;
                // the provider clears it. If Windows already has the password, tell the agent so it
                // can check whether the lock screen went away; otherwise just close.
                bool submitted;
                {
                    std::lock_guard<std::mutex> lock(resultMutex_);
                    submitted = submitted_;
                }
                requestPending_ = false;
                if (!submitted) return;
                status = kStatusProviderClosed;
                substatus = 0;
                stopping = true;
            }
            // Else timed out (no ReportResult within resultWaitMs): reply STATUS_IO_TIMEOUT below.
            if (!stopping) {
                requestPending_ = false;
                if (sink_) sink_->OnUnlockDone();
            }
        } else {
            requestPending_ = false;  // not ready to serve: reply STATUS_IO_TIMEOUT
        }
    }

    // While stopping, skip the wait: Stop() closes (not disconnects) the instance, and the client
    // can still read the reply from a closed pipe.
    if (WriteReply(status, substatus, stopping) && !stopping) WaitForClientToClose();
}

bool PipeServer::WriteReply(uint32_t status, uint32_t substatus, bool stopping) {
    auto reply = FormatReply(status, substatus);
    memcpy(writeBuf_, reply.data(), sizeof(writeBuf_));
    PrepareIo();
    DWORD written = 0;
    BOOL ok = WriteFile(pipe_, writeBuf_, static_cast<DWORD>(sizeof(writeBuf_)), &written, &ov_);
    // While stopping, stopEvent_ is already set, so this wait only has its own short cap.
    Io r = Await(ok, ok ? 0 : GetLastError(), &written, kWriteWaitMs, !stopping);
    return r == Io::Done;
}

// Replaces FlushFileBuffers, which can block forever on a client that never reads. The agent reads
// the reply and closes; our pending read then fails with ERROR_BROKEN_PIPE. Waits at most
// drainWaitMs and gives up at once on stop (RunLoop then leaves the instance for Stop() to close,
// which keeps the reply readable), so DisconnectNamedPipe never discards a reply the client is
// about to read.
void PipeServer::WaitForClientToClose() {
    PrepareIo();
    DWORD n = 0;
    BOOL ok = ReadFile(pipe_, drainBuf_, sizeof(drainBuf_), &n, &ov_);
    Await(ok, ok ? 0 : GetLastError(), &n, options_.drainWaitMs, true);
    SecureZeroMemory(drainBuf_, sizeof(drainBuf_));
}

}  // namespace wake
