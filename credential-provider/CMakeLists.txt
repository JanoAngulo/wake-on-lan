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

# The credential provider DLL: COM in-process server loaded by LogonUI as SYSTEM.
add_library(WakeCredentialProvider SHARED
    src/Dll.cpp
    src/ClassFactory.cpp
    src/Provider.cpp
    src/Credential.cpp
    src/PipeServer.cpp
    src/resources.rc)
target_link_libraries(WakeCredentialProvider PRIVATE pipe_protocol secur32 shlwapi advapi32)
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
