# CYCLONE — HANDOFF DOCUMENT

> Prepared for a fresh AI session (ChatGPT/Claude/another agent) to continue
> development of the Cyclone application without any prior context.
> **Read `README.md` and `docs/ARCHITECTURE.md` too — this document is the
> operating manual on top of them.**

---

## 1. What Cyclone is

Cyclone is a **desktop messaging app whose contacts are persistent AI agents**.
It is the user's own agent network: the human types to agents in direct chats
and groups; agents work through a real Hermes runtime, talk to each other,
hand off work, and report back — on the desktop **and** over Telegram.

No demo bots, no fake conversations. Every screen renders real state: real
agents, real conversations, real tasks, real runs, real integrations. If
something doesn't exist yet, the UI shows a proper empty state.

Design authority: `docs/ARCHITECTURE.md` §visual priorities — real Grok Bot
screenshots (reference: restrained Windows messenger look — 278px sidebar
`#F7F7F7`, conversation area `#FCFCFC`, agent bubbles `#EEEEEE`, 41px headers,
Windows-native title controls, living colored SVG characters).

---

## 2. Stack and how to run it

```
Windows host (git-bash terminal)
├── cyclone-core     FastAPI app (Python 3.11)   → http://127.0.0.1:8787
├── cyclone-hermes   Hermes gateway (DeepSeek)   → internal API :8642
├── cyclone-n8n      n8n workflows               → http://127.0.0.1:5678
├── cyclone-postgres Postgres 16                 → internal :5432
├── cyclone-redis    Redis 7                     → internal :6379
└── desktop app      React + Vite + TypeScript   → dev server :1420
```

**Start the stack** (from repo root `C:\Users\Agent\Cyclone`):

```bash
docker compose -f docker/docker-compose.yml --env-file .env up -d --build
```

