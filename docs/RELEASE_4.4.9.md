# Cyclone Mobile 4.4.9 — chat drawer redesign

Cyclone Mobile 4.4.9 is the released Ask Cyclone conversation-surface update.
It does not change phone mutation authority, GATE policy, capture security, or the execution harness.

## What changed

- In-app Ask Cyclone now uses a shared rounded chat drawer with a visible grab handle.
- Dragging the handle down minimizes the drawer into a compact **Ask Cyclone** pill.
- Tapping or dragging the pill upward reopens the same conversation/current run.
- The accessibility overlay uses the same drawer interaction instead of an invisible drag zone.
- Active background work also rests as the same compact run pill while remaining outside Cyclone's
  vision/capture path.
- The plus surface uses progressively disclosed Camera, Files, Screen, Explain screen,
  Model & intelligence, and Create routine actions.
- The AI empty state is reduced to **Ready when you are** with three small starter prompts.
- Assistant replies render as calm inline text; user requests remain compact right-aligned bubbles.
- In-chat task cards have zero drop shadow, quiet state tinting, checkpoints and a compact progress
  indicator for working, action-needed and completed states.

## Preserved runtime boundaries

- `PhoneToolExecutor` remains the canonical phone mutation engine.
- The overlay remains `TYPE_ACCESSIBILITY_OVERLAY` and retains `FLAG_SECURE`.
- Minimizing presentation does not release or restart task ownership.
- GATE confirmation behavior is unchanged.
- External picker/screen-share handoff still suppresses overlay interaction while Android owns focus.

## Version identity

- Android version: `4.4.9`
- Android versionCode: `109`
- Previous mobile version: `4.4.8`
- Publication authorization: **true**
- Physical Pixel 8 acceptance: **UNVERIFIED**

## Validation

The release workflow passed the repository Mobile build gate before signing and publication, including
unit tests, lint, release assembly, metadata/product guards, gateway/MCP contracts, and provenance
packaging. The signed APK was verified against the 4.4.8 update-compatible signer.

Physical-device Pixel acceptance remains **UNVERIFIED**. Follow-up acceptance should cover drawer
drag/snap behavior, keyboard interaction, Instagram task progress, overlay host-app scrolling,
lock-screen suppression, and capture invisibility.
