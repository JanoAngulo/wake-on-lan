// Tests for the pipe server's request, reply and shutdown paths, against a real local named pipe
// with a test-only name, owned by the current user. Run one case: pipe_server_test <name>.
#include "PipeServer.h"

#include <sddl.h>

#include <atomic>
#include <chrono>
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

static std::wstring PipeName() {
    return L"\\\\.\\pipe\\wake-unlock-test-" + std::to_wstring(GetCurrentProcessId());
}

static std::wstring CurrentUserSid() {
    HANDLE token = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return L"";
    DWORD size = 0;
    GetTokenInformation(token, TokenUser, nullptr, 0, &size);
    std::vector<BYTE> buf(size);
    std::wstring result;
    if (GetTokenInformation(token, TokenUser, buf.data(), size, &size)) {
        LPWSTR text = nullptr;
        if (ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(buf.data())->User.Sid, &text)) {
            result = text;
            LocalFree(text);
        }
    }
    CloseHandle(token);
    return result;
}

struct FakeSink : wake::IUnlockSink {
    HANDLE requested = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    std::wstring password;
    std::atomic<int> done{0};
    bool accept = true;

    ~FakeSink() override { CloseHandle(requested); }
    bool OnUnlockRequest(const std::wstring& p) override {
        password = p;
        SetEvent(requested);
        return accept;
    }
    void OnUnlockDone() override { ++done; }
};

static wake::PipeServerOptions TestOptions() {
    static std::wstring name;
    name = PipeName();
    wake::PipeServerOptions o;
    o.name = name.c_str();
    o.systemOwner = false;
    return o;
}

static HANDLE Connect(DWORD timeoutMs = 3000) {
    auto start = std::chrono::steady_clock::now();
    for (;;) {
        HANDLE h = CreateFileW(PipeName().c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr,
                               OPEN_EXISTING, 0, nullptr);
        if (h != INVALID_HANDLE_VALUE) {
            DWORD mode = PIPE_READMODE_MESSAGE;
            SetNamedPipeHandleState(h, &mode, nullptr, nullptr);
            return h;
        }
        auto elapsed = std::chrono::steady_clock::now() - start;
        if (elapsed > std::chrono::milliseconds(timeoutMs)) return INVALID_HANDLE_VALUE;
        Sleep(20);
    }
}

static bool Send(HANDLE h, uint32_t version, const std::wstring& password) {
    std::vector<uint8_t> frame;
    auto put = [&](uint32_t v) {
        for (int i = 0; i < 4; ++i) frame.push_back(static_cast<uint8_t>(v >> (8 * i)));
    };
    put(version);
    put(static_cast<uint32_t>(password.size() * 2));
    for (wchar_t c : password) {
        frame.push_back(static_cast<uint8_t>(c & 0xFF));
        frame.push_back(static_cast<uint8_t>((c >> 8) & 0xFF));
    }
    DWORD written = 0;
    return WriteFile(h, frame.data(), static_cast<DWORD>(frame.size()), &written, nullptr) &&
           written == frame.size();
}

// Reads the 8-byte reply. False if the pipe closed without one.
static bool Receive(HANDLE h, uint32_t* status, uint32_t* substatus) {
    uint8_t buf[16]{};
    DWORD read = 0;
    if (!ReadFile(h, buf, sizeof(buf), &read, nullptr) || read != 8) return false;
    *status = buf[0] | (buf[1] << 8) | (buf[2] << 16) | (static_cast<uint32_t>(buf[3]) << 24);
    *substatus = buf[4] | (buf[5] << 8) | (buf[6] << 16) | (static_cast<uint32_t>(buf[7]) << 24);
    return true;
}

static long long TimeStopMs(wake::PipeServer& server) {
    auto start = std::chrono::steady_clock::now();
    server.Stop();
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start)
        .count();
}

