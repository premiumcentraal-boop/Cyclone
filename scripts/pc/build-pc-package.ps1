<#
Plan 31: build the web-only Cyclone for Windows package, Cyclone-PC-<version>.zip, then prove it on this Windows machine:
install it with install.ps1 into a scratch profile, run `cyclone version`, and start the runtime to check it serves
Glass and the /v1/pc routes. No Tauri window is built.

Zip layout (the same as Cyclone One's install folder, so paths, AI connections and saved data keep working):
  CyclonePCRuntime.exe  CycloneAgentMCP.exe  install.ps1  THIRD_PARTY_NOTICES
  android-platform-tools\  mcp-tunnel\  chatgpt-attach\  live-phone\cloudflared.exe
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$OutDir = 'dist\pc-package'
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Lock = Get-Content (Join-Path $Repo 'packaging\pc-companion\sidecar-build.lock.json') -Raw | ConvertFrom-Json
$Resources = Join-Path $Repo 'apps\pc-companion\src-tauri\resources'
$Venv = Join-Path $Repo 'build\pc-package-venv'
$Python = Join-Path $Venv 'Scripts\python.exe'
if (-not (Test-Path $Python)) { python -m venv $Venv }

# Bundled third-party binaries, each checksum-locked (packaging/pc-companion/third-party-binaries.lock.json).
& $Python (Join-Path $Repo 'scripts\pc-companion\prepare-scrcpy-server.py') --repo $Repo
& $Python (Join-Path $Repo 'scripts\pc-companion\prepare-android-platform-tools.py') --repo $Repo
& $Python -m pip install --disable-pip-version-check "pyinstaller==$($Lock.pyinstaller)"
& $Python -m pip install --disable-pip-version-check (Join-Path $Repo 'apps\device-gateway') (Join-Path $Repo 'tools\cyclone-agent-mcp') (Join-Path $Repo 'tools\codex-phone-mcp') (Join-Path $Repo 'tools\cyclone-ports-sdk')
& $Python (Join-Path $Repo 'scripts\pc-companion\prepare-live-bridge.py')

# Glass ships inside the runtime; the gateway serves it at /glass/.
Push-Location (Join-Path $Repo 'apps\glass')
try { npm ci --no-audit --no-fund; npm run build } finally { Pop-Location }

$Work = Join-Path $Repo 'build\pc-package'
$Exes = Join-Path $Work 'exe'
& $Python -m PyInstaller --clean --noconfirm --distpath $Exes --workpath (Join-Path $Work 'agent') (Join-Path $Repo 'packaging\pc-companion\pyinstaller\CycloneAgentMCP.spec')
& $Python -m PyInstaller --clean --noconfirm --distpath $Exes --workpath (Join-Path $Work 'runtime') (Join-Path $Repo 'packaging\pc-companion\pyinstaller\CyclonePCRuntime.spec')

$Stage = Join-Path $Work 'stage'
if (Test-Path $Stage) { Remove-Item -Recurse -Force $Stage }
New-Item -ItemType Directory -Force -Path $Stage | Out-Null
Copy-Item (Join-Path $Exes 'CyclonePCRuntime.exe'), (Join-Path $Exes 'CycloneAgentMCP.exe') $Stage
Copy-Item (Join-Path $Repo 'packaging\pc\install.ps1') $Stage
foreach ($folder in @('android-platform-tools', 'mcp-tunnel', 'chatgpt-attach', 'live-phone')) {
    $source = Join-Path $Resources $folder
    if (-not (Test-Path $source)) { throw "Missing bundled folder: $source" }
    Copy-Item -Recurse $source (Join-Path $Stage $folder)
}
# Never ship an owner's local secrets from a developer checkout.
Get-ChildItem -Recurse -Force $Stage -Include 'gateway.env', 'fleet.dpapi', '*.ephemeral.json' | Remove-Item -Force
& $Python (Join-Path $Repo 'scripts\pc-companion\generate-third-party-notices.py') --lock (Join-Path $Repo 'packaging\pc-companion\third-party-binaries.lock.json') --output (Join-Path $Stage 'THIRD_PARTY_NOTICES')

$Adb = Join-Path $Stage 'android-platform-tools\adb.exe'
$AdbVersion = (& $Adb version 2>&1) -join "`n"
if ($AdbVersion -notmatch 'Version\s+37\.0\.1') { throw "Bundled adb failed version verification: $AdbVersion" }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$Zip = Join-Path (Resolve-Path $OutDir) "Cyclone-PC-$Version.zip"
if (Test-Path $Zip) { Remove-Item -Force $Zip }
Compress-Archive -Path (Join-Path $Stage '*') -DestinationPath $Zip
$Hash = (Get-FileHash -Algorithm SHA256 $Zip).Hash.ToLowerInvariant()
Set-Content -Path "$Zip.sha256" -Value "$Hash  Cyclone-PC-$Version.zip" -NoNewline
Write-Host "Built $Zip ($Hash)"

# ---- Cyclone-Setup-<version>.exe: one double-click install (alpha.48), wrapping the same install.ps1 ---------------
$Payload = Join-Path $Work 'setup-payload'
if (Test-Path $Payload) { Remove-Item -Recurse -Force $Payload }
New-Item -ItemType Directory -Force -Path $Payload | Out-Null
Copy-Item $Zip (Join-Path $Payload 'Cyclone-PC.zip')
# install.ps1 -Zip checks a sidecar holding the hash alone.
Set-Content -Path (Join-Path $Payload 'Cyclone-PC.zip.sha256') -Value $Hash -NoNewline
Copy-Item (Join-Path $Repo 'packaging\pc\install.ps1') $Payload
$Makensis = (Get-Command makensis -ErrorAction SilentlyContinue).Source
if (-not $Makensis) {
    foreach ($candidate in @("${env:ProgramFiles(x86)}\NSIS\makensis.exe", "$env:ProgramFiles\NSIS\makensis.exe")) {
        if (Test-Path $candidate) { $Makensis = $candidate; break }
    }
}
if (-not $Makensis) {
    # The Chocolatey feed sometimes answers 504 for a minute; retry with backoff before calling it a failure.
    $Makensis = "${env:ProgramFiles(x86)}\NSIS\makensis.exe"
    foreach ($wait in @(0, 20, 45, 90)) {
        if ($wait) { Write-Host "NSIS not installed yet; retrying Chocolatey in $wait s."; Start-Sleep -Seconds $wait }
        choco install nsis -y --no-progress
        if (Test-Path $Makensis) { break }
    }
    if (-not (Test-Path $Makensis)) { throw 'NSIS could not be installed from Chocolatey after 4 attempts (feed unavailable).' }
}
$VersionToml = Get-Content (Join-Path $Repo 'release\version.toml') -Raw
if ($VersionToml -notmatch '(?m)^android_version_code\s*=\s*(\d+)') { throw 'release/version.toml has no android_version_code.' }
$Code = [int]$Matches[1]
if ($Version -notmatch '^(\d+)\.(\d+)\.(\d+)') { throw "Unexpected version $Version" }
$FileVersion = "$($Matches[1]).$($Matches[2]).$($Matches[3]).$Code"
$SetupName = "Cyclone-Setup-$Version.exe"
$Setup = Join-Path (Resolve-Path $OutDir) $SetupName
& $Makensis /V2 "/DVERSION=$Version" "/DFILEVERSION=$FileVersion" "/DPAYLOAD=$Payload" "/DOUTFILE=$Setup" (Join-Path $Repo 'packaging\pc\cyclone-setup.nsi')
$SetupHash = (Get-FileHash -Algorithm SHA256 $Setup).Hash.ToLowerInvariant()
Set-Content -Path "$Setup.sha256" -Value "$SetupHash  $SetupName" -NoNewline
Write-Host "Built $Setup ($SetupHash)"

# ---- Prove it: install into a scratch profile, run cyclone, start the runtime -------------------------------------
$Scratch = Join-Path $Work 'scratch-profile'
if (Test-Path $Scratch) { Remove-Item -Recurse -Force $Scratch }
New-Item -ItemType Directory -Force -Path $Scratch | Out-Null
# Plan 50: two versions of the example run-logger as real plugin packages (PyInstaller program, conformance-checked
# the way Cyclone starts it), so the smoke below installs, updates and rolls back a plugin with the installed runtime.
$PluginDist = Join-Path $Work 'plugins'
$Logger = Join-Path $Repo 'tools\cyclone-ports-sdk\examples\logger'
$Logger2 = Join-Path $Work 'logger-0.1.1'
if (Test-Path $Logger2) { Remove-Item -Recurse -Force $Logger2 }
Copy-Item -Recurse $Logger $Logger2
foreach ($file in 'cyclone-plugin.toml', 'cyclone-plugin.json') {
    $path = Join-Path $Logger2 $file
    (Get-Content $path -Raw).Replace('"0.1.0"', '"0.1.1"') | Set-Content -Path $path -NoNewline
}
$pluginBuilds = @()
foreach ($folder in $Logger, $Logger2) {
    $built = (& $Python -m cyclone_ports.build --folder $folder --script (Join-Path $folder 'plugin.py') --out $PluginDist --test-settings '{"folder": "runs"}' | Select-Object -Last 1) | ConvertFrom-Json
    if (-not $built.ok) { throw "The example plugin didn't build: $($built | ConvertTo-Json -Compress)" }
    $pluginBuilds += $built.file
}

function Wait-PluginJob($job, $headers) {
    for ($i = 0; $i -lt 240 -and $job.state -eq 'running'; $i++) {
        Start-Sleep -Milliseconds 500
        $job = Invoke-RestMethod -TimeoutSec 120 -Headers $headers "http://127.0.0.1:8799/v1/plugins/jobs/$($job.id)"
    }
    if ($job.state -ne 'done') { throw "Plugin job $($job.action) ended $($job.state): $($job.detail)" }
    return $job
}

function Install-PluginFile($file, $headers) {
    $resolved = Wait-PluginJob (Invoke-RestMethod -TimeoutSec 120 -Method Post -Headers $headers -ContentType 'application/json' -Body (@{ source = $file } | ConvertTo-Json) 'http://127.0.0.1:8799/v1/plugins/resolve') $headers
    $body = @{ sha256 = $resolved.result.sha256; accept = $true; trustUnverified = $true; allowed = @('run.event', 'log.line'); settings = @{} } | ConvertTo-Json
    Wait-PluginJob (Invoke-RestMethod -TimeoutSec 120 -Method Post -Headers $headers -ContentType 'application/json' -Body $body 'http://127.0.0.1:8799/v1/plugins/install') $headers | Out-Null
}

$savedLocal = $env:LOCALAPPDATA
try {
    $env:LOCALAPPDATA = $Scratch
    Copy-Item $Zip (Join-Path $Scratch 'Cyclone-PC.zip')
    Set-Content -Path (Join-Path $Scratch 'Cyclone-PC.zip.sha256') -Value $Hash -NoNewline
    & powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Repo 'packaging\pc\install.ps1') -Zip (Join-Path $Scratch 'Cyclone-PC.zip') -NoStart
    if ($LASTEXITCODE -ne 0) { throw "install.ps1 failed ($LASTEXITCODE)" }
    $Shim = Join-Path $Scratch 'Cyclone One\bin\cyclone.cmd'
    if (-not (Test-Path $Shim)) { throw "install.ps1 did not create $Shim" }
    $reported = (& $Shim version 2>&1) -join "`n"
    if ($reported -notmatch [regex]::Escape("Cyclone $Version")) { throw "cyclone version answered '$reported', expected Cyclone $Version" }
    Write-Host "cyclone version: $reported"

    $token = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
    $env:CYCLONE_DEVICE_GATEWAY_TOKEN = $token
    $env:CYCLONE_DEVICE_GATEWAY_PORT = '8799'
    $env:CYCLONE_DEVICE_GATEWAY_RUNTIME = Join-Path $Scratch 'Cyclone One\runtime'
    # The same start-up the cyclone command uses: first-use pairing instead of a fixed Android bridge token.
    $env:CYCLONE_DESKTOP_PAIRING_BOOTSTRAP = '1'
    $runtime = Start-Process -FilePath (Join-Path $Scratch 'Cyclone One\CyclonePCRuntime.exe') -ArgumentList 'serve' -PassThru -WindowStyle Hidden
    try {
        $ready = $false
        for ($i = 0; $i -lt 60 -and -not $ready; $i++) {
            Start-Sleep -Seconds 1
            try { $ready = (Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8799/glass/').StatusCode -eq 200 } catch { }
        }
        if (-not $ready) {
            $state = if ($runtime.HasExited) { "exited with code $($runtime.ExitCode)" } else { 'still running' }
            throw "The installed runtime did not serve Glass at /glass/ (runtime $state)."
        }
        $welcome = Invoke-RestMethod -TimeoutSec 120 -Headers @{ Authorization = "Bearer $token" } 'http://127.0.0.1:8799/v1/pc/welcome'
        if ($welcome.seen -ne $false) { throw "The /v1/pc routes did not answer as expected: $($welcome | ConvertTo-Json -Compress)" }
        try { Invoke-RestMethod -TimeoutSec 120 'http://127.0.0.1:8799/v1/pc/tunnel'; throw 'The /v1/pc routes answered without the bearer.' } catch { if ($_.Exception.Message -like '*answered without*') { throw } }
        # Plan 48: the Port Hub ships with the kit it imports; a missing kit answers 503 and fails the build here.
        $ports = Invoke-RestMethod -TimeoutSec 120 -Headers @{ Authorization = "Bearer $token" } 'http://127.0.0.1:8799/v1/ports/overview'
        if ($ports.contract -ne 'cyclone.ports/1' -or @($ports.catalog).Count -lt 12) { throw "The Port Hub did not answer as expected: $($ports | ConvertTo-Json -Compress -Depth 3)" }
        Write-Host 'The installed runtime serves Glass, the authenticated /v1/pc routes and the Port Hub.'

        # Plan 50: install a plugin package, see it run and pass the hub's signed checks, update it, roll it back.
        $auth = @{ Authorization = "Bearer $token" }
        # Prove the shipped first-run starter, including the frozen runtime's JSON resource.
        $starters = Invoke-RestMethod -TimeoutSec 120 -Headers $auth 'http://127.0.0.1:8799/v1/cc/signup/starters'
        if (@($starters.starters).Count -ne 1) { throw 'The fresh install did not contain one signup starter.' }
        $starter = $starters.starters[0]
        if ($starter.id -ne 'instagram-phone-v1' -or @($starter.map.pages).Count -ne 12) { throw 'The installed Instagram starter schema is incomplete.' }
        $starterTable = Invoke-RestMethod -TimeoutSec 120 -Headers $auth "http://127.0.0.1:8799/v1/cc/tables/$($starter.tableId)"
        $starterRows = Invoke-RestMethod -TimeoutSec 120 -Headers $auth "http://127.0.0.1:8799/v1/cc/tables/$($starter.tableId)/rows"
        if (@($starterRows.rows).Count -ne 0) { throw 'A fresh starter must contain no personal account rows.' }
        foreach ($column in @('Mobile number', 'Date of birth', 'Full name', 'Username', 'Phone')) {
            if ($column -notin @($starterTable.properties.name)) { throw "Starter table is missing $column." }
        }
        if ('Password' -in @($starterTable.properties.name) -or 'Confirmation code' -in @($starterTable.properties.name)) { throw 'Secrets must not become starter columns.' }
        $starterGuide = Invoke-RestMethod -TimeoutSec 120 -Headers $auth "http://127.0.0.1:8799/v1/cc/pages/$($starter.pageId)"
        if (@($starterGuide.blocks).Count -lt 12) { throw 'The installed signup guide is incomplete.' }
        Write-Host 'The fresh Windows install contains the empty Instagram signup table, guide and packaged schema.'
        Install-PluginFile $pluginBuilds[0] $auth
        $plugin = Invoke-RestMethod -TimeoutSec 120 -Headers $auth 'http://127.0.0.1:8799/v1/plugins/run-logger'
        if ($plugin.state -ne 'running' -or $plugin.version -ne '0.1.0') { throw "The plugin didn't start: $($plugin | ConvertTo-Json -Compress)" }
        $checked = Invoke-RestMethod -TimeoutSec 120 -Method Post -Headers $auth 'http://127.0.0.1:8799/v1/ports/plugins/run-logger/check'
        if ($checked.plugin.status -ne 'active') { throw "The Port Hub's checks failed for the installed plugin: $($checked.plugin | ConvertTo-Json -Compress -Depth 4)" }
        Install-PluginFile $pluginBuilds[1] $auth
        $plugin = Invoke-RestMethod -TimeoutSec 120 -Headers $auth 'http://127.0.0.1:8799/v1/plugins/run-logger'
        if ($plugin.version -ne '0.1.1' -or $plugin.previous -ne '0.1.0' -or $plugin.state -ne 'running') { throw "The update didn't land: $($plugin | ConvertTo-Json -Compress)" }
        Wait-PluginJob (Invoke-RestMethod -TimeoutSec 120 -Method Post -Headers $auth 'http://127.0.0.1:8799/v1/plugins/run-logger/rollback') $auth | Out-Null
        $plugin = Invoke-RestMethod -TimeoutSec 120 -Headers $auth 'http://127.0.0.1:8799/v1/plugins/run-logger'
        if ($plugin.version -ne '0.1.0' -or $plugin.state -ne 'running') { throw "The rollback didn't land: $($plugin | ConvertTo-Json -Compress)" }
        # A one-file program runs as a starter plus its child, so one plugin shows as one or two processes, never more.
        $running = @(Get-Process run-logger -ErrorAction SilentlyContinue).Count
        if ($running -lt 1 -or $running -gt 2) { throw "One run-logger should run after update and rollback, found $running processes." }
        # The runtime dies: its Job Objects must take every plugin process with it. The runtime is a one-file program
        # too (a starter plus its child), so stop both runtime processes, and only them: no tree kill, or the plugins
        # would be stopped by us instead of by the Job Object.
        $runtimes = @(Get-Process CyclonePCRuntime -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$Scratch*" })
        if ($runtimes.Count -lt 1) { throw 'The runtime process was not found under the scratch profile.' }
        $runtimes | Stop-Process -Force
        $left = 1
        for ($i = 0; $i -lt 20 -and $left -ne 0; $i++) {
            Start-Sleep -Milliseconds 500
            $left = @(Get-Process run-logger -ErrorAction SilentlyContinue).Count
        }
        if ($left -ne 0) { throw 'A plugin process outlived the runtime.' }
        Write-Host 'A packaged plugin installed, passed the checks, updated, rolled back and died with the runtime.'
    } finally {
        Stop-Process -Id $runtime.Id -Force -ErrorAction SilentlyContinue
        Get-Process CyclonePCRuntime, run-logger -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$Scratch*" } | Stop-Process -Force -ErrorAction SilentlyContinue
        Get-Process adb -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$Scratch*" } | Stop-Process -Force -ErrorAction SilentlyContinue
    }
} finally {
    $env:LOCALAPPDATA = $savedLocal
    Remove-Item Env:CYCLONE_DEVICE_GATEWAY_TOKEN, Env:CYCLONE_DEVICE_GATEWAY_PORT, Env:CYCLONE_DEVICE_GATEWAY_RUNTIME, Env:CYCLONE_DESKTOP_PAIRING_BOOTSTRAP -ErrorAction SilentlyContinue
}

# ---- Prove the setup: silent install into a scratch profile, then uninstall keeping the owner's data ---------------
$SetupScratch = Join-Path $Work 'scratch-setup'
if (Test-Path $SetupScratch) { Remove-Item -Recurse -Force $SetupScratch }
New-Item -ItemType Directory -Force -Path $SetupScratch | Out-Null
$InstallDir = Join-Path $SetupScratch 'Cyclone One'
$UninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Cyclone'
$savedLocal = $env:LOCALAPPDATA
try {
    # install.ps1 (inside the setup) installs into %LOCALAPPDATA%\Cyclone One; /D keeps the setup on the same folder.
    $env:LOCALAPPDATA = $SetupScratch
    # /D must be last and unquoted, so the arguments are one string.
    $SetupLog = Join-Path $env:TEMP 'Cyclone-Setup.log'
    Remove-Item -Force $SetupLog -ErrorAction SilentlyContinue
    $run = Start-Process -FilePath $Setup -ArgumentList "/S /D=$InstallDir" -Wait -PassThru
    if ($run.ExitCode -ne 0) {
        if (Test-Path $SetupLog) { Write-Host "---- $SetupLog"; Get-Content $SetupLog | Write-Host } else { Write-Host "The setup wrote no $SetupLog." }
        throw "$SetupName /S failed ($($run.ExitCode))"
    }
    $Shim = Join-Path $InstallDir 'bin\cyclone.cmd'
    if (-not (Test-Path $Shim)) { throw "$SetupName did not create $Shim" }
    $reported = (& $Shim version 2>&1) -join "`n"
    if ($reported -notmatch [regex]::Escape("Cyclone $Version")) { throw "After $SetupName, cyclone version answered '$reported'" }
    $Uninstaller = Join-Path $InstallDir 'Uninstall Cyclone.exe'
    if (-not (Test-Path $Uninstaller)) { throw "$SetupName wrote no uninstaller" }
    # install.ps1 runs any uninstall.exe in the folder as the retired window's: the setup must never leave one.
    if (Test-Path (Join-Path $InstallDir 'uninstall.exe')) { throw 'The folder holds uninstall.exe; the next update would run it.' }
    $entry = Get-ItemProperty -Path $UninstallKey
    if ($entry.DisplayVersion -ne $Version) { throw "Apps & features shows '$($entry.DisplayVersion)', expected $Version" }
    Write-Host "$SetupName installed Cyclone $Version with its Apps & features entry."

    # The owner's data survives an uninstall.
    New-Item -ItemType Directory -Force -Path (Join-Path $InstallDir 'runtime') | Out-Null
    Set-Content -Path (Join-Path $InstallDir 'runtime\owner-data.txt') -Value 'kept'
    $gone = Start-Process -FilePath $Uninstaller -ArgumentList "/S _?=$InstallDir" -Wait -PassThru
    if ($gone.ExitCode -ne 0) { throw "The uninstaller failed ($($gone.ExitCode))" }
    if (Test-Path (Join-Path $InstallDir 'CyclonePCRuntime.exe')) { throw 'The uninstaller left CyclonePCRuntime.exe.' }
    if (Test-Path $Shim) { throw 'The uninstaller left the cyclone command.' }
    if (Test-Path $UninstallKey) { throw 'The uninstaller left the Apps & features entry.' }
    if (-not (Test-Path (Join-Path $InstallDir 'runtime\owner-data.txt'))) { throw "The uninstaller deleted the owner's data." }
    Write-Host 'The uninstaller removed Cyclone and kept the owner data.'
} finally {
    $env:LOCALAPPDATA = $savedLocal
    Remove-Item -Force (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Cyclone.lnk') -ErrorAction SilentlyContinue
}
