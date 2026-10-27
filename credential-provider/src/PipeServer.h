#pragma once

#include <windows.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

#include "PipeProtocol.h"

namespace wake {

// Receives a password from the pipe thread and hands it to the credential.
// Implemented by Provider. Both methods run on the pipe thread, not LogonUI's apartment thread.
struct IUnlockSink {
    // Store the password, mark it pending and trigger re-enumeration. False if not ready to serve.
    virtual bool OnUnlockRequest(const std::wstring& password) = 0;
    // The request is finished (Windows answered or we timed out): drop the pending password.
    virtual void OnUnlockDone() = 0;
    virtual ~IUnlockSink() = default;
};

struct PipeServerOptions {
    const wchar_t* name = L"\\\\.\\pipe\\wake-unlock";
    // Cap on waiting for ReportResult. Below the agent's 15 s so the agent always gets our answer.
    DWORD resultWaitMs = 12000;
    // Cap on waiting for a client to send its request after connecting.
    DWORD requestWaitMs = 5000;
    // Cap on waiting for the client to read the reply and close before we disconnect.
    DWORD drainWaitMs = 2000;
    // Owner SYSTEM in the security descriptor. Only the tests turn this off: a non-SYSTEM process
    // cannot create an object owned by SYSTEM.
    bool systemOwner = true;
};

// Owns the \\.\pipe\wake-unlock server and its single worker thread.
// One instance, message mode, local only. The locked user's SID gates the DACL.
//
// Every overlapped operation uses the members ov_ and the member buffers, and is either completed or
// cancelled-and-waited-for before the next one starts, so no I/O is ever pending on memory that has
// gone away. Every wait is bounded or interruptible by Stop(), so Stop() never blocks for long.
class PipeServer {
  public:
    explicit PipeServer(PipeServerOptions options = {});
    ~PipeServer();

    PipeServer(const PipeServer&) = delete;
    PipeServer& operator=(const PipeServer&) = delete;

    // Create the pipe and start the thread. sink outlives the server. Calls Stop() first.
    HRESULT Start(IUnlockSink* sink, const std::wstring& lockedUserSid);
    // Signal the thread to stop and join it. Safe to call when not started. Bounded: every pipe
    // wait is interrupted at once, except the 8-byte "provider closed" write (capped at 1 s).
    void Stop();
    bool Running() const { return thread_.joinable(); }

    // Called from LogonUI's thread (Credential::ReportResult) to deliver Windows' answer.
    void PostResult(uint32_t status, uint32_t substatus);
    // Called from LogonUI's thread when GetSerialization hands the pending password to Windows.
    void MarkSubmitted();
    // True between accepting a request and finishing it (result, timeout or stop).
    bool RequestPending() const { return requestPending_.load(); }

  private:
    enum class Io { Done, Failed, Interrupted };

    void ThreadMain();
    void RunLoop();
    void ServeOneClient();
    bool Stopping() const;
    void PrepareIo();
    Io Await(BOOL completedNow, DWORD startError, DWORD* bytes, DWORD timeoutMs, bool stopAware);
    bool WriteReply(uint32_t status, uint32_t substatus, bool stopping);
    void WaitForClientToClose();

    PipeServerOptions options_;
    IUnlockSink* sink_ = nullptr;
    HANDLE pipe_ = INVALID_HANDLE_VALUE;
    HANDLE ioEvent_ = nullptr;      // manual-reset: overlapped I/O completion for the pipe thread
    HANDLE stopEvent_ = nullptr;    // manual-reset: set by Stop()
    HANDLE resultEvent_ = nullptr;  // auto-reset: set by PostResult()
    std::thread thread_;

    // Pipe-thread I/O state. Members, not locals, so a cancelled operation can never complete into a
    // dead stack frame.
    OVERLAPPED ov_{};
    DWORD lastIoError_ = 0;
    uint8_t readBuf_[8 + kMaxPasswordBytes]{};
    uint8_t writeBuf_[8]{};
    uint8_t drainBuf_[16]{};

    std::atomic<bool> requestPending_{false};
    std::mutex resultMutex_;  // guards the fields below and resultEvent_'s lifetime
    uint32_t status_ = 0;
    uint32_t substatus_ = 0;
    bool resultPosted_ = false;
    bool submitted_ = false;
};

}  // namespace wake
