# Cyclone V4 — Multi-stage Session OS build

Base line: **published mobile `v3.9.12`** (`c9ed77a`, versionCode 66) — Background Intelligence (one Shizuku workspace, Take control / Continue, GATE confirm, exact-session progress frames). PC companion in that tag is unchanged; Cyclone One **0.2.1** already has JPEG live + handoff on a separate branch and will be pulled in Stage 4.

## Product north star
**Cyclone Session OS**: reliable, low-token phone control that can scale toward many sessions.
- ClosePaw-shaped Fast Path (a11y JSON + smallest act + fingerprint settle)
- Ruto-shaped display-scoped sessions (build on 3.9.12 workspace, not replace)
- Sanna-shaped planner + tiny UI sub-agent + NL playbooks → **compile** to deterministic skills (Cyclone moat)
- Cyclone One as glass/MCP, not a second brain
- Never: 20 concurrent VLMs; yes: mostly compiled skills + few hot LLM agents

## Grok budget (week of 2026-09-05 → 12 Sep ~10:21 CEST)
- At plan start: ~**67%** weekly pool left (Build ~25% used). Target **≤12–15% Build per stage**.
- **Do not** attempt V4 in one headless run.
- After each stage: PR + CI green + short written handoff before next stage.

## Stages (strict order — do not skip)

### Stage 1 — Fast Path harness (THIS RUN)
Branch: `grok/cyclone-v4-s1-fastpath` from `v3.9.12`.
Goal: Make ordinary navigation feel like ClosePaw — mid-model capable.
Deliverables:
1. Primary observation = structured a11y / Page Card with stable `element_index` (vision escalate only when tree empty/custom canvas).
2. Control loop: perceive → **one** screen-changing act (or batched form fills) → settle 300ms → fingerprint → optional +500/+1000; Unchanged ≠ auto double-channel retry.
3. Planner/UI split surface for MCP: planner tools (open_app/intent/wait/finish) vs UI sub-agent tools (get_tree, click/type/swipe by index).
4. Kill extra LLM “verify” rounds for ordinary taps; local fingerprint is authority.
5. Intent/deep-link landing before icon hunting (keep 3.9.12 workspace routing).
6. Tests + `docs/V4_STAGE1_FASTPATH.md` + version identity toward **4.0.0-alpha.1** *module* flags (do not publish full v4 APK yet unless smoke green).
Acceptance: unit/integration tests for settle/fingerprint/nav-isolation; honest UNVERIFIED for physical; PR open.
Out of scope: multi-VD scale-out, skill compiler, One V4 packaging, Magisk.

### Stage 2 — Session Kernel
Base: Stage 1 merge. Harden 3.9.12 background workspace: displayId on all inject/launch, TRUSTED|OWN_DISPLAY_GROUP where applicable, N≥2 sessions design (may still gate product to 1 hot BG until stable), session_id in gateway/MCP.

### Stage 3 — Skill Compiler
NL playbook per package after runs; promote stable paths to deterministic PhoneToolExecutor routes; vision only on miss.

### Stage 4 — Cyclone One V4 glass
Merge One 0.2.1 JPEG/handoff; session.added/removed; tiles; MCP requires session_id; no second navigator on PC.

### Stage 5 — V4 release lane
`v4.0.0` / Cyclone One 1.0 pairing, CI tags, release notes, physical checklist.

## Coordination rule
Each Grok session reads `artifacts/V4_BUILD_PLAN.md` + previous stage PR. Parent orchestrator only starts next stage after PR exists.
