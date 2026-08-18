# Cyclone Mobile V2.2 — Guided Setup

V2.2 adds a first-install setup coach around the existing Cyclone Mobile V2.1 runtime.

## First install

Cyclone now opens a five-card introduction and setup flow:

1. **Welcome** — explains local deterministic automation, Hermes fallback, human takeover, and permission boundaries.
2. **Phone control** — direct path to Android Accessibility for Cyclone native control and the embedded Mobilerun enhanced engine.
3. **Event access** — notification listener, optional calendar permission, and optional overlay permission.
4. **Cyclone Core** — simplified Core address, pairing token, and device-name fields with one-tap save/connect. A bare host such as `192.168.1.10:8787` is normalized to the mobile WebSocket endpoint.
5. **Ready check** — shows what is ready and which optional capabilities remain unavailable.

The setup can be skipped. Skipping never grants permissions and never pretends a missing capability is available.

## Contextual reminders

After onboarding, a compact setup card appears while key capabilities are missing. A user can snooze it for the current app session.

When a feature explicitly depends on a missing connection, the runtime can request the relevant setup card again. V2.2 currently wires this into Hermes/Core-backed AI automation requests: attempting an AI-generated automation while Core is disconnected opens the Core pairing step rather than silently failing.

The reminder state is intentionally generic (`SetupNeed`) so phone control, calendar, notifications, overlay, or future capabilities can request their own setup step without coupling Android execution code to Compose UI.

## V2.2 version

- Android `minSdk`: 34 (Android 14)
- Android `targetSdk`: 35
- versionCode: 5
- versionName: `0.4.0-v2.2`

## Verification

CI verifies compilation and unit tests. Physical-device validation remains required for:

- returning from Android Settings into the live setup wizard
- native Accessibility activation
- embedded Mobilerun Accessibility activation
- notification listener activation
- runtime permission result refresh
- Core pairing on a real network
- contextual reminder after a failed AI/Core request
- screen-size behavior on small and large Android 14+ devices
