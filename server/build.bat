@echo off
REM Build the BitStreamer server as a native Windows executable.
REM (macOS/Linux users: use the Makefile instead.)
setlocal
go build -o bitstreamer.exe .
if %ERRORLEVEL% neq 0 (
    echo Build failed.
    exit /b %ERRORLEVEL%
)
echo Built bitstreamer.exe
