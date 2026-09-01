@echo off
setlocal DisableDelayedExpansion

set "_BREAKHUB_PYTHON=python.exe"
:breakhub_scan_python
if "%~1"=="" goto breakhub_check_python
if /I "%~1"=="-Python" goto breakhub_capture_python
if /I "%~1"=="--python" goto breakhub_capture_python
shift /1
goto breakhub_scan_python

:breakhub_capture_python
if "%~2"=="" (
    echo [BreakHub] %~1 requires a Python executable. 1>&2
    exit /b 2
)
set "_BREAKHUB_PYTHON=%~2"
shift /1
shift /1
goto breakhub_scan_python

:breakhub_check_python
if exist "%_BREAKHUB_PYTHON%" goto breakhub_run
where.exe "%_BREAKHUB_PYTHON%" >nul 2>&1
if errorlevel 1 (
    echo [BreakHub] Python was not found: %_BREAKHUB_PYTHON% 1>&2
    exit /b 9009
)

:breakhub_run
"%_BREAKHUB_PYTHON%" "%~dp0internal\repo_tasks.py" build %*
set "_BREAKHUB_EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %_BREAKHUB_EXIT_CODE%
