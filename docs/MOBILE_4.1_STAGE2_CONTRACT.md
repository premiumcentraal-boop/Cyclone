# Cyclone Mobile 4.1 Stage B2 — Dual-plane Session Contract

**Identity:** mobile `4.1.0-alpha.2` / versionCode `77`  
**Base:** B1 `4.1.0-alpha.1` (`178c820`, versionCode 76) on published `v4.0.4` (`38f7628`, versionCode 75)  
**Branch:** `grok/mobile-4.1-s2-contract`  
**Worktree:** `Cyclone-mobile-4.1-s2-contract`  
**Physical Pixel 8:** **UNVERIFIED** — orchestrator mandate: no local assemble, no gradle test, no adb smoke; the device may still be on **4.0.3**. CI green is not device evidence.

Stage B2 freezes the three-plane Session Contract shared with One A2/A3 and fail-closes illegal `session_id` / `display_id` / `workspaceId`+`generation` mixes. It does **not** start task glass, raise VD concurrency, add Magisk, rewrite Cyclone One, or publish 4.1.0.

## Why B2 exists

B1 made Phone control READY honest after force-stop. Dual-plane rules (Foreground vs Session Kernel VD vs Layer 2) were still a draft, so One glass could guess labels and callers could mix a named VD onto display 0 or attach Layer 2 ids to a VD session.

B2 exists so:

1. The contract is frozen, not draft.
2. Illegal mixes fail closed with stable error classes.
3. Gateway/MCP responses carry `plane` metadata so One can label without guessing.
4. Product hot-gate for named VD Ask stays **1** (1→2 skipped as scope creep).

## What shipped in source

Identity is `4.1.0-alpha.2` / versionCode **77** on the B1 `178c820` tree. Kotlin production SessionContract / gateway plane attach lives on this branch beside the JVM mismatch-matrix tests. This docs session did not assemble or run those tests.

1. **Frozen contract.** `docs/SESSION_CONTRACT.md` is canonical for Mobile B2 + One A2/A3: Foreground (`default-foreground` / display 0), Session Kernel VD (named `session_id`, `displayId>0`), Layer 2 (`workspaceId`+`workspaceGeneration`, display 0, time-sliced mutate lock). No fourth plane.

2. **Fail-closed classifier.** `SessionContract.classify` binds omitted Android identity to default-foreground. `requireUi` rejects missing `sessionId` with `SESSION_REQUIRED` (MCP observe/act; no silent default). Named missing/`0` display → `SESSION_DISPLAY_MISMATCH`. default-foreground + nonzero display → `SESSION_DISPLAY_MISMATCH`. workspace XOR generation → `WORKSPACE_GENERATION_REQUIRED`. Named VD + workspace ids, or Layer 2 ids + `displayId>0` → `PLANE_MISMATCH`. Layer 2 mutate still fail-closes on stale generation (`MUTATE_LOCK` / `WORKSPACE_GENERATION_STALE`).

3. **Plane metadata.** `SessionPlane.toJson()` / `SessionContract.attach` emit `kind`, `label`, `sessionId`, `displayId`, `workspaceId`, `workspaceGeneration` so One glass does not infer plane from a tile.

4. **Hot-gate stays 1.** `SessionKernel.PRODUCT_HOT_BACKGROUND_LIMIT` and `WorkspaceTasks.PRODUCT_HOT_BACKGROUND_LIMIT` remain 1. Isolation proofs stay the existing `V4FoundationTest` / `GatewaySessionBindingTest` / `WorkspaceDisplayPolicyTest`. Types/APIs already hold N≥2. Do not claim 20.

`pc_companion` stays **1.0.0**. `device_gateway`, `mcp`, and `python_version` stay **4.0.0**. `channel=development`. `publication_authorized=false` — this is an alpha line, not a published 4.1.0.

## Pixel checklist

Every row is **UNVERIFIED**. This session did not assemble, sideload, or adb-smoke a Pixel. The phone may still be on 4.0.3 / versionCode 74. Latest published remains 4.0.4 / 75.

| Check | Result |
| --- | --- |
| Install `4.1.0-alpha.2` / versionCode 77 over 4.1.0-alpha.1 / 4.0.4 (or 4.0.3) with the update-compatible signer | **UNVERIFIED** |
| MCP observe/act missing `session_id` returns `SESSION_REQUIRED`; Android omitted identity still pairs One 1.0.0 as default-foreground | **UNVERIFIED** |
| Named VD missing/`0` display and default-foreground + nonzero display fail closed (`SESSION_DISPLAY_MISMATCH`); no silent display-0 rewrite | **UNVERIFIED** |
| Layer 2 mutate without matching generation fail-closes; One glass does not draw Layer 2 as a VD tile | **UNVERIFIED** |
| Responses include `plane` `{kind, label, sessionId, displayId, …}` | **UNVERIFIED** |
| Named VD Ask hot-gate remains 1 | **UNVERIFIED** |

## Out of scope (B3+)

- B3 task glass + queue observability (do not implement here)
- B4 Fast Path / skills on a named VD
- Magisk / auto-root
- Raising the product hot-gate 1→2 (skipped as scope creep; isolation proofs already exist)
- Cyclone One installer rewrite
- `docs/RELEASE_4.1.md` (that is B5)

## Handoff to B3

B2 remains **DONE in source/CI** with Pixel **UNVERIFIED**. Do not start B3 in this worktree.

B3 (`grok/mobile-4.1-s3-glass`, identity `4.1.0-alpha.3` / versionCode 78) must finish Gemini-style task glass on top of 4.0.1 glass + 4.0.3 queue:

- Glass subtitle tracks real Fast Path / skill / Layer 2 slice step
- View progress opens the exact plane (VD session frames vs Layer 2 app)
- Ghost overlay / teardown regressions remain green
- Ask bar raise / non-blocking human Instagram scroll preserved

Do not implement those here.

## Tests

JVM (source; CI owns execution):

- `SessionContractTest` — mismatch matrix: empty/explicit foreground, `requireUi` `SESSION_REQUIRED`, named missing/`0` display, named display 7 VD, foreground+nonzero display, workspace XOR generation, named+workspace `PLANE_MISMATCH`, Layer 2 classify, attach metadata, `session_id` alias copy + conflicting aliases, hot-gate == 1
- `GatewayPlaneMetadataTest` — One-glass fields on `SessionPlane.toJson()` / `SessionContract.attach` (JVM-pure; does not call Android `GatewaySessionAdapter.sessionJson`)
- Isolation proofs (existing, unchanged): `V4FoundationTest`, `GatewaySessionBindingTest`, `WorkspaceDisplayPolicyTest`

Python (source; CI owns execution):

- `tools/codex-phone-mcp/tests/test_session_contract.py` — MCP mismatch matrix + plane attach (`SESSION_REQUIRED`, `SESSION_DISPLAY_MISMATCH`, `PLANE_MISMATCH`, `WORKSPACE_GENERATION_REQUIRED`, Layer 2, `phone_status.planes`)
- `apps/device-gateway/tests/test_session_plane.py` — gateway classify/parse mix rejects; omitted identity still None

**Not executed in this agent session** — no gradle, no pytest, no assemble, no adb. When CI later runs, treat green as source/CI evidence only. It does not fill the Pixel table.
