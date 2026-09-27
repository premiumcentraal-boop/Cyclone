; Cyclone for Windows setup (alpha.48): one double-click installs or updates Cyclone for this user, no admin.
;
; It wraps the tested install.ps1, so the rules stay in one place: check the package, stop Cyclone, keep the pairing,
; Remote MCP token and VMOS fleet, remove the retired Cyclone One window, install into %LOCALAPPDATA%\Cyclone One
; and add the `cyclone` command. On top it adds what a Windows app is expected to have: Start menu and desktop
; shortcuts, an entry in Apps & features with an uninstaller, and "Open Cyclone now" at the end.
;
; The uninstaller is "Uninstall Cyclone.exe", never "uninstall.exe": install.ps1 runs any uninstall.exe it finds in
; the folder as the retired Cyclone One window's uninstaller.
;
; Built and tested by scripts/pc/build-pc-package.ps1:
;   makensis /DVERSION=<product> /DFILEVERSION=a.b.c.d /DPAYLOAD=<dir> /DOUTFILE=<exe> cyclone-setup.nsi
; PAYLOAD holds Cyclone-PC.zip, Cyclone-PC.zip.sha256 (the hash only) and install.ps1.

Unicode true

!ifndef VERSION
  !error "Pass /DVERSION=<product version>"
!endif
!ifndef FILEVERSION
  !error "Pass /DFILEVERSION=a.b.c.d"
!endif
!ifndef PAYLOAD
  !error "Pass /DPAYLOAD=<folder with Cyclone-PC.zip, Cyclone-PC.zip.sha256 and install.ps1>"
!endif
!ifndef OUTFILE
  !define OUTFILE "Cyclone-Setup.exe"
!endif
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\Cyclone"
!define UNINSTALLER "Uninstall Cyclone.exe"

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"

Name "Cyclone"
OutFile "${OUTFILE}"
RequestExecutionLevel user
InstallDir "$LOCALAPPDATA\Cyclone One"
ShowInstDetails show
ShowUninstDetails show
BrandingText "Cyclone ${VERSION}"

VIProductVersion "${FILEVERSION}"
VIAddVersionKey "ProductName" "Cyclone"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "FileDescription" "Cyclone for Windows setup"
VIAddVersionKey "CompanyName" "Cyclone"
VIAddVersionKey "LegalCopyright" "Cyclone"

!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TITLE "Install Cyclone"
!define MUI_WELCOMEPAGE_TEXT "This installs Cyclone ${VERSION} for you: the Cyclone runtime, Glass and the cyclone command.$\r$\n$\r$\nAn older Cyclone or Cyclone One on this PC is replaced. Your phone pairing, Remote MCP token and VMOS fleet are kept.$\r$\n$\r$\nNo administrator rights are needed."
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_TITLE "Cyclone is installed"
!define MUI_FINISHPAGE_TEXT "Open Cyclone from the Start menu or the desktop, or type cyclone in a terminal. Glass opens in its own window.$\r$\n$\r$\nTo stop Cyclone, close its window. To update, type cyclone update."
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "Open Cyclone now"
!define MUI_FINISHPAGE_RUN_FUNCTION OpenCyclone
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Function OpenCyclone
  ExecShell "open" "$INSTDIR\bin\cyclone.cmd"
FunctionEnd

Section "Cyclone" SecMain
  ; $PLUGINSDIR exists only once a page or plugin has run; a silent install (/S) shows no page, so create it here.
  InitPluginsDir
  SetOutPath "$PLUGINSDIR"
  File "${PAYLOAD}\Cyclone-PC.zip"
  File "${PAYLOAD}\Cyclone-PC.zip.sha256"
  File "${PAYLOAD}\install.ps1"

  DetailPrint "Installing Cyclone ${VERSION}..."
  ; The setup is 32-bit: run the 64-bit PowerShell on a 64-bit PC, so install.ps1 sees every process's path.
  ${If} ${RunningX64}
    ${DisableX64FSRedirection}
  ${EndIf}
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\install.ps1" -Zip "$PLUGINSDIR\Cyclone-PC.zip" -NoStart'
  Pop $0
  ${If} ${RunningX64}
    ${EnableX64FSRedirection}
  ${EndIf}
  ${If} $0 != "0"
    MessageBox MB_ICONSTOP "Cyclone was not installed (install.ps1 reported $0). The details above say why." /SD IDOK
    SetErrorLevel 2
    Abort
  ${EndIf}
  ${IfNot} ${FileExists} "$INSTDIR\CyclonePCRuntime.exe"
    MessageBox MB_ICONSTOP "Cyclone was not installed: CyclonePCRuntime.exe is missing from $INSTDIR." /SD IDOK
    SetErrorLevel 2
    Abort
  ${EndIf}

  ; Shortcuts start the same `cyclone` command a terminal does (its window is Cyclone's on/off switch).
  SetOutPath "$INSTDIR"
  CreateShortcut "$SMPROGRAMS\Cyclone.lnk" "$INSTDIR\bin\cyclone.cmd" "" "$INSTDIR\CyclonePCRuntime.exe" 0
  CreateShortcut "$DESKTOP\Cyclone.lnk" "$INSTDIR\bin\cyclone.cmd" "" "$INSTDIR\CyclonePCRuntime.exe" 0

  WriteUninstaller "$INSTDIR\${UNINSTALLER}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayName" "Cyclone"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "Publisher" "Cyclone"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\CyclonePCRuntime.exe"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "UninstallString" '"$INSTDIR\${UNINSTALLER}"'
  WriteRegStr HKCU "${UNINSTALL_KEY}" "QuietUninstallString" '"$INSTDIR\${UNINSTALLER}" /S'
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoRepair" 1
SectionEnd

; Removes the program, the command and the shortcuts. The owner's data (pairing in runtime\, the Remote MCP token in
; mcp-tunnel\config, the VMOS fleet in chatgpt-attach\) stays in the folder, as the old window's uninstaller did.
Section "Uninstall"
  DetailPrint "Stopping Cyclone..."
  nsExec::ExecToLog '"$SYSDIR\taskkill.exe" /F /IM CyclonePCRuntime.exe'
  Pop $0
  nsExec::ExecToLog '"$SYSDIR\taskkill.exe" /F /IM CycloneAgentMCP.exe'
  Pop $0
  ${If} ${FileExists} "$INSTDIR\android-platform-tools\adb.exe"
    nsExec::ExecToLog '"$INSTDIR\android-platform-tools\adb.exe" kill-server'
    Pop $0
  ${EndIf}
  ${If} ${FileExists} "$INSTDIR\CyclonePCRuntime.exe"
    DetailPrint "Removing the cyclone command..."
    nsExec::ExecToLog '"$INSTDIR\CyclonePCRuntime.exe" install-cli --remove'
    Pop $0
  ${EndIf}

  Delete "$INSTDIR\CyclonePCRuntime.exe"
  Delete "$INSTDIR\CycloneAgentMCP.exe"
  Delete "$INSTDIR\install.ps1"
  Delete "$INSTDIR\THIRD_PARTY_NOTICES"
  RMDir /r "$INSTDIR\android-platform-tools"
  RMDir /r "$INSTDIR\live-phone"
  RMDir /r "$INSTDIR\bin"
  Delete "$SMPROGRAMS\Cyclone.lnk"
  Delete "$DESKTOP\Cyclone.lnk"
  DeleteRegKey HKCU "${UNINSTALL_KEY}"
  Delete "$INSTDIR\${UNINSTALLER}"
  ; Only removed when nothing of the owner's is left in it.
  RMDir "$INSTDIR"
SectionEnd
