# Cyclone One 1.1 — Session Contract Glass (PC sprint)

**Status:** A1 DONE, A2 DONE, A3 DONE (code+docs; Pixel UNVERIFIED). A4 not started.  
**Codename:** Session Contract Glass  
**Base:** installed One **1.0.0** sources as of `v4.0.0`, rebased onto **`v4.0.4` mobile protocol** (tag `38f7628`) so gateway/MCP can speak Layer 2  
**Out:** Cyclone One **1.1.0** Setup.exe + device_gateway **4.1.0** + mcp **4.1.0**  
**Now:** One **1.1.0-alpha.3** + gateway/MCP **4.1.0-alpha.3** on this branch; mobile stays **4.0.4** / versionCode **75**  
**Grok:** each stage on Agent PC with **subagents required**

## Why this sprint exists

Mobile raced **4.0.1 → 4.0.4** while One/gateway/MCP stayed at **1.0.0 / 4.0.0**.
Installed MCP has no `phone_workspace`. Retest proved default-foreground browse works, but tooling auth and multi-plane glass are incomplete.

## North star

Any local tool can attach to One without scraping process env. Operators see and own the correct plane (foreground / VD session / Layer 2 workspace). PC never becomes a second brain.

## Stages (strict order)

### A0 — Line sync (optional housekeeping, can be first tiny PR)
- Fast-forward / merge `v4.0.4` release line into `main` (currently 17 commits ahead).
- Document component matrix: mobile 4.0.4 + One 1.0.0 gap.
- Out: clean base for A1. No One feature work.

### A1 — Tooling seam ✅ DONE
Branch: `grok/one-1.1-s1-tooling` from One/gateway tree at `v4.0.4` tip (or post-A0 main).  
Goal: stop env-scraping forever.
Deliverables (landed):
1. Persist or export PC gateway bearer (DPAPI / runtime file) with rotation; `sessionSecretPersisted=true` path for local tools.
2. Long-lived MCP + auto-inject `CYCLONE_DEVICE_GATEWAY_*` into One runtime and Cursor `mcp.json`.
3. Unify install path: prefer `Cyclone One`; detect/warn on legacy `Cyclone PC Companion` 3.8.x.
4. `doctor` green without process-memory scrape.
5. Docs `docs/ONE_1.1_STAGE1_TOOLING.md` + version identity toward **1.1.0-alpha.1** / gateway-mcp **4.1.0-alpha.1**.
Acceptance: cold MCP attach → `phone_status` without manual token; doctor READY on Agent PC (code path; Pixel UNVERIFIED — not a merge gate).  
Out of scope: Layer 2 UI, multi-tile media, mobile APK.  
Handoff: next work is **A2** on `grok/one-1.1-s2-layer2` from this A1 tip. See [`ONE_1.1_STAGE1_TOOLING.md`](ONE_1.1_STAGE1_TOOLING.md).

### A2 — Absorb mobile Layer 2 (4.0.3 protocol) ✅ DONE
Branch: `grok/one-1.1-s2-layer2` from A1.  
Goal: One/MCP speak `workspace.*` / `phone_workspace` as shipped on phone 4.0.3+.
Deliverables (landed):
1. Gateway routes for `workspace.list/register/switch/pause/release/arm/next` and GET/POST `/v1/devices/{id}/workspaces` protocol `cyclone.one.layer2.v1`.
2. MCP tool `phone_workspace` (+ schemas); after switch, mutating `phone_act.params` carry `workspaceId` + `workspaceGeneration`.
3. Glass: Layer 2 strip — registered workspaces, lock owner, pause/release, armed goal, generation; distinct from VD session tiles.
4. Fail closed on stale generation / wrong package (`TARGET_MISMATCH`) / pending GATE / named session mix.
5. Docs `docs/ONE_1.1_STAGE2_LAYER2.md` + version identity **1.1.0-alpha.2** / gateway-mcp **4.1.0-alpha.2**. A1 tooling seam preserved.
Acceptance: contract tests against mobile 4.0.4 fixtures; Pixel smoke UNVERIFIED unless run.  
Out of scope: lifting mobile hot-gate; Magisk; named VD tiles; operator pack; release cut; mobile APK.  
Handoff: next work is **A3** on `grok/one-1.1-s3-sessions` from this A2 tip. Do not implement A3 here. See [`ONE_1.1_STAGE2_LAYER2.md`](ONE_1.1_STAGE2_LAYER2.md).

### A3 — Session Kernel glass (named VD tiles) ✅ DONE
Branch: `grok/one-1.1-s3-sessions` from A2.  
Goal: named Shizuku sessions are first-class on One, distinct from Layer 2.
Deliverables (landed):
1. Real `session.added/removed` tiles with `session_id`, `displayId`, owner HUMAN/AI.
2. Per-session JPEG focus tile (no silent rewrite named → display 0).
3. Pause / Take control / Give to AI per tile; `PHONE_LOCKED` / `HUMAN_HAS_CONTROL` preserved.
4. Clear UI copy separating **Foreground** vs **Session Kernel VD** vs **Layer 2 workspace**. A2 Layer 2 strip stays distinct.
5. Docs `docs/ONE_1.1_STAGE3_SESSIONS.md` + version identity **1.1.0-alpha.3** / gateway-mcp **4.1.0-alpha.3**. A1 tooling seam + A2 `phone_workspace` / Layer 2 glass preserved. Tests as source: MCP observe/act with non-default `session_id` routes to the matching tile in UI wiring tests.
Acceptance: MCP observe/act with non-default `session_id` routes to matching tile in UI wiring tests; physical UNVERIFIED.  
Out of scope: A4 operator MCP pack; A5 release cut; mobile APK; Magisk; 20 concurrent VDs; local compile; claiming parallel input.  
Handoff: next work is **A4** on `grok/one-1.1-s4-operator` from this A3 tip. Do not implement A4 here. See [`ONE_1.1_STAGE3_SESSIONS.md`](ONE_1.1_STAGE3_SESSIONS.md).

### A4 — Operator MCP pack
Branch: `grok/one-1.1-s4-operator` from A3.  
Goal: make the retest suite boringly reliable.
Deliverables:
1. Schema clarity: `phone.open_app` → `params.package` required; improve error text.
2. Fix or remove dead HTTP `/v1/observe` (no empty 422).
3. `phone.home` already-on-home ≠ `VERIFICATION_FAILED` false negative.
4. Browse path does not require OpenRouter key.
5. Document `session_id=default-foreground` in One UI + doctor.
6. Tests + docs + alpha.4.
Acceptance: scripted suite status→observe→locate→home→open Chrome without OpenRouter; timings recorded.  
Out of scope: mobile skill compiler changes.

### A5 — One 1.1.0 release lane
Branch: `grok/one-1.1-s5-release` from A4.  
Goal: cut One **1.1.0** + gateway/MCP **4.1.0** paired for mobile ≥4.0.4.
Deliverables:
1. Drop alphas; `release/version.toml` components updated.
2. CI Setup.exe + release notes `docs/RELEASE_ONE_1.1.md`.
3. Pixel checklist (USB, bearer doctor, Layer 2 list, default-foreground Chrome open).
4. Publication path documented; physical marked honestly.
Acceptance: GitHub release assets; uninstall note for legacy Companion 3.8.1.

## Coordination

- Mobile sprint B can start B1 in parallel with A1.
- **A2 must land before** demos that need Layer 2 from PC/MCP.
- A3 should track Mobile B2 (dual-plane rules) — share contract doc `docs/SESSION_CONTRACT.md`.
