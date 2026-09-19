#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
$vbs = Join-Path $PSScriptRoot 'Launch Cyclone Glass Helper.vbs'
$desktop = [Environment]::GetFolderPath('Desktop')
$lnkPath = Join-Path $desktop 'Cyclone Glass.lnk'
$w = New-Object -ComObject WScript.Shell
$lnk = $w.CreateShortcut($lnkPath)
$lnk.TargetPath = $vbs
$lnk.WorkingDirectory = $PSScriptRoot
$lnk.Description = 'Cyclone Glass - Start / Stop / Update (no command prompts)'
$lnk.Save()
Write-Host "Desktop shortcut: $lnkPath"