static void replies_reported_result() {
    FakeSink sink;
    wake::PipeServer server(TestOptions());
    CHECK(SUCCEEDED(server.Start(&sink, CurrentUserSid())));
    HANDLE c = Connect();
    CHECK(c != INVALID_HANDLE_VALUE);
    CHECK(Send(c, 1, L"pässwörd"));
    CHECK(WaitForSingleObject(sink.requested, 3000) == WAIT_OBJECT_0);
    CHECK(sink.password == L"pässwörd");
    CHECK(server.RequestPending());
    server.PostResult(0xC000006D, 0xC000006A);
    uint32_t status = 0, sub = 0;
    CHECK(Receive(c, &status, &sub));
    CHECK(status == 0xC000006D && sub == 0xC000006A);
    CloseHandle(c);
    CHECK(sink.done == 1);
    CHECK(!server.RequestPending());
    CHECK(TimeStopMs(server) < 1000);
}

static void stop_after_submit_replies_provider_closed() {
    FakeSink sink;
    wake::PipeServer server(TestOptions());
    CHECK(SUCCEEDED(server.Start(&sink, CurrentUserSid())));
    HANDLE c = Connect();
    CHECK(Send(c, 1, L"pw"));
    CHECK(WaitForSingleObject(sink.requested, 3000) == WAIT_OBJECT_0);
    server.MarkSubmitted();
    CHECK(TimeStopMs(server) < 1500);
    // Read only after Stop() closed the server end: the reply must still be there.
    uint32_t status = 0, sub = 1;
    CHECK(Receive(c, &status, &sub));
    CHECK(status == wake::kStatusProviderClosed && sub == 0);
    CHECK(sink.done == 0);
    CloseHandle(c);
}

static void stop_before_submit_closes_without_reply() {
    FakeSink sink;
    wake::PipeServer server(TestOptions());
    CHECK(SUCCEEDED(server.Start(&sink, CurrentUserSid())));
    HANDLE c = Connect();
    CHECK(Send(c, 1, L"pw"));
    CHECK(WaitForSingleObject(sink.requested, 3000) == WAIT_OBJECT_0);
    CHECK(TimeStopMs(server) < 1000);
    uint32_t status = 0, sub = 0;
    CHECK(!Receive(c, &status, &sub));
    CloseHandle(c);
}

static void stop_while_idle_is_prompt() {
    FakeSink sink;
    wake::PipeServer server(TestOptions());
    CHECK(SUCCEEDED(server.Start(&sink, CurrentUserSid())));
    Sleep(100);  // let the thread park in ConnectNamedPipe
    CHECK(TimeStopMs(server) < 1000);
    CHECK(!server.Running());
}

static void stop_while_client_silent_is_prompt() {
    FakeSink sink;
    wake::PipeServer server(TestOptions());
    CHECK(SUCCEEDED(server.Start(&sink, CurrentUserSid())));
    HANDLE c = Connect();
    CHECK(c != INVALID_HANDLE_VALUE);
    Sleep(100);  // the server's request read is now pending
    CHECK(TimeStopMs(server) < 1000);
    CloseHandle(c);
}

static void result_timeout_replies_io_timeout() {
    FakeSink sink;
    auto options = TestOptions();
    options.resultWaitMs = 300;
    wake::PipeServer server(options);
    CHECK(SUCCEEDED(server.Start(&sink, CurrentUserSid())));
    HANDLE c = Connect();
    CHECK(Send(c, 1, L"pw"));
    uint32_t status = 0, sub = 1;
    CHECK(Receive(c, &status, &sub));
    CHECK(status == 0xC00000B5 && sub == 0);  // STATUS_IO_TIMEOUT
    CHECK(sink.done == 1);                    // the staged password is dropped
    CloseHandle(c);
    CHECK(TimeStopMs(server) < 1000);
}

