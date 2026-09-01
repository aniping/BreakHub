@echo off
setlocal DisableDelayedExpansion

where.exe python.exe >nul 2>&1
if errorlevel 1 (
    echo [BreakHub] Python ^(python.exe^) is required. 1>&2
    exit /b 9009
)

python.exe "%~dp0internal\repo_tasks.py" test %*
set "_BREAKHUB_EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %_BREAKHUB_EXIT_CODE%
