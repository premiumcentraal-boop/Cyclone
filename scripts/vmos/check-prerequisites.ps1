param(
  [Parameter(Mandatory=$true)][string]$MobileApk,
  [Parameter(Mandatory=$true)][string]$VmosSerial,
  [string]$Adb = "adb"
)

$ErrorActionPreference = "Stop"
$blockers = @()
$warnings = @()

$windows = [System.Environment]::OSVersion.Version
if ($windows.Major -lt 10) { $blockers += "Windows 10 or later is required." }

$oneRoot = Join-Path $env:LOCALAPPDATA "Cyclone One"
if (-not (Test-Path $oneRoot)) {
  $blockers += "Cyclone One is not installed under %LOCALAPPDATA%\\Cyclone One. Install Cyclone One 1.1.2 first."
}

try { & $Adb version | Out-Null } catch { $blockers += "ADB is not callable on this Windows runtime." }

if (-not (Test-Path $MobileApk)) {
  $blockers += "Cyclone Mobile APK was not found: $MobileApk"
} elseif ([IO.Path]::GetExtension($MobileApk).ToLowerInvariant() -ne ".apk") {
  $blockers += "Cyclone Mobile artifact must be an .apk file."
}

$deviceState = $null
$androidRelease = $null
$sdk = $null
if ($blockers.Count -eq 0) {
  try {
    $deviceState = (& $Adb -s $VmosSerial get-state 2>$null | Select-Object -First 1).Trim()
    if ($deviceState -ne "device") { $blockers += "VMOS ADB serial '$VmosSerial' is not in device state. Re-open VMOS Local Debugging → ADB and reconnect." }
  } catch {
    $blockers += "VMOS ADB serial '$VmosSerial' is unreachable. Re-open VMOS Local Debugging → ADB and reconnect."
  }
}

if ($deviceState -eq "device") {
  try {
    $sdk = [int]((& $Adb -s $VmosSerial shell getprop ro.build.version.sdk).Trim())
    $androidRelease = (& $Adb -s $VmosSerial shell getprop ro.build.version.release).Trim()
    if ($sdk -lt 33) { $blockers += "Cyclone Mobile requires Android API 33 / Android 13 or newer; VMOS reports API $sdk." }
    elseif ($sdk -lt 35) { $warnings += "VMOS Android $androidRelease is supported compatibility mode; Android 15/API 35 is preferred." }
  } catch {
    $blockers += "Could not read the VMOS Android version over ADB."
  }
}

$result = [pscustomobject]@{
  Ready = ($blockers.Count -eq 0)
  Windows = $windows.ToString()
  CycloneOneRoot = $oneRoot
  MobileApk = (Resolve-Path $MobileApk -ErrorAction SilentlyContinue).Path
  VmosSerial = $VmosSerial
  VmosAdbState = $deviceState
  AndroidRelease = $androidRelease
  AndroidSdk = $sdk
  PreferredAndroidSdk = 35
  MinimumAndroidSdk = 33
  Blockers = $blockers
  Warnings = $warnings
}
$result | ConvertTo-Json -Depth 4

if ($blockers.Count -gt 0) { exit 2 }
