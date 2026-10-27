#pragma once

#include <windows.h>
#include <credentialprovider.h>

#include <mutex>
#include <string>

#include "PipeServer.h"

namespace wake {

class Credential;

// The credential provider LogonUI loads. Serves one tile, only for CPUS_UNLOCK_WORKSTATION, and
// owns the pipe server that the agent talks to. Implements IUnlockSink so the pipe thread can push
// a password in and trigger re-enumeration. The tile is always listed (it shows the module is
// loaded) but is the default, with auto-logon, only while a phone request is pending.
class Provider : public ICredentialProvider, public ICredentialProviderSetUserArray, public IUnlockSink {
  public:
    Provider();

    // IUnknown
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    IFACEMETHODIMP_(ULONG) AddRef() override;
    IFACEMETHODIMP_(ULONG) Release() override;

    // ICredentialProvider
    IFACEMETHODIMP SetUsageScenario(CREDENTIAL_PROVIDER_USAGE_SCENARIO cpus, DWORD dwFlags) override;
    IFACEMETHODIMP SetSerialization(
        const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* pcpcs) override;
    IFACEMETHODIMP Advise(ICredentialProviderEvents* pcpe, UINT_PTR upAdviseContext) override;
    IFACEMETHODIMP UnAdvise() override;
    IFACEMETHODIMP GetFieldDescriptorCount(DWORD* pdwCount) override;
    IFACEMETHODIMP GetFieldDescriptorAt(DWORD dwIndex,
                                        CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** ppcpfd) override;
    IFACEMETHODIMP GetCredentialCount(DWORD* pdwCount, DWORD* pdwDefault,
                                      BOOL* pbAutoLogonWithDefault) override;
    IFACEMETHODIMP GetCredentialAt(DWORD dwIndex, ICredentialProviderCredential** ppcpc) override;

    // ICredentialProviderSetUserArray
    IFACEMETHODIMP SetUserArray(ICredentialProviderUserArray* users) override;

    // IUnlockSink (called on the pipe thread)
    bool OnUnlockRequest(const std::wstring& password) override;
    void OnUnlockDone() override;

  private:
    ~Provider();
    // Starts the pipe once both Advise (events) and SetUserArray (SID) have happened, whichever
    // comes last, and restarts it if the SID changed. Runs on LogonUI's thread.
    HRESULT StartPipeIfReady();
    // Stops the pipe, detaches it from the credential and drops any staged password.
    void StopPipe();

    LONG ref_ = 1;
    CREDENTIAL_PROVIDER_USAGE_SCENARIO cpus_ = CPUS_INVALID;

    std::mutex mutex_;  // guards the shared state the pipe thread touches
    ICredentialProviderEvents* events_ = nullptr;
    UINT_PTR adviseContext_ = 0;
    Credential* credential_ = nullptr;
    bool passwordPending_ = false;

    std::wstring userName_;
    std::wstring userSid_;
    std::wstring pipeSid_;  // the SID the running pipe's DACL was built for (LogonUI's thread only)
    PipeServer pipe_;
};

}  // namespace wake
