#Requires -Version 5.1
<#
.SYNOPSIS
  Cyclone Glass - Start / Stop / Update / Open (no command-prompt windows).
#>
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'GlassControl.ps1')

[System.Windows.Forms.Application]::EnableVisualStyles()
$form = New-Object System.Windows.Forms.Form
$form.Text = 'Cyclone Glass'
$form.Size = New-Object System.Drawing.Size(460, 260)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedSingle'
$form.MaximizeBox = $false
$form.Font = New-Object System.Drawing.Font('Segoe UI', 10)

$lbl = New-Object System.Windows.Forms.Label
$lbl.Location = New-Object System.Drawing.Point(16, 14)
$lbl.Size = New-Object System.Drawing.Size(410, 40)
$lbl.Text = 'Checking…'

$detail = New-Object System.Windows.Forms.Label
$detail.Location = New-Object System.Drawing.Point(16, 58)
$detail.Size = New-Object System.Drawing.Size(410, 36)
$detail.ForeColor = [System.Drawing.Color]::DimGray

$btnStart = New-Object System.Windows.Forms.Button
$btnStart.Text = 'Start'
$btnStart.Location = New-Object System.Drawing.Point(16, 110)
$btnStart.Size = New-Object System.Drawing.Size(95, 36)

$btnStop = New-Object System.Windows.Forms.Button
$btnStop.Text = 'Stop'
$btnStop.Location = New-Object System.Drawing.Point(120, 110)
$btnStop.Size = New-Object System.Drawing.Size(95, 36)

$btnUpdate = New-Object System.Windows.Forms.Button
$btnUpdate.Text = 'Update'
$btnUpdate.Location = New-Object System.Drawing.Point(224, 110)
$btnUpdate.Size = New-Object System.Drawing.Size(95, 36)

$btnOpen = New-Object System.Windows.Forms.Button
$btnOpen.Text = 'Open UI'
$btnOpen.Location = New-Object System.Drawing.Point(328, 110)
$btnOpen.Size = New-Object System.Drawing.Size(95, 36)

$chkRebuild = New-Object System.Windows.Forms.CheckBox
$chkRebuild.Text = 'Rebuild UI on update (recommended after Glass changes)'
$chkRebuild.Checked = $true
$chkRebuild.Location = New-Object System.Drawing.Point(16, 158)
$chkRebuild.AutoSize = $true

$lnk = New-Object System.Windows.Forms.LinkLabel
$lnk.Text = 'Logs'
$lnk.Location = New-Object System.Drawing.Point(16, 192)
$lnk.AutoSize = $true
$lnk.add_Click({ Start-Process explorer.exe (Get-GlassLogDir) })

function Refresh-Status {
  $ch = Get-UpdateChannel
  if (Test-GlassUp) {
    $lbl.Text = 'Cyclone Glass is running'
    $detail.Text = "http://127.0.0.1:8000  ·  channel $($ch.branch)"
    $detail.ForeColor = [System.Drawing.Color]::ForestGreen
    $btnStart.Enabled = $false
    $btnStop.Enabled = $true
    $btnOpen.Enabled = $true
  } else {
    $lbl.Text = 'Cyclone Glass is stopped'
    $detail.Text = "Update channel: $($ch.branch)  ·  data kept on update (.env, traces)"
    $detail.ForeColor = [System.Drawing.Color]::DimGray
    $btnStart.Enabled = $true
    $btnStop.Enabled = $false
    $btnOpen.Enabled = $false
  }
  $btnUpdate.Enabled = $true
}

$btnStart.add_Click({
  $btnStart.Enabled = $false
  $lbl.Text = 'Starting…'
  $form.Refresh()
  $r = Start-GlassHidden
  [System.Windows.Forms.MessageBox]::Show($r.Message, 'Cyclone Glass') | Out-Null
  Refresh-Status
})

$btnStop.add_Click({
  $btnStop.Enabled = $false
  $lbl.Text = 'Stopping…'
  $form.Refresh()
  $r = Stop-GlassHidden
  [System.Windows.Forms.MessageBox]::Show($r.Message, 'Cyclone Glass') | Out-Null
  Refresh-Status
})

$btnUpdate.add_Click({
  $confirm = [System.Windows.Forms.MessageBox]::Show(
    "Update Cyclone Glass from GitHub?`n`n• Replaces app files with the latest channel tip`n• Keeps .env, traces, and local DBs`n• Stops Glass during the update",
    'Cyclone Glass - Update',
    [System.Windows.Forms.MessageBoxButtons]::YesNo,
    [System.Windows.Forms.MessageBoxIcon]::Question)
  if ($confirm -ne [System.Windows.Forms.DialogResult]::Yes) { return }
  $btnUpdate.Enabled = $false
  $btnStart.Enabled = $false
  $btnStop.Enabled = $false
  $lbl.Text = 'Updating from GitHub (no console)…'
  $form.Refresh()
  $params = @{ Restart = $true }
  if ($chkRebuild.Checked) { $params.RebuildUi = $true }
  $r = Update-CycloneGlass @params
  [System.Windows.Forms.MessageBox]::Show($r.Message, 'Cyclone Glass') | Out-Null
  Refresh-Status
})

$btnOpen.add_Click({ Start-Process 'http://127.0.0.1:8000' })

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 4000
$timer.add_Tick({ Refresh-Status })
$timer.Start()

$form.Controls.AddRange(@($lbl, $detail, $btnStart, $btnStop, $btnUpdate, $btnOpen, $chkRebuild, $lnk))
Refresh-Status
[void]$form.ShowDialog()
