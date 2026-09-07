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

Worktree `Cyclone-mobile-4.1-s5-release`, branch `grok/mobile-4.1-s5-release`, base B4 tip **`f1e0239`** (`4.1.0-alpha.4` / 79) stacking B3 **`ee077b3`** / B2 **`0eaef0f`** / B1 **`178c820`** on **`v4.0.4`** (`38f7628`, versionCode 75).

Alphas 76–79 were not published 4.1 APKs. Release identity is **4.1.0** / versionCode **80**.

| Surface | This identity | Notes |
|---|---|---|
| Mobile | **4.1.0** / versionCode **80** | `publication_authorized=false`. Release-lane identity only; not a claim that GitHub tag `v4.1.0` exists. |
| Cyclone One / pc_companion | **1.0.0** (unchanged) | Full Layer 2 MCP needs One ≥ **1.1.0** (separate A5 cut). 4.0.4 phone + One 1.0.0 remains foreground-capable (USB / default-foreground). One 1.1 is not required for mobile-only Ask on device. |
| Device gateway | **4.0.0** (unchanged) | — |
| Agent MCP | **4.0.0** (unchanged) | — |
| python_version | **4.0.0** (unchanged) | — |

Installed Pixel may still be **4.0.3** / 74. Latest published mobile remains **4.0.4** / 75 until the operator cuts `v4.1.0`. Physical Pixel 8 = **UNVERIFIED**. `publication_authorized=false`. Do not claim the GitHub tag exists.

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

B3 (`4.1.0-alpha.3` / 78) adds glass/queue observability on that frozen contract (real Fast Path / skill / Layer 2 subtitle, exact-plane View progress). It does not invent a fourth plane. Hot-gate stays 1.

B4 (`4.1.0-alpha.4` / 79) closes named-VD Fast Path + skills (`session_id≠default-foreground`, `displayId>0`). Hot-gate stays 1. Pixel **UNVERIFIED**.

B5 drops alpha.4 / 79 → **4.1.0** / **80** release-lane identity. Pixel **UNVERIFIED**.
