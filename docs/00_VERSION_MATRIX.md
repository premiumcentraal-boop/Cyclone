# Cyclone version matrix — snapshot 2026-09-07 (Europe/Amsterdam)

## What is installed / published

| Surface | Installed (Pixel / Agent PC) | Latest published | This branch (`grok/one-1.1-s1-tooling`) |
|---|---|---|---|
| Mobile APK | **4.0.3** / versionCode **74** (Pixel may lag) | **4.0.4** / versionCode **75** (`v4.0.4` @ `38f7628`) | still **4.0.4** / **75** — do not bump |
| Cyclone One | **1.0.0** (`C:\Users\Agent\AppData\Local\Cyclone One`) | **1.0.0** (cut with `v4.0.0`) | **1.1.0-alpha.1** (not a GitHub release) |
| Device gateway | (bundled with One 1.0.0) | published **4.0.0** | **4.1.0-alpha.1** |
| Agent MCP | One 1.0.0 `CycloneAgentMCP.exe` | published **4.0.0** | **4.1.0-alpha.1** |
| Legacy Companion | **3.8.1** still installed beside One | obsolete for V4 | warn + prefer Cyclone One; uninstall legacy |

Authoritative IDs: `release/version.toml` — `product_version=4.0.4`, `android_version_code=75`, `python_version=4.1.0-alpha.1`, `components.mobile=4.0.4`, `pc_companion=1.1.0-alpha.1`, `device_gateway=4.1.0-alpha.1`, `mcp=4.1.0-alpha.1`, `channel=development`. Physical Pixel stays **UNVERIFIED**.

## Repo divergence (critical)

- `main` tip is **behind `v4.0.4`** (release line ~17 commits ahead of stale `main` / 4.0.1 source)
- PRs for this sprint may target the **`v4.0.4` / `release/cyclone-mobile-v4.0.4` line**, not stale `main`
- Sprint bases must start from **`v4.0.4` tag** (or a `release/cyclone-mobile-v4.0.4` tip), **not** stale `main`
- First housekeeping PR (optional Stage 0): fast-forward / merge release line into `main` so Grok worktrees and CI agree

## Pixel retest evidence (One 1.0.0 ↔ phone 4.0.3)

Works today with `session_id=default-foreground` + live PC gateway bearer:
- USB / a11y / AI trust / semantic observe → locate → home → `open_app` (`params.package`) → Chrome
- MCP cold-start `serve` ~3s per process
- PC bearer **no longer requires env scrape** (A1 code path: DPAPI / 0600 runtime file + token-free locator; Pixel **UNVERIFIED**)
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
