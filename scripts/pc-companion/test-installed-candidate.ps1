param([Parameter(Mandatory=$true)][string]$Installer, [Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference = 'Stop'
$InstallDir = Join-Path $env:RUNNER_TEMP 'CycloneOneInstalledAcceptance'
if (Test-Path $InstallDir) { Remove-Item -Recurse -Force $InstallDir }
$process = Start-Process -FilePath (Resolve-Path $Installer) -ArgumentList "/S /D=$InstallDir" -PassThru
if (-not $process.WaitForExit(120000)) { $process.Kill(); throw 'Installer timed out' }
if ($process.ExitCode -ne 0) { throw "Installer failed: $($process.ExitCode)" }

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
if ($MarkerText -notmatch 'sha256=84df1e5628bc7e6a9f2bf750ab98c591a99a6d622fd48f789cf278336bab5b99') {
  throw 'Platform-Tools version marker does not contain the pinned archive SHA-256.'
}

@{
  installed=$true
  required_binaries=$Required
  cli_help=$true
  direct_bridge_component=$true
  bundled_adb=$true
  bundled_adb_version='37.0.1'
  bundled_adb_path='android-platform-tools\\adb.exe'
  physical_phone='UNVERIFIED'
} | ConvertTo-Json | Set-Content -Encoding utf8 $Output
