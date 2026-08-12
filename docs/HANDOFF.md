# CYCLONE — Where We Left Off (Handoff for the Next AI)

> **Read this first.** It is the single source of truth for continuing work. It was written 2026-08-12 by the previous session, which ran out of tool-call budget while implementing the visual system. Everything below is either **verified fact** or **explicitly unverified** — nothing is claimed to work that was not actually run.

---

## 0. TL;DR — Current state in one paragraph

Cyclone is a Windows-first "Grok-Bot-style" agent operating environment: a React/Vite/Tauri desktop app + FastAPI control plane (Cyclone Core) + official Hermes Agent container + PostgreSQL/Redis + n8n + Obsidian vault + a Windows Host Bridge. The **design system and most frontend/backend source exist**, and the Core unit test suite passed (12 tests). **The stack has never been started end-to-end, the app has never been built or rendered, and no model/Telegram credentials exist.** The immediate job is: get approval → create `.env` → `docker compose up -d --build` → wire the missing API routes → compile/run the UI → verify against `DESIGN.md`.

---

## 1. The mission (from the operator)

Build Cyclone as a faithful implementation of the attached visual specification:

- **`C:\Users\Agent\AppData\Local\hermes\profiles\research\attachments\DESIGN.md — Cyclone 1_1 Grok Bot Visual System.md`** (2430 lines — READ IT IN FULL; it is the absolute visual authority; images win over text where they disagree)
- Reference image: **`C:\Users\Agent\Downloads\Grok-Bot-featured-image-scaled.webp`** (1200×630; also the only screenshot found; user references additional screens in DESIGN.md that we do NOT have copies of — do not invent them)
- **Do NOT copy** the example bots/names/messages/companies from the reference. Use Cyclone's real agents and data.
- Infrastructure (Docker, Hermes, n8n, Obsidian, DeepSeek, DBs) must be **invisible** in normal use. The product should feel like "messaging a team of AI coworkers."
- **No fake content.** Proper empty/loading/working/error/offline states instead of demo data.
- Deliverable eventually: Windows app + installer, first agent usable with a DeepSeek key.

---

## 2. Environment facts (this machine — verified)

| Item | Value |
|---|---|
| OS | Windows 10, home `C:\Users\Agent` |
| Repo | `C:\Users\Agent\Cyclone` (git repo, **all files untracked, zero commits**) |
| Remote | `https://github.com/premiumcentraal-boop/Cyclone.git` (branch `main`; gh CLI NOT installed) |
| Shell | Git-Bash/MSYS (POSIX syntax; use `ls`, `$HOME`, single quotes) |
| Docker | Desktop 29.3.0, engine server up, Compose v5.1.0 ✅ |
| Node / npm | v22.23.2 / 12.0.2 ✅ |
| Rust / Cargo | ❌ **missing** → Tauri `.exe`/NSIS installer BLOCKED until operator installs Rust |
| .NET | runtime present, **SDK missing** → Host Bridge BLOCKED |
| Python (host) | Hermes venv 3.11: fastapi 0.133.1, uvicorn 0.41.0, httpx 0.28.1, pytest 9.1.1, pydantic 2.13.4; **psycopg/redis NOT installed on host** (they exist only in the Docker image — Core tests that touch DB need Docker or a local venv) |
| Hermes upstream | local checkout `C:\Users\Agent\AppData\Local\hermes\hermes-agent` @ `4f675cf2fc` (use as source of truth for Hermes APIs) |
| `.env` | ❌ **MISSING** (creation was blocked pending operator approval) |
| `node_modules` | ✅ present in `apps/desktop` (npm install completed) |

**Approval quirk:** terminal commands containing heredocs / writes to `.env` triggered an approval prompt that timed out (user did not respond). The operator explicitly asked for a handoff doc. **Ask for approval before:** writing `.env`, `docker compose up`, `npm install`, installing Rust/.NET, or anything long-running.

---

## 3. What exists (inventory)

### 3.1 Docs (written, accurate)
- `README.md` — product summary, quick start
- `docs/ARCHITECTURE.md` — evidence-backed design with official-source citations (xAI Grok Bot docs, Hermes docs incl. upstream `docker-compose.windows.yml`, n8n docs)
- `docs/STATUS.md` — honest acceptance matrix
- `docs/plans/2026-08-12-cyclone-vertical-build.md` — vertical build plan
- `.env.example` — template with placeholders + correct Windows paths
- `.gitignore` — excludes `.env`, vault content, node_modules, target, etc.

