// Tests for picking the tile's user and building the pipe's security descriptor. Run one case:
// sign_in_config_test <name>.
#include "SignInConfig.h"

#include <cstdio>
#include <cstring>

static int failures = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);      \
            ++failures;                                                      \
        }                                                                    \
    } while (0)

using wake::BuildPipeSddl;
using wake::LooksLikeSid;
using wake::PickUser;

static const std::vector<std::wstring> kUsers = {L"S-1-5-21-1-1001", L"S-1-5-21-1-1002"};
static const wchar_t kWithoutService[] = L"O:SYD:(A;;GA;;;SY)(A;;GRGW;;;S-1-5-21-1-1001)";

static void picks_preferred_user() {
    CHECK(PickUser(kUsers, std::wstring(L"S-1-5-21-1-1002")) == 1);
}

static void picks_preferred_case_insensitive() {
    CHECK(PickUser(kUsers, std::wstring(L"s-1-5-21-1-1002")) == 1);
}

static void falls_back_to_first_when_absent() {
    CHECK(PickUser(kUsers, std::nullopt) == 0);
}

static void falls_back_to_first_when_not_listed() {
    CHECK(PickUser(kUsers, std::wstring(L"S-1-5-21-1-1003")) == 0);
}

static void sddl_without_service() {
    CHECK(BuildPipeSddl(true, L"S-1-5-21-1-1001", std::nullopt) == kWithoutService);
}

static void sddl_with_service() {
    CHECK(BuildPipeSddl(true, L"S-1-5-21-1-1001", std::wstring(L"S-1-5-87-1-2-3-4-5")) ==
          std::wstring(kWithoutService) + L"(A;;GRGW;;;S-1-5-87-1-2-3-4-5)");
}

static void sddl_drops_invalid_service_sid() {
    CHECK(BuildPipeSddl(true, L"S-1-5-21-1-1001", std::wstring(L"S-1-5-87)(A;;GA;;;WD")) == kWithoutService);
}

static void sddl_without_system_owner() {
    CHECK(BuildPipeSddl(false, L"S-1-5-21-1-1001", std::nullopt) == L"D:(A;;GA;;;SY)(A;;GRGW;;;S-1-5-21-1-1001)");
}

static void rejects_non_sid_strings() {
    CHECK(!LooksLikeSid(L""));
    CHECK(!LooksLikeSid(L"WD"));
    CHECK(!LooksLikeSid(L"S-1-5-21-1)(A"));
    CHECK(!LooksLikeSid(L"S-1-"));
    CHECK(!LooksLikeSid(L"S-1-" + std::wstring(181, L'1')));
    CHECK(LooksLikeSid(L"S-1-5-87-1039190396-1455568823"));
}

struct Case {
    const char* name;
    void (*run)();
};

int main(int argc, char** argv) {
    const Case cases[] = {
        {"picks_preferred_user", picks_preferred_user},
        {"picks_preferred_case_insensitive", picks_preferred_case_insensitive},
        {"falls_back_to_first_when_absent", falls_back_to_first_when_absent},
        {"falls_back_to_first_when_not_listed", falls_back_to_first_when_not_listed},
        {"sddl_without_service", sddl_without_service},
        {"sddl_with_service", sddl_with_service},
        {"sddl_drops_invalid_service_sid", sddl_drops_invalid_service_sid},
        {"sddl_without_system_owner", sddl_without_system_owner},
        {"rejects_non_sid_strings", rejects_non_sid_strings},
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
