# Cyclone Mobile 4.1 Stage B1 — Sticky control plane

**Identity:** mobile `4.1.0-alpha.1` / versionCode `76`  
**Base:** published `v4.0.4` (`38f7628`, versionCode 75)  
**Branch:** `grok/mobile-4.1-s1-sticky`  
**Physical Pixel 8:** **UNVERIFIED** — orchestrator mandate: no local assemble, no adb smoke; the device may still be on **4.0.3**. CI green is not device evidence.

Stage B1 stops permissions/connect from being a footgun and records the 4.0.4 setup claims as source/CI, not Pixel proof. It does **not** freeze dual-plane Session Contract rules, raise VD concurrency, add Magisk, rewrite Cyclone One, or publish 4.1.0.

## Why B1 exists

4.0.1–4.0.4 already claimed in-app Shizuku install, Back-always on helper failure/cancel, and managed-profile create (`pm create-user --managed`) without Shelter shopping. Physical Pixel acceptance for those claims is still **UNVERIFIED**. Force-stop also left Phone control easy to misread as Ready when the accessibility service was no longer bound.

B1 exists so:

1. Phone control READY is honest after process death.
2. PC Gateway / AI trust show one doctor-aligned next action when not Ready.
3. 4.0.4 helper/profile harden stays the source of truth for this alpha identity.

## What shipped in source

Identity is `4.1.0-alpha.1` / versionCode **76** on the `v4.0.4` tree. Kotlin production code for sticky Accessibility, Gateway next-actions, and Shizuku/profile regression guards is in this branch.

1. **Sticky Accessibility.** `PhoneControlReadiness` READY = Android Accessibility setting enabled **and** the Cyclone service bound. Setting on + service dead after force-stop → **Repair** (not Ready). Setting off → **Enable**, even if a stale bound flag remains. One tap opens Android Accessibility settings. Settings, setup wizard, background readiness, and PC Gateway status share that snapshot.

2. **PC Gateway + AI trust.** `GatewayReadyDoctor` emits one next action (`TURN_ON_GATEWAY` → `FIX_USB_BRIDGE` → `REPAIR_PHONE_CONTROL` / `ENABLE_PHONE_CONTROL` → `CONFIRM_AI_TRUST` → `PAIR_AI_TRUST` → `WAIT_FOR_PC`). The control center and AI card show that action when not Ready. Transport-on is not Ready.

3. **4.0.4 harden.** `OfficialHelperInstallPolicy` pins GitHub Shizuku **13.6.0** (SHA-256 + package `moe.shizuku.privileged.api`). Download refuses Play Store / `market://`. Installer Back stays outside `when (phase)` plus `BackHandler`. Profile create stays `create-user --managed`; JVM + Python CI guards forbid Play URLs and Shelter/Island shopping copy.

`pc_companion` stays **1.0.0**. `device_gateway`, `mcp`, and `python_version` stay **4.0.0**. `channel=development`. `publication_authorized=false` — this is an alpha line, not a published 4.1.0.

## Pixel checklist

Every row is **UNVERIFIED**. This session did not assemble, sideload, or adb-smoke a Pixel. The phone may still be on 4.0.3 / versionCode 74. Latest published remains 4.0.4 / 75.

| Check | Result |
| --- | --- |
| Install `4.1.0-alpha.1` / versionCode 76 over 4.0.4 (or 4.0.3) with the update-compatible signer | **UNVERIFIED** |
| Force-stop Cyclone → reopen → Phone control is **not** false-Ready → one-tap Enable/Repair → READY matches system Accessibility | **UNVERIFIED** |
| PC Gateway off / a11y off / no AI trust each shows a clear next action | **UNVERIFIED** |
| Background tasks install helper without Play Store; cancel/fail still has Back | **UNVERIFIED** |
| Profile create path (if rooted) or honest skip if not rooted | **UNVERIFIED** |
| One **1.0.0** still pairs USB / default-foreground | **UNVERIFIED** |

## Out of scope (B2+)

- Dual-plane fail-closed (`session_id` / `display_id` / `workspaceId`+`generation`)
- Layer 2 MCP on One (`phone_workspace` and related)
- Raising Session Kernel VD concurrency
- Magisk / auto-root
- Cyclone One installer rewrite
- `docs/RELEASE_4.1.md` (that is B5)
- Editing `docs/SESSION_CONTRACT.md` rules (that is B2)

## Handoff to B2

B1 remains **DONE in source/CI** with Pixel **UNVERIFIED**. Do not start B2 in this worktree.

B2 (`grok/mobile-4.1-s2-contract`, identity `4.1.0-alpha.2` / versionCode 77) must freeze `docs/SESSION_CONTRACT.md` as shared input with One A2/A3:

- Foreground vs Session Kernel VD vs Layer 2
- Fail closed on mixed `session_id` / `display_id` / `workspaceId`+`generation`
- MCP/gateway plane metadata for One glass

Do not invent a fourth plane. Do not implement those rules here.

## Tests

JVM (source; CI owns execution):
- `PhoneControlReadinessTest` — READY / ENABLE / REPAIR matrix, including stale bound flag
- `GatewayReadyNextActionTest` — doctor priority, including AI trust vs wait-for-PC
- `OfficialHelperInstallPolicyTest` — pinned GitHub 13.6.0, forbidden Play/market URLs, Back always available
- `ProfileSetupPlanTest` — managed profile create, install-existing without accounts, allowlisted shell

Python CI:
- `test_mobile_permission_architecture.py` — Back outside `when (phase)`, no Play Store in helper/profile Kotlin, Shelter/Island copy absent

**Not executed in this agent session** — no gradle, no pytest, no assemble. When CI later runs, treat green as source/CI evidence only. It does not fill the Pixel table.
