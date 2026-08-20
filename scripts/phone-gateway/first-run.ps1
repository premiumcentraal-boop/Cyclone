param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [switch]$ConfigureForward,
    [switch]$SkipMcpCheck
)

$ErrorActionPreference = "Stop"
$Package = "com.cyclone.mobile"
$GatewayUrl = if ($env:CYCLONE_DEVICE_GATEWAY_URL) { $env:CYCLONE_DEVICE_GATEWAY_URL } else { "http://127.0.0.1:8765" }
$Token = $env:CYCLONE_DEVICE_GATEWAY_TOKEN

function Pass($msg) { Write-Host "[PASS] $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red; exit 1 }
function Note($msg) { Write-Host "[INFO] $msg" -ForegroundColor Cyan }

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Fail "adb was not found. Install Android Platform Tools and add adb.exe to PATH." }
Pass "adb is available"

$deviceLines = adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\S+\s+\S+" }
$authorized = @($deviceLines | Where-Object { $_ -match "\sdevice$" })
$unauthorized = @($deviceLines | Where-Object { $_ -match "\sunauthorized$" })
if ($unauthorized.Count -gt 0) { Fail "An Android device is unauthorized. Unlock the Pixel 8 and accept the USB debugging prompt." }

if (-not $Serial) {
    if ($authorized.Count -ne 1) { Fail "Expected exactly one authorized Android device. Pass -Serial <adb-serial> when multiple devices are connected." }
    $Serial = (($authorized[0] -split "\s+")[0])
}
if (-not ($authorized | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" })) { Fail "ADB serial '$Serial' is not an authorized connected device." }
Pass "ADB device selected: $Serial"

$model = (adb -s $Serial shell getprop ro.product.model).Trim()
if ($model -ne "Pixel 8") { Fail "Selected device reports '$model', not the first-test target Pixel 8." }
Pass "Pixel 8 confirmed"

$pkg = (adb -s $Serial shell pm path $Package 2>$null)
if (-not $pkg) { Fail "Cyclone package $Package is not installed on the Pixel 8." }
Pass "Cyclone is installed"

$forward = adb -s $Serial forward --list | Where-Object { $_ -match "tcp:8766\s+localabstract:cyclone_gateway" }
if (-not $forward -and $ConfigureForward) {
    Note "Creating the local ADB forward tcp:8766 -> localabstract:cyclone_gateway"
    adb -s $Serial forward tcp:8766 localabstract:cyclone_gateway | Out-Null
    $forward = adb -s $Serial forward --list | Where-Object { $_ -match "tcp:8766\s+localabstract:cyclone_gateway" }
}
if (-not $forward) { Fail "Cyclone Android bridge forwarding is not configured. Re-run with -ConfigureForward after enabling PC Gateway inside Cyclone." }
Pass "Cyclone Android bridge ADB forward is configured"

if (-not $Token) { Fail "CYCLONE_DEVICE_GATEWAY_TOKEN is not set in this PowerShell session." }
$headers = @{ Authorization = "Bearer $Token" }
try { $status = Invoke-RestMethod -Uri "$GatewayUrl/v1/device/status" -Headers $headers -Method Get -TimeoutSec 5 }
catch { Fail "PC Device Gateway is not reachable/authenticated at $GatewayUrl. Start Agent 1's gateway and use the matching token. $($_.Exception.Message)" }
Pass "PC Device Gateway is reachable"

$statusJson = $status | ConvertTo-Json -Depth 10 -Compress
if ($statusJson -notmatch '"gatewayEnabled"\s*:\s*true' -and $statusJson -notmatch '"gateway_enabled"\s*:\s*true') { Fail "Gateway responded, but Cyclone PC Gateway does not report enabled. Enable 'PC Gateway (USB debugging)' in Cyclone." }
if ($statusJson -notmatch '"accessibilityReady"\s*:\s*true' -and $statusJson -notmatch '"accessibilityConnected"\s*:\s*true' -and $statusJson -notmatch '"accessibility_ready"\s*:\s*true') { Fail "Cyclone Accessibility does not report ready. Enable Cyclone in Android Accessibility settings." }
Pass "Cyclone Gateway + Accessibility report ready"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$mcpDir = Join-Path $repoRoot "tools\codex-phone-mcp"
Push-Location $mcpDir
try {
    python -m cyclone_phone_mcp --self-test | Write-Host
    if ($LASTEXITCODE -ne 0) { Fail "Cyclone Phone MCP self-test could not talk to Device Gateway." }
    Pass "Cyclone Phone MCP self-test passed"
} finally { Pop-Location }

if (-not $SkipMcpCheck) {
    if (-not (Get-Command codex -ErrorAction SilentlyContinue)) { Fail "codex CLI is not installed/on PATH. Install/open Codex, then register the MCP server as documented in docs/CODEX_PHONE_FIRST_RUN.md." }
    $mcpList = codex mcp list 2>&1 | Out-String
    if ($mcpList -notmatch "cyclone-phone") { Fail "Codex does not list an MCP server named 'cyclone-phone'. Register it using the command in docs/CODEX_PHONE_FIRST_RUN.md." }
    Pass "Codex lists cyclone-phone MCP"
}

Write-Host ""
Write-Host "Pixel 8 is ready for the safe Codex Settings -> Apps acceptance run." -ForegroundColor Green
Write-Host "Next: cd tools/codex-phone-mcp; python -m cyclone_phone_mcp.acceptance --live --execute" -ForegroundColor Yellow
