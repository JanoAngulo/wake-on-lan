#include "Provider.h"

#include <propkey.h>

#include "Credential.h"

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

Provider::Provider() {
    WakeDllAddRef();
}

Provider::~Provider() {
    pipe_.Stop();
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
        // Unlock only. Anything else (logon, UAC, change password) is not ours.
        if (cpus != CPUS_UNLOCK_WORKSTATION) return E_NOTIMPL;
        cpus_ = cpus;
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Provider::SetSerialization(const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION*) {
    return E_NOTIMPL;
}

IFACEMETHODIMP Provider::Advise(ICredentialProviderEvents* pcpe, UINT_PTR upAdviseContext) {
    try {
        std::wstring sid;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (events_) events_->Release();
            events_ = pcpe;
            if (events_) events_->AddRef();
            adviseContext_ = upAdviseContext;
            sid = userSid_;
        }
        // Start the pipe outside the lock; it may call back in on its own thread.
        return pipe_.Start(this, sid);
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Provider::UnAdvise() {
    try {
        pipe_.Stop();  // stops and joins the pipe thread before we drop events_
        std::lock_guard<std::mutex> lock(mutex_);
        passwordPending_ = false;
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
        *pdwCount = credential_ ? 1 : 0;
        *pdwDefault = credential_ ? 0 : CREDENTIAL_PROVIDER_NO_DEFAULT;
        // Auto-submit only while a phone password is staged; otherwise the tile just sits there.
        *pbAutoLogonWithDefault = (credential_ && passwordPending_) ? TRUE : FALSE;
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
        DWORD count = 0;
        if (SUCCEEDED(users->GetCount(&count)) && count > 0) {
            ICredentialProviderUser* user = nullptr;
            if (SUCCEEDED(users->GetAt(0, &user)) && user) {
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
                user->Release();
            }
        }

        std::lock_guard<std::mutex> lock(mutex_);
        userName_ = name;
        userSid_ = sid;
        if (!credential_) {
            credential_ = new (std::nothrow) Credential(userName_, userSid_, &pipe_);
            if (!credential_) return E_OUTOFMEMORY;
        }
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
