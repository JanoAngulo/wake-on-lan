#include "Credential.h"

#include <wincred.h>

#include <cstring>
#include <vector>

#include "Guids.h"
#include "PipeServer.h"
#include "UnlockLogon.h"

// Tile bitmap, also referenced by resources.rc.
#define IDB_TILE 101

extern HINSTANCE g_hInst;  // set in Dll.cpp, used to load the tile bitmap

// Keep the DLL loaded while a credential lives (defined in Dll.cpp).
void WakeDllAddRef();
void WakeDllRelease();

namespace wake {

// Windows 10+ lists a provider under "Sign-in options" only through fields tagged with these
// (CPFG_CREDENTIAL_PROVIDER_LOGO and CPFG_CREDENTIAL_PROVIDER_LABEL, ShlGuid.h). Local copies keep
// the GUIDs out of the link, so the test executables don't need an initguid translation unit.
static constexpr GUID kProviderLogo = {0x2d837775, 0xf6cd, 0x464e, {0xa7, 0x45, 0x48, 0x2f, 0xd0, 0xb4, 0x74, 0x93}};
static constexpr GUID kProviderLabel = {0x286bbff3, 0xbad4, 0x438f, {0xb0, 0x07, 0x79, 0xb7, 0x26, 0x7c, 0x3d, 0x48}};

const CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR kFieldDescriptors[kFieldCount] = {
    {FI_TILE_IMAGE, CPFT_TILE_IMAGE, const_cast<LPWSTR>(L"Tile Image"), kProviderLogo},
    {FI_LABEL, CPFT_LARGE_TEXT, const_cast<LPWSTR>(L"Wake"), {}},
    {FI_MESSAGE, CPFT_SMALL_TEXT, const_cast<LPWSTR>(L"Waiting for your phone"), {}},
    {FI_PROVIDER_LABEL, CPFT_SMALL_TEXT, const_cast<LPWSTR>(L"Wake"), kProviderLabel},
};

struct FieldStatePair {
    CREDENTIAL_PROVIDER_FIELD_STATE cpfs;
    CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE cpfis;
};

static const FieldStatePair kFieldStates[kFieldCount] = {
    {CPFS_DISPLAY_IN_BOTH, CPFIS_NONE},
    {CPFS_DISPLAY_IN_BOTH, CPFIS_NONE},
    {CPFS_DISPLAY_IN_SELECTED_TILE, CPFIS_NONE},
    {CPFS_HIDDEN, CPFIS_NONE},
};

static HRESULT CopyString(PCWSTR src, LPWSTR* dst) {
    size_t bytes = (wcslen(src) + 1) * sizeof(wchar_t);
    *dst = static_cast<LPWSTR>(CoTaskMemAlloc(bytes));
    if (!*dst) return E_OUTOFMEMORY;
    memcpy(*dst, src, bytes);
    return S_OK;
}

static void Wipe(std::wstring& s) {
    if (!s.empty()) SecureZeroMemory(&s[0], s.size() * sizeof(wchar_t));
    s.clear();
}

static void Wipe(std::vector<wchar_t>& v) {
    if (!v.empty()) SecureZeroMemory(v.data(), v.size() * sizeof(wchar_t));
    v.clear();
}

static HRESULT LookupNegotiatePackage(ULONG* pkg) {
    HANDLE lsa = nullptr;
    NTSTATUS st = LsaConnectUntrusted(&lsa);
    if (st < 0) return HRESULT_FROM_WIN32(LsaNtStatusToWinError(st));
    LSA_STRING name;
    char negotiate[] = "Negotiate";
    name.Buffer = negotiate;
    name.Length = static_cast<USHORT>(sizeof(negotiate) - 1);
    name.MaximumLength = static_cast<USHORT>(sizeof(negotiate));
    st = LsaLookupAuthenticationPackage(lsa, &name, pkg);
    LsaDeregisterLogonProcess(lsa);
    return st < 0 ? HRESULT_FROM_WIN32(LsaNtStatusToWinError(st)) : S_OK;
}

// The sample's ProtectIfNecessaryAndCopyPassword for the unlock scenario: CredProtect the password
// unless it is already protected. Both buffers are NUL-terminated.
static HRESULT ProtectIfNecessary(std::vector<wchar_t>& plain, std::vector<wchar_t>* out) {
    const DWORD cchIn = static_cast<DWORD>(plain.size());  // includes the terminator, as CredProtect wants
    if (cchIn <= 1) {
        out->assign(1, L'\0');
        return S_OK;
    }
    CRED_PROTECTION_TYPE type = CredUnprotected;
    if (CredIsProtectedW(plain.data(), &type) && type != CredUnprotected) {
        *out = plain;
        return S_OK;
    }
    DWORD cch = 0;
    if (CredProtectW(FALSE, plain.data(), cchIn, nullptr, &cch, nullptr)) return E_UNEXPECTED;
    DWORD err = GetLastError();
    if (err != ERROR_INSUFFICIENT_BUFFER || cch == 0) return HRESULT_FROM_WIN32(err);
    out->assign(cch, L'\0');
    if (!CredProtectW(FALSE, plain.data(), cchIn, out->data(), &cch, nullptr)) {
        err = GetLastError();
        Wipe(*out);
        return err ? HRESULT_FROM_WIN32(err) : E_FAIL;
    }
    return S_OK;
}

// Local account: KERB_INTERACTIVE_UNLOCK_LOGON (KerbWorkstationUnlockLogon) with a protected
// password. The sample notes CredPackAuthenticationBuffer does not work for a local unlock.
static HRESULT PackLocalUser(const std::wstring& qualifiedName, std::vector<wchar_t>& password,
                             BYTE** prgb, DWORD* pcb) {
    std::vector<wchar_t> protectedPassword;
    HRESULT hr = ProtectIfNecessary(password, &protectedPassword);
    if (FAILED(hr)) return hr;

    std::wstring domain, user;
    SplitUserName(qualifiedName, &domain, &user);
    hr = PackUnlockLogon(domain, user, protectedPassword.data(),
                         wcsnlen(protectedPassword.data(), protectedPassword.size()), prgb, pcb);
    Wipe(protectedPassword);
    return hr;
}

// Microsoft account, Azure AD or domain user: the sample's CredPackAuthenticationBuffer path with
// the qualified user name (for example "MicrosoftAccount\user@example.com").
static HRESULT PackProviderUser(const std::wstring& qualifiedName, std::vector<wchar_t>& password,
                                BYTE** prgb, DWORD* pcb) {
    std::vector<wchar_t> name(qualifiedName.size() + 1, L'\0');
    if (!qualifiedName.empty())
        memcpy(name.data(), qualifiedName.data(), qualifiedName.size() * sizeof(wchar_t));
    const DWORD flags = CRED_PACK_PROTECTED_CREDENTIALS | CRED_PACK_ID_PROVIDER_CREDENTIALS;

    DWORD cb = 0;
    if (CredPackAuthenticationBufferW(flags, name.data(), password.data(), nullptr, &cb))
        return E_UNEXPECTED;
    DWORD err = GetLastError();
    if (err != ERROR_INSUFFICIENT_BUFFER || cb == 0) return err ? HRESULT_FROM_WIN32(err) : E_FAIL;

    const DWORD allocated = cb;
    BYTE* blob = static_cast<BYTE*>(CoTaskMemAlloc(allocated));
    if (!blob) return E_OUTOFMEMORY;
    if (!CredPackAuthenticationBufferW(flags, name.data(), password.data(), blob, &cb)) {
        err = GetLastError();
        SecureZeroMemory(blob, allocated);
        CoTaskMemFree(blob);
        return err ? HRESULT_FROM_WIN32(err) : E_FAIL;
    }
    *prgb = blob;
    *pcb = cb;
    return S_OK;
}

// --- Credential ---

Credential::Credential(const std::wstring& qualifiedUserName, const std::wstring& userSid,
                       bool isLocalUser)
    : userName_(qualifiedUserName), userSid_(userSid), isLocalUser_(isLocalUser) {
    WakeDllAddRef();
}

Credential::~Credential() {
    Wipe(password_);
    WakeDllRelease();
}

IFACEMETHODIMP Credential::QueryInterface(REFIID riid, void** ppv) {
    try {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_ICredentialProviderCredential ||
            riid == IID_ICredentialProviderCredential2) {
            *ppv = static_cast<ICredentialProviderCredential2*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP_(ULONG) Credential::AddRef() {
    return InterlockedIncrement(&ref_);
}

IFACEMETHODIMP_(ULONG) Credential::Release() {
    LONG n = InterlockedDecrement(&ref_);
    if (n == 0) delete this;
    return n;
}

IFACEMETHODIMP Credential::Advise(ICredentialProviderCredentialEvents*) {
    return S_OK;
}

IFACEMETHODIMP Credential::UnAdvise() {
    return S_OK;
}

bool Credential::RequestPendingLocked() const {
    return !password_.empty() && pipe_ && pipe_->RequestPending();
}

IFACEMETHODIMP Credential::SetSelected(BOOL* pbAutoLogon) {
    try {
        if (!pbAutoLogon) return E_POINTER;
        std::lock_guard<std::mutex> lock(mutex_);
        // Auto-submit only for a phone request in flight, never for a leftover password.
        *pbAutoLogon = RequestPendingLocked() ? TRUE : FALSE;
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Credential::SetDeselected() {
    return S_OK;
}

IFACEMETHODIMP Credential::GetFieldState(DWORD dwFieldID, CREDENTIAL_PROVIDER_FIELD_STATE* pcpfs,
                                         CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE* pcpfis) {
    try {
        if (dwFieldID >= kFieldCount || !pcpfs || !pcpfis) return E_INVALIDARG;
        *pcpfs = kFieldStates[dwFieldID].cpfs;
        *pcpfis = kFieldStates[dwFieldID].cpfis;
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Credential::GetStringValue(DWORD dwFieldID, LPWSTR* ppwsz) {
    try {
        if (!ppwsz) return E_POINTER;
        *ppwsz = nullptr;
        if (dwFieldID == FI_LABEL || dwFieldID == FI_PROVIDER_LABEL) return CopyString(L"Wake", ppwsz);
        if (dwFieldID == FI_MESSAGE) return CopyString(L"Waiting for your phone", ppwsz);
        return E_INVALIDARG;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Credential::GetBitmapValue(DWORD dwFieldID, HBITMAP* phbmp) {
    try {
        if (!phbmp) return E_POINTER;
        if (dwFieldID != FI_TILE_IMAGE) return E_INVALIDARG;
        HBITMAP bmp = static_cast<HBITMAP>(LoadImageW(g_hInst, MAKEINTRESOURCEW(IDB_TILE),
                                                      IMAGE_BITMAP, 0, 0, LR_CREATEDIBSECTION));
        if (!bmp) return HRESULT_FROM_WIN32(GetLastError());
        *phbmp = bmp;
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Credential::GetCheckboxValue(DWORD, BOOL*, LPWSTR*) {
    return E_NOTIMPL;
}

IFACEMETHODIMP Credential::GetSubmitButtonValue(DWORD, DWORD*) {
    return E_NOTIMPL;
}

IFACEMETHODIMP Credential::GetComboBoxValueCount(DWORD, DWORD*, DWORD*) {
    return E_NOTIMPL;
}

IFACEMETHODIMP Credential::GetComboBoxValueAt(DWORD, DWORD, LPWSTR*) {
    return E_NOTIMPL;
}

IFACEMETHODIMP Credential::SetStringValue(DWORD, LPCWSTR) {
    return E_NOTIMPL;
}

IFACEMETHODIMP Credential::SetCheckboxValue(DWORD, BOOL) {
    return E_NOTIMPL;
}

IFACEMETHODIMP Credential::SetComboBoxSelectedValue(DWORD, DWORD) {
    return E_NOTIMPL;
}

IFACEMETHODIMP Credential::CommandLinkClicked(DWORD) {
    return E_NOTIMPL;
}

IFACEMETHODIMP Credential::GetSerialization(
    CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* pcpgsr,
    CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* pcpcs, LPWSTR* ppwszOptionalStatusText,
    CREDENTIAL_PROVIDER_STATUS_ICON* pcpsiOptionalStatusIcon) {
    try {
        if (!pcpgsr || !pcpcs) return E_POINTER;
        *pcpgsr = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
        ZeroMemory(pcpcs, sizeof(*pcpcs));
        if (ppwszOptionalStatusText) *ppwszOptionalStatusText = nullptr;
        if (pcpsiOptionalStatusIcon) *pcpsiOptionalStatusIcon = CPSI_NONE;

        // Take the password out (single use): one phone request is serialized at most once.
        std::vector<wchar_t> password;
        std::wstring name;
        bool isLocal = true;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!RequestPendingLocked()) {
                Wipe(password_);  // nothing in flight: no submission, stay on the tile
                return S_OK;
            }
            password.assign(password_.size() + 1, L'\0');
            memcpy(password.data(), password_.data(), password_.size() * sizeof(wchar_t));
            Wipe(password_);
            name = userName_;
            isLocal = isLocalUser_;
        }

        BYTE* blob = nullptr;
        DWORD cb = 0;
        HRESULT hr = isLocal ? PackLocalUser(name, password, &blob, &cb)
                             : PackProviderUser(name, password, &blob, &cb);
        Wipe(password);
        if (FAILED(hr)) return hr;

        ULONG authPackage = 0;
        hr = LookupNegotiatePackage(&authPackage);
        if (FAILED(hr)) {
            SecureZeroMemory(blob, cb);
            CoTaskMemFree(blob);
            return hr;
        }

        pcpcs->ulAuthenticationPackage = authPackage;
        pcpcs->clsidCredentialProvider = CLSID_WakeCredentialProvider;
        pcpcs->cbSerialization = cb;
        pcpcs->rgbSerialization = blob;  // LogonUI frees this with CoTaskMemFree
        *pcpgsr = CPGSR_RETURN_CREDENTIAL_FINISHED;

        // Windows now has the password: if the provider closes before ReportResult, the pipe
        // tells the agent "provider closed" instead of going silent.
        std::lock_guard<std::mutex> lock(mutex_);
        if (pipe_) pipe_->MarkSubmitted();
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Credential::ReportResult(NTSTATUS ntsStatus, NTSTATUS ntsSubstatus,
                                        LPWSTR* ppwszOptionalStatusText,
                                        CREDENTIAL_PROVIDER_STATUS_ICON* pcpsiOptionalStatusIcon) {
    try {
        if (ppwszOptionalStatusText) *ppwszOptionalStatusText = nullptr;
        if (pcpsiOptionalStatusIcon) *pcpsiOptionalStatusIcon = CPSI_NONE;

        std::lock_guard<std::mutex> lock(mutex_);
        if (pipe_)
            pipe_->PostResult(static_cast<uint32_t>(ntsStatus), static_cast<uint32_t>(ntsSubstatus));
        Wipe(password_);
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Credential::GetUserSid(LPWSTR* ppwszSid) {
    try {
        if (!ppwszSid) return E_POINTER;
        *ppwszSid = nullptr;
        std::lock_guard<std::mutex> lock(mutex_);
        if (userSid_.empty()) return E_NOTIMPL;  // let LogonUI fall back
        return CopyString(userSid_.c_str(), ppwszSid);
    } catch (...) {
        return E_UNEXPECTED;
    }
}

void Credential::SetPassword(const std::wstring& password) {
    std::lock_guard<std::mutex> lock(mutex_);
    Wipe(password_);
    password_ = password;
}

void Credential::ClearPassword() {
    std::lock_guard<std::mutex> lock(mutex_);
    Wipe(password_);
}

void Credential::SetUser(const std::wstring& qualifiedUserName, const std::wstring& userSid,
                         bool isLocalUser) {
    std::lock_guard<std::mutex> lock(mutex_);
    userName_ = qualifiedUserName;
    userSid_ = userSid;
    isLocalUser_ = isLocalUser;
}

void Credential::AttachPipe(PipeServer* pipe) {
    std::lock_guard<std::mutex> lock(mutex_);
    pipe_ = pipe;
}

void Credential::DetachPipe() {
    std::lock_guard<std::mutex> lock(mutex_);
    pipe_ = nullptr;
}

}  // namespace wake
