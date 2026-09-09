# Cyclone One 1.1.3 — Live Phone for Mobile 4.2.4

Install Cyclone-PC-Companion-1.1.3-Setup.exe, paired with Cyclone Mobile 4.2.4 / versionCode 85. This Windows release does not replace or change the published Android APK.

Includes CycloneLivePhone.exe, CyclonePCRuntime.exe and CycloneAgentMCP.exe. Cloud AI through a PC connector can use bounded typed phone commands, structured observations and current decoded PNG/JPEG screenshots. The connector reads the returned screenshot_path; this is observation-based vision, not continuous video streaming.

Connections keeps ON PC AI, LIVE PHONE, REMOTE MCP and BACKGROUND PHONE separate. Native Codex continues through CycloneAgentMCP. Live Phone is restricted to physical USB/LAN devices, default-foreground, display 0. PhoneToolExecutor and GATE remain authoritative. No shell/ADB/custom endpoint/token interface is exposed.

Start Live Phone again after reopening One. Pause/Stop invalidates old observations; stopped sessions discard screenshots. Uncertain actions are never replayed. Runtime restart uses the same private authenticated Windows IPC without caller port/token changes. Swipe remains semantic scrolling; custom gesture-only surfaces may be unsupported.

This cut adds truthful PC-connector readiness, decoded image validation, typed-text response redaction, and failed-action exit status. Windows checks include native MCP regressions, named-pipe/DPAPI restart tests, frontend build, sidecar freezing and actual silent installation with all four installed executable files checked.

Physical-phone acceptance is deferred to the user for post-release feedback as explicitly requested. Chrome, Settings/SIM, Snapchat and actual-phone restart scenarios remain UNVERIFIED. No physical acceptance claims are made. Windows signing is optional; signing-state.txt reports the actual installer Authenticode state. If NotSigned, this is an unsigned development installer.

Use the Setup.exe for installation; the standalone sidecar EXEs are included for checksum/provenance completeness.
