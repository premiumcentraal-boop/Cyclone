# Cyclone version matrix — snapshot 2026-09-08 (Europe/Amsterdam)

## What is installed / published

| Surface | Installed (Pixel / Agent PC) | Latest published | Component IDs on latest mobile tag |
|---|---|---|---|
| Mobile APK | **4.0.3** / versionCode **74** (may still be on device) | **4.0.4** / versionCode **75** (`v4.0.4` @ `38f7628`) | mobile=4.0.4 |
| Cyclone One | **1.0.0** (`C:\Users\Agent\AppData\Local\Cyclone One`) | **1.0.0** (cut with `v4.0.0`) | pc_companion=1.0.0 |
| Device gateway | (bundled with One 1.0.0) | still declared **4.0.0** | device_gateway=4.0.0 |
| Agent MCP | One 1.0.0 `CycloneAgentMCP.exe` | still declared **4.0.0** | mcp=4.0.0 |
| Legacy Companion | **3.8.1** still installed beside One | obsolete for V4 | — |

## This worktree / sprint identity (not published)

Worktree `Cyclone-mobile-4.1-s2-contract`, branch `grok/mobile-4.1-s2-contract`, base B1 **`178c820`** (`4.1.0-alpha.1` / 76) on **`v4.0.4`** (`38f7628`, versionCode 75).

| Surface | This alpha | Notes |
|---|---|---|
| Mobile | **4.1.0-alpha.2** / versionCode **77** | `publication_authorized=false`. Not a published 4.1.0. B2 dual-plane contract. |
| Cyclone One / pc_companion | **1.0.0** (unchanged) | USB / default-foreground pairing still the 4.0.4 contract |
| Device gateway | **4.0.0** (unchanged) | — |
| Agent MCP | **4.0.0** (unchanged) | — |
| python_version | **4.0.0** (unchanged) | — |

Installed Pixel may still be **4.0.3** / 74. Latest published mobile remains **4.0.4** / 75. This alpha was not assembled or sideloaded in the B2 docs session. Physical Pixel 8 = **UNVERIFIED**.

## Repo divergence (critical)

- `main` tip was last noted as `44446e4` (**4.0.1** source). This session did not rewrite history and does not claim `main` was fast-forwarded.
- `v4.0.4` remains the sprint base (17 commits ahead of that `main` note, 0 behind at the time of the snapshot)
- Sprint bases must start from **`v4.0.4` tag** (or a `release/cyclone-mobile-v4.0.4` tip), **not** stale `main`
- First housekeeping PR (optional Stage 0): fast-forward / merge release line into `main` so Grok worktrees and CI agree — **not done here**

## Pixel retest evidence (One 1.0.0 ↔ phone 4.0.3)

Works today with `session_id=default-foreground` + live PC gateway bearer:
- USB / a11y / AI trust / semantic observe → locate → home → `open_app` (`params.package`) → Chrome
- MCP cold-start `serve` ~3s per process
- PC bearer is **in-memory** (must scrape `CyclonePCRuntime` env today)
- Raw NDJSON without token = AUTH_REQUIRED; HTTP `/v1/observe` = empty 422

## MCP gap vs mobile 4.0.3 notes

Installed One MCP tools include `phone_virtual_*` but **no `phone_workspace`**.
Mobile 4.0.3 release notes require PC gateway/MCP update for:
`workspace.list/register/switch/pause/release/arm/next` and MCP `phone_workspace`,
plus `workspaceId` + `workspaceGeneration` inside `phone_act.params` after switch.

**This is the primary One↔Mobile integration debt for the next sprints.**

## Three control planes (do not collapse)

1. **Human foreground** — `session_id=default-foreground`, `displayId=0`
2. **Session Kernel VD** (V4 Stage 2) — named `sessionId` + `displayId>0` (Shizuku isolated display); one hot Ask still product-gated
3. **Layer 2 workspaces** (mobile 4.0.3) — N registered display-0 app/profile workspaces; **time-sliced** global mutate lock; NOT parallel phone input; switch verifies package/user/generation

One glass must label these distinctly. Mobile must fail closed when a call mixes planes incorrectly.

B2 (`4.1.0-alpha.2` / 77) freezes those mix rules and requires `plane: {kind, label, sessionId, displayId, workspaceId, workspaceGeneration}` on responses so One glass does not guess. Product hot-gate for named VD Ask remains 1.
