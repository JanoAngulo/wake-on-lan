#pragma once

#include <windows.h>

#include <cstdarg>
#include <cwchar>

namespace wake {

// One line to the Application event log (source "WakeUnlock") so the lock-screen behaviour can be
// seen afterwards. Status codes and counts only: never a password or a user name.
inline void Trace(const wchar_t* format, ...) noexcept {
    wchar_t text[200];
    va_list args;
    va_start(args, format);
    _vsnwprintf_s(text, _TRUNCATE, format, args);
    va_end(args);
    static HANDLE source = RegisterEventSourceW(nullptr, L"WakeUnlock");
    if (!source) return;
    const wchar_t* strings[] = {text};
    ReportEventW(source, EVENTLOG_INFORMATION_TYPE, 0, 1, nullptr, 1, 0, strings, nullptr);
}

}  // namespace wake
