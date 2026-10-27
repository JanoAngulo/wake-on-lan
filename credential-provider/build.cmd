@echo off
rem Builds WakeCredentialProvider.dll (x64, Release) and runs its tests.
rem Needs Visual Studio 2022 Build Tools with the C++ workload (includes CMake and the Windows SDK).
rem Output: credential-provider\build\Release\WakeCredentialProvider.dll, or %WAKE_CP_BUILD%\Release\ when set
rem (useful when Controlled Folder Access stops CMake writing inside the checkout).
setlocal
if not defined WAKE_CP_BUILD set "WAKE_CP_BUILD=%~dp0build"
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" goto notools
for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Workload.VCTools -property installationPath`) do set "VS=%%i"
if not defined VS goto notools
set "CMAKE_BIN=%VS%\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin"

cd /d "%~dp0"
"%CMAKE_BIN%\cmake.exe" -S . -B "%WAKE_CP_BUILD%" -G "Visual Studio 17 2022" -A x64 || exit /b 1
"%CMAKE_BIN%\cmake.exe" --build "%WAKE_CP_BUILD%" --config Release || exit /b 1
"%CMAKE_BIN%\ctest.exe" --test-dir "%WAKE_CP_BUILD%" -C Release --output-on-failure || exit /b 1
exit /b 0

:notools
echo Visual Studio 2022 Build Tools with the C++ workload were not found.
exit /b 1
