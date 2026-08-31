# Cyclone

Cyclone is a **phone skill OS**: observe an Android screen, act with verification, learn a durable skill, and replay it without the model rediscovering the UI.

> Current shipped product: **Cyclone 3.6.0** (`versionCode` 43).  
> Next infrastructure layer: **V4** (overlay + page card + act envelope + skill compile). Do not call an APK `4.0.0` until Pixel slices 1–4 are green.

📱 **Install the phone app:** [`MOBILE_DOWNLOADS.md`](MOBILE_DOWNLOADS.md)  
🤖 **Agents start here:** [`AGENTS.md`](AGENTS.md) → [`docs/agent-system/README.md`](docs/agent-system/README.md)  
📍 **Working git line:** [`docs/WORKING_LINE.md`](docs/WORKING_LINE.md)

## What the product is

A user asks for a phone goal or teaches a routine. Cyclone should:

1. **Observe** a compact page card (`pageText`, `pageSummary`, goal-ranked controls).
2. **Act** through one engine: `PhoneToolExecutor`.
3. **Verify** after-state. Transport success is not action success.
4. **Learn** two-or-more verified steps into a draft skill in `AutomationStore`.
5. **Replay** a `verified` skill with the model quiet.

Policy stays on the phone. Pay / send / delete / grant require GATE. PC AIs talk through four MCP tools, not a generic shell.

## Current stack (3.6)

| Piece | Path | Role |
|---|---|---|
| Cyclone Mobile | `apps/mobile` | Android app `com.cyclone.mobile`. Surfaces: Home, Teach, AI, Automations, Brain, Settings. |
| PC Device Gateway | `apps/device-gateway` | Loopback HTTP + ADB forward to the phone localabstract gateway. |
| PC Companion | `apps/pc-companion` | Tauri Windows companion + live view. |
| Codex / any-PC MCP | `tools/codex-phone-mcp` | Constrained STDIO tools. Official loop: `tools/codex-phone-mcp/SKILL.md`. |
| Teamwork Sniper | `apps/teamwork-sniper` | Separate Picnic app. Not the Cyclone Mobile package. |

Desktop / Core / Hermes / n8n / Host Bridge still live under `apps/cyclone-core`, `apps/desktop`, `services/`, `docker/`. They are a **legacy control-plane**, not the path for phone learning. Do not add phone autonomy dependencies on them.

## Agent source of truth

When documents disagree:

1. Current executable code and tests.
2. Release evidence (`release/version.toml`, GitHub Release assets, `releases/<version>/BUILD_VERIFIED.json`).
3. `AGENTS.md`, `docs/agent-system/CURRENT_STATE.md`, `docs/agent-system/project.yaml`.
4. V4 steering: `docs/agent-system/V4_BUILD_BIBLE.md`.
5. Historical version folders and stubs — context only.

Root files named `STATUS.md` / `HANDOFF.md` / `CYCLONE_V2_*` are archived pointers. They are not current authority.

## Repository layout

```text
apps/mobile/              Android phone autonomy app
apps/device-gateway/      PC Device Gateway (FastAPI)
apps/pc-companion/        Windows companion
apps/teamwork-sniper/     Separate Picnic APK
tools/codex-phone-mcp/    Constrained MCP for PC AIs
docs/agent-system/        Canonical agent knowledge
docs/design/mobile-v32/   Current mobile UX + overlay handoff
docs/release-notes/       Per-version notes
release/version.toml      Product version + publication flags
```

Legacy desktop layout (`apps/cyclone-core`, `apps/desktop`, `docker/`, `vault/`) remains in-tree for that product line.

## Install / run (phone path)

1. Install `Cyclone-3.6.0.apk` from the [v3.6.0 release](https://github.com/premiumcentraal-boop/Cyclone/releases/tag/v3.6.0). Uninstall any previous `com.cyclone.mobile` first.
2. Enable Accessibility for Cyclone. Pairing and gateway stay off until you turn them on.
3. On Windows, install `Cyclone-PC-Companion-3.6.0-Setup.exe` from the same release. Quit a running Companion before upgrading (it locks `CycloneAgentMCP.exe`).
4. Point Codex or another PC AI at `tools/codex-phone-mcp` and follow `SKILL.md`.

## Invariants agents must not break

- One package: `com.cyclone.mobile`. One launcher: `.MainActivity`.
- One mutation engine: `PhoneToolExecutor`.
- Semantic first, vision last. Re-observe after page-changing acts.
- No generic `adb shell` / root / PowerShell tools for the model.
- No secrets in Brain or learning stores.
- CI green is not physical VERIFIED.
- Do not advertise UI version `4.0.0` until V4 slices 1–4 are green on a Pixel.

## License

Proprietary. Third-party components remain under their own licenses. See `docs/OPEN_SOURCE_COMPONENTS.md`.
