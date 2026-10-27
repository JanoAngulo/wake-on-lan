cmake_minimum_required(VERSION 3.20)
project(WakeCredentialProvider LANGUAGES CXX RC)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Static CRT: LogonUI must not depend on a Visual C++ redistributable being installed.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
add_compile_definitions(UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
add_compile_options(/W4 /permissive- /EHsc /utf-8)

add_library(pipe_protocol STATIC src/PipeProtocol.cpp)
target_include_directories(pipe_protocol PUBLIC src)

# The parts with no COM or LogonUI state: the pipe server and the unlock-logon packing.
add_library(provider_core STATIC src/PipeServer.cpp src/UnlockLogon.cpp)
target_link_libraries(provider_core PUBLIC pipe_protocol advapi32 ole32)

# The credential provider DLL: COM in-process server loaded by LogonUI as SYSTEM.
add_library(WakeCredentialProvider SHARED
    src/Dll.cpp
    src/ClassFactory.cpp
    src/Provider.cpp
    src/Credential.cpp
    src/resources.rc)
target_link_libraries(WakeCredentialProvider PRIVATE provider_core secur32 shlwapi advapi32 credui)
target_sources(WakeCredentialProvider PRIVATE src/WakeCredentialProvider.def)
# Unused-parameter noise from the many COM stubs; the DLL keeps /W4 otherwise.
target_compile_options(WakeCredentialProvider PRIVATE /wd4100)

enable_testing()
add_executable(pipe_protocol_test tests/pipe_protocol_test.cpp)
target_link_libraries(pipe_protocol_test PRIVATE pipe_protocol)
foreach(name parses_ascii parses_non_ascii_utf16 rejects_wrong_version rejects_length_past_end
             rejects_odd_length rejects_over_1024 formats_reply)
    add_test(NAME ${name} COMMAND pipe_protocol_test ${name})
endforeach()

add_executable(unlock_logon_test tests/unlock_logon_test.cpp)
target_link_libraries(unlock_logon_test PRIVATE provider_core)
foreach(name packs_local_unlock_logon packs_empty_domain rejects_oversized_strings
             splits_qualified_names)
    add_test(NAME ${name} COMMAND unlock_logon_test ${name})
endforeach()

add_executable(pipe_server_test tests/pipe_server_test.cpp)
target_link_libraries(pipe_server_test PRIVATE provider_core)
foreach(name replies_reported_result stop_after_submit_replies_provider_closed
             stop_before_submit_closes_without_reply stop_while_idle_is_prompt
             stop_while_client_silent_is_prompt result_timeout_replies_io_timeout
             client_that_never_reads_is_dropped stop_during_drain_keeps_reply rejects_bad_request
             refuses_existing_pipe_name refuses_empty_sid)
    add_test(NAME ${name} COMMAND pipe_server_test ${name})
    # A shutdown bug shows up as a hang; fail it instead of stalling the build.
    set_tests_properties(${name} PROPERTIES TIMEOUT 20)
endforeach()
