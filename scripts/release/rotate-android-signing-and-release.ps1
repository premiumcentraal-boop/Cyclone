param(
    [string]$Repo = "premiumcentraal-boop/Cyclone",
    [string]$Environment = "mobile-release-approval",
    [string]$ReleaseBranch = "release/cyclone-mobile-v3.9.1",
    [string]$LegacyRef = "origin/release/cyclone-mobile-v3.9.0"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Require-Command([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) { throw "Required command '$Name' was not found in PATH." }
    return $command.Source
}

function Resolve-ApkSigner {
    $direct = Get-Command apksigner -ErrorAction SilentlyContinue
    if ($direct) { return $direct.Source }

    $sdkRoots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, "$env:LOCALAPPDATA\Android\Sdk") |
        Where-Object { $_ -and (Test-Path $_) } |
        Select-Object -Unique

    foreach ($sdk in $sdkRoots) {
        $buildTools = Join-Path $sdk "build-tools"
        if (-not (Test-Path $buildTools)) { continue }
        $candidate = Get-ChildItem $buildTools -Directory |
            Sort-Object Name -Descending |
            ForEach-Object {
                $bat = Join-Path $_.FullName "apksigner.bat"
                $exe = Join-Path $_.FullName "apksigner"
                if (Test-Path $bat) { $bat }
                elseif (Test-Path $exe) { $exe }
            } |
            Select-Object -First 1
        if ($candidate) { return $candidate }
    }
    throw "apksigner was not found. Install Android SDK Build Tools 35.0.0, then rerun this script."
}

function Extract-QuotedSetting([string]$Text, [string]$Name) {
    $match = [regex]::Match($Text, [regex]::Escape($Name) + '\s*=\s*"([^"]+)"')
    if (-not $match.Success) { throw "Could not recover legacy $Name from Git history." }
    return $match.Groups[1].Value
}

