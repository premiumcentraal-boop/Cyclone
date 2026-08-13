[CmdletBinding()]
param(
    [switch]$WebOnly,
    [switch]$NoOpen,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $Root "docker\docker-compose.yml"
$EnvFile = Join-Path $Root ".env"
$DesktopDir = Join-Path $Root "apps\desktop"
$RuntimeDir = Join-Path $Root ".runtime"

function Require-Command([string]$Name, [string]$InstallHint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name is required. $InstallHint"
    }
}

function Set-EnvLine([string]$Text, [string]$Name, [string]$Value) {
    $pattern = "(?m)^$([regex]::Escape($Name))=.*$"
    $replacement = "$Name=$Value"
    if ([regex]::IsMatch($Text, $pattern)) {
        return [regex]::Replace($Text, $pattern, $replacement)
    }
    return "$Text`r`n$replacement`r`n"
}

function Get-EnvLine([string]$Text, [string]$Name) {
    $match = [regex]::Match($Text, "(?m)^$([regex]::Escape($Name))=(.*)$")
    if ($match.Success) { return $match.Groups[1].Value.Trim() }
    return $null
}

function Convert-ToComposePath([string]$Path) {
    return $Path.Replace("\", "/")
}

function New-Secret {
    return [guid]::NewGuid().ToString("N")
}

function Test-Port([int]$Port) {
    try {
        $client = [System.Net.Sockets.TcpClient]::new()
        $task = $client.ConnectAsync("127.0.0.1", $Port)
        $connected = $task.Wait(500)
        $client.Dispose()
        return $connected
    } catch {
        return $false
    }
}

Require-Command "docker" "Install Docker Desktop with Linux containers enabled: https://docs.docker.com/desktop/setup/install/windows-install/"
Require-Command "node" "Install Node.js 22 or newer: https://nodejs.org/"
Require-Command "npm.cmd" "Install Node.js 22 or newer so npm is available."

Write-Host "Checking Docker Desktop..." -ForegroundColor Cyan
& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Desktop is installed but its engine is not running. Start Docker Desktop and run Launch-Cyclone.bat again."
}

$defaultVault = Join-Path $RuntimeDir "Vault"
$defaultWorkspace = Join-Path $RuntimeDir "Workspace"
New-Item -ItemType Directory -Force -Path $defaultVault, $defaultWorkspace | Out-Null

if (-not (Test-Path $EnvFile)) {
    Write-Host "Creating .env with local-only secrets..." -ForegroundColor Cyan
    $envText = Get-Content (Join-Path $Root ".env.example") -Raw
    $values = @{
        "CYCLONE_VAULT_HOST_PATH" = Convert-ToComposePath $defaultVault
        "CYCLONE_WORKSPACE_HOST_PATH" = Convert-ToComposePath $defaultWorkspace
        "POSTGRES_PASSWORD" = New-Secret
        "N8N_POSTGRES_PASSWORD" = New-Secret
        "N8N_ENCRYPTION_KEY" = New-Secret
        "N8N_USER_MANAGEMENT_JWT_SECRET" = New-Secret
        "HERMES_API_KEY" = New-Secret
        "CYCLONE_INTERNAL_API_KEY" = New-Secret
        "CYCLONE_HOST_BRIDGE_TOKEN" = New-Secret
    }
    foreach ($entry in $values.GetEnumerator()) {
        $envText = Set-EnvLine $envText $entry.Key $entry.Value
    }
    Set-Content -Path $EnvFile -Value $envText -Encoding UTF8
} else {
    $envText = Get-Content $EnvFile -Raw
    $vaultPath = Get-EnvLine $envText "CYCLONE_VAULT_HOST_PATH"
    $workspacePath = Get-EnvLine $envText "CYCLONE_WORKSPACE_HOST_PATH"
    if ([string]::IsNullOrWhiteSpace($vaultPath)) { $vaultPath = Convert-ToComposePath $defaultVault }
    if ([string]::IsNullOrWhiteSpace($workspacePath)) { $workspacePath = Convert-ToComposePath $defaultWorkspace }
    New-Item -ItemType Directory -Force -Path $vaultPath.Replace("/", "\"), $workspacePath.Replace("/", "\") | Out-Null
}

if (-not $SkipInstall -and -not (Test-Path (Join-Path $DesktopDir "node_modules"))) {
    Write-Host "Installing Cyclone Desktop dependencies..." -ForegroundColor Cyan
    & npm.cmd --prefix $DesktopDir ci
    if ($LASTEXITCODE -ne 0) { throw "npm ci failed." }
}

Write-Host "Starting Cyclone Core, Hermes, Postgres, Redis, and n8n..." -ForegroundColor Cyan
& docker compose --env-file $EnvFile -f $ComposeFile up -d --build
if ($LASTEXITCODE -ne 0) { throw "Docker Compose could not start Cyclone." }

Write-Host "Waiting for Cyclone Core..." -ForegroundColor Cyan
$healthy = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    try {
        $health = Invoke-RestMethod "http://127.0.0.1:8787/health" -TimeoutSec 2
        if ($health.status -eq "ok") { $healthy = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $healthy) {
    throw "Cyclone Core did not become healthy. Inspect with: docker compose --env-file .env -f docker/docker-compose.yml logs cyclone-core"
}

$cargoAvailable = [bool](Get-Command "cargo" -ErrorAction SilentlyContinue)
if (-not $WebOnly -and $cargoAvailable) {
    Write-Host "Launching the native Tauri desktop..." -ForegroundColor Green
    Start-Process -FilePath "npm.cmd" -WorkingDirectory $DesktopDir -ArgumentList @("run", "tauri:dev") | Out-Null
    Write-Host "Cyclone is starting in a native window." -ForegroundColor Green
} else {
    if (-not $WebOnly -and -not $cargoAvailable) {
        Write-Host "Rust/Cargo is not installed; launching the browser client instead." -ForegroundColor Yellow
        Write-Host "Install Rust from https://rustup.rs/ later for the native Tauri window." -ForegroundColor Yellow
    } else {
        Write-Host "Launching the browser client..." -ForegroundColor Green
    }
    Start-Process -FilePath "npm.cmd" -WorkingDirectory $DesktopDir -ArgumentList @("run", "dev", "--", "--host", "127.0.0.1") | Out-Null
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (Test-Port 1420) { break }
        Start-Sleep -Seconds 1
    }
    if (-not $NoOpen) { Start-Process "http://127.0.0.1:1420" | Out-Null }
    Write-Host "Cyclone is available at http://127.0.0.1:1420" -ForegroundColor Green
}

Write-Host "Local stack is ready. Add a model key and Telegram settings to .env, then rerun this launcher if needed." -ForegroundColor Cyan
