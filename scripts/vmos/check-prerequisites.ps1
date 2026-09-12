param(
  [Parameter(Mandatory=$true)][string]$MobileApk,
  [Parameter(Mandatory=$true)][string]$VmosSerial,
  [string]$Adb = "adb"
)

$ErrorActionPreference = "Stop"
$blockers = @()
$warnings = @()

$requiredOneVersion = "1.5.0"
$requiredMobileName = "Cyclone-4.3.6.apk"
$requiredMobileSha256 = "4894dd8c0a69d3445d81b2f33c98ceef86630a0951d273912bd240b87b27dc17"

$windows = [System.Environment]::OSVersion.Version
if ($windows.Major -lt 10) { $blockers += "Windows 10 or later is required." }

$oneRoot = Join-Path $env:LOCALAPPDATA "Cyclone One"
$oneExe = Join-Path $oneRoot "Cyclone One.exe"
$oneVersion = $null
if (-not (Test-Path $oneExe)) {
  $blockers += "Cyclone One is not installed at '%LOCALAPPDATA%\Cyclone One\Cyclone One.exe'. Install published Cyclone One 1.5.0 first."
} else {
  try {
    $rawOneVersion = (Get-Item $oneExe).VersionInfo.ProductVersion
    if ($rawOneVersion -match '(\d+\.\d+\.\d+)') { $oneVersion = $Matches[1] }
    if ([string]::IsNullOrWhiteSpace($oneVersion)) {
      $blockers += "Could not verify the installed Cyclone One version from '$oneExe'."
    } elseif ([version]$oneVersion -lt [version]$requiredOneVersion) {
      $blockers += "Cyclone One $requiredOneVersion or newer is required; installed version is $oneVersion."
    } elseif ($oneVersion -ne $requiredOneVersion) {
      $warnings += "Validated VMOS baseline is Cyclone One $requiredOneVersion; installed version is $oneVersion."
    }
  } catch {
    $blockers += "Could not read the installed Cyclone One version from '$oneExe'."
  }
}

$adbResolved = $null
try {
  $adbCommand = Get-Command $Adb -ErrorAction Stop
  $adbResolved = $adbCommand.Source
  & $Adb version | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "adb version failed" }
} catch {
  $blockers += "adb.exe is not callable. Install Google Android SDK Platform-Tools; Cyclone One does not bundle ADB."
}

$mobileResolved = $null
$mobileSha256 = $null
if (-not (Test-Path $MobileApk)) {
  $blockers += "Cyclone Mobile APK was not found: $MobileApk"
} elseif ([IO.Path]::GetExtension($MobileApk).ToLowerInvariant() -ne ".apk") {
  $blockers += "Cyclone Mobile artifact must be an .apk file."
} else {
  $mobileResolved = (Resolve-Path $MobileApk).Path
  $leaf = [IO.Path]::GetFileName($mobileResolved)
  if ($leaf -match '^Cyclone-(\d+)\.(\d+)\.(\d+)\.apk$') {
    $mobileVersion = [version]("{0}.{1}.{2}" -f $Matches[1], $Matches[2], $Matches[3])
    if ($mobileVersion -lt [version]"4.3.6") {
      $blockers += "Cyclone Mobile 4.3.6 or newer is required; found $mobileVersion."
    }
  }
  if ($leaf -ieq $requiredMobileName) {
    $mobileSha256 = (Get-FileHash -Algorithm SHA256 $mobileResolved).Hash.ToLowerInvariant()
    if ($mobileSha256 -ne $requiredMobileSha256) {
      $blockers += "$requiredMobileName SHA-256 does not match the published v4.3.6 asset."
    }
  } else {
    $warnings += "Using '$leaf' instead of the exact validated $requiredMobileName asset; verify its release provenance separately."
  }
}

$deviceState = $null
$androidRelease = $null
$sdk = $null
if ($null -ne $adbResolved) {
  try {
    $deviceState = (& $Adb -s $VmosSerial get-state 2>$null | Select-Object -First 1).Trim()
    if ($deviceState -ne "device") {
      $blockers += "VMOS ADB serial '$VmosSerial' is not in device state. Re-open VMOS Local Debugging → ADB and reconnect."
    }
  } catch {
    $blockers += "VMOS ADB serial '$VmosSerial' is unreachable. Confirm VMOS remote-ADB account authorization, then re-open Local Debugging → ADB and reconnect."
  }
}

if ($deviceState -eq "device") {
  try {
    $sdk = [int]((& $Adb -s $VmosSerial shell getprop ro.build.version.sdk).Trim())
    $androidRelease = (& $Adb -s $VmosSerial shell getprop ro.build.version.release).Trim()
    if ($sdk -lt 33) {
      $blockers += "Cyclone Mobile requires Android API 33 / Android 13 or newer; VMOS reports API $sdk."
    } elseif ($sdk -lt 35) {
      $warnings += "VMOS Android $androidRelease is compatibility mode; Android 15/API 35 is the preferred VMOS target."
    } elseif ($sdk -gt 35) {
      $warnings += "VMOS Android $androidRelease/API $sdk is newer than the validated Android 13/14/15 VMOS target set."
    }
  } catch {
    $blockers += "Could not read the VMOS Android version over ADB."
  }
}

$result = [pscustomobject]@{
  Ready = ($blockers.Count -eq 0)
  Windows = $windows.ToString()
  CycloneOneRoot = $oneRoot
  CycloneOneExe = $oneExe
  CycloneOneVersion = $oneVersion
  RequiredCycloneOneVersion = $requiredOneVersion
  AdbPath = $adbResolved
  MobileApk = $mobileResolved
  MobileSha256 = $mobileSha256
  RequiredMobileSha256 = $requiredMobileSha256
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
