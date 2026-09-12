param([Parameter(Mandatory=$true)][string]$Installer, [Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference = 'Stop'
$InstallDir = Join-Path $env:RUNNER_TEMP 'CycloneOneInstalledAcceptance'
$process = Start-Process -FilePath (Resolve-Path $Installer) -ArgumentList "/S /D=$InstallDir" -PassThru
if (-not $process.WaitForExit(120000)) { $process.Kill(); throw 'Installer timed out' }
if ($process.ExitCode -ne 0) { throw "Installer failed: $($process.ExitCode)" }
$Required = @('cyclone-pc-companion.exe','CyclonePCRuntime.exe','CycloneAgentMCP.exe','CycloneLivePhone.exe')
foreach ($Name in $Required) { if (-not (Test-Path (Join-Path $InstallDir $Name))) { throw "Installed binary missing: $Name" } }
$help = & (Join-Path $InstallDir 'CycloneLivePhone.exe') --help
if ($LASTEXITCODE -ne 0 -or ($help -join ' ') -notmatch 'observe') { throw 'Installed adapter cannot start' }
$Cloudflared = Get-ChildItem -Path $InstallDir -Recurse -Filter cloudflared.exe -File | Select-Object -First 1
if ($null -eq $Cloudflared) { throw 'Installed Direct Live Phone HTTPS bridge component missing' }
@{ installed=$true; required_binaries=$Required; cli_help=$true; direct_bridge_component=$true; physical_phone='UNVERIFIED' } | ConvertTo-Json | Set-Content -Encoding utf8 $Output
