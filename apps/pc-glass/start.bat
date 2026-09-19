@echo off
rem Copyright 2026 Google LLC / Cyclone PC Glass adaptations
rem
rem Licensed under the Apache License, Version 2.0 (the "License");
rem you may not use this file except in compliance with the License.
rem You may obtain a copy of the License at
rem
rem     http://www.apache.org/licenses/LICENSE-2.0
rem
rem Unless required by applicable law or agreed to in writing, software
rem distributed under the License is distributed on an "AS IS" BASIS,
rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
rem See the License for the specific language governing permissions and
rem limitations under the License.

chcp 65001 >nul 2>&1
setlocal EnableDelayedExpansion
title Cyclone PC Glass

cd /d "%~dp0"

rem Product default: Cyclone gateway driver (override with CYCLONE_CONNECTED=0 for raw Artemis/ADB).
if not defined CYCLONE_CONNECTED set "CYCLONE_CONNECTED=1"
if not defined CYCLONE_DEVICE_GATEWAY_URL set "CYCLONE_DEVICE_GATEWAY_URL=http://127.0.0.1:8765"
rem Mode A companion session advertised by Device Gateway (R2 smoke). Not a random invent —
rem override from phone_status / GET /v1/devices if your fleet uses a different id.
if /I "!CYCLONE_CONNECTED!"=="1" if not defined CYCLONE_SESSION_ID set "CYCLONE_SESSION_ID=default-foreground"

echo ======================================================
echo       Cyclone PC Glass (Artemis-based UI)
echo ======================================================
echo.
echo CYCLONE_CONNECTED=!CYCLONE_CONNECTED!
echo CYCLONE_DEVICE_GATEWAY_URL=!CYCLONE_DEVICE_GATEWAY_URL!
echo CYCLONE_SESSION_ID=!CYCLONE_SESSION_ID!
echo.

rem Run the PowerShell bootstrap with execution policy bypass
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1" %*

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo An error occurred while starting Cyclone PC Glass.
    pause
)
