# Cyclone testbench link keeper (alpha 109).
# Keeps the phone's USB debugging link up for round-the-clock runs, the way phone farms do it in software:
#   1. Keepalive: a tiny `adb shell true` every 30 s, so the link is never idle long enough for Windows to suspend it.
#   2. Heal in steps when the link drops: reconnect the offline transport, then the device, then restart the ADB server.
#   3. Never interfere: it waits while Cyclone's PC updater runs and while a job holds runtime\adb.lock (installs).
#   4. Say what it can't fix: status in runtime\link.json and the log, including "replug needed" when only the cable can.
# It changes nothing on Windows or the phone; settings that prevent drops are listed in SEAT.md.
param([string]$Serial = "", [int]$EverySeconds = 15)
$ErrorActionPreference = 'Continue'
$Root = Join-Path $env:LOCALAPPDATA 'Cyclone One'
$Adb = Join-Path $Root 'android-platform-tools\adb.exe'
$Run = Join-Path $Root 'runtime'
$Log = Join-Path $Run 'link-keeper.log'
$Status = Join-Path $Run 'link.json'
$Lock = Join-Path $Run 'adb.lock'
function Say($t) { "$(Get-Date -Format o) $t" | Add-Content -Path $Log -Encoding utf8 }

function Adb([string[]]$a, [int]$ms = 10000) {
  $psi = New-Object System.Diagnostics.ProcessStartInfo $Adb
  $psi.Arguments = ($a | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
  $psi.UseShellExecute = $false; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true; $psi.CreateNoWindow = $true
  $p = [System.Diagnostics.Process]::Start($psi)
  if (-not $p.WaitForExit($ms)) { try { $p.Kill() } catch { }; return "timeout" }
  return ($p.StandardOutput.ReadToEnd() + $p.StandardError.ReadToEnd()).Trim()
}

function Busy {
  if (Test-Path $Lock) {
    # A lock older than 20 minutes is stale (its job died): ignore it.
    if (((Get-Date) - (Get-Item $Lock).LastWriteTime).TotalMinutes -lt 20) { return "a job holds adb.lock" }
  }
  if (Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -match 'Cyclone One\\install\.ps1' }) { return "Cyclone is updating" }
  return $null
}

function State {
  $list = Adb @('devices')
  if ($list -eq 'timeout') { return 'adb-hung' }
  $rows = @($list -split "`r?`n" | Where-Object { $_ -match '^\S+\s+(device|offline|unauthorized)' })
  if (-not $script:Serial -and $rows.Count -ge 1) { $script:Serial = ($rows[0] -split '\s+')[0]; Say "watching $script:Serial" }
  if (-not $script:Serial) { return 'missing' }
  $row = $rows | Where-Object { $_ -match ('^' + [regex]::Escape($script:Serial) + '\s') } | Select-Object -First 1
  if (-not $row) { return 'missing' }
  return ($row -split '\s+')[1]
}

function Usb-Present {
  @(Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object { $_.InstanceId -match 'VID_18D1' -and $_.Status -eq 'OK' }).Count -gt 0
}

function Write-Status($state, $note) {
  @{ at = (Get-Date -Format o); serial = $script:Serial; state = $state; note = $note; lastGood = $script:lastGood; heals = $script:heals } |
    ConvertTo-Json | Set-Content -Path $Status -Encoding utf8
}

if (-not (Test-Path $Adb)) { Say "no adb at $Adb"; exit 1 }
Say "link keeper started"
$lastGood = $null; $heals = 0; $badSince = $null; $step = 0; $lastAlive = Get-Date '2000-01-01'; $lastState = ''
while ($true) {
  $busy = Busy
  if ($busy) { Write-Status 'paused' $busy; Start-Sleep -Seconds $EverySeconds; continue }
  $s = State
  if ($s -ne $lastState) { Say "link: $s"; $lastState = $s }
  if ($s -eq 'device') {
    if (((Get-Date) - $lastAlive).TotalSeconds -ge 30) { [void](Adb @('-s', $Serial, 'shell', 'true')); $lastAlive = Get-Date }
    if ($badSince) { Say "link back after $([int]((Get-Date) - $badSince).TotalSeconds) s (step $step)" }
    $lastGood = Get-Date -Format o; $badSince = $null; $step = 0
    Write-Status 'connected' ''
  } elseif ($s -eq 'unauthorized') {
    Write-Status 'unauthorized' 'Tap Allow on the phone (tick Always allow from this computer).'
  } else {
    if (-not $badSince) { $badSince = Get-Date }
    $down = ((Get-Date) - $badSince).TotalSeconds
    # Escalate one step at a time; wait between steps so the link can come back on its own.
    if ($step -eq 0 -and $s -eq 'offline') { Say "heal 1: reconnect offline"; [void](Adb @('reconnect', 'offline')); $step = 1; $heals++ }
    elseif ($step -le 1 -and $down -ge 20 -and $Serial) { Say "heal 2: reconnect device"; [void](Adb @('-s', $Serial, 'reconnect')); $step = 2; $heals++ }
    elseif ($step -le 2 -and $down -ge 60) { Say "heal 3: restart ADB server"; [void](Adb @('kill-server')); Start-Sleep 2; [void](Adb @('start-server') 20000); $step = 3; $heals++ }
    elseif ($step -ge 3 -and $down -ge 180 -and ([int]$down % 300) -lt $EverySeconds) { Say "heal 4: restart ADB server again"; [void](Adb @('kill-server')); Start-Sleep 2; [void](Adb @('start-server') 20000); $heals++ }
    $note = if ($step -ge 3 -and $down -ge 90) {
      if (Usb-Present) { 'Windows still sees the phone, but its debugging link is stuck: unplug the cable and plug it back in.' }
      else { 'Windows does not see the phone: check the cable and that the phone is on.' }
    } else { "healing (step $step)" }
    Write-Status $s $note
  }
  Start-Sleep -Seconds $EverySeconds
}
