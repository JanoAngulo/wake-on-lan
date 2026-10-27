#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>

// Frames on \\.\pipe\wake-unlock, all little-endian.
//   agent -> provider: u32 version (1), u32 length, length bytes of the password as UTF-16LE
//   provider -> agent: u32 status, u32 substatus (the NTSTATUS pair from ReportResult; 0 = signed in)
namespace wake {

constexpr uint32_t kProtocolVersion = 1;
constexpr size_t kMaxPasswordBytes = 1024;

std::optional<std::wstring> ParseRequest(const uint8_t* data, size_t size);

std::array<uint8_t, 8> FormatReply(uint32_t status, uint32_t substatus);

}  // namespace wake