### 3.2 Docker (`docker/docker-compose.yml`)
Services: `postgres:16-alpine`, `redis:7-alpine`, official `nousresearch/hermes-agent:latest` (gateway run, API server on 8642 with `API_SERVER_KEY`), `cyclone-core` (build from `apps/cyclone-core`), `n8n:2.34.5` (Postgres-backed).
- Host ports only `127.0.0.1:8787` (Core) and `127.0.0.1:5678` (n8n); Hermes internal-only on the private network
- Named volumes: `postgres_data`, `redis_data`, `hermes_data`, `n8n_data`
- Bind mounts: workspace + vault from Windows paths in `.env`
- **Never started / never `docker compose config`-validated** (validation was in the blocked command)

### 3.3 Cyclone Core (`apps/cyclone-core`) — FastAPI
- `app/main.py` — routes: health, agents GET, conversations CRUD, messages (POST → creates task → Hermes `/v1/runs` + background poller), tasks GET, runs GET/stop, approvals, memory write/search, internal automation event ingress (header `X-Cyclone-Internal-Key`), host-bridge authorize
- `app/contracts.py` — pydantic contracts (includes `avatar_shape`, `ComputerSessionResponse`, `ComputerOwnershipRequest`)
- `app/repository.py` — psycopg3 async pool, full CRUD; **includes `get_latest_computer_session` and `set_computer_session_owner` methods (unused!)**
- `app/hermes.py` — private authenticated Hermes adapter (`/v1/runs`, health, stop)
- `app/events.py` — in-process SSE event bus (no Redis usage despite redis dep)
- `app/memory.py` — safe Obsidian vault writes (category allowlist, traversal guards)
- `app/policy.py` — host capability policy (read-only allowed; consequential → approval; workspace path enforcement)
- `db/init.sql` — full schema: agents, conversations, messages, tasks, handoffs, approvals, routines, automation_events, knowledge_entries, audit_events, **computer_sessions**; seeds **Chief only** (slug `chief`, color `#70B7A7`, shape `round`)
- `db/migrations/002_visual_system.sql` — `ALTER TABLE agents ADD avatar_shape` + `computer_sessions` table (for existing volumes)
- `scripts/apply_migrations.py` — numbered migration runner (needs `CYCLONE_DATABASE_URL`; not wired into compose/startup yet)
- `Dockerfile` (python:3.11-slim, non-root), `requirements.txt` (fastapi 0.133.1, uvicorn 0.41.0, httpx, pydantic, psycopg[binary,pool], redis, pytest, pytest-asyncio)
- `tests/` — 7 files, **12 tests passing as of the last full run** (`python -m pytest tests -q` → `............`). `test_visual_contracts.py` was added AFTER that run and was never executed. Re-run the suite before trusting it.

### 3.4 Desktop UI (`apps/desktop`) — React 19 + Vite + TS
- `src/App.tsx` — app shell: live mode (Core API + SSE), disconnected mode (no fake data, offline state), loading skeletons; selection, sending, approvals, computer overlay opening
- `src/styles.css` — **complete DESIGN.md visual system**: window radius 20px, sidebar `clamp(224px, 28.5vw, 300px)`, palette `#FCFCFC/#EFEFEF/#F4F4F2/#11110F`, Inter, 13px chat type, pill composer with near-black mic button, 7px scrollbar, frameless titlebar dots, message enter animation, reduced-motion support
- `src/components/BotAvatar.tsx` — **animated SVG agent heads** (round/triangle/diamond/pebble/squircle, gradient material, two white rotated eye capsules, blink + glance timers, cursor tracking, state eyes, reduced-motion), `CrewAvatar` (overlapping heads, white separators)
- `src/components/MessageTimeline.tsx` — time separators, handoff separator ("Messages from X and Y"), system events, routine events, computer task cards, approval cards, human/agent bubbles, crew author labels
- `src/components/Mention.tsx` — semantic inline mentions with tiny live avatars
- `src/components/Composer.tsx` — `@` mention picker, plus button, mic, send; `parseComposerMentions` export
- `src/components/RemoteComputerOverlay.tsx` — fullscreen-ish overlay: scrim `rgba(20,20,18,.36)`, rounded computer window, hover controls, "You" cursor, Take control / Return control, unavailable/checkpoint states, **no fake live stream**
- `src/components/TitleBar.tsx`, `Icons.tsx` (thin-line custom SVGs), `ConversationRow.tsx` (+skeletons)
- `src/core-client.ts` — typed client; **calls `GET /api/v1/agents/{id}/computer` and `POST /api/v1/computers/{sessionId}/ownership` which DO NOT EXIST yet in Core** → will 404 (handled gracefully, but wire them)
- `src/types.ts` — full types + `parseMessage` (semantic mentions, handoff/routine/computer/approval extraction) + `types.test.ts` (vitest; never run)
- `src/mock-data.ts` — reduced to offline/empty helpers only (no fake business content)
- `src/window-controls.ts` — Tauri window API with browser fallback
- `src-tauri/` — `Cargo.toml`, `build.rs`, `tauri.conf.json` (frameless, 1120×740, NSIS, CSP allowing `127.0.0.1:8787` + fonts), `src/main.rs` (commands: docker_available, core_url, window controls) — **uncompilable here (no Rust), syntax/API unverified**
- `package.json` — scripts: `dev`, `build` (tsc --noEmit && vite build), `test` (vitest), `tauri:dev/build`

