@echo off
setlocal DisableDelayedExpansion

where.exe pwsh.exe >nul 2>&1
if errorlevel 1 (
    echo [BreakHub] PowerShell 7 ^(pwsh.exe^) is required. 1>&2
    exit /b 9009
)

pwsh.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0internal\package-java-demo.ps1" %*
set "_BREAKHUB_EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %_BREAKHUB_EXIT_CODE%
