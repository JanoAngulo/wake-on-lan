// Tests for the agent <-> credential provider pipe frames. Run one case: pipe_protocol_test <name>.
#include "PipeProtocol.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static int failures = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        if (!(cond)) {                                                       \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);      \
            ++failures;                                                      \
        }                                                                    \
    } while (0)

static void PutU32(std::vector<uint8_t>& out, uint32_t v) {
    for (int i = 0; i < 4; ++i) out.push_back(static_cast<uint8_t>(v >> (8 * i)));
}

static std::vector<uint8_t> Utf16le(const std::wstring& s) {
    std::vector<uint8_t> out;
    for (wchar_t c : s) {
        out.push_back(static_cast<uint8_t>(c & 0xFF));
        out.push_back(static_cast<uint8_t>((c >> 8) & 0xFF));
    }
    return out;
}

static std::vector<uint8_t> Frame(uint32_t version, uint32_t length, const std::vector<uint8_t>& payload) {
    std::vector<uint8_t> out;
    PutU32(out, version);
    PutU32(out, length);
    out.insert(out.end(), payload.begin(), payload.end());
    return out;
}

static std::vector<uint8_t> PasswordFrame(const std::wstring& password) {
    auto payload = Utf16le(password);
    return Frame(1, static_cast<uint32_t>(payload.size()), payload);
}

static void parses_ascii() {
    auto frame = PasswordFrame(L"abc");
    auto parsed = wake::ParseRequest(frame.data(), frame.size());
    CHECK(parsed.has_value());
    CHECK(parsed && *parsed == L"abc");
}

static void parses_non_ascii_utf16() {
    // n-tilde, e-acute, then U+1F600 as a surrogate pair: 8 bytes in all.
    const std::wstring password = L"ñé\xD83D\xDE00";
    auto frame = PasswordFrame(password);
    CHECK(frame.size() == 8 + 8);
    auto parsed = wake::ParseRequest(frame.data(), frame.size());
    CHECK(parsed && *parsed == password);
}

static void rejects_wrong_version() {
    auto payload = Utf16le(L"abc");
    auto frame = Frame(2, static_cast<uint32_t>(payload.size()), payload);
    CHECK(!wake::ParseRequest(frame.data(), frame.size()).has_value());
}

static void rejects_length_past_end() {
    auto frame = Frame(1, 10, Utf16le(L"abc"));
    CHECK(!wake::ParseRequest(frame.data(), frame.size()).has_value());
}

static void rejects_odd_length() {
    auto frame = Frame(1, 3, {'a', 0, 'b'});
    CHECK(!wake::ParseRequest(frame.data(), frame.size()).has_value());
}

static void rejects_over_1024() {
    std::vector<uint8_t> payload(1026, 'a');
    auto frame = Frame(1, static_cast<uint32_t>(payload.size()), payload);
    CHECK(!wake::ParseRequest(frame.data(), frame.size()).has_value());
    CHECK(!wake::ParseRequest(frame.data(), 4).has_value());
}

static void formats_reply() {
    auto reply = wake::FormatReply(0xC000006D, 0xC000006A);
    const uint8_t expected[8] = {0x6D, 0x00, 0x00, 0xC0, 0x6A, 0x00, 0x00, 0xC0};
    CHECK(std::memcmp(reply.data(), expected, 8) == 0);
}

struct Case {
    const char* name;
    void (*run)();
};

int main(int argc, char** argv) {
    const Case cases[] = {
        {"parses_ascii", parses_ascii},
        {"parses_non_ascii_utf16", parses_non_ascii_utf16},
        {"rejects_wrong_version", rejects_wrong_version},
        {"rejects_length_past_end", rejects_length_past_end},
        {"rejects_odd_length", rejects_odd_length},
        {"rejects_over_1024", rejects_over_1024},
        {"formats_reply", formats_reply},
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
