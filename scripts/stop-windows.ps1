[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$ComposeFile = Join-Path $Root "docker\docker-compose.yml"
$EnvFile = Join-Path $Root ".env"

if (-not (Get-Command "docker" -ErrorAction SilentlyContinue)) {
    throw "Docker Desktop is required to stop Cyclone."
}

if (Test-Path $EnvFile) {
    & docker compose --env-file $EnvFile -f $ComposeFile down
} else {
    & docker compose -f $ComposeFile down
}
if ($LASTEXITCODE -ne 0) { throw "Docker Compose could not stop Cyclone." }
Write-Host "Cyclone services stopped. Persistent Docker volumes were preserved." -ForegroundColor Green