- `--env-file .env` is **required**: compose otherwise resolves `.env` from
  `docker/` (which doesn't exist). The root `.env` holds all secrets and is
  gitignored.
- The `hermes-config` one-shot service re-syncs Hermes' model config on every
  `up` (DeepSeek-first: `model.provider=deepseek`,
  `model.base_url=https://api.deepseek.com/v1`, default `deepseek-v4-flash`).
- **Desktop dev**: `cd apps/desktop && npm install && npm run dev` (port 1420,
  strictPort). Production build: `npm run build` (outputs to `dist/`).
- **DB migrations** are NOT run by the container automatically:

```bash
docker exec cyclone-cyclone-core-1 python scripts/apply_migrations.py
```

  New migrations: `apps/cyclone-core/db/migrations/NNN_name.sql` (plain SQL).

**Tests**: `cd apps/cyclone-core && python -m pytest tests -q` (currently 40
passing) · `cd apps/desktop && npm test` (2 passing).

---

## 3. Architecture in one page

```
Desktop UI / Telegram / (future: API clients)
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│ cyclone-core (FastAPI :8787)                                 │
│  repository.py   Postgres access (psycopg3)                  │
│  events.py       in-process EventBus (SSE + subscriptions)   │
│  orchestrator.py wake_agent / _monitor_run / try_handoff     │
│  router.py       deterministic group message routing         │
│  context.py      focused context packets per agent run       │
│  mentions.py     @slug / @everyone / @HANDOFF parsing        │
│  hermes.py       HTTP adapter to the Hermes gateway          │
│  telegram.py     TelegramWorker (long-poll ingress/egress)   │
│  mcp_server.py   agent collaboration tools (MCP, /mcp)       │
│  memory.py       vault knowledge (markdown, FTS retrieval)   │
│  fts.py          stopword-filtered OR-term search            │
└──────────────────────────────────────────────────────────────┘
        │ HTTP /v1/runs
        ▼
┌──────────────────────────────────────────────────────────────┐
│ cyclone-hermes (Hermes gateway, DeepSeek deepseek-v4-flash)  │
│  persistent agents = runs in named conversations             │
└──────────────────────────────────────────────────────────────┘
```

**Core principles** (do not violate):
- **Cyclone owns persistence and multi-agent communication.** Hermes does
  reasoning/tools only. A group chat is NOT one Hermes conversation and NOT
  one `delegate_task()`.
- Route by `agent.id`, never display name.
- Every human/agent message is a durable row; events (SSE for the UI, event
  bus for the Telegram forwarder) are derived from those rows.
- Never fabricate data, results, or integrations.

---

## 4. Current state (verified working)

### 4.1 Collaboration layer (the headline feature)
- **Agents**: 5 live — Chief (blue/round, coordinator), Research (purple/
  triangle), Writer (amber/squircle), Developer (teal/diamond), Reviewer
  (red/pebble). Persistent identities: id, slug, name, role, description,
  avatar color+shape, hermes_profile, workspace, status.
- **Conversations**: `direct | group | cluster | routine | telegram` with
  memberships. Groups: "Website Launch" (`eed9f154-26f0-45c8-931f-1995f09a648e`).
- **Semantic mentions**: `@slug` persisted in `message_mentions` (type agent/
  everyone), char offsets. `@everyone` = broadcast to all members (inbox
  events, no auto-wake). Leading `@slug` routes ownership; inline mentions
  are references. `@HANDOFF @slug: summary | criteria` = delegation syntax.
- **Routing** (`router.py`, deterministic, unit-tested): leading mention →
  first inline mention → active-task owner → last agent speaker → coordinator
  → first member. Explicit mentions always win.
- **Handoffs**: durable `handoffs` row + child task + chat event +
  auto-started follow-up run on the receiving agent. Depth guard (4),
  self-handoff ignored.
- **Inbox**: `agent_inbox` rows (pending → processing → done/failed),
  startup recovery sweep re-dispatches stale items.
- **Context packets**: role-scoped (identity + rules + roster + trigger +
  task + last messages + vault knowledge), NOT full history.
- **MCP server** at `http://127.0.0.1:8787/mcp` (Streamable HTTP): tools
  `send_agent_message`, `handoff_task`, `post_result`, `update_task_status`,
  `get_group_context`, `get_my_inbox`, `mention_agents`, `register_artifact`.
  **Not yet registered in Hermes** — see Backlog item 1.

### 4.2 Telegram channel (fully working)
- Bot `@cycloneapp_bot`; token + `TELEGRAM_ALLOWED_USERS=7690834361` +
  `TELEGRAM_HOME_CHANNEL=7690834361` in root `.env` (active, NOT commented).
- TelegramWorker long-polls `getUpdates`, upserts a `users` row (user record
  "Ka", chat 7690834361 exists), finds/creates the `kind='telegram'`
  conversation (`telegram-<chat_id>` key), dispatches through the same
  message route (mentions/routing/wake), and forwards agent results back via
  event-bus subscriptions **restored at startup** (see Pitfalls).
- Approval answers work in chat: "allow once / allow session / always allow /
  deny" (plus aliases) resolve the real Hermes run approval.
- **TELEGRAM env must live on `cyclone-core` ONLY** — Hermes' own gateway
  Telegram platform polls the same bot and causes 409 conflicts (fixed in
  compose, do not reintroduce).

### 4.3 Desktop app
- Visual system per DESIGN (Windows controls, 278px sidebar, bubbles,
  timestamps, Plugins entry, User entry from `/api/v1/users/me`, utility
  panel, Plugins view, first-agent state, character creator with the
  ten-color palette + 8 shapes).
- Composer: `+` attachment popover (Attach file → real upload to
  `/api/v1/attachments`, Paste from clipboard, Attach URL), chips, file cards
  in timeline, and a **model selector** (Auto / deepseek-v4-flash /
  deepseek-v4-pro) sending real per-message provider/model overrides.
- Question cards: rendered from real Hermes approval events
  (`waiting_for_approval` → `approval.request` SSE capture), decisions via
  `POST /api/v1/runs/{run_id}/approval`.
- Identity comes from `/api/v1/users/me` (real DB record).

### 4.4 Runtime plumbing
- Vault knowledge retrieval (markdown notes, OR-term FTS, 600-char excerpts
  injected into run context). "Cyclone Lighthouse Directive" note exists.
- n8n workflows imported (manual + webhook variants) but **inactive** (see
  Backlog item 3).
- `ComputerSession` model: session lifecycle + human/agent ownership; UI
  shows honest unavailable/empty states (no real sessions yet).

---

## 5. Key flows (read before touching code)

**Human → agent (desktop or Telegram):**
`POST /api/v1/conversations/{id}/messages` →
`create_message_and_start_agent` (main.py) →
persist message + mentions → router picks ONE responder → task created →
`wake_agent` (orchestrator) → inbox item → `_dispatch_inbox_item` →
context packet → `_start_agent_run` → Hermes `/v1/runs` → `_monitor_run`
polls 1s up to **600s** (10 min; `waiting_for_approval` resets the clock) →
result message (kind `result`, author = the agent) → `agent.run.completed`
event → UI refreshes via SSE, Telegram forwarder sends it.

**Handoff:** agent output contains `@HANDOFF @slug: summary | criteria` →
`try_handoff` creates child task + handoff row + chat event +
`wake_agent(target)` with the summary as trigger → target runs with the
handoff context. Sender stays idle; no open LLM waits.

**Run approval:** monitor sees `waiting_for_approval` → `_handle_run_approval`
captures the `approval.request` SSE event once per run → posts the question
message → desktop card or Telegram reply resolves via the run-approval
endpoint → monitor continues.

---

## 6. Env & configuration

`.env` (root, gitignored, user edits it — **always re-verify bytes/interpolation
before env-dependent deploys**; it once had the Telegram lines commented out
again after being configured):

```
DEEPSEEK_API_KEY, OPENROUTER_API_KEY, HERMES_API_KEY, CYCLONE_DATABASE_URL,
CYCLONE_REDIS_URL, CYCLONE_VAULT_PATH/HOST, CYCLONE_WORKSPACE_PATH/HOST,
CYCLONE_CORS_ORIGINS, TELEGRAM_BOT_TOKEN, TELEGRAM_ALLOWED_USERS,
TELEGRAM_HOME_CHANNEL
```

**Approval gate** (hard rule): writing `.env` and
`docker compose restart/stop/kill/down` require interactive user approval —
never retry a blocked command via another route (e.g. don't use `up --force-recreate`
to emulate a restart). Background `docker compose up -d` (build/recreate) and
`docker exec` pass ungated. Hermes config hot-reloads via
`docker exec cyclone-hermes-1 hermes config set ...` — no restart needed.

Secrets are never printed, committed, or hardcoded. The tool masks tokens in
output; assume any echo of `.env` leaks nothing but verify lengths only.

---

## 7. Known issues / Backlog (next priorities)

1. **Register the Cyclone MCP server in Hermes** so agents can actually call
   the collaboration tools (today they use the text `@HANDOFF` protocol):
   `docker exec cyclone-hermes-1 hermes mcp add cyclone --url http://cyclone-core:8787/mcp`
   then verify `hermes mcp list` / `hermes mcp test cyclone` and a live run
   using `send_agent_message`. (MCP config auto-reloads; the toolset includes
   MCP tools when servers are configured.)
2. **Visual regression captures** at 1040×759 and 1036×758 for the ten
   DESIGN states (01-direct-chat … 10-computer-takeover) — headless Chrome
   against `http://127.0.0.1:1420` with real state; store under
   `apps/desktop/.visual/` (gitignored). Vision verification on this model
   stack is unavailable — use DOM dumps (`--dump-dom`) and class/path greps.
3. **Activate n8n workflows**: they are imported but inactive; activation
   needs `docker compose restart n8n` (approval-gated — ask the user).
   Alternative: `UPDATE workflow_entity SET active=true` + restart.
4. **Acceptance test sweep for the collaboration layer**: explicit mention
   routing, handoff chain Chief→Research→Writer→Reviewer, sequential
   dependency chains, parallel work, reviewer rejection loop
   (`changes_requested` status exists), `@everyone` broadcast, restart
   recovery of inbox items, unmentioned-message routing — against live
   Hermes runs (each takes 30–120s; results are real DeepSeek output).
5. **`_recovery_sweep`** exists and runs at startup — add a test for it
   (currently only exercised implicitly).
6. **Attachments**: files land in `<workspace>/attachments` (served at
   `/attachments/…`); no size/type policy beyond 15MB client cap, no
   dedup/cleanup.
7. **Reactions & task dependencies** are persisted but have no UI yet
   (reactions = ack-only by design, never approval).
8. **Routines**: `agent_routines` endpoint returns real rows (currently
   empty); the utility panel shows the honest empty state. Wiring actual
   scheduled routines to Hermes runs is open design work.
9. **Tauri shell** (`apps/desktop/src-tauri`) exists but is not built
   (no Rust toolchain; the browser dev server is the live surface).
10. **Computer sessions**: the full takeover UI exists; the Host Bridge
    (which would let agents drive a real Windows session) is not installed.

---

## 8. Pitfalls (learned the hard way)

- **Compose env-file**: always `--env-file .env` from the repo root; compose
  looks for `docker/.env` otherwise.
- **TELEGRAM_* belongs on cyclone-core, never on the hermes service** —
  Hermes' own Telegram platform steals the long-poll (409 conflicts).
- **Event-bus subscriptions are in-memory** — any restart requires
  `restore_subscriptions()` (already called in the worker's `run()`); when
  adding new event types to the Telegram forwarder, remember the allowlist in
  `_forward_loop`.
- **Agent results are published as `agent.run.completed`, not
  `message.created`** — any consumer filtering on event type must include
  both.
- **Monitor window**: runs doing real tool work take minutes; the window is
  600 iterations and resets on `waiting_for_approval`. Don't shrink it.
- **SVG gradients**: `fill="url(#${id})"` must be a template literal —
  a plain string renders the avatar transparent (fixed once, keep it fixed).
- **Module-level `useState` in React crashes the whole app at load** — hooks
  only inside components (fixed once; tsc won't catch it).
- **DB CHECK constraints**: widening an enum needs a migration
  (`003` added `group`/`changes_requested`; `004` widened avatar shapes;
  `005` created `users`; `006` recolored agents). Always include legacy
  values in read contracts (`AgentSummary.avatar_shape` accepts diamond/
  pebble).
- **Migration runner** applies files in one transaction per file; run it
  manually after deploys (`docker exec cyclone-cyclone-core-1 python
  scripts/apply_migrations.py`).
- **Vision is unavailable** on the current model stack — verify UI via
  headless-Chrome DOM dumps and rendered class/path greps, never claim
  visual correctness you cannot see.
- **`docker exec` needs `-i` for stdin heredocs** (`docker exec -i container
  python - <<EOF`).
- **Vitest discovery** is scoped to `src/**/*.test.ts` — junk `*.test.*`
  files outside `src/` break the suite.
- **.env is user-edited**: it once reverted the Telegram lines to commented
  form after being configured — verify before every env-dependent deploy.
- The user is the operator (Telegram chat 7690834361); he works remotely and
  answers approvals by text. Keep him informed via the bot once it is live.

---

## 9. Git / working style

- Repo: `C:\Users\Agent\Cyclone`, branch `main` (origin `main`).
- Commit style: conventional prefixes (`feat:`, `fix:`, `test:`,
  `docs:`), one logical change per commit, LF→CRLF warnings are cosmetic.
- Verification before claiming done: pytest + vitest + live curl/DOM check.
- Design authority: real Grok Bot screenshots > earlier screenshots >
  `docs/ARCHITECTURE.md` (DESIGN) > existing implementation. No Grok/xAI
  content, no demo data — real states only.

*Generated 2026-08-13 · stack green (core/hermes/n8n/postgres/redis healthy),
desktop dev server on :1420, Telegram channel verified end-to-end.*