### 3.5 Host Bridge (`apps/host-bridge`)
- Minimal ASP.NET Core service (`Program.cs`, csproj): loopback-only, bearer token, workspace path allowlist, JSONL audit, read-only capabilities only (`filesystem.read`, `process.list`, `window.list`), **no generic shell**. **Cannot build (.NET SDK missing).**

### 3.6 n8n (`services/n8n/`)
- `workflows/cyclone-routine-event.json` — manual trigger → POST to Core internal automation event endpoint with `X-Cyclone-Internal-Key`
- `README.md` — import instructions (never executed)

### 3.7 Vault / scripts
- `scripts/bootstrap-vault.py` — creates the 13 category folders (never run; blocked)
- `vault/templates/` — `knowledge-note.md`, `decision-note.md`

---

## 4. The canonical "Where we stopped" list — KNOWN GAPS, by priority

### 🔴 P1 — Required before anything runs
1. **`.env` missing** — copy `.env.example` → `.env`, generate random secrets, keep the Windows paths. DeepSeek key: user must paste it (`DEEPSEEK_API_KEY=...`). Do NOT commit `.env`.
2. **Create local folders**: `python scripts/bootstrap-vault.py "C:/Users/Agent/Documents/CycloneVault"` + `mkdir -p "C:/Users/Agent/Documents/CycloneWorkspace"`.
3. **`docker compose -f docker/docker-compose.yml config`** — never validated. Fix any errors.
4. **`docker compose up -d --build`** — never started. First run pulls the multi-GB Hermes image. Then verify `docker compose ps` + `curl http://127.0.0.1:8787/health` + `curl http://127.0.0.1:8642/health` (bearer = `HERMES_API_KEY` from `.env`).

### 🟠 P2 — Code gaps the previous session left behind
5. **Wire computer-session routes in `apps/cyclone-core/app/main.py`** (contracts + repo methods exist):
   - `GET /api/v1/agents/{agent_id}/computer` → `repository.get_latest_computer_session` (404 → return an explicit `unavailable` response, never fabricate a session)
   - `POST /api/v1/computers/{session_id}/ownership` → `repository.set_computer_session_owner` (only `agent|human|idle`; audit the takeover)
6. **No agent-creation endpoint** — user wants to "make my first agent." Add `POST /api/v1/agents` (slug, name, role, description, avatar_color, avatar_shape, provider, model, hermes_profile) + repo method + tests. Only Chief is seeded today.
7. **Re-run Core tests** (`python -m pytest tests -q` from `apps/cyclone-core`) — `test_visual_contracts.py` and the computer-session repo additions were never executed.
8. **Frontend never compiled**: run `npm run build` (tsc) in `apps/desktop`; fix TS errors (known suspects: `parseMessage` mention regex escaping in `types.ts:245`, `RefObject` casts in `BotAvatar.tsx`, possibly-unused imports `demoAgents`/`demoConversations`/`isPreview` in `App.tsx`). Then `npm test` (vitest — `types.test.ts` never run).
9. **Run the UI**: `npm run dev` → http://127.0.0.1:1420 (Core must be up). Verify against DESIGN.md: sidebar %, row height, bubbles, avatars, composer, overlay. **Screenshot comparison loop required by DESIGN.md §69–70** (Playwright visual tests listed: chat-direct, chat-crew, computer-card, computer-overlay — none exist yet).

### 🟡 P3 — Feature-completion for the stated workflows
10. **DeepSeek**: user adds key → `docker compose restart hermes` → real message to Chief through the app → verify a real response appears with no fabrication.
11. **"New conversation / new agent" UI** — the sidebar `+` currently only sets a notice; wire it to the new endpoints.
12. **n8n routine e2e**: open `http://127.0.0.1:5678`, create owner account, import `cyclone-routine-event.json`, execute, confirm automation message in a Core conversation.
13. **Migrations wiring**: decide whether `apply_migrations.py` runs on Core startup or as an explicit compose one-shot; document it.
14. **Telegram**: only with an operator-provided bot token; currently disabled by design.
15. **Host Bridge**: needs .NET SDK install (operator approval), then `dotnet build` + tests.
16. **Tauri**: needs Rust install (operator approval), then `npm run tauri:build` → NSIS installer → verify `.exe` artifact path.
17. **Git**: initial commit + push to `premiumcentraal-boop/Cyclone` (remote exists; gh CLI absent → git HTTPS credentials needed).

