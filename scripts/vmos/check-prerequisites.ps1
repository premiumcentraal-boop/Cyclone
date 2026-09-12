param(
  [Parameter(Mandatory=$true)][string]$MobileApk,
  [string]$Adb = "adb"
)

$ErrorActionPreference = "Stop"
$issues = @()
$warnings = @()

$windows = [System.Environment]::OSVersion.Version
if ($windows.Major -lt 10) { $issues += "Windows 10 or later is required." }

$oneRoot = Join-Path $env:LOCALAPPDATA "Cyclone One"
$oneExe = Join-Path $oneRoot "Cyclone One.exe"
if (-not (Test-Path $oneRoot)) { $issues += "Cyclone One is not installed under %LOCALAPPDATA%\\Cyclone One." }

try { & $Adb version | Out-Null } catch { $issues += "ADB is not callable. Install/repair Cyclone One PC runtime or Android platform-tools." }

if (-not (Test-Path $MobileApk)) { $issues += "Cyclone Mobile APK was not found: $MobileApk" }
elseif ([IO.Path]::GetExtension($MobileApk) -ne ".apk") { $issues += "Mobile artifact must be an .apk file." }

[pscustomobject]@{
  Ready = ($issues.Count -eq 0)
  Windows = $windows.ToString()
  CycloneOneRoot = $oneRoot
  CycloneOneExePresent = (Test-Path $oneExe)
  MobileApk = (Resolve-Path $MobileApk -ErrorAction SilentlyContinue).Path
  Blockers = $issues
  Warnings = $warnings
} | ConvertTo-Json -Depth 4

if ($issues.Count -gt 0) { exit 2 }
