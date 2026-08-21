$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Lock = Get-Content (Join-Path $Repo 'packaging\pc-companion\sidecar-build.lock.json') -Raw | ConvertFrom-Json
python -m pip install --disable-pip-version-check "pyinstaller==$($Lock.pyinstaller)"
python -m pip install --disable-pip-version-check (Join-Path $Repo 'apps\device-gateway') (Join-Path $Repo 'tools\cyclone-agent-mcp')
$Dist = Join-Path $Repo 'dist\pc-companion'
New-Item -ItemType Directory -Force -Path $Dist | Out-Null
python -m PyInstaller --noconfirm --distpath $Dist --workpath (Join-Path $Repo 'build\pyinstaller\agent') (Join-Path $Repo 'packaging\pc-companion\pyinstaller\CycloneAgentMCP.spec')
python -m PyInstaller --noconfirm --distpath $Dist --workpath (Join-Path $Repo 'build\pyinstaller\runtime') (Join-Path $Repo 'packaging\pc-companion\pyinstaller\CyclonePCRuntime.spec')
