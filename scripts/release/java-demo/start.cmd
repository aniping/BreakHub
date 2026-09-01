@echo off
setlocal EnableExtensions DisableDelayedExpansion

set /a "_BREAKHUB_JAR_COUNT=0"
set "_BREAKHUB_JAR="
for %%F in ("%~dp0instrument-demo*.jar") do if exist "%%~fF" (
    set /a "_BREAKHUB_JAR_COUNT+=1"
    set "_BREAKHUB_JAR=%%~fF"
)
if not "%_BREAKHUB_JAR_COUNT%"=="1" (
    echo [BreakHub] Expected exactly one Java Demo JAR beside this script; found %_BREAKHUB_JAR_COUNT%. 1>&2
    exit /b 1
)
where.exe java.exe >nul 2>&1
if errorlevel 1 (
    echo [BreakHub] Java ^(java.exe^) was not found. 1>&2
    exit /b 9009
)

pushd "%~dp0"
java.exe -jar "%_BREAKHUB_JAR%"
set "_BREAKHUB_EXIT_CODE=%ERRORLEVEL%"
popd
if not "%_BREAKHUB_EXIT_CODE%"=="0" echo [BreakHub] Java Demo exited with code %_BREAKHUB_EXIT_CODE%. 1>&2
endlocal & exit /b %_BREAKHUB_EXIT_CODE%
