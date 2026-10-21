; Kill running One + legacy Companion + sidecar processes before overwrite/uninstall.
; The 3.6.0-beta installer failed with:
;   Error opening file for writing: ...\CycloneAgentMCP.exe
; because the previous Companion left that sidecar running.
; Current-user NSIS product is Cyclone One; stop that GUI so upgrades can overwrite.
; Doctor and MCP warn if a sibling Cyclone PC Companion 3.8.x directory remains;
; skip a MessageBox here so silent installs stay quiet.
; Every comment line must start with ';' — NSIS treats a bare word as a command.

!macro NSIS_HOOK_PREINSTALL
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM "Cyclone One.exe" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM "Cyclone PC Companion.exe" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM cyclone-pc-companion.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CycloneAgentMCP.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CyclonePCRuntime.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C powershell.exe -NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "%LOCALAPPDATA%\Cyclone One\mcp-tunnel\scripts\stop-tunnel.ps1" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CycloneLivePhone.exe >NUL 2>&1'
  Sleep 1500
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM "Cyclone One.exe" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM "Cyclone PC Companion.exe" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM cyclone-pc-companion.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CycloneAgentMCP.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CyclonePCRuntime.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C powershell.exe -NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "%LOCALAPPDATA%\Cyclone One\mcp-tunnel\scripts\stop-tunnel.ps1" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CycloneLivePhone.exe >NUL 2>&1'
  Sleep 1500
!macroend
