# Set up the Cyclone testbench seat in this folder (alpha 108). Run from the Cyclone-testbench folder.
param([switch]$Startup)
$ErrorActionPreference = 'Stop'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Resolve-Path (Join-Path $Here '..\..\..')
New-Item -ItemType Directory -Force -Path (Join-Path $Root '.claude') | Out-Null
Copy-Item (Join-Path $Here 'settings.local.json') (Join-Path $Root '.claude\settings.local.json') -Force
Copy-Item (Join-Path $Here 'briefing.md') (Join-Path $Root 'CLAUDE.local.md') -Force
Write-Host "Seat permissions and briefing copied into $Root"
$Watchdog = Join-Path $Here 'cyclone-watchdog.ps1'
if ($Startup) {
  $StartupDir = [Environment]::GetFolderPath('Startup')
  $Line = "powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$Watchdog`""
  Set-Content -Path (Join-Path $StartupDir 'cyclone-testbench-watchdog.cmd') -Value "@echo off`r`nstart `"`" $Line" -Encoding ascii
  Write-Host "The watchdog starts at every login (remove cyclone-testbench-watchdog.cmd from your Startup folder to stop that)."
}
$running = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'powershell.exe' -and $_.CommandLine -match 'cyclone-watchdog.ps1' }
if (-not $running) {
  Start-Process powershell -ArgumentList '-NoProfile', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass', '-File', "`"$Watchdog`"" -WindowStyle Hidden
  Write-Host "Watchdog started (it restarts Cyclone without a console window)."
} else { Write-Host "Watchdog already running." }
Write-Host "Next: claude remote-control"
