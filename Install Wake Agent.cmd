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

powershell -NoProfile -ExecutionPolicy Bypass -File "pc-setup\setup.ps1" -AgentJar "agent\build\libs\wake-agent.jar"
if errorlevel 1 (
    echo.
    echo Setup stopped with an error. The message above says what went wrong.
) else (
    echo.
    echo Done. Look for the green power icon in the system tray ^(click ^^ if hidden^).
    echo To pair a phone: right-click the icon and choose "Pair phone...".
)
echo.
pause
