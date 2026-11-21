# Cyclone Alpha 131 — bootstrap cold-start poll budget

Android version code: **285**, based on Alpha 130.

The preparation flow now waits up to approximately 30 seconds (150 polling attempts at 200 ms intervals, plus read time) for the receiving Cyclone process to write its bootstrap public key. This call previously retained the six-second default despite force-stopping and cold-starting the receiver immediately beforehand. A slow cold start could therefore fail before the key was available.

Only this poll budget changes. The bootstrap sequence, acknowledgement repair, wire contract, bindings and permissions are unchanged. A regression guard pins the cold-start public-key wait to the established budget. Existing acknowledgement tests continue to cover atomic write/read, timeout, correlation and retry behavior.

Physical-device verification remains **UNVERIFIED** for this artifact. No device operations were performed in this run. Update normally while retaining app data, then retry profile switching; no reboot or data reset is required by this fix. No platform verdict is claimed.

Gateway/MCP and Glass versions are unchanged. Standard release CI builds and signs the exact tested phone commit and publishes the normal combined developer prerelease.
