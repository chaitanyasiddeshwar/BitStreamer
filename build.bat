@echo off
REM Build both the BitStreamer server and the Fire TV client into dist\.
REM Result: dist\ holds bitstreamer.exe and client.apk side by side — run the
REM server from there and it serves the APK at /client.apk for the Downloader.
setlocal
set "ROOT=%~dp0"

echo ==^> Building server -^> dist\
pushd "%ROOT%server"
call build.bat
set RC=%errorlevel%
popd
if not "%RC%"=="0" (
    echo Server build failed.
    exit /b %RC%
)

echo ==^> Building client ^(assembleRelease^) -^> dist\client.apk
pushd "%ROOT%client"
call gradlew.bat assembleRelease
set RC=%errorlevel%
popd
if not "%RC%"=="0" (
    echo Client build failed.
    exit /b %RC%
)

echo.
echo ==^> Build complete. dist\:
dir /b "%ROOT%dist"
