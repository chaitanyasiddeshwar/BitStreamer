@echo off
REM Build the BitStreamer server as a native Windows executable into the shared
REM repo-root dist\ folder (alongside client.apk). macOS/Linux: use the Makefile.
setlocal
if not exist "..\dist" mkdir "..\dist"
go build -o "..\dist\bitstreamer.exe" .
if %ERRORLEVEL% neq 0 (
    echo Build failed.
    exit /b %ERRORLEVEL%
)
echo Built ..\dist\bitstreamer.exe
