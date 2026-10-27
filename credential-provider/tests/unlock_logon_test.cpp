// Tests for the local-user KERB_INTERACTIVE_UNLOCK_LOGON packing. Run one case: unlock_logon_test <name>.
#include "UnlockLogon.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

static int failures = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);      \
            ++failures;                                                      \
        }                                                                    \
    } while (0)

// Byte offset stored in a relocated UNICODE_STRING.Buffer.
static size_t OffsetOf(const UNICODE_STRING& u) {
    return static_cast<size_t>(reinterpret_cast<uintptr_t>(u.Buffer));
}

// The string's bytes sit inside the block at its offset and match what was packed.
static bool HoldsAt(const BYTE* blob, DWORD cb, const UNICODE_STRING& u, const std::wstring& expected) {
    size_t offset = OffsetOf(u);
    if (u.Length != expected.size() * sizeof(wchar_t)) return false;
    if (u.MaximumLength != u.Length) return false;
    if (offset < sizeof(KERB_INTERACTIVE_UNLOCK_LOGON) || offset + u.Length > cb) return false;
    return u.Length == 0 || std::memcmp(blob + offset, expected.data(), u.Length) == 0;
}

static void packs_local_unlock_logon() {
    const std::wstring domain = L"MY-PC", user = L"john", password = L"pässwörd";
    BYTE* blob = nullptr;
    DWORD cb = 0;
    HRESULT hr = wake::PackUnlockLogon(KerbWorkstationUnlockLogon, domain, user, password.c_str(), password.size(), &blob, &cb);
    CHECK(SUCCEEDED(hr));
    if (FAILED(hr) || !blob) return;

    const size_t expectedSize =
        sizeof(KERB_INTERACTIVE_UNLOCK_LOGON) + (domain.size() + user.size() + password.size()) * 2;
    CHECK(cb == expectedSize);

    auto* kiul = reinterpret_cast<const KERB_INTERACTIVE_UNLOCK_LOGON*>(blob);
    CHECK(kiul->Logon.MessageType == KerbWorkstationUnlockLogon);
    CHECK(kiul->LogonId.LowPart == 0 && kiul->LogonId.HighPart == 0);
    CHECK(HoldsAt(blob, cb, kiul->Logon.LogonDomainName, domain));
    CHECK(HoldsAt(blob, cb, kiul->Logon.UserName, user));
    CHECK(HoldsAt(blob, cb, kiul->Logon.Password, password));

    // Packed back to back right after the struct, in domain, user, password order.
    CHECK(OffsetOf(kiul->Logon.LogonDomainName) == sizeof(KERB_INTERACTIVE_UNLOCK_LOGON));
    CHECK(OffsetOf(kiul->Logon.UserName) == OffsetOf(kiul->Logon.LogonDomainName) + domain.size() * 2);
    CHECK(OffsetOf(kiul->Logon.Password) == OffsetOf(kiul->Logon.UserName) + user.size() * 2);
    CHECK(OffsetOf(kiul->Logon.Password) + password.size() * 2 == cb);

    SecureZeroMemory(blob, cb);
    CoTaskMemFree(blob);
}

// Windows 10+ unlocks the lock screen through CPUS_LOGON, which takes an interactive logon.
static void packs_lock_screen_logon_as_interactive() {
    const std::wstring password = L"x";
    BYTE* blob = nullptr;
    DWORD cb = 0;
    HRESULT hr = wake::PackUnlockLogon(KerbInteractiveLogon, L"MY-PC", L"john", password.c_str(), password.size(), &blob, &cb);
    CHECK(SUCCEEDED(hr));
    if (FAILED(hr) || !blob) return;
    CHECK(reinterpret_cast<const KERB_INTERACTIVE_UNLOCK_LOGON*>(blob)->Logon.MessageType == KerbInteractiveLogon);
    SecureZeroMemory(blob, cb);
    CoTaskMemFree(blob);
}

static void packs_empty_domain() {
    const std::wstring password = L"ñé\xD83D\xDE00";
    BYTE* blob = nullptr;
    DWORD cb = 0;
    HRESULT hr = wake::PackUnlockLogon(KerbWorkstationUnlockLogon, L"", L"john", password.c_str(), password.size(), &blob, &cb);
    CHECK(SUCCEEDED(hr));
    if (FAILED(hr) || !blob) return;
    auto* kiul = reinterpret_cast<const KERB_INTERACTIVE_UNLOCK_LOGON*>(blob);
    CHECK(kiul->Logon.LogonDomainName.Length == 0);
    CHECK(HoldsAt(blob, cb, kiul->Logon.UserName, L"john"));
    CHECK(HoldsAt(blob, cb, kiul->Logon.Password, password));
    CHECK(kiul->Logon.Password.Length == 8);  // lengths are bytes, not characters
    SecureZeroMemory(blob, cb);
    CoTaskMemFree(blob);
}

static void rejects_oversized_strings() {
    std::wstring huge(0x8000, L'a');
    BYTE* blob = reinterpret_cast<BYTE*>(1);
    DWORD cb = 1;
    CHECK(wake::PackUnlockLogon(KerbWorkstationUnlockLogon, L"MY-PC", L"john", huge.c_str(), huge.size(), &blob, &cb) == E_INVALIDARG);
    CHECK(blob == nullptr && cb == 0);
}

static void splits_qualified_names() {
    std::wstring domain, user;
    wake::SplitUserName(L"MY-PC\\john", &domain, &user);
    CHECK(domain == L"MY-PC" && user == L"john");
    wake::SplitUserName(L"MicrosoftAccount\\user@example.com", &domain, &user);
    CHECK(domain == L"MicrosoftAccount" && user == L"user@example.com");
    wake::SplitUserName(L"john", &domain, &user);
    CHECK(domain.empty() && user == L"john");
}

struct Case {
    const char* name;
    void (*run)();
};

int main(int argc, char** argv) {
    const Case cases[] = {
        {"packs_local_unlock_logon", packs_local_unlock_logon},
        {"packs_lock_screen_logon_as_interactive", packs_lock_screen_logon_as_interactive},
        {"packs_empty_domain", packs_empty_domain},
        {"rejects_oversized_strings", rejects_oversized_strings},
        {"splits_qualified_names", splits_qualified_names},
    };
    int ran = 0;
    for (const auto& c : cases) {
        if (argc > 1 && std::strcmp(argv[1], c.name) != 0) continue;
        c.run();
        ++ran;
    }
    if (ran == 0) {
        std::printf("unknown test %s\n", argc > 1 ? argv[1] : "");
        return 2;
    }
    std::printf(failures ? "%d check(s) failed\n" : "ok\n", failures);
    return failures ? 1 : 0;
}
