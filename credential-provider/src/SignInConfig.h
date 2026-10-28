#pragma once

#include <optional>
#include <string>
#include <vector>

// Which user the tile serves and who may open the pipe. The first three are pure so the native tests
// can check them; the last two read Windows state.
namespace wake {

// The account the pre-login sign-in program runs under (its task SID). One line to change if it ever
// becomes a Windows service instead (spec §9).
inline constexpr wchar_t kSignInAccount[] = L"NT TASK\\Wake Sign-in";

// Index of the user whose SID matches preferred (case-insensitive); 0 when there's no match.
size_t PickUser(const std::vector<std::wstring>& sids, const std::optional<std::wstring>& preferred);

// "S-1-" followed by digits and dashes only, at most 184 characters. Guards what goes into an SDDL string.
bool LooksLikeSid(const std::wstring& s);

// Owner SYSTEM (when systemOwner), SYSTEM full control, the user read+write, and the sign-in program
// read+write when serviceSid is a valid SID.
std::wstring BuildPipeSddl(bool systemOwner, const std::wstring& userSid, const std::optional<std::wstring>& serviceSid);

// HKLM\SOFTWARE\Wake SignInUserSid, written by the installer (admin-only). Nullopt when absent or not a SID.
std::optional<std::wstring> ReadPreferredUserSid();

// The SID of kSignInAccount, or nullopt when it doesn't exist (sign-in after shutdown not installed).
std::optional<std::wstring> LookupServiceSid();

}  // namespace wake
