# Cyclone 3.5 frozen baseline

Frozen on 2026-08-27 (Europe/Berlin) before the three Cyclone 3.5 workstreams started.

## Source identity

- Base branch: `origin/integration/cyclone-v3.3-gateway`
- Exact base SHA: `e0149ab0638c77fa3d99d9d383f1d912fcbca25e`
- Latest verified release ancestor: `977ed4673400293c00b5d465c6729a7bbf5ce954`
- Release/tag: `v3.3.0-beta.2`
- Release URL: https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v3.3.0-beta.2

The frozen base is one documentation/agent-guidance commit above the verified Beta 2 release. The release commit is an ancestor of the base, and the intervening diff changes only agent guidance, templates, and agent-context tests/scripts.

## Versions at the frozen SHA

- Mobile `versionName`: `3.3.0-beta.2`
- Mobile `versionCode`: `35`
- PC Companion package/Tauri/Cargo: `3.3.0-beta.2`
- Device Gateway: `3.3.0b2`
- Codex Phone MCP: `3.3.0b2`
- Cyclone Agent MCP: `1.0.0b2`
- Canonical release metadata: product `3.3.0-beta.2`, Python `3.3.0b2`

## Verified release evidence

- GitHub Actions run: `33089372883` (`Cyclone 3.3 Combined Release CI`, successful)
- Verified source SHA: `977ed4673400293c00b5d465c6729a7bbf5ce954`
- Published assets include the matching Mobile APK, PC Companion installer, provenance, source SHA, checksums, and third-party notices.
- Published APK SHA-256: `d26c35d1502f18e69fd9c2998cb4a9d4acd381c6e11ae803229dd2c40edd93f2`
- Published PC installer SHA-256: `46abe19fdcef3c062c50d6a785374ad5d25fe623dfed0f75d34fe186f499fd04`

## Physical-device evidence at baseline

- Canonical release metadata records `physical_pixel8 = "MEDIA_PASS_PC_WEBSOCKET_HOTFIX"`.
- The release notes state that gateway, desktop, packaging, Android assembly, and real local WebSocket readiness passed.
- A second Android device was `NOT_RUN`.
- This baseline does not claim that every physical acceptance item in the Cyclone 3.5 mission was verified; each Cyclone 3.5 physical gate must be re-run or marked `UNVERIFIED`.

## Agent branches and worktrees

- Agent 1: `research/v35-vmos-archaeology` — `C:\Users\Agent\Cyclone-v35-agent1`
- Agent 2: `feature/v35-fleet-virtualization` — `C:\Users\Agent\Cyclone-v35-agent2`
- Agent 3: `feature/v35-ai-integration-release` — `C:\Users\Agent\Cyclone-v35-agent3`
- Integration: `integration/cyclone-3.5-hard-launch` — `C:\Users\Agent\Cyclone-v35-coordinator`

All four branches were created directly from the exact frozen base SHA. The existing dirty checkout at `C:\Users\Agent\Cyclone` is intentionally outside the Cyclone 3.5 implementation work and must not be modified or cleaned by these agents.

## Source-of-truth note

The mission refers to `docs/project-brain/**`, but those paths do not exist at the frozen baseline. The current canonical replacement is `docs/agent-system/**`, as directed by the root `AGENTS.md`; agents must use `CURRENT_STATE.md`, `ARCHITECTURE_AND_CONTRACTS.md`, `DECISIONS.md`, the relevant ownership documents, and executable code/tests.
