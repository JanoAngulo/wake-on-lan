#include "UnlockLogon.h"

#include <cstring>

namespace wake {

void SplitUserName(const std::wstring& qualified, std::wstring* domain, std::wstring* user) {
    size_t slash = qualified.find(L'\\');
    if (slash == std::wstring::npos) {
        domain->clear();
        *user = qualified;
    } else {
        *domain = qualified.substr(0, slash);
        *user = qualified.substr(slash + 1);
    }
}

// UNICODE_STRING lengths are USHORT bytes; anything longer cannot be packed.
static bool FitsUnicodeString(size_t chars) {
    return chars <= 0x7FFF;
}

// Copy chars to *cursor and point dst->Buffer at the offset from base.
static void AppendRelative(UNICODE_STRING* dst, const wchar_t* src, size_t chars, BYTE* base,
                           BYTE** cursor) {
    USHORT bytes = static_cast<USHORT>(chars * sizeof(wchar_t));
    dst->Length = bytes;
    dst->MaximumLength = bytes;
    if (bytes) memcpy(*cursor, src, bytes);
    dst->Buffer = reinterpret_cast<PWSTR>(*cursor - base);
    *cursor += bytes;
}

HRESULT PackUnlockLogon(const std::wstring& domain, const std::wstring& user, const wchar_t* password,
                        size_t passwordChars, BYTE** prgb, DWORD* pcb) {
    if (!prgb || !pcb || (!password && passwordChars)) return E_INVALIDARG;
    *prgb = nullptr;
    *pcb = 0;
    if (!FitsUnicodeString(domain.size()) || !FitsUnicodeString(user.size()) ||
        !FitsUnicodeString(passwordChars)) {
        return E_INVALIDARG;
    }

    DWORD cb = static_cast<DWORD>(sizeof(KERB_INTERACTIVE_UNLOCK_LOGON) +
                                  (domain.size() + user.size() + passwordChars) * sizeof(wchar_t));
    BYTE* blob = static_cast<BYTE*>(CoTaskMemAlloc(cb));
    if (!blob) return E_OUTOFMEMORY;
    ZeroMemory(blob, cb);

    auto* out = reinterpret_cast<KERB_INTERACTIVE_UNLOCK_LOGON*>(blob);
    // LogonId stays zero: Winlogon fills it in.
    KERB_INTERACTIVE_LOGON* logon = &out->Logon;
    logon->MessageType = KerbWorkstationUnlockLogon;

    BYTE* cursor = blob + sizeof(KERB_INTERACTIVE_UNLOCK_LOGON);
    AppendRelative(&logon->LogonDomainName, domain.c_str(), domain.size(), blob, &cursor);
    AppendRelative(&logon->UserName, user.c_str(), user.size(), blob, &cursor);
    AppendRelative(&logon->Password, password, passwordChars, blob, &cursor);

    *prgb = blob;
    *pcb = cb;
    return S_OK;
}

}  // namespace wake
