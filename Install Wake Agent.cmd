@echo off
rem Double-click to install or update the Wake agent on this PC.
rem Asks for administrator permission, then runs pc-setup\setup.ps1.
setlocal
cd /d "%~dp0"

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Asking for administrator permission...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

echo.
echo  Wake agent setup
echo  ================
echo.

if exist "WakeAgent\WakeAgent.exe" (
    rem Release download: the agent ships with its own Java runtime.
    powershell -NoProfile -ExecutionPolicy Bypass -File "pc-setup\setup.ps1" -AgentDir "WakeAgent" -UnlockDll "unlock\WakeCredentialProvider.dll"
    goto done
)

rem Source checkout: build the agent jar if needed and run it with the installed JDK 21.
if not exist "agent\build\libs\wake-agent.jar" (
    echo Building the agent, this takes a minute...
    call gradlew.bat :agent:agentJar -q
    if errorlevel 1 (
        echo.
        echo Build failed. Open a terminal here and run: gradlew :agent:agentJar
        pause
        exit /b 1
    )
)
rem The unlock module is optional here: build it first with credential-provider\build.cmd.
set "UNLOCK_DLL=credential-provider\build\Release\WakeCredentialProvider.dll"
if defined WAKE_CP_BUILD set "UNLOCK_DLL=%WAKE_CP_BUILD%\Release\WakeCredentialProvider.dll"
powershell -NoProfile -ExecutionPolicy Bypass -File "pc-setup\setup.ps1" -AgentJar "agent\build\libs\wake-agent.jar" -UnlockDll "%UNLOCK_DLL%"

:done
if errorlevel 1 (
    echo.
    echo Setup stopped with an error. The message above says what went wrong.
) else (
    echo.
    echo Done. Look for the green power icon in the system tray ^(click ^^ if hidden^).
    echo To pair a phone: click the icon and choose "Pair a phone...".
)
echo.
pause
