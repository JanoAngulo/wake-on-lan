#include "Credential.h"

#include <ntsecapi.h>
#include <NTSecAPI.h>

#include "Guids.h"
#include "PipeServer.h"

// Tile bitmap, also referenced by resources.rc.
#define IDB_TILE 101

extern HINSTANCE g_hInst;  // set in Dll.cpp, used to load the tile bitmap

namespace wake {

const CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR kFieldDescriptors[kFieldCount] = {
    {FI_TILE_IMAGE, CPFT_TILE_IMAGE, const_cast<LPWSTR>(L"Tile Image")},
    {FI_LABEL, CPFT_LARGE_TEXT, const_cast<LPWSTR>(L"Wake")},
    {FI_MESSAGE, CPFT_SMALL_TEXT, const_cast<LPWSTR>(L"Waiting for your phone")},
};

struct FieldStatePair {
    CREDENTIAL_PROVIDER_FIELD_STATE cpfs;
    CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE cpfis;
};

static const FieldStatePair kFieldStates[kFieldCount] = {
    {CPFS_DISPLAY_IN_BOTH, CPFIS_NONE},
    {CPFS_DISPLAY_IN_BOTH, CPFIS_NONE},
    {CPFS_DISPLAY_IN_SELECTED_TILE, CPFIS_NONE},
};

static HRESULT CopyString(PCWSTR src, LPWSTR* dst) {
    size_t bytes = (wcslen(src) + 1) * sizeof(wchar_t);
    *dst = static_cast<LPWSTR>(CoTaskMemAlloc(bytes));
    if (!*dst) return E_OUTOFMEMORY;
    memcpy(*dst, src, bytes);
    return S_OK;
}

// --- Kerberos unlock-logon packing (mirrors the Windows-classic-samples helpers) ---

static void InitUnicode(UNICODE_STRING* u, PCWSTR s) {
    size_t len = s ? wcslen(s) : 0;
    u->Length = static_cast<USHORT>(len * sizeof(wchar_t));
    u->MaximumLength = u->Length;
    u->Buffer = const_cast<PWSTR>(s);
}

// Copy src's bytes to *cursor and point dst->Buffer at the offset from base (what LSA expects).
static void AppendRelative(UNICODE_STRING* dst, const UNICODE_STRING& src, BYTE* base, BYTE** cursor) {
    dst->Length = src.Length;
    dst->MaximumLength = src.Length;
    if (src.Length) memcpy(*cursor, src.Buffer, src.Length);
    dst->Buffer = reinterpret_cast<PWSTR>(*cursor - base);
    *cursor += src.Length;
}

static HRESULT PackUnlockLogon(PCWSTR domain, PCWSTR user, const std::wstring& password, BYTE** prgb,
                               DWORD* pcb) {
    KERB_INTERACTIVE_UNLOCK_LOGON in{};
    KERB_INTERACTIVE_LOGON* logon = &in.Logon;
    logon->MessageType = KerbWorkstationUnlockLogon;
    InitUnicode(&logon->LogonDomainName, domain);
    InitUnicode(&logon->UserName, user);
    logon->Password.Length = static_cast<USHORT>(password.size() * sizeof(wchar_t));
    logon->Password.MaximumLength = logon->Password.Length;
    logon->Password.Buffer = const_cast<PWSTR>(password.c_str());

    DWORD cb = sizeof(in) + logon->LogonDomainName.Length + logon->UserName.Length +
               logon->Password.Length;
    BYTE* blob = static_cast<BYTE*>(CoTaskMemAlloc(cb));
    if (!blob) return E_OUTOFMEMORY;

    auto* out = reinterpret_cast<KERB_INTERACTIVE_UNLOCK_LOGON*>(blob);
    ZeroMemory(&out->LogonId, sizeof(out->LogonId));
    KERB_INTERACTIVE_LOGON* outLogon = &out->Logon;
    outLogon->MessageType = logon->MessageType;

    BYTE* cursor = blob + sizeof(in);
    AppendRelative(&outLogon->LogonDomainName, logon->LogonDomainName, blob, &cursor);
    AppendRelative(&outLogon->UserName, logon->UserName, blob, &cursor);
    AppendRelative(&outLogon->Password, logon->Password, blob, &cursor);

    *prgb = blob;
    *pcb = cb;
    return S_OK;
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

// Split "DOMAIN\user" (or "MicrosoftAccount\user@host") at the first backslash.
static void SplitUserName(const std::wstring& qualified, std::wstring* domain, std::wstring* user) {
    size_t slash = qualified.find(L'\\');
    if (slash == std::wstring::npos) {
        domain->clear();
        *user = qualified;
    } else {
        *domain = qualified.substr(0, slash);
        *user = qualified.substr(slash + 1);
    }
}

// --- Credential ---

Credential::Credential(const std::wstring& qualifiedUserName, const std::wstring& userSid,
                       PipeServer* pipe)
    : pipe_(pipe), userName_(qualifiedUserName), userSid_(userSid) {}

Credential::~Credential() {
    if (!password_.empty())
        SecureZeroMemory(&password_[0], password_.size() * sizeof(wchar_t));
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

IFACEMETHODIMP Credential::SetSelected(BOOL* pbAutoLogon) {
    try {
        if (!pbAutoLogon) return E_POINTER;
        *pbAutoLogon = HasPassword() ? TRUE : FALSE;
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
        if (dwFieldID == FI_LABEL) return CopyString(L"Wake", ppwsz);
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
        if (ppwszOptionalStatusText) *ppwszOptionalStatusText = nullptr;
        if (pcpsiOptionalStatusIcon) *pcpsiOptionalStatusIcon = CPSI_NONE;

        std::wstring password;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            password = password_;
            reported_ = false;
        }
        if (password.empty()) {
            // No phone password yet: stay on the tile, no submission.
            *pcpgsr = CPGSR_NO_CREDENTIAL_NOT_FINISHED;
            return S_OK;
        }

        std::wstring domain, user;
        SplitUserName(userName_, &domain, &user);

        BYTE* blob = nullptr;
        DWORD cb = 0;
        HRESULT hr = PackUnlockLogon(domain.c_str(), user.c_str(), password, &blob, &cb);
        SecureZeroMemory(&password[0], password.size() * sizeof(wchar_t));
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

        if (pipe_)
            pipe_->PostResult(static_cast<uint32_t>(ntsStatus), static_cast<uint32_t>(ntsSubstatus));

        std::lock_guard<std::mutex> lock(mutex_);
        reported_ = true;
        if (!password_.empty())
            SecureZeroMemory(&password_[0], password_.size() * sizeof(wchar_t));
        password_.clear();
        return S_OK;
    } catch (...) {
        return E_UNEXPECTED;
    }
}

IFACEMETHODIMP Credential::GetUserSid(LPWSTR* ppwszSid) {
    try {
        if (!ppwszSid) return E_POINTER;
        *ppwszSid = nullptr;
        if (userSid_.empty()) return E_NOTIMPL;  // let LogonUI fall back
        return CopyString(userSid_.c_str(), ppwszSid);
    } catch (...) {
        return E_UNEXPECTED;
    }
}

void Credential::SetPassword(const std::wstring& password) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!password_.empty())
        SecureZeroMemory(&password_[0], password_.size() * sizeof(wchar_t));
    password_ = password;
}

void Credential::ClearPassword() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!password_.empty())
        SecureZeroMemory(&password_[0], password_.size() * sizeof(wchar_t));
    password_.clear();
}

bool Credential::HasPassword() {
    std::lock_guard<std::mutex> lock(mutex_);
    return !password_.empty();
}

}  // namespace wake
