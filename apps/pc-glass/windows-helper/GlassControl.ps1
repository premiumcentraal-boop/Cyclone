#Requires -Version 5.1
<#
.SYNOPSIS
  Start / stop / update Cyclone Glass without flashing a console window.
  Updates replace tracked app files from GitHub and keep local data (.env, traces, DBs).
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-HelperRoot { return $PSScriptRoot }
function Get-GlassRoot { return (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }
function Get-RepoRoot {
  # apps/pc-glass -> repo root
  return (Resolve-Path (Join-Path (Get-GlassRoot) '..\..')).Path
}

function Get-GlassLogDir {
  $dir = Join-Path $env:LOCALAPPDATA 'CycloneGlass\logs'
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  return $dir
}

function Write-GlassLog([string]$Message) {
  $line = '{0:u} {1}' -f (Get-Date), $Message
  Add-Content -Path (Join-Path (Get-GlassLogDir) 'helper.log') -Value $line -Encoding UTF8
}

function Get-UpdateChannel {
  $path = Join-Path (Get-HelperRoot) 'update-channel.json'
  if (-not (Test-Path $path)) {
    return [pscustomobject]@{
      product = 'Cyclone Glass'
      branch = 'feature/pc-glass-artemis'
      remote = 'origin'
      glass_subdir = 'apps/pc-glass'
    }
  }
  return (Get-Content $path -Raw | ConvertFrom-Json)
}


function Import-GlassModeAEnv {
  # Load CYCLONE_* from glass .env and device-gateway serve.env (token never printed).
  $glass = Get-GlassRoot
  $repo = Get-RepoRoot
  $files = @(
    (Join-Path $glass '.env'),
    (Join-Path $repo 'apps\device-gateway\.runtime\serve.env')
  )
  foreach ($f in $files) {
    if (-not (Test-Path $f)) { continue }
    Get-Content $f | ForEach-Object {
      $line = $_.Trim()
      if (-not $line -or $line.StartsWith('#')) { return }
      if ($line -notmatch '^(?<k>[^=]+)=(?<v>.*)$') { return }
      $k = $Matches['k'].Trim().TrimStart([char]0xFEFF)
      $v = $Matches['v'].Trim().Trim('"').Trim("'")
      if ($k -notlike 'CYCLONE_*' -and $k -notlike 'OPEN*ROUTER*') { return }
      if (-not $v) { return }
      # Prefer first non-empty; serve.env may fill TOKEN after .env
      $cur = [Environment]::GetEnvironmentVariable($k, 'Process')
      if ([string]::IsNullOrWhiteSpace($cur)) {
        [Environment]::SetEnvironmentVariable($k, $v, 'Process')
      } elseif ($k -eq 'CYCLONE_DEVICE_GATEWAY_TOKEN' -and $cur.Length -lt 8) {
        [Environment]::SetEnvironmentVariable($k, $v, 'Process')
      }
    }
  }
  if (-not $env:CYCLONE_DEVICE_GATEWAY_URL) { $env:CYCLONE_DEVICE_GATEWAY_URL = 'http://127.0.0.1:8765' }
  if (-not $env:CYCLONE_SESSION_ID) { $env:CYCLONE_SESSION_ID = 'default-foreground' }
  $env:CYCLONE_CONNECTED = '1'
}

function Test-GlassUp([int]$Port = 8000) {
  try {
    $req = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$Port/api/status")
    $req.Timeout = 2000
    $resp = $req.GetResponse()
    $resp.Close()
    return $true
  } catch {
    return $false
  }
}

function Start-GlassHidden {
  param([int]$Port = 8000)
  $root = Get-GlassRoot
  if (Test-GlassUp -Port $Port) {
    Write-GlassLog "Glass already up on :$Port"
    return @{ Ok = $true; Message = "Already running on http://127.0.0.1:$Port" }
  }
  $uvCmd = Get-Command uv -ErrorAction SilentlyContinue
  if (-not $uvCmd) {
    return @{ Ok = $false; Message = 'uv not found on PATH. Install https://docs.astral.sh/uv/' }
  }
  Import-GlassModeAEnv
  $env:CYCLONE_CONNECTED = '1'
  $env:PYTHONUNBUFFERED = '1'
  Write-GlassLog "Starting Cyclone Glass in $root (port $Port) session=$($env:CYCLONE_SESSION_ID) tokenLen=$($env:CYCLONE_DEVICE_GATEWAY_TOKEN.Length) device=$($env:CYCLONE_DEVICE_ID)"

  $logDir = Get-GlassLogDir
  $logOut = Join-Path $logDir 'glass-stdout.log'
  $logErr = Join-Path $logDir 'glass-stderr.log'
  # File redirects (not Start-Job pipe readers): an unread redirected pipe fills
  # (~4KB) and freezes the Glass event loop / QueueWorker so tasks look "started"
  # then vanish while /api/status stays idle.
  Set-Content -LiteralPath $logOut -Value '' -Encoding UTF8
  Set-Content -LiteralPath $logErr -Value '' -Encoding UTF8

  $proc = Start-Process -FilePath $uvCmd.Source `
    -ArgumentList @('run', 'python', '-m', 'artemis', 'ui', '--port', "$Port", '--no-open') `
    -WorkingDirectory $root `
    -WindowStyle Hidden `
    -PassThru `
    -RedirectStandardOutput $logOut `
    -RedirectStandardError $logErr

  for ($i = 0; $i -lt 45; $i++) {
    Start-Sleep -Seconds 2
    if (Test-GlassUp -Port $Port) {
      Write-GlassLog "up after $($i*2)s pid=$($proc.Id)"
      return @{ Ok = $true; Message = "Started - http://127.0.0.1:$Port"; Pid = $proc.Id }
    }
    if ($proc.HasExited) {
      return @{ Ok = $false; Message = "Process exited early (see $logErr)" }
    }
  }
  return @{ Ok = $false; Message = "Timed out waiting for :$Port (logs: %LOCALAPPDATA%\CycloneGlass\logs)" }
}

function Stop-GlassHidden {
  param([int]$Port = 8000)
  $root = Get-GlassRoot
  $uvCmd = Get-Command uv -ErrorAction SilentlyContinue
  if (-not $uvCmd) { return @{ Ok = $false; Message = 'uv not found on PATH' } }
  Write-GlassLog "Stopping Cyclone Glass on :$Port"
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $uvCmd.Source
  $psi.Arguments = "run python -m artemis stop --port $Port"
  $psi.WorkingDirectory = $root
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $proc = [System.Diagnostics.Process]::Start($psi)
  $null = $proc.WaitForExit(60000)
  Start-Sleep -Seconds 1
  if (Test-GlassUp -Port $Port) {
    return @{ Ok = $false; Message = 'Stop finished but :8000 still responds' }
  }
  return @{ Ok = $true; Message = 'Stopped' }
}

function Update-CycloneGlass {
  <#
    .SYNOPSIS
      Rapid-fire update: fetch GitHub tip, hard-replace tracked files, keep data.
  #>
  param(
    [string]$Branch = '',
    [switch]$RebuildUi,
    [switch]$Restart
  )
  $channel = Get-UpdateChannel
  if (-not $Branch) { $Branch = [string]$channel.branch }
  $remote = [string]$channel.remote
  if (-not $remote) { $remote = 'origin' }

  $repo = Get-RepoRoot
  $glass = Get-GlassRoot
  Write-GlassLog "Update start repo=$repo branch=$Branch"

  if (-not (Test-Path (Join-Path $repo '.git'))) {
    return @{ Ok = $false; Message = "Not a git checkout: $repo" }
  }

  # 1) Stop server so files are not locked
  if (Test-GlassUp) {
    $stop = Stop-GlassHidden
    if (-not $stop.Ok) {
      Write-GlassLog "stop warning: $($stop.Message)"
    }
  }

  # 2) Snapshot data that must survive (already gitignored; still back up)
  $backupRoot = Join-Path $env:LOCALAPPDATA ("CycloneGlass\update-backup\{0:yyyyMMdd-HHmmss}" -f (Get-Date))
  New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null
  $preserve = @('.env', 'traces', 'data_engine.db', 'data_engine.db-journal', 'scratch')
  foreach ($name in $preserve) {
    $src = Join-Path $glass $name
    if (Test-Path $src) {
      Copy-Item -Recurse -Force $src (Join-Path $backupRoot $name)
      Write-GlassLog "backed up $name"
    }
  }

  $before = ''
  Push-Location $repo
  try {
    # git writes progress to stderr; do not treat that as a terminating error
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
      $before = (& git rev-parse --short HEAD 2>$null | Out-String).Trim()
      $fetchOut = & git fetch $remote $Branch 2>&1
      $fetchCode = $LASTEXITCODE
      $fetchOut | ForEach-Object { Write-GlassLog "$_" }
      if ($fetchCode -ne 0) {
        return @{ Ok = $false; Message = "git fetch failed (see helper.log)" }
      }

      # Replace ALL tracked files with remote tip (removes deleted tracked files too)
      $coOut = & git checkout -B $Branch "$remote/$Branch" 2>&1
      $coOut | ForEach-Object { Write-GlassLog "$_" }
      $resetOut = & git reset --hard "$remote/$Branch" 2>&1
      $resetCode = $LASTEXITCODE
      $resetOut | ForEach-Object { Write-GlassLog "$_" }
      if ($resetCode -ne 0) {
        return @{ Ok = $false; Message = "git reset --hard failed" }
      }

      # Remove leftover untracked junk under glass, but NEVER data / venv / node_modules
      Push-Location $glass
      try {
        $cleanOut = & git clean -fd -e .env -e '.env.*' -e traces -e scratch -e .venv -e 'apps/showcase_ui/node_modules' `
          -e data_engine.db -e 'data_engine.db-*' -e '*.log' -e .artemis_server.json `
          -e windows-helper/dist 2>&1
        $cleanOut | ForEach-Object { Write-GlassLog "$_" }
      } finally {
        Pop-Location
      }

      $after = (& git rev-parse --short HEAD 2>$null | Out-String).Trim()
    } finally {
      $ErrorActionPreference = $prevEap
    }
  } finally {
    Pop-Location
  }

  # 3) Restore data if somehow wiped (should not happen for untracked)
  foreach ($name in $preserve) {
    $dst = Join-Path $glass $name
    $bak = Join-Path $backupRoot $name
    if ((Test-Path $bak) -and -not (Test-Path $dst)) {
      Copy-Item -Recurse -Force $bak $dst
      Write-GlassLog "restored $name from backup"
    }
  }

  # 4) Ensure Mode A defaults in .env without clobbering secrets
  $envFile = Join-Path $glass '.env'
  if (Test-Path $envFile) {
    $raw = Get-Content $envFile -Raw
    if ($raw -notmatch '(?m)^CYCLONE_CONNECTED=') {
      Add-Content $envFile "`nCYCLONE_CONNECTED=1"
    }
    if ($raw -notmatch '(?m)^CYCLONE_SESSION_ID=') {
      Add-Content $envFile "`nCYCLONE_SESSION_ID=default-foreground"
    }
    if ($raw -notmatch '(?m)^CYCLONE_DEVICE_GATEWAY_URL=') {
      Add-Content $envFile "`nCYCLONE_DEVICE_GATEWAY_URL=http://127.0.0.1:8765"
    }
  }

  # 5) Optional UI rebuild (needed when Angular sources change)
  $rebuildNote = ''
  if ($RebuildUi) {
    $ui = Join-Path $glass 'apps\showcase_ui'
    $npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if (-not $npm) { $npm = Get-Command npm -ErrorAction SilentlyContinue }
    if ($npm -and (Test-Path $ui)) {
      Write-GlassLog 'npm run build showcase_ui'
      Push-Location $ui
      try {
        & $npm.Source run build 2>&1 | ForEach-Object { Write-GlassLog $_ }
        if ($LASTEXITCODE -ne 0) {
          return @{ Ok = $false; Message = "Update pulled $before -> $after but UI build failed"; Before = $before; After = $after }
        }
        $rebuildNote = '; UI rebuilt'
      } finally {
        Pop-Location
      }
    } else {
      $rebuildNote = '; skipped UI rebuild (npm missing)'
    }
  }

  if ($Restart) {
    $start = Start-GlassHidden
    $rebuildNote += "; start: $($start.Message)"
  }

  $msg = "Cyclone Glass updated $before -> $after (data kept: .env, traces, DBs)$rebuildNote"
  Write-GlassLog $msg
  return @{ Ok = $true; Message = $msg; Before = $before; After = $after; Backup = $backupRoot }
}
