#include "Provider.h"
#include "Trace.h"

#include <propkey.h>

#include "Credential.h"
#include "SignInConfig.h"

// Keep the DLL loaded while a provider lives (defined in Dll.cpp).
void WakeDllAddRef();
void WakeDllRelease();

namespace wake {

static HRESULT CopyFieldDescriptor(const CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR& src,
                                   CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** dst) {
    auto* out = static_cast<CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR*>(
        CoTaskMemAlloc(sizeof(CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR)));
    if (!out) return E_OUTOFMEMORY;
    out->dwFieldID = src.dwFieldID;
    out->cpft = src.cpft;
    out->guidFieldType = src.guidFieldType;
    if (src.pszLabel) {
        size_t bytes = (wcslen(src.pszLabel) + 1) * sizeof(wchar_t);
        out->pszLabel = static_cast<LPWSTR>(CoTaskMemAlloc(bytes));
        if (!out->pszLabel) {
            CoTaskMemFree(out);
            return E_OUTOFMEMORY;
        }
        memcpy(out->pszLabel, src.pszLabel, bytes);
    } else {
        out->pszLabel = nullptr;
    }
    *dst = out;
    return S_OK;
}

// Local account or not, from the user's identity provider (the PKEY_Identity_ProviderID value that
// ICredentialProviderUser::GetProviderID returns), the same test Microsoft's sample makes. If
// LogonUI can't say, fall back to the qualified name's prefix.
static bool IsLocalUser(ICredentialProviderUser* user, const std::wstring& qualifiedName) {
    GUID providerId{};
    if (SUCCEEDED(user->GetProviderID(&providerId)))
        return IsEqualGUID(providerId, Identity_LocalUserProvider) != FALSE;
    return _wcsnicmp(qualifiedName.c_str(), L"MicrosoftAccount\\", 17) != 0 &&
           _wcsnicmp(qualifiedName.c_str(), L"AzureAD\\", 8) != 0;
}

Provider::Provider() {
    WakeDllAddRef();
}

Provider::~Provider() {
    try {
        StopPipe();
    } catch (...) {
    }
    if (credential_) credential_->Release();
    if (events_) events_->Release();
    WakeDllRelease();
}

IFACEMETHODIMP Provider::QueryInterface(REFIID riid, void** ppv) {
    try {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_ICredentialProvider) {
            *ppv = static_cast<ICredentialProvider*>(this);
        } else if (riid == IID_ICredentialProviderSetUserArray) {
            *ppv = static_cast<ICredentialProviderSetUserArray*>(this);
        } else {
            *ppv = nullptr;
            return E_NOINTERFACE;
        }
        AddRef();
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP_(ULONG) Provider::AddRef() {
    return InterlockedIncrement(&ref_);
}

IFACEMETHODIMP_(ULONG) Provider::Release() {
    LONG n = InterlockedDecrement(&ref_);
    if (n == 0) delete this;
    return n;
}

IFACEMETHODIMP Provider::SetUsageScenario(CREDENTIAL_PROVIDER_USAGE_SCENARIO cpus, DWORD) {
    try {
        Trace(L"SetUsageScenario cpus=%d", static_cast<int>(cpus));
        // Windows 10+ merged lock-screen unlock into CPUS_LOGON, so both are ours. UAC, change
        // password and CredUI are not.
        if (cpus != CPUS_LOGON && cpus != CPUS_UNLOCK_WORKSTATION) return E_NOTIMPL;
        cpus_ = cpus;
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Provider::SetSerialization(const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION*) {
    return E_NOTIMPL;
}

HRESULT Provider::StartPipeIfReady() {
    std::wstring sid;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if ((cpus_ != CPUS_LOGON && cpus_ != CPUS_UNLOCK_WORKSTATION) || !events_ || !credential_ || userSid_.empty())
            return S_OK;  // the other half (Advise or SetUserArray) has not happened yet
        sid = userSid_;
    }
    if (pipe_.Running() && sid == pipeSid_) return S_OK;

    StopPipe();
    {
        // Attach before the thread starts so a request that arrives at once is served.
        std::lock_guard<std::mutex> lock(mutex_);
        if (credential_) credential_->AttachPipe(&pipe_);
    }
    // Start outside the lock: the pipe thread calls back into OnUnlockRequest, which takes it.
    HRESULT hr = pipe_.Start(this, sid, LookupServiceSid());
    Trace(L"pipe start hr=0x%08X", static_cast<unsigned>(hr));
    if (FAILED(hr)) {
        StopPipe();
        return hr;
    }
    pipeSid_ = sid;
    return S_OK;
}

void Provider::StopPipe() {
    pipe_.Stop();  // joins the pipe thread; mutex_ must not be held here
    pipeSid_.clear();
    std::lock_guard<std::mutex> lock(mutex_);
    passwordPending_ = false;
    if (credential_) {
        credential_->DetachPipe();
        credential_->ClearPassword();
    }
}

IFACEMETHODIMP Provider::Advise(ICredentialProviderEvents* pcpe, UINT_PTR upAdviseContext) {
    try {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (events_) events_->Release();
            events_ = pcpe;
            if (events_) events_->AddRef();
            adviseContext_ = upAdviseContext;
        }
        Trace(L"Advise");
        StartPipeIfReady();  // as in SetUserArray: never fail Advise over the pipe
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Provider::UnAdvise() {
    try {
        Trace(L"UnAdvise");
        StopPipe();  // stops and joins the pipe thread before we drop events_
        std::lock_guard<std::mutex> lock(mutex_);
        if (events_) {
            events_->Release();
            events_ = nullptr;
        }
        adviseContext_ = 0;
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Provider::GetFieldDescriptorCount(DWORD* pdwCount) {
    try {
        if (!pdwCount) return E_POINTER;
        *pdwCount = kFieldCount;
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Provider::GetFieldDescriptorAt(DWORD dwIndex,
                                              CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** ppcpfd) {
    try {
        if (!ppcpfd) return E_POINTER;
        if (dwIndex >= kFieldCount) return E_INVALIDARG;
        return CopyFieldDescriptor(kFieldDescriptors[dwIndex], ppcpfd);
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Provider::GetCredentialCount(DWORD* pdwCount, DWORD* pdwDefault,
                                            BOOL* pbAutoLogonWithDefault) {
    try {
        if (!pdwCount || !pdwDefault || !pbAutoLogonWithDefault) return E_POINTER;
        std::lock_guard<std::mutex> lock(mutex_);
        // The tile is always listed so the user can see the module is loaded, but it takes the
        // default from password, PIN or Hello only while a phone request is pending.
        const bool pending = credential_ && passwordPending_ && pipe_.RequestPending();
        *pdwCount = credential_ ? 1 : 0;
        *pdwDefault = pending ? 0 : CREDENTIAL_PROVIDER_NO_DEFAULT;
        *pbAutoLogonWithDefault = pending ? TRUE : FALSE;
        Trace(L"GetCredentialCount count=%lu pending=%d", *pdwCount, pending ? 1 : 0);
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Provider::GetCredentialAt(DWORD dwIndex, ICredentialProviderCredential** ppcpc) {
    try {
        if (!ppcpc) return E_POINTER;
        *ppcpc = nullptr;
        std::lock_guard<std::mutex> lock(mutex_);
        if (dwIndex != 0 || !credential_) return E_INVALIDARG;
        return credential_->QueryInterface(IID_ICredentialProviderCredential,
                                           reinterpret_cast<void**>(ppcpc));
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Provider::SetUserArray(ICredentialProviderUserArray* users) {
    try {
        if (!users) return E_INVALIDARG;
        std::wstring name, sid;
        bool isLocal = true;
        DWORD count = 0;
        if (SUCCEEDED(users->GetCount(&count)) && count > 0) {
            // Serve the account that installed Wake when LogonUI lists several; otherwise the first.
            std::vector<std::wstring> sids;
            for (DWORD i = 0; i < count; ++i) {
                std::wstring each;
                ICredentialProviderUser* candidate = nullptr;
                if (SUCCEEDED(users->GetAt(i, &candidate)) && candidate) {
                    LPWSTR raw = nullptr;
                    if (SUCCEEDED(candidate->GetSid(&raw)) && raw) {
                        each = raw;
                        CoTaskMemFree(raw);
                    }
                    candidate->Release();
                }
                sids.push_back(each);
            }
            const DWORD index = static_cast<DWORD>(PickUser(sids, ReadPreferredUserSid()));
            ICredentialProviderUser* user = nullptr;
            if (SUCCEEDED(users->GetAt(index, &user)) && user) {
                LPWSTR raw = nullptr;
                if (SUCCEEDED(user->GetStringValue(PKEY_Identity_QualifiedUserName, &raw)) && raw) {
                    name = raw;
                    CoTaskMemFree(raw);
                }
                LPWSTR rawSid = nullptr;
                if (SUCCEEDED(user->GetSid(&rawSid)) && rawSid) {
                    sid = rawSid;
                    CoTaskMemFree(rawSid);
                }
                isLocal = IsLocalUser(user, name);
                user->Release();
            }
        }

        {
            std::lock_guard<std::mutex> lock(mutex_);
            userName_ = name;
            userSid_ = sid;
            if (!credential_) {
                credential_ = new (std::nothrow) Credential(userName_, userSid_, isLocal);
                if (!credential_) return E_OUTOFMEMORY;
                credential_->SetScenario(cpus_);
            } else {
                credential_->SetUser(userName_, userSid_, isLocal);
                credential_->SetScenario(cpus_);
            }
        }
        Trace(L"SetUserArray users=%lu sid=%d local=%d", count, sid.empty() ? 0 : 1, isLocal ? 1 : 0);
        // A pipe failure must not cost the tile: LogonUI drops a provider whose SetUserArray fails.
        StartPipeIfReady();
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

bool Provider::OnUnlockRequest(const std::wstring& password) {
    try {
        ICredentialProviderEvents* events = nullptr;
        UINT_PTR context = 0;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!credential_ || !events_) return false;
            credential_->SetPassword(password);
            passwordPending_ = true;
            events = events_;
            context = adviseContext_;
            events->AddRef();
        }
        // CredentialsChanged is callable from any thread; call it without the lock to avoid
        // re-entrancy against LogonUI's apartment thread.
        events->CredentialsChanged(context);
        events->Release();
        return true;
    } catch (...) {
        return false;
    }
}

void Provider::OnUnlockDone() {
    try {
        std::lock_guard<std::mutex> lock(mutex_);
        passwordPending_ = false;
        if (credential_) credential_->ClearPassword();
    } catch (...) {
    }
}

}  // namespace wake
