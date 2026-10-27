#include "PipeServer.h"

#include <sddl.h>

#include "PipeProtocol.h"

namespace wake {

// NTSTATUS values we invent when Windows never got to answer.
static constexpr uint32_t kStatusTimeout = 0xC00000B5;      // STATUS_IO_TIMEOUT
static constexpr uint32_t kStatusBadRequest = 0xC000000D;   // STATUS_INVALID_PARAMETER
static constexpr DWORD kResultWaitMs = 20000;               // cap on waiting for ReportResult

PipeServer::~PipeServer() {
    Stop();
}

bool PipeServer::Stopping() const {
    return WaitForSingleObject(stopEvent_, 0) == WAIT_OBJECT_0;
}

HRESULT PipeServer::Start(IUnlockSink* sink, const std::wstring& lockedUserSid) {
    Stop();
    sink_ = sink;

    // Owner SYSTEM; DACL: SYSTEM full control, the locked user read+write. No one else can open it.
    std::wstring sddl = L"O:SYD:(A;;GA;;;SY)(A;;GRGW;;;" + lockedUserSid + L")";
    SECURITY_ATTRIBUTES sa{};
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = FALSE;
    if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1,
                                                              &sa.lpSecurityDescriptor, nullptr)) {
        return HRESULT_FROM_WIN32(GetLastError());
    }

    pipe_ = CreateNamedPipeW(L"\\\\.\\pipe\\wake-unlock", PIPE_ACCESS_DUPLEX | FILE_FLAG_OVERLAPPED,
                             PIPE_TYPE_MESSAGE | PIPE_READMODE_MESSAGE | PIPE_REJECT_REMOTE_CLIENTS,
                             1, 64, 4096, 0, &sa);
    DWORD createErr = GetLastError();
    LocalFree(sa.lpSecurityDescriptor);
    if (pipe_ == INVALID_HANDLE_VALUE) {
        return HRESULT_FROM_WIN32(createErr);
    }

    ioEvent_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    stopEvent_ = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    resultEvent_ = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!ioEvent_ || !stopEvent_ || !resultEvent_) {
        HRESULT hr = HRESULT_FROM_WIN32(GetLastError());
        Stop();
        return hr;
    }

    thread_ = std::thread(&PipeServer::ThreadMain, this);
    return S_OK;
}

void PipeServer::Stop() {
    if (stopEvent_) SetEvent(stopEvent_);
    if (pipe_ != INVALID_HANDLE_VALUE) CancelIoEx(pipe_, nullptr);
    if (thread_.joinable()) thread_.join();

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
    if (resultEvent_) {
        CloseHandle(resultEvent_);
        resultEvent_ = nullptr;
    }
    sink_ = nullptr;
}

void PipeServer::PostResult(uint32_t status, uint32_t substatus) {
    {
        std::lock_guard<std::mutex> lock(resultMutex_);
        status_ = status;
        substatus_ = substatus;
    }
    if (resultEvent_) SetEvent(resultEvent_);
}

void PipeServer::ThreadMain() {
    // This thread lives in LogonUI's process; no exception may escape it.
    try {
        RunLoop();
    } catch (...) {
    }
}

void PipeServer::RunLoop() {
    while (!Stopping()) {
        OVERLAPPED ov{};
        ov.hEvent = ioEvent_;
        ResetEvent(ioEvent_);

        BOOL connected = ConnectNamedPipe(pipe_, &ov);
        DWORD err = GetLastError();
        if (!connected && err == ERROR_IO_PENDING) {
            HANDLE waits[2] = {ioEvent_, stopEvent_};
            if (WaitForMultipleObjects(2, waits, FALSE, INFINITE) != WAIT_OBJECT_0) break;
            DWORD bytes = 0;
            if (!GetOverlappedResult(pipe_, &ov, &bytes, FALSE)) {
                DisconnectNamedPipe(pipe_);
                continue;
            }
        } else if (!connected && err != ERROR_PIPE_CONNECTED) {
            DisconnectNamedPipe(pipe_);
            continue;
        }

        ServeOneClient();
        DisconnectNamedPipe(pipe_);
    }
}

void PipeServer::ServeOneClient() {
    uint8_t buf[8 + kMaxPasswordBytes];
    OVERLAPPED ov{};
    ov.hEvent = ioEvent_;
    ResetEvent(ioEvent_);

    DWORD read = 0;
    BOOL ok = ReadFile(pipe_, buf, sizeof(buf), &read, &ov);
    if (!ok && GetLastError() == ERROR_IO_PENDING) {
        HANDLE waits[2] = {ioEvent_, stopEvent_};
        if (WaitForMultipleObjects(2, waits, FALSE, INFINITE) != WAIT_OBJECT_0) return;
        if (!GetOverlappedResult(pipe_, &ov, &read, FALSE)) return;
    } else if (!ok) {
        return;
    }

    auto parsed = ParseRequest(buf, read);
    SecureZeroMemory(buf, sizeof(buf));

    uint32_t status = kStatusBadRequest;
    uint32_t substatus = 0;
    if (parsed) {
        status = kStatusTimeout;
        ResetEvent(resultEvent_);
        if (sink_ && sink_->OnUnlockRequest(*parsed)) {
            HANDLE waits[2] = {resultEvent_, stopEvent_};
            DWORD w = WaitForMultipleObjects(2, waits, FALSE, kResultWaitMs);
            if (w == WAIT_OBJECT_0) {
                std::lock_guard<std::mutex> lock(resultMutex_);
                status = status_;
                substatus = substatus_;
            } else if (w == WAIT_OBJECT_0 + 1) {
                // Stopping: do not touch the credential, just leave.
                if (!parsed->empty())
                    SecureZeroMemory(&(*parsed)[0], parsed->size() * sizeof(wchar_t));
                return;
            }
            if (sink_) sink_->OnUnlockDone();
        }
        if (!parsed->empty())
            SecureZeroMemory(&(*parsed)[0], parsed->size() * sizeof(wchar_t));
    }

    auto reply = FormatReply(status, substatus);
    OVERLAPPED wov{};
    wov.hEvent = ioEvent_;
    ResetEvent(ioEvent_);
    DWORD written = 0;
    if (!WriteFile(pipe_, reply.data(), static_cast<DWORD>(reply.size()), &written, &wov) &&
        GetLastError() == ERROR_IO_PENDING) {
        HANDLE waits[2] = {ioEvent_, stopEvent_};
        if (WaitForMultipleObjects(2, waits, FALSE, 5000) == WAIT_OBJECT_0)
            GetOverlappedResult(pipe_, &wov, &written, FALSE);
        else
            CancelIoEx(pipe_, &wov);
    }
    FlushFileBuffers(pipe_);
}

}  // namespace wake
