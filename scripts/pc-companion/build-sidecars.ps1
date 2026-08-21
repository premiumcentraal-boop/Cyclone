param(
    [string]$TargetTriple = "x86_64-pc-windows-msvc"
)

$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Lock = Get-Content (Join-Path $Repo 'packaging\pc-companion\sidecar-build.lock.json') -Raw | ConvertFrom-Json
python -m pip install --disable-pip-version-check "pyinstaller==$($Lock.pyinstaller)"
python -m pip install --disable-pip-version-check (Join-Path $Repo 'apps\device-gateway') (Join-Path $Repo 'tools\cyclone-agent-mcp')
$Dist = Join-Path $Repo 'dist\pc-companion'
New-Item -ItemType Directory -Force -Path $Dist | Out-Null
python -m PyInstaller --noconfirm --distpath $Dist --workpath (Join-Path $Repo 'build\pyinstaller\agent') (Join-Path $Repo 'packaging\pc-companion\pyinstaller\CycloneAgentMCP.spec')
python -m PyInstaller --noconfirm --distpath $Dist --workpath (Join-Path $Repo 'build\pyinstaller\runtime') (Join-Path $Repo 'packaging\pc-companion\pyinstaller\CyclonePCRuntime.spec')

$TauriBinaries = Join-Path $Repo 'apps\pc-companion\src-tauri\binaries'
New-Item -ItemType Directory -Force -Path $TauriBinaries | Out-Null
foreach ($Name in @('CyclonePCRuntime', 'CycloneAgentMCP')) {
    $Source = Join-Path $Dist "$Name.exe"
    if (-not (Test-Path $Source)) { throw "Missing sidecar output: $Source" }
    Copy-Item $Source (Join-Path $TauriBinaries "$Name-$TargetTriple.exe") -Force
}
Write-Host "Cyclone sidecars staged for Tauri target $TargetTriple"
