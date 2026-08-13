@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch-windows.ps1" %*
if errorlevel 1 (
  echo.
  echo Cyclone could not be launched. Review the message above.
  pause
)
