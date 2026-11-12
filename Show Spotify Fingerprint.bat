@echo off
title CarToon - SHA1 fingerprint for Spotify
set "KEYTOOL=%ProgramFiles%\Android\Android Studio\jbr\bin\keytool.exe"
set "STORE=%USERPROFILE%\.android\debug.keystore"

if not exist "%KEYTOOL%" (
  echo Could not find keytool at "%KEYTOOL%".
  pause
  exit /b 1
)
if not exist "%STORE%" (
  echo No debug key found yet. Run the app once from Android Studio, then try again.
  pause
  exit /b 1
)

echo Copy the line that starts with SHA1: into Spotify's dashboard
echo (App settings ^> Android packages ^> Package SHA1 fingerprint).
echo Package name: com.example.cartoon
echo.
"%KEYTOOL%" -list -v -keystore "%STORE%" -alias androiddebugkey -storepass android -keypass android | findstr /c:"SHA1:"
echo.
pause
