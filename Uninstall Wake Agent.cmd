@echo off
rem Double-click to remove the Wake agent: logon task, firewall rules, files and pairing.
setlocal
cd /d "%~dp0"

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Asking for administrator permission...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "pc-setup\setup.ps1" -Uninstall
echo.
pause
