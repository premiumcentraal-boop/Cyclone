# Cyclone Vertical Build Implementation Plan

> **For Hermes:** Use the `subagent-driven-development` process for isolated review where an implementation task is separable. This is an active build plan; do not stop after this document.

**Goal:** Deliver and verify the smallest real vertical Cyclone path first, then extend it into persistent collaboration, memory, automation, host control, and a Windows desktop package.

**Architecture:** Tauri/React is the desktop surface. Cyclone Core is a FastAPI control plane that owns application records and policy; it integrates the official Hermes gateway over its authenticated private API and n8n through one authenticated Core ingress/egress layer. Postgres owns durable structured state; a Windows Obsidian vault supplies durable Markdown knowledge; Docker provides the agent computer; a native .NET Host Bridge supplies restricted Windows access.

**Tech Stack:** Python 3.11/FastAPI/psycopg/httpx; PostgreSQL 16; Redis 7; official `nousresearch/hermes-agent`; n8n; Docker Compose; React/TypeScript/Tauri; .NET 8 Host Bridge; pytest and Vitest.

---

## Gate 0: Proven evidence and environment

**Files:**
- Create: `docs/ARCHITECTURE.md`
- Create: `docs/STATUS.md`
- Verify: upstream official docs and local toolchain

**Acceptance:** Design decisions distinguish evidence, assumptions, and blockers. Docker Desktop, Node, Rust, and model/Telegram credential status are checked rather than assumed.

## Gate 1: Compose and Core health vertical slice

**Files:**
- Create: `docker/docker-compose.yml`
- Create: `apps/cyclone-core/Dockerfile`
- Create: `apps/cyclone-core/db/init.sql`
- Create: `apps/cyclone-core/app/*`
- Test: `apps/cyclone-core/tests/*`

**Steps:**
1. Write unit tests for settings, policy evaluation, event envelope, and Hermes request contracts.
2. Run target tests and observe expected failure before implementing missing functions.
3. Implement fail-fast settings, restricted CORS, database state, Core health, and authenticated internal event ingress.
4. Run unit tests, `docker compose config`, build the Core image, and start the stack.
5. Verify Core/Redis/Postgres/Hermes/n8n health with real command output.

**Acceptance:** localhost-only Core health endpoint reports each dependency honestly; state survives a compose restart.

## Gate 2: Real agent message and persistent conversation

**Files:**
- Create/Modify: `apps/cyclone-core/app/repository.py`, `hermes.py`, `main.py`
- Test: Core API tests using mocked Hermes plus live smoke test when configured

**Steps:**
1. Write a failing test for user message → Core task + Hermes run request.
2. Implement a single authenticated adapter to Hermes `/v1/runs` and monitor completion through `/v1/runs/{id}`.
3. Persist Core messages/tasks/status changes and broadcast Core SSE events.
4. Verify mock contract tests; run a real model request only if configured local credentials are present.

**Acceptance:** no fake output; an unconfigured model is explicitly blocked/degraded; configured real output is visibly recorded and attributable to Hermes.

## Gate 3: Agents, clusters, tasks, handoffs, review, approvals

**Files:**
- Create/Modify: Core contracts/repository/policy/tests
- Create: `packages/protocol/*`

**Steps:**
1. Write failing API tests for persistent agent roster, cluster membership, task transitions, handoff, and approval decisions.
2. Implement minimal schema-backed endpoints and normalized conversation events.
3. Integrate actual Hermes delegation lifecycle only from real Runs/SSE—not artificial animations.
4. Test task/approval state transitions and cancellation behavior.

**Acceptance:** group chat is an actual structured conversation; task activity derives from persisted events.

## Gate 4: Obsidian vault and retrieval

**Files:**
- Create: `scripts/bootstrap-vault.py`, `vault/templates/*`
- Create/Modify: Core memory service/tests

**Steps:**
1. Write a failing test for safe category/file-name handling, Markdown write, and keyword retrieval.
2. Bootstrap normal vault folders at the configured Windows location.
3. Implement controlled Markdown write and Postgres keyword metadata/index.
4. Test that a relevant note is returned, while raw chat is not auto-persisted.

**Acceptance:** a normal Obsidian vault contains human-readable entries and keyword retrieval is demonstrably live. Semantic retrieval remains deferred unless a real indexer executes.

## Gate 5: n8n routine

**Files:**
- Create: `services/n8n/workflows/*`
- Create/Modify: Core automation ingress/tests

**Steps:**
1. Write failing test for signed n8n event ingress and idempotency.
2. Implement Core handler that stores automation event and emits a conversation activity record.
3. Define a small n8n workflow that calls the one Core route.
4. Import/execute it against running n8n and verify the Core conversation event.

**Acceptance:** a real n8n execution—not a mocked curl—creates a Cyclone activity record.

## Gate 6: Windows Host Bridge

**Files:**
- Create: `apps/host-bridge/*`
- Test: `apps/host-bridge.Tests/*`

**Steps:**
1. Write failing tests for loopback binding/config validation, bearer auth, safe-root paths, command allowlist, audit logs, and approval header requirement.
2. Implement native .NET service with structured restricted tool endpoints.
3. Test safe read/process list and denied destructive command; invoke a Core approval decision before an approved test write.

**Acceptance:** no generic remote shell exists; every tool request has audit and policy evidence.

## Gate 7: Desktop and packaging

**Files:**
- Create: `apps/desktop/*`
- Create: `.github/workflows/*`

**Steps:**
1. Write UI/API client tests first for startup states, conversations, activity, and approval card behavior.
2. Implement original split-pane agent conversation UI over Core SSE.
3. Build React assets, then install/verify Rust only with operator approval if absent.
4. Build Tauri release, run it, create installer, and validate artifact paths.
5. Run CI-equivalent test/build commands, push only if Git credentials allow it.

**Acceptance:** actual `.exe`/installer paths exist and launch on Windows; otherwise status remains blocked with direct prerequisite evidence.

## Final acceptance gate

Run actual configured test flow:

```text
Administrator → Chief Hermes Agent → Research Agent → Developer Agent
→ Reviewer verification → Obsidian knowledge entry → n8n follow-up
→ Cyclone conversation result → Telegram notification
```

Each stage requires a saved artifact, DB/event record, API response, or transport delivery result. Never replace unavailable credentials or tools with simulated success.

## Known gate blockers at plan creation

- No Rust/Cargo in PATH: cannot build Tauri installer until operator approves installation.
- No model credentials assumed: real Hermes model execution cannot be asserted yet.
- No Telegram credentials assumed: no Telegram completion message can be sent yet.
- Current system operates in an empty starter repository: all product source and tests must be created.
