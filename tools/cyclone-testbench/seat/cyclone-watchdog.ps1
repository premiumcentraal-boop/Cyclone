# Cyclone testbench watchdog (alpha 108).
# Keeps Cyclone's gateway running WITHOUT a console window (a click in a console froze the gateway on 5 October 2026),
# with test-only Lab approvals switched on, and restarts it when it stops answering. Close: stop this PowerShell process.
# It never updates Cyclone (run `cyclone update` yourself, then the watchdog starts the new version).
param(
  [string]$Approvals = "test-only",   # "off" to keep every Lab approval declined
  [int]$EverySeconds = 60
)
$ErrorActionPreference = 'Continue'
$Runtime = Join-Path $env:LOCALAPPDATA 'Cyclone One\CyclonePCRuntime.exe'
$Log = Join-Path $env:LOCALAPPDATA 'Cyclone One\runtime\testbench-watchdog.log'
function Say($text) { "$(Get-Date -Format o) $text" | Add-Content -Path $Log -Encoding utf8 }

function Test-Gateway {
  # Any HTTP answer (401 included) means alive; no answer in 5 s means down or frozen.
  try { Invoke-WebRequest -Uri 'http://127.0.0.1:8765/v1/lab/missions' -TimeoutSec 5 -UseBasicParsing | Out-Null; return $true }
  catch { if ($_.Exception.Response) { return $true } else { return $false } }
}

function Stop-Cyclone {
  Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'CyclonePCRuntime.exe' -and $_.CommandLine -match ' terminal' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Start-Cyclone {
  if ($Approvals -eq 'test-only') { $env:CYCLONE_LAB_APPROVALS = 'test-only' } else { Remove-Item Env:CYCLONE_LAB_APPROVALS -ErrorAction SilentlyContinue }
  Start-Process -FilePath $Runtime -ArgumentList 'terminal', '--no-update' -WindowStyle Hidden
  Say "started Cyclone hidden (approvals: $Approvals)"
}

if (-not (Test-Path $Runtime)) { Say "Cyclone is not installed at $Runtime"; exit 1 }
Say "watchdog started"
$misses = 0
while ($true) {
  if (Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'Cyclone One\\install\.ps1' }) {
    # `cyclone update` is installing: leave Cyclone alone until it is done.
    Start-Sleep -Seconds 15; continue
  }
  $all = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'CyclonePCRuntime.exe' -and $_.CommandLine -match ' terminal' })
  $ids = $all | ForEach-Object { $_.ProcessId }
  # The root Cyclone process (its packaged child has a Cyclone parent). Ours has this watchdog as its parent.
  $roots = @($all | Where-Object { $ids -notcontains $_.ParentProcessId })
  $foreign = @($roots | Where-Object { $_.ParentProcessId -ne $PID })
  if ($foreign.Count -gt 0) {
    # A Cyclone started by hand or by `cyclone update` runs in a console that a click can freeze, and without the
    # approval switch: replace it with a hidden one.
    Say "replacing a Cyclone this watchdog did not start"
    Stop-Cyclone; Start-Sleep -Seconds 3; Start-Cyclone; Start-Sleep -Seconds 40
  } elseif ($roots.Count -eq 0) {
    Start-Cyclone; Start-Sleep -Seconds 40
  }
  if (Test-Gateway) { $misses = 0 } else {
    $misses++
    Say "gateway did not answer ($misses)"
    if ($misses -ge 2) { Say "restarting Cyclone"; Stop-Cyclone; Start-Sleep -Seconds 3; Start-Cyclone; Start-Sleep -Seconds 40; $misses = 0 }
  }
  Start-Sleep -Seconds $EverySeconds
}
