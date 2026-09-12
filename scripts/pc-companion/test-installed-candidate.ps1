param([Parameter(Mandatory=$true)][string]$Installer, [Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference = 'Stop'
$InstallDir = Join-Path $env:RUNNER_TEMP 'CycloneOneInstalledAcceptance'
if (Test-Path $InstallDir) { Remove-Item -Recurse -Force $InstallDir }
$process = Start-Process -FilePath (Resolve-Path $Installer) -ArgumentList "/S /D=$InstallDir" -PassThru
if (-not $process.WaitForExit(120000)) { $process.Kill(); throw 'Installer timed out' }
if ($process.ExitCode -ne 0) { throw "Installer failed: $($process.ExitCode)" }
Write-Host "Installed candidate accepted at clean test directory: $InstallDir"

$Required = @('cyclone-pc-companion.exe','CyclonePCRuntime.exe','CycloneAgentMCP.exe','CycloneLivePhone.exe')
foreach ($Name in $Required) {
  if (-not (Test-Path (Join-Path $InstallDir $Name))) { throw "Installed binary missing: $Name" }
}

$help = & (Join-Path $InstallDir 'CycloneLivePhone.exe') --help
if ($LASTEXITCODE -ne 0 -or ($help -join ' ') -notmatch 'observe') { throw 'Installed adapter cannot start' }

$Cloudflared = Get-ChildItem -Path $InstallDir -Recurse -Filter cloudflared.exe -File | Select-Object -First 1
if ($null -eq $Cloudflared) { throw 'Installed Direct Live Phone HTTPS bridge component missing' }

$PlatformTools = Join-Path $InstallDir 'android-platform-tools'
$Adb = Join-Path $PlatformTools 'adb.exe'
$AdbApi = Join-Path $PlatformTools 'AdbWinApi.dll'
$AdbUsbApi = Join-Path $PlatformTools 'AdbWinUsbApi.dll'
$Marker = Join-Path $PlatformTools 'CYCLONE_PLATFORM_TOOLS_VERSION.txt'
foreach ($Path in @($Adb, $AdbApi, $AdbUsbApi, $Marker)) {
  if (-not (Test-Path $Path)) { throw "Installed Android Platform-Tools payload missing: $Path" }
}
$AdbVersion = (& $Adb version 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0 -or $AdbVersion -notmatch 'Version\s+37\.0\.1') {
  throw "Installed bundled adb.exe is not Platform-Tools 37.0.1. Output: $AdbVersion"
}
$MarkerText = Get-Content $Marker -Raw
if ($MarkerText -notmatch 'version=37\.0\.1') { throw 'Platform-Tools version marker is not 37.0.1.' }
if ($MarkerText -notmatch 'sha256=45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99') {
  throw 'Platform-Tools version marker does not contain the pinned archive SHA-256.'
}
Write-Host 'Installed bundled adb accepted: Android Platform-Tools 37.0.1 with pinned archive SHA-256.'

$FirstRunGuide = Get-ChildItem -Path $InstallDir -Recurse -Filter CYCLONE_FIRST_RUN.md -File | Select-Object -First 1
if ($null -eq $FirstRunGuide) { throw 'Installed first-run guide is missing.' }
$GuideText = Get-Content $FirstRunGuide.FullName -Raw
$GuideFragments = @(
  'Connect phone',
  'Android Platform-Tools 37.0.1',
  'no Android Studio or separate ADB install',
  'Android 15',
  'Android 13/14 are compatible',
  'Android 12 and older are unsupported',
  'Cyclone Mobile 4.3.6',
  'PC Gateway & QR pairing',
  'loopback-only'
)
foreach ($Fragment in $GuideFragments) {
  if ($GuideText.IndexOf($Fragment, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
    throw "Installed first-run guide missing required instruction: $Fragment"
  }
}
Write-Host "Installed first-run guide accepted: $($FirstRunGuide.FullName)"
Write-Host 'Installed first-run contract accepted: bundled ADB, VMOS Android 15/13/14 guidance, Cyclone Mobile 4.3.6, and PC Gateway & QR pairing handoff.'

function Get-FreeLoopbackPort {
  $Listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
  $Listener.Start()
  try { return ([System.Net.IPEndPoint]$Listener.LocalEndpoint).Port }
  finally { $Listener.Stop() }
}

$GatewayPort = Get-FreeLoopbackPort
$GatewayBase = "http://127.0.0.1:$GatewayPort"
$GatewayToken = ([Guid]::NewGuid().ToString('N')) + ([Guid]::NewGuid().ToString('N'))
$GatewayRuntime = Join-Path $env:RUNNER_TEMP 'CycloneOneGatewayAcceptance'
if (Test-Path $GatewayRuntime) { Remove-Item -Recurse -Force $GatewayRuntime }
New-Item -ItemType Directory -Force -Path $GatewayRuntime | Out-Null
$GatewayProcess = $null
$EnvironmentNames = @(
  'CYCLONE_DEVICE_GATEWAY_TOKEN',
  'CYCLONE_DEVICE_GATEWAY_URL',
  'CYCLONE_DEVICE_GATEWAY_PORT',
  'CYCLONE_DEVICE_GATEWAY_RUNTIME',
  'CYCLONE_DESKTOP_PAIRING_BOOTSTRAP',
  'ADB_PATH'
)
$PreviousEnvironment = @{}
foreach ($Name in $EnvironmentNames) {
  $PreviousEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
}

try {
  $env:CYCLONE_DEVICE_GATEWAY_TOKEN = $GatewayToken
  $env:CYCLONE_DEVICE_GATEWAY_URL = $GatewayBase
  $env:CYCLONE_DEVICE_GATEWAY_PORT = [string]$GatewayPort
  $env:CYCLONE_DEVICE_GATEWAY_RUNTIME = $GatewayRuntime
  $env:CYCLONE_DESKTOP_PAIRING_BOOTSTRAP = '1'
  $env:ADB_PATH = $Adb

  $GatewayProcess = Start-Process -FilePath (Join-Path $InstallDir 'CyclonePCRuntime.exe') -ArgumentList 'serve' -WorkingDirectory $InstallDir -WindowStyle Hidden -PassThru
  $Headers = @{ Authorization = "Bearer $GatewayToken" }
  $Fleet = $null
  $Deadline = [DateTime]::UtcNow.AddSeconds(45)
  while ([DateTime]::UtcNow -lt $Deadline) {
    if ($GatewayProcess.HasExited) { throw "Installed CyclonePCRuntime exited before gateway readiness: $($GatewayProcess.ExitCode)" }
    try {
      $Fleet = Invoke-RestMethod -Uri "$GatewayBase/v1/fleet" -Headers $Headers -Method Get -TimeoutSec 3
      break
    } catch {
      Start-Sleep -Milliseconds 500
    }
  }
  if ($null -eq $Fleet) { throw 'Installed CyclonePCRuntime did not expose its authenticated loopback gateway.' }
  if ($null -eq $Fleet.protocol -or -not ($Fleet.PSObject.Properties.Name -contains 'devices')) {
    throw 'Installed CyclonePCRuntime fleet response is missing protocol/devices.'
  }
  Write-Host "Installed gateway accepted: $GatewayBase protocol=$($Fleet.protocol) bearer-authenticated=yes"

  $Transport = Invoke-RestMethod -Uri "$GatewayBase/v1/transport/usb" -Headers $Headers -Method Get -TimeoutSec 15
  if ($Transport.mode -ne 'usb' -or -not ($Transport.PSObject.Properties.Name -contains 'ok')) {
    throw 'Installed CyclonePCRuntime did not expose the authenticated transport-onboarding API.'
  }
  Write-Host "Installed transport onboarding accepted: mode=$($Transport.mode) authenticated=yes"
} finally {
  if ($null -ne $GatewayProcess -and -not $GatewayProcess.HasExited) {
    & taskkill.exe /F /T /PID $GatewayProcess.Id | Out-Null
  }
  foreach ($Name in $EnvironmentNames) {
    [Environment]::SetEnvironmentVariable($Name, $PreviousEnvironment[$Name], 'Process')
  }
}

@{
  installed=$true
  required_binaries=$Required
  cli_help=$true
  direct_bridge_component=$true
  bundled_adb=$true
  bundled_adb_version='37.0.1'
  bundled_adb_path='android-platform-tools\\adb.exe'
  gateway_ready=$true
  gateway_loopback=$GatewayBase
  gateway_authenticated=$true
  transport_onboarding_api=$true
  first_run_guide=$true
  vmos_image_tip=$true
  mobile_tip=$true
  trust_pairing_tip=$true
  physical_phone='UNVERIFIED'
} | ConvertTo-Json | Set-Content -Encoding utf8 $Output
