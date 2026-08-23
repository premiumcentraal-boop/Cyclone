# Cyclone Mobile 3.1.0-beta.5 + PC Companion 1.0.0-beta.6

This paired beta targets the remaining physical-device pairing crash and Windows console-window UX.

## Pairing crash isolation

- `pair.begin` is protocol-only on Android: no Toast, capture, Accessibility initialization, or other UI transition is started by the ADB socket worker.
- Gateway dispatch records unexpected non-fatal worker failures instead of allowing them to escape the request boundary.
- The existing post-pair authenticated health probes remain required before Desktop marks a phone paired.

## Live USB diagnostics

PC Companion starts a bounded read-only Android diagnostic monitor as soon as an authorized USB phone is detected, before pairing begins.

The monitor records:
- Cyclone process PID transitions;
- warning/error logcat scoped to the Cyclone PID;
- Android `ApplicationExitInfo` / crash-buffer evidence through the fixed crash collector;
- Cyclone's beta process journal when `run-as` is available;
- accessibility state, package state, and bounded memory/process snapshots;
- explicit PC pairing-stage markers before and after the four-letter challenge.

The monitor does not require root or `su` and does not expose arbitrary ADB/shell commands. Pairing codes, strong session credentials, clipboard contents, passwords, OTPs, and typed values are intentionally excluded.

## Windows application packaging

- Tauri release builds use the Windows GUI subsystem.
- `CyclonePCRuntime` is packaged without a console.
- `CycloneAgentMCP` is packaged without a console.

The installed Companion should therefore behave as one GUI application without separate command-prompt windows for its bundled processes.
