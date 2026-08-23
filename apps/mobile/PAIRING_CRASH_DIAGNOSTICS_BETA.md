# Pairing crash diagnostics beta

This beta isolates desktop pairing from live screen streaming and from optional accessibility runtimes.

## Expected pairing sequence

1. Desktop requests the four-letter challenge over the USB-only localabstract Gateway.
2. The phone validates the code and creates/rotates the strong session credential.
3. Desktop performs two authenticated `bridge.status` health probes before marking the phone paired.
4. Fleet cards stay stream-free. Live screen capture starts only after the user explicitly opens a paired phone.

## Android crash evidence

Cyclone records a local process journal at:

`files/cyclone-diagnostics/process-crash-journal.log`

It records pairing lifecycle stages, guarded callback exceptions, uncaught Java exceptions, and historical Android process-exit reasons. The beta desktop runtime also captures a fixed read-only ADB snapshot if the phone disappears during pairing, including Android `exit-info`, the dedicated crash logcat buffer, accessibility state, and (for debuggable beta APKs) the app-private process journal.

## Windows crash evidence

Pairing failure snapshots are written beneath Cyclone PC Companion's app-local-data directory:

`runtime/diagnostics/pairing-<device-id>-<timestamp>.json`

Open **Settings & diagnostics → Pairing crash diagnostics → Open diagnostics folder** to locate them.

Pairing is not reported as successful unless the post-credential Gateway health probes pass.
