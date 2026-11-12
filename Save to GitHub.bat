@echo off
title CarToon - Save to GitHub
cd /d "%~dp0"

set "GIT=git"
where git >nul 2>&1
if errorlevel 1 (
  if exist "%ProgramFiles%\Git\cmd\git.exe" (
    set "GIT=%ProgramFiles%\Git\cmd\git.exe"
  ) else (
    echo Git was not found. Install it from git-scm.com, then run this again.
    pause
    exit /b 1
  )
)

rem Name shown on commits (only set for this project if you have none yet)
"%GIT%" config user.name >nul 2>&1 || "%GIT%" config user.name "Dappermanphoto"
"%GIT%" config user.email >nul 2>&1 || "%GIT%" config user.email "Dappermanphoto@users.noreply.github.com"

set "MSG="
set /p MSG=Describe what you changed (or just press Enter): 
if "%MSG%"=="" set "MSG=Update from %COMPUTERNAME%"

echo.
echo Saving a snapshot (commit)...
"%GIT%" add -A
"%GIT%" commit -m "%MSG%"

echo.
echo Getting any newer changes from GitHub first (pull)...
"%GIT%" pull --rebase
if errorlevel 1 (
  echo.
  echo The same files were changed on both computers. Nothing was uploaded.
  echo Copy the messages above and paste them to Claude.
  pause
  exit /b 1
)

echo.
echo Uploading to GitHub (push)...
"%GIT%" push
if errorlevel 1 (
  echo.
  echo Upload failed. Copy the messages above and paste them to Claude.
  pause
  exit /b 1
)

echo.
echo Done! Your changes are on GitHub.
pause
