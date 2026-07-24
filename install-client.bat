@echo off

:: 1. Get Fire TV IP address from command line argument if provided
set "FIRETV_IP=%~1"
if "%FIRETV_IP%"=="" set "FIRETV_IP=%1"
if "%FIRETV_IP%"=="" set "FIRETV_IP=%*"

echo =========================================================
echo  BitStreamer Client APK - Fire TV ADB Installer
echo =========================================================
echo.

:: 2. Find ADB executable
set "ADB_CMD=adb"
where adb >nul 2>&1
if not errorlevel 1 goto ADB_FOUND
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ADB_CMD=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    echo Found Android Studio ADB: %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
    goto ADB_FOUND
)
echo [ERROR] 'adb' command not found!
echo Please install Android Platform Tools or add adb to your system PATH.
echo.
pause
exit /b 1

:ADB_FOUND

:: 3. Locate client APK
set "APK_FILE="
if exist "%~dp0client.apk" set "APK_FILE=%~dp0client.apk"
if "%APK_FILE%"=="" if exist "%~dp0client\app\build\outputs\apk\debug\app-debug.apk" set "APK_FILE=%~dp0client\app\build\outputs\apk\debug\app-debug.apk"
if "%APK_FILE%"=="" if exist "%~dp0client\app\build\outputs\apk\release\app-release.apk" set "APK_FILE=%~dp0client\app\build\outputs\apk\release\app-release.apk"

if not "%APK_FILE%"=="" goto APK_FOUND
echo [ERROR] Could not find client.apk!
echo Please make sure client.apk is in the same directory as this script.
echo.
pause
exit /b 1

:APK_FOUND
echo Found APK: %APK_FILE%
echo.

:: 4. Prompt for IP address if not provided as argument
if not "%FIRETV_IP%"=="" goto GOT_IP
echo (Tip: On your Fire TV, check Settings -^> My Fire TV -^> About -^> Network for the IP address)
set /p "FIRETV_IP=Enter Fire TV IP Address (e.g. 192.168.1.50): "

:GOT_IP
if not "%FIRETV_IP%"=="" goto RUN_INSTALL
echo [ERROR] No IP address provided. Exiting.
pause
exit /b 1

:RUN_INSTALL
echo.
echo Connecting to Fire TV at %FIRETV_IP%:5555 ...
"%ADB_CMD%" connect %FIRETV_IP%:5555

echo.
echo Installing %APK_FILE% to %FIRETV_IP% ...
"%ADB_CMD%" -s %FIRETV_IP%:5555 install -r "%APK_FILE%"

if %ERRORLEVEL% equ 0 (
    echo.
    echo =========================================================
    echo [SUCCESS] BitStreamer Client successfully installed/updated!
    echo =========================================================
) else (
    echo.
    echo =========================================================
    echo [FAILED] Installation failed!
    echo.
    echo Troubleshooting tips:
    echo 1. Ensure 'ADB Debugging' is enabled on Fire TV:
    echo    Settings -^> My Fire TV -^> Developer Options -^> ADB Debugging -^> ON
    echo 2. Check for an 'Allow USB Debugging?' prompt on your Fire TV screen
    echo    and select 'Always allow from this computer'.
    echo 3. Make sure Fire TV and PC are on the same Wi-Fi network.
    echo =========================================================
)

echo.
pause
