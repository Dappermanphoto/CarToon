@echo off
title CarToon - first upload to GitHub
cd /d "%~dp0"

set "GIT=git"
where git >nul 2>&1
if errorlevel 1 (
  if exist "%ProgramFiles%\Git\cmd\git.exe" (
    set "GIT=%ProgramFiles%\Git\cmd\git.exe"
  ) else (
    echo Git was not found. Restart your PC and run this file again.
    pause
    exit /b 1
  )
)

if not exist ".git" (
  echo Creating local Git repository...
  "%GIT%" init -b main
)

rem Name shown on your commits (only set for this project if you have none yet)
"%GIT%" config user.name >nul 2>&1 || "%GIT%" config user.name "Dappermanphoto"
"%GIT%" config user.email >nul 2>&1 || "%GIT%" config user.email "Dappermanphoto@users.noreply.github.com"

"%GIT%" remote get-url origin >nul 2>&1
if errorlevel 1 (
  "%GIT%" remote add origin https://github.com/Dappermanphoto/CarToon.git
)

echo Saving a snapshot (commit)...
"%GIT%" add -A
"%GIT%" commit -m "First version" >nul 2>&1
"%GIT%" branch -M main

echo Uploading to GitHub (push)... A browser may ask you to sign in - approve it.
"%GIT%" push -u origin main
if errorlevel 1 (
  echo.
  echo Upload failed. Copy the messages above and paste them to Claude.
  pause
  exit /b 1
)

echo.
echo Done! Your project is on GitHub: https://github.com/Dappermanphoto/CarToon
pause
