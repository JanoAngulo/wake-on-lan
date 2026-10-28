#include "SignInConfig.h"

#include <windows.h>
#include <sddl.h>

#include <cwchar>

namespace wake {

size_t PickUser(const std::vector<std::wstring>& sids, const std::optional<std::wstring>& preferred) {
    if (preferred) {
        for (size_t i = 0; i < sids.size(); ++i) {
            if (_wcsicmp(sids[i].c_str(), preferred->c_str()) == 0) return i;
        }
    }
    return 0;
}

bool LooksLikeSid(const std::wstring& s) {
    if (s.size() > 184 || s.size() < 5 || s.compare(0, 4, L"S-1-") != 0 || s.back() == L'-') return false;
    for (size_t i = 4; i < s.size(); ++i) {
        if (!iswdigit(s[i]) && s[i] != L'-') return false;
    }
    return true;
}

std::wstring BuildPipeSddl(bool systemOwner, const std::wstring& userSid, const std::optional<std::wstring>& serviceSid) {
    std::wstring sddl = systemOwner ? L"O:SY" : L"";
    sddl += L"D:(A;;GA;;;SY)(A;;GRGW;;;" + userSid + L")";
    if (serviceSid && LooksLikeSid(*serviceSid)) sddl += L"(A;;GRGW;;;" + *serviceSid + L")";
    return sddl;
}

std::optional<std::wstring> ReadPreferredUserSid() {
    wchar_t value[256] = {};
    DWORD bytes = sizeof(value);
    if (RegGetValueW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Wake", L"SignInUserSid", RRF_RT_REG_SZ, nullptr, value, &bytes) !=
        ERROR_SUCCESS) {
        return std::nullopt;
    }
    std::wstring sid = value;
    if (!LooksLikeSid(sid)) return std::nullopt;
    return sid;
}

std::optional<std::wstring> LookupServiceSid() {
    BYTE sid[SECURITY_MAX_SID_SIZE] = {};
    DWORD sidSize = sizeof(sid);
    wchar_t domain[256] = {};
    DWORD domainSize = ARRAYSIZE(domain);
    SID_NAME_USE use{};
    if (!LookupAccountNameW(nullptr, kSignInAccount, sid, &sidSize, domain, &domainSize, &use)) return std::nullopt;
    LPWSTR text = nullptr;
    if (!ConvertSidToStringSidW(sid, &text)) return std::nullopt;
    std::wstring result = text;
    LocalFree(text);
    return result;
}

}  // namespace wake