---

## 5. Verified results (so far — the honest ledger)

- ✅ Core unit suite: **12 passed** (`.............` on the last full run: settings, policy, hermes adapter, memory, events, api-routes)
- ✅ `npm install` in `apps/desktop` completed (node_modules + package-lock present)
- ✅ Upstream Hermes research recorded with citations (ARCHITECTURE.md)
- ✅ DESIGN.md fully read and applied to source structure
- ❌ Docker stack: never started, never validated
- ❌ `.env`: missing
- ❌ Core: never run against Postgres/Redis (host lacks psycopg/redis)
- ❌ Hermes live connection: never tested
- ❌ DeepSeek real response: never tested (no key)
- ❌ Desktop: never compiled, never rendered, never screenshot-compared
- ❌ n8n: never run
- ❌ Tauri exe/installer: not built (no Rust)
- ❌ Host Bridge: not built (no .NET SDK)
- ❌ Telegram: not configured
- ❌ Git: zero commits
- ❌ Playwright visual regression: not created

---

## 6. Non-negotiables / guardrails for the next AI

1. **Never claim it works without running it.** No "the app works" until: stack up, Core healthy, UI rendered, screenshot-compared. No fake computer streams, no fake agent results, no simulated Telegram.
2. **DESIGN.md is law.** Don't "improve" the design: no KPI cards, no dashboards, no gradients, no giant headers, no third column, no heavy toolbars, no emoji-for-core-UI. Frame-less window look must stay.
3. **Do not copy the reference's fictional content** (Sales Outbound, Offsite crew, Armand Segall, Acme, etc.). Use Cyclone's real agents (Chief + whatever the user creates).
4. **Secrets**: `.env` untracked; never print API keys; DeepSeek key only from the user.
5. **Approvals**: installing Rust/.NET, writing `.env`, and long docker/npm commands need operator consent (previous attempt timed out awaiting approval — ask explicitly, then wait).
6. **Windows specifics**: terminal = git-bash; prefer forward slashes; path tool quirks (see `windows-wsl-file-operations` skill); don't treat hostname as username.
7. **If a tool is broken** (e.g., psycopg missing on host), report a blocker with evidence instead of "fixing" the environment; run DB-touching tests inside Docker or a project-local venv.

---

## 7. Suggested first 60 minutes for the next AI

1. Read this doc + DESIGN.md (§1–§83) + `docs/ARCHITECTURE.md` + `apps/desktop/src/App.tsx` + `apps/cyclone-core/app/main.py`.
2. Ask the operator: (a) approve `.env` creation + `docker compose up`, (b) provide the DeepSeek key (or confirm they'll paste it themselves).
3. `.env` → folders → `docker compose config` → `docker compose up -d --build` (background, notify) → health checks.
4. While images build: add P2 routes (`computer`, `POST /api/v1/agents`) + tests; run Core pytest; run `npm run build` + `npm test`; fix TS errors.
5. `npm run dev`, screenshot against the reference, run the DESIGN.md polish pass (§82).
6. Then: real DeepSeek message → n8n routine → (later) Rust/Tauri, .NET bridge, Telegram, git push.

---

## 8. Key paths cheat-sheet

```text
C:\Users\Agent\Cyclone\
  .env.example → .env (YOU create)
  docker\docker-compose.yml
  apps\cyclone-core\app\{main,contracts,repository,hermes,events,memory,policy,settings}.py
  apps\cyclone-core\db\init.sql, db\migrations\002_visual_system.sql
  apps\cyclone-core\tests\
  apps\desktop\src\App.tsx, styles.css, types.ts, core-client.ts, components\*
  apps\desktop\src-tauri\{Cargo.toml, tauri.conf.json, src\main.rs}
  apps\host-bridge\Cyclone.HostBridge\Program.cs
  services\n8n\workflows\cyclone-routine-event.json
  scripts\bootstrap-vault.py
  vault\templates\*.md
  docs\{ARCHITECTURE,STATUS}.md, docs\plans\2026-08-12-cyclone-vertical-build.md

Design authority:
  C:\Users\Agent\AppData\Local\hermes\profiles\research\attachments\DESIGN.md — Cyclone 1_1 Grok Bot Visual System.md
Reference image:
  C:\Users\Agent\Downloads\Grok-Bot-featured-image-scaled.webp
Hermes upstream source of truth:
  C:\Users\Agent\AppData\Local\hermes\hermes-agent (docs: website/docs/user-guide/{docker,features/api-server,features/delegation,messaging/telegram}.md)
```
