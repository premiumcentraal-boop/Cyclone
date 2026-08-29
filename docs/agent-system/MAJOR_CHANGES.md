# Major changes

## Cyclone V4 (direction, not yet a shipped APK)

V4 is the next infrastructure layer on 3.5.x / Infrastructure V3. It is not a rewrite.

- Product face: Gemini-style overlay (Analysis → Confirm → Working → Live view → Gate).
- Agent face: page card + after-state act + four-tool MCP + `tools/codex-phone-mcp/SKILL.md`.
- Memory face: verified paths compile into the existing AutomationStore as draft skills.
- Fleet face: teacher device writes skills; workers run `verified` only.
- Lab face: Pixel golden-page fixtures first; AVD optional; emulator must not promote Brain.

Steering docs: `V4_BUILD_BIBLE.md`, `V4_DIRECTIONS.md`, `V4_ROADMAP.md`.
Do not bump `versionName` to 4.0.0 until roadmap slices 1–4 are green on a physical Pixel.

## Cyclone 3.5

- Unified physical and virtual Android targets behind `DeviceBackend`.
- Added a Device Wall with search, source filters, durable groups, explicit selection,
  reconnect-aware cards and typed per-device batch results.
- Added a loopback-first Android Emulator provider with persistent lifecycle state and honest host
  capability reporting.
- Strengthened Teach compilation with evidence, selector repair and workflow quality gates.
- Added bounded agent reliability and explicit-target fleet, virtual-device and routine MCP tools.
- Preserved Android as the only phone mutation and semantic-verification authority.
- Completed a clean-room VMOS research ledger; incompatible GPL/noncommercial sources and
  proprietary binaries remain outside Cyclone production code.

Exact build, hardware/provider acceptance and publication evidence belongs in
`docs/cyclone-3.5/HARD_LAUNCH_REPORT.md`.
