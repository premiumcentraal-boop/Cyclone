@echo off
REM Cyclone Glass — opens the no-console helper (Start / Stop / Update).
cd /d "%~dp0"
wscript //nologo "%~dp0windows-helper\Launch Cyclone Glass Helper.vbs"
