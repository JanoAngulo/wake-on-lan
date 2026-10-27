#pragma once

#include <windows.h>

#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

namespace wake {

// Receives a password from the pipe thread and hands it to the credential.
// Implemented by Provider. OnUnlockRequest runs on the pipe thread, not LogonUI's apartment thread.
struct IUnlockSink {
    // Store the password, mark it pending and trigger re-enumeration. False if not ready to serve.
    virtual bool OnUnlockRequest(const std::wstring& password) = 0;
    // The request is finished (Windows answered or we timed out): drop the pending password.
    virtual void OnUnlockDone() = 0;
    virtual ~IUnlockSink() = default;
};

// Owns the \\.\pipe\wake-unlock server and its single worker thread.
// One instance, message mode, local only. The locked user's SID gates the DACL.
class PipeServer {
  public:
    PipeServer() = default;
    ~PipeServer();

    PipeServer(const PipeServer&) = delete;
    PipeServer& operator=(const PipeServer&) = delete;

    // Create the pipe and start the thread. sink outlives the server. Idempotent-safe: Stop first.
    HRESULT Start(IUnlockSink* sink, const std::wstring& lockedUserSid);
    // Signal the thread to stop, cancel any wait, and join it. Safe to call when not started.
    void Stop();

    // Called from LogonUI's thread (Credential::ReportResult) to deliver Windows' answer.
    void PostResult(uint32_t status, uint32_t substatus);

  private:
    void ThreadMain();
    void RunLoop();
    void ServeOneClient();
    bool Stopping() const;

    IUnlockSink* sink_ = nullptr;
    HANDLE pipe_ = INVALID_HANDLE_VALUE;
    HANDLE ioEvent_ = nullptr;      // overlapped I/O completion for the pipe thread
    HANDLE stopEvent_ = nullptr;    // manual-reset: set by Stop()
    HANDLE resultEvent_ = nullptr;  // auto-reset: set by PostResult()
    std::thread thread_;

    std::mutex resultMutex_;
    uint32_t status_ = 0;
    uint32_t substatus_ = 0;
};

}  // namespace wake