function New-RandomPassword {
    $bytes = New-Object byte[] 36
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return ([Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','A').Replace('/','B'))
}

$git = Require-Command "git"
$gh = Require-Command "gh"
$keytool = Require-Command "keytool"
$apksigner = Resolve-ApkSigner

Write-Host "Checking GitHub authentication..."
& $gh auth status
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI is not authenticated. Run: gh auth login" }

Write-Host "Fetching legacy signing source without checking it out..."
& $git fetch origin "release/cyclone-mobile-v3.9.0" --quiet
if ($LASTEXITCODE -ne 0) { throw "Could not fetch release/cyclone-mobile-v3.9.0." }

$legacyGradle = (& $git show "$LegacyRef`:apps/mobile/app/build.gradle.kts") -join "`n"
if ($LASTEXITCODE -ne 0 -or -not $legacyGradle) { throw "Could not read the legacy Android signing configuration." }
$legacyB64 = ((& $git show "$LegacyRef`:apps/mobile/release.keystore.b64") -join "").Trim()
if ($LASTEXITCODE -ne 0 -or -not $legacyB64) { throw "Could not read the legacy release keystore from Git history." }

$releaseBlock = [regex]::Match($legacyGradle, 'create\("ciRelease"\)\s*\{(?<body>.*?)\r?\n\s*\}', [System.Text.RegularExpressions.RegexOptions]::Singleline)
if (-not $releaseBlock.Success) { throw "Could not locate the legacy ciRelease signing block." }
$legacyBody = $releaseBlock.Groups['body'].Value
$oldStorePassword = Extract-QuotedSetting $legacyBody "storePassword"
$oldAlias = Extract-QuotedSetting $legacyBody "keyAlias"
$oldKeyPassword = Extract-QuotedSetting $legacyBody "keyPassword"

$secureDir = Join-Path $HOME ".cyclone-signing"
New-Item -ItemType Directory -Force -Path $secureDir | Out-Null
$oldKeystore = Join-Path $env:TEMP "cyclone-old-release.p12"
$newKeystore = Join-Path $secureDir "cyclone-android-rotated-2026.p12"
$lineage = Join-Path $secureDir "cyclone-signing-lineage.bin"

[IO.File]::WriteAllBytes($oldKeystore, [Convert]::FromBase64String($legacyB64))

if (Test-Path $newKeystore) { throw "Refusing to overwrite existing rotated key: $newKeystore" }
if (Test-Path $lineage) { throw "Refusing to overwrite existing signing lineage: $lineage" }

$newStorePassword = New-RandomPassword
$newKeyPassword = $newStorePassword
$newAlias = "cyclone-rotated-2026"

Write-Host "Generating a new private Android signing key..."
& $keytool -genkeypair -v `
    -keystore $newKeystore `
    -storetype PKCS12 `
    -storepass $newStorePassword `
    -keypass $newKeyPassword `
    -alias $newAlias `
    -keyalg RSA `
    -keysize 4096 `
    -validity 10000 `
    -dname "CN=Cyclone Mobile, OU=Release, O=Cyclone, C=NL"
if ($LASTEXITCODE -ne 0) { throw "keytool failed to generate the rotated signing key." }

$env:CYCLONE_OLD_STORE_PASSWORD = $oldStorePassword
$env:CYCLONE_OLD_KEY_PASSWORD = $oldKeyPassword
$env:CYCLONE_NEW_STORE_PASSWORD = $newStorePassword
$env:CYCLONE_NEW_KEY_PASSWORD = $newKeyPassword

try {
    Write-Host "Creating Android proof-of-rotation lineage..."
    & $apksigner rotate `
        --out $lineage `
        --old-signer `
        --ks $oldKeystore `
        --ks-key-alias $oldAlias `
        --ks-pass env:CYCLONE_OLD_STORE_PASSWORD `
        --key-pass env:CYCLONE_OLD_KEY_PASSWORD `
        --new-signer `
        --ks $newKeystore `
        --ks-key-alias $newAlias `
        --ks-pass env:CYCLONE_NEW_STORE_PASSWORD `
        --key-pass env:CYCLONE_NEW_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw "apksigner rotate failed." }

    & $apksigner lineage --in $lineage --print-certs -v
    if ($LASTEXITCODE -ne 0) { throw "Generated signing lineage did not validate." }

    $newKeystoreB64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($newKeystore))
    $lineageB64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($lineage))

    Write-Host "Writing rotated signing credentials to GitHub environment '$Environment'..."
    & $gh secret set CYCLONE_ANDROID_KEYSTORE_B64 --repo $Repo --env $Environment --body $newKeystoreB64
    if ($LASTEXITCODE -ne 0) { throw "Failed to set CYCLONE_ANDROID_KEYSTORE_B64." }
    & $gh secret set CYCLONE_ANDROID_STORE_PASSWORD --repo $Repo --env $Environment --body $newStorePassword
    if ($LASTEXITCODE -ne 0) { throw "Failed to set CYCLONE_ANDROID_STORE_PASSWORD." }
    & $gh secret set CYCLONE_ANDROID_KEY_ALIAS --repo $Repo --env $Environment --body $newAlias
    if ($LASTEXITCODE -ne 0) { throw "Failed to set CYCLONE_ANDROID_KEY_ALIAS." }
    & $gh secret set CYCLONE_ANDROID_KEY_PASSWORD --repo $Repo --env $Environment --body $newKeyPassword
    if ($LASTEXITCODE -ne 0) { throw "Failed to set CYCLONE_ANDROID_KEY_PASSWORD." }
    & $gh secret set CYCLONE_ANDROID_SIGNING_LINEAGE_B64 --repo $Repo --env $Environment --body $lineageB64
    if ($LASTEXITCODE -ne 0) { throw "Failed to set CYCLONE_ANDROID_SIGNING_LINEAGE_B64." }

    Write-Host "GitHub secrets configured. Finding the latest 3.9.1 publish run..."
    $runJson = & $gh run list --repo $Repo --workflow "mobile-publish-v391.yml" --branch $ReleaseBranch --limit 1 --json databaseId,status,conclusion
    if ($LASTEXITCODE -ne 0 -or -not $runJson) { throw "Could not locate the Cyclone Mobile Publish 3.9.1 run." }
    $run = $runJson | ConvertFrom-Json | Select-Object -First 1
    if (-not $run) { throw "No Cyclone Mobile Publish 3.9.1 run exists to rerun." }

    if ($run.status -eq "completed") {
        Write-Host "Rerunning publish workflow run $($run.databaseId) with the rotated signing key..."
        & $gh run rerun $run.databaseId --repo $Repo
        if ($LASTEXITCODE -ne 0) { throw "Could not rerun publish workflow $($run.databaseId)." }
    } else {
        Write-Host "Publish workflow run $($run.databaseId) is already $($run.status); the new environment secrets will be available to the next protected signing job."
    }

    Write-Host "Watching release workflow $($run.databaseId)..."
    & $gh run watch $run.databaseId --repo $Repo --exit-status
    if ($LASTEXITCODE -ne 0) { throw "Release workflow failed. Inspect run $($run.databaseId) in GitHub Actions." }

    Write-Host "Cyclone 3.9.1 release workflow completed successfully."
    Write-Host "IMPORTANT: Back up $newKeystore and $lineage somewhere offline and secure. Never commit the private keystore."
}
finally {
    Remove-Item $oldKeystore -Force -ErrorAction SilentlyContinue
    Remove-Item Env:CYCLONE_OLD_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:CYCLONE_OLD_KEY_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:CYCLONE_NEW_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:CYCLONE_NEW_KEY_PASSWORD -ErrorAction SilentlyContinue
}
