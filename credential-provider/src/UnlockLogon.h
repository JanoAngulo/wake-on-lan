#pragma once

#include <windows.h>
#include <objbase.h>
#include <ntsecapi.h>

#include <string>

// Packing of the unlock-logon serialization, kept free of COM objects and LogonUI state so the
// native tests can check it. Mirrors the KerbInteractiveUnlockLogonInit/Pack helpers in Microsoft's
// Windows-classic-samples CredentialProvider (MIT).
namespace wake {

// Split "DOMAIN\user" at the first backslash. No backslash: empty domain, the whole name as user.
void SplitUserName(const std::wstring& qualified, std::wstring* domain, std::wstring* user);

// Pack a KERB_INTERACTIVE_UNLOCK_LOGON with MessageType KerbWorkstationUnlockLogon. The three
// strings follow the struct in one CoTaskMemAlloc block; each UNICODE_STRING.Buffer holds the
// string's byte offset from the start of the block, which is what LSA expects. Lengths are in bytes
// with no terminator. The caller owns *prgb (CoTaskMemFree, after SecureZeroMemory).
HRESULT PackUnlockLogon(const std::wstring& domain, const std::wstring& user, const wchar_t* password,
                        size_t passwordChars, BYTE** prgb, DWORD* pcb);

}  // namespace wake