static void client_that_never_reads_is_dropped() {
    FakeSink sink;
    auto options = TestOptions();
    options.drainWaitMs = 300;
    wake::PipeServer server(options);
    CHECK(SUCCEEDED(server.Start(&sink, CurrentUserSid())));
    HANDLE stalled = Connect();
    CHECK(Send(stalled, 1, L"pw"));
    CHECK(WaitForSingleObject(sink.requested, 3000) == WAIT_OBJECT_0);
    server.PostResult(0, 0);
    // The stalled client keeps its handle and never reads; the server must move on by itself.
    HANDLE next = Connect(3000);
    CHECK(next != INVALID_HANDLE_VALUE);
    if (next != INVALID_HANDLE_VALUE) CloseHandle(next);
    CloseHandle(stalled);
    CHECK(TimeStopMs(server) < 1000);
}

static void stop_during_drain_keeps_reply() {
    FakeSink sink;
    wake::PipeServer server(TestOptions());  // default 2 s drain wait
    CHECK(SUCCEEDED(server.Start(&sink, CurrentUserSid())));
    HANDLE c = Connect();
    CHECK(Send(c, 1, L"pw"));
    CHECK(WaitForSingleObject(sink.requested, 3000) == WAIT_OBJECT_0);
    server.PostResult(0, 0);
    Sleep(100);  // reply written, server now waiting for the client to read and close
    CHECK(TimeStopMs(server) < 1000);
    uint32_t status = 1, sub = 1;
    CHECK(Receive(c, &status, &sub));
    CHECK(status == 0 && sub == 0);
    CloseHandle(c);
}

static void rejects_bad_request() {
    FakeSink sink;
    wake::PipeServer server(TestOptions());
    CHECK(SUCCEEDED(server.Start(&sink, CurrentUserSid())));
    HANDLE c = Connect();
    CHECK(Send(c, 2, L"pw"));
    uint32_t status = 0, sub = 1;
    CHECK(Receive(c, &status, &sub));
    CHECK(status == 0xC000000D && sub == 0);  // STATUS_INVALID_PARAMETER
    CHECK(WaitForSingleObject(sink.requested, 0) == WAIT_TIMEOUT);
    CloseHandle(c);
    server.Stop();
}

static void refuses_existing_pipe_name() {
    // Someone else already owns the name: Start must fail instead of joining their pipe.
    HANDLE squatter = CreateNamedPipeW(PipeName().c_str(), PIPE_ACCESS_DUPLEX, PIPE_TYPE_MESSAGE,
                                       PIPE_UNLIMITED_INSTANCES, 64, 64, 0, nullptr);
    CHECK(squatter != INVALID_HANDLE_VALUE);
    FakeSink sink;
    wake::PipeServer server(TestOptions());
    CHECK(FAILED(server.Start(&sink, CurrentUserSid())));
    CHECK(!server.Running());
    CloseHandle(squatter);
}

static void refuses_empty_sid() {
    FakeSink sink;
    wake::PipeServer server(TestOptions());
    CHECK(server.Start(&sink, L"") == E_INVALIDARG);
    CHECK(!server.Running());
}

struct Case {
    const char* name;
    void (*run)();
};

int main(int argc, char** argv) {
    const Case cases[] = {
        {"replies_reported_result", replies_reported_result},
        {"stop_after_submit_replies_provider_closed", stop_after_submit_replies_provider_closed},
        {"stop_before_submit_closes_without_reply", stop_before_submit_closes_without_reply},
        {"stop_while_idle_is_prompt", stop_while_idle_is_prompt},
        {"stop_while_client_silent_is_prompt", stop_while_client_silent_is_prompt},
        {"result_timeout_replies_io_timeout", result_timeout_replies_io_timeout},
        {"client_that_never_reads_is_dropped", client_that_never_reads_is_dropped},
        {"stop_during_drain_keeps_reply", stop_during_drain_keeps_reply},
        {"rejects_bad_request", rejects_bad_request},
        {"refuses_existing_pipe_name", refuses_existing_pipe_name},
        {"refuses_empty_sid", refuses_empty_sid},
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
