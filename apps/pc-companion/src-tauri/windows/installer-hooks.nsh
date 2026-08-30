; Kill running Companion + sidecar processes before overwrite/uninstall.
; The 3.6.0-beta installer failed with:
;   Error opening file for writing: ...\CycloneAgentMCP.exe
because the previous Companion left that sidecar running.

!macro NSIS_HOOK_PREINSTALL
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM "Cyclone PC Companion.exe" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM cyclone-pc-companion.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CycloneAgentMCP.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CyclonePCRuntime.exe >NUL 2>&1'
  Sleep 1500
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM "Cyclone PC Companion.exe" >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM cyclone-pc-companion.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CycloneAgentMCP.exe >NUL 2>&1'
  ClearErrors
  ExecWait 'cmd /C taskkill /F /T /IM CyclonePCRuntime.exe >NUL 2>&1'
  Sleep 1500
!macroend
