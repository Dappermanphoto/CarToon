@echo off
title CarToon - Android Auto car screen
set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"

if not exist "%ADB%" (
  echo Could not find adb at "%ADB%".
  echo In Android Studio: Tools ^> SDK Manager ^> SDK Tools ^> install "Android SDK Platform-Tools".
  pause
  exit /b 1
)

echo Looking for your phone...
"%ADB%" start-server >nul 2>&1
"%ADB%" devices
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo.
  echo Phone not ready. Plug it in, unlock it, and tap "Allow" on the USB debugging prompt.
  echo Then run this file again.
  pause
  exit /b 1
)

echo Connecting to Android Auto head unit server...
"%ADB%" forward tcp:5277 tcp:5277
if errorlevel 1 (
  echo Port forwarding failed.
  pause
  exit /b 1
)

echo.
echo Starting the car screen.
echo   Type  restrict none  then Enter = parked (CarToon can run)
echo   Type  restrict all   then Enter = driving
echo.
cd /d "%SDK%\extras\google\auto"
desktop-head-unit.exe
pause
