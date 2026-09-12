<#
.SYNOPSIS
  Sync VMOS pads over SSH+ADB and emit JSON for Cyclone One ChatGPT Attach.

.DESCRIPTION
  Transport only. Does not inject input. Never prints Connect Keys or VMOS AccessKeys.
#>
[CmdletBinding()]
param(
  [string]$FleetFile,
  [string]$AdbPath,
  [switch]$SkipTunnel,
  [switch]$Json
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Find-Adb([string]$preferred) {
  if ($preferred -and (Test-Path $preferred)) { return $preferred }
  $candidates = @(
    (Join-Path $env:LOCALAPPDATA 'Cyclone One\android-platform-tools\adb.exe'),
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
  )
  foreach ($item in $candidates) {
    if (Test-Path $item) { return $item }
  }
  $where = Get-Command adb -ErrorAction SilentlyContinue
  if ($where) { return $where.Source }
  throw 'adb not found. Install Cyclone One platform-tools or Android SDK platform-tools.'
}

function Read-Fleet {
  if ($FleetFile) {
    if (-not (Test-Path $FleetFile)) { throw "Fleet file missing: $FleetFile" }
    return (Get-Content -Raw -Path $FleetFile | ConvertFrom-Json)
  }
  $raw = [Console]::In.ReadToEnd()
  if (-not $raw) { throw 'Fleet JSON was empty.' }
  return $raw | ConvertFrom-Json
}

function Test-PortListening([int]$port) {
  $found = netstat -ano | Select-String LISTENING | Select-String (":$port ")
  return [bool]$found
}

function Ensure-Tunnel($pad, $stateDir) {
  $localPort = [int]$pad.localAdbPort
  if (Test-PortListening $localPort) { return }
  if (-not $pad.sshHost) { return }
  $key = [string]$pad.connectKey
  if (-not $key) { throw "Connect Key missing for $($pad.label)" }
  $askPass = Join-Path $stateDir ("askpass-{0}.cmd" -f $localPort)
  $keyFile = Join-Path $stateDir ("connect-key-{0}.ephemeral.txt" -f $localPort)
  Set-Content -Path $keyFile -Value $key -Encoding ascii -NoNewline
  @"
@echo off
type "%~dp0connect-key-$localPort.ephemeral.txt"
"@ | Set-Content -Path $askPass -Encoding ascii
  $env:SSH_ASKPASS = $askPass
  $env:SSH_ASKPASS_REQUIRE = 'force'
  $env:DISPLAY = 'dummy'
  $remote = if ($pad.remoteAdbSpec) { [string]$pad.remoteAdbSpec } else { 'localhost:1' }
  $user = if ($pad.sshUser) { [string]$pad.sshUser } else { 's' }
  $fwd = "${localPort}:${remote}"
  $sshArgs = @(
    '-oStrictHostKeyChecking=accept-new',
    '-oPreferredAuthentications=password',
    '-oPubkeyAuthentication=no',
    '-oNumberOfPasswordPrompts=1',
    '-p', ("{0}" -f [int]$pad.sshPort),
    '-L', $fwd,
    '-Nf',
    ("{0}@{1}" -f $user, [string]$pad.sshHost)
  )
  $p = Start-Process -FilePath ssh -ArgumentList $sshArgs -PassThru -WindowStyle Hidden
  Start-Sleep -Seconds 2
  if (-not (Test-PortListening $localPort)) {
    throw "SSH tunnel failed for $($pad.label) on port $localPort"
  }
  try { Remove-Item -Force $keyFile, $askPass -ErrorAction SilentlyContinue } catch {}
  $null = $p
}

function Connect-AdbPad($adb, [string]$serial) {
  & $adb disconnect $serial 2>$null | Out-Null
  $state = 'unknown'
  for ($i = 1; $i -le 5; $i++) {
    $null = & $adb connect $serial 2>&1
    Start-Sleep -Seconds 1
    $state = (((& $adb -s $serial get-state 2>&1 | Select-Object -First 1) | Out-String)).Trim()
    if ($state -eq 'device') { break }
    & $adb disconnect $serial 2>$null | Out-Null
  }
  return $state
}

function Get-Mobile($adb, [string]$serial) {
  $path = (& $adb -s $serial shell pm path com.cyclone.mobile 2>&1 | Out-String)
  $installed = $path -match 'package:'
  $pid = (& $adb -s $serial shell pidof com.cyclone.mobile 2>&1 | Out-String).Trim()
  $running = $installed -and $pid -and ($pid -notmatch 'error')
  if ($running) { return 'running' }
  if ($installed) { return 'installed' }
  return 'missing'
}

$adb = Find-Adb $AdbPath
$fleet = Read-Fleet
$stateDir = Join-Path $env:LOCALAPPDATA 'Cyclone One\chatgpt-attach\runtime'
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
& $adb start-server 2>$null | Out-Null

$pads = New-Object System.Collections.Generic.List[object]
$i = 0
foreach ($pad in @($fleet.pads)) {
  $i++
  $label = if ($pad.label) { [string]$pad.label } else { "pad-$i" }
  $id = if ($pad.id) { [string]$pad.id } else { $label }
  $item = [ordered]@{
    id = $id
    label = $label
    ok = $false
    deviceId = ''
    serial = ''
    adb = 'failed'
    mobile = 'unknown'
    error = ''
  }
  try {
    if (-not $SkipTunnel) { Ensure-Tunnel $pad $stateDir }
    $serial = if ($pad.serial) { [string]$pad.serial } else { "localhost:$([int]$pad.localAdbPort)" }
    $state = Connect-AdbPad $adb $serial
    if ($state -ne 'device') { throw "ADB state=$state" }
    $item.serial = $serial
    $item.adb = $state
    $item.mobile = Get-Mobile $adb $serial
    $item.ok = $true
  } catch {
    $item.error = ([string]$_.Exception.Message) -replace 'connectKey\s*[:=]\s*\S+', '[redacted]'
    $item.ok = $false
  }
  [void]$pads.Add([pscustomobject]$item)
}

$result = [ordered]@{
  ok = [bool]@($pads | Where-Object ok).Count
  adbPath = $adb
  generatedAt = (Get-Date).ToString('o')
  controlApi = [string]$fleet.controlApiBase
  pads = @($pads.ToArray())
}
$jsonText = ($result | ConvertTo-Json -Depth 6 -Compress)
if ($Json) { Write-Output $jsonText } else { Write-Output $jsonText }
