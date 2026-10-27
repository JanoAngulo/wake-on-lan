#include "PipeProtocol.h"

namespace wake {

static uint32_t ReadU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

static void WriteU32(uint8_t* p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = static_cast<uint8_t>(v >> (8 * i));
}

std::optional<std::wstring> ParseRequest(const uint8_t* data, size_t size) {
    if (data == nullptr || size < 8) return std::nullopt;
    if (ReadU32(data) != kProtocolVersion) return std::nullopt;
    const uint32_t length = ReadU32(data + 4);
    if (length > kMaxPasswordBytes || length % 2 != 0 || length > size - 8) return std::nullopt;

    std::wstring password(length / 2, L'\0');
    for (size_t i = 0; i < password.size(); ++i) {
        const uint8_t* unit = data + 8 + 2 * i;
        password[i] = static_cast<wchar_t>(unit[0] | (unit[1] << 8));
    }
    return password;
}

std::array<uint8_t, 8> FormatReply(uint32_t status, uint32_t substatus) {
    std::array<uint8_t, 8> reply{};
    WriteU32(reply.data(), status);
    WriteU32(reply.data() + 4, substatus);
    return reply;
}

}  // namespace wake
