# Plan 53: The Glass Manager — build runs

Status: **Planned.** Written 2026-10-06 at alpha.110 (Glass 1.0.0-alpha.61). Design: [52](52-glass-manager-design.md).
Ten runs, about 6–7 weeks at one run per 2–4 days. Each run is one PR, one alpha, green CI, and states physical
verification honestly.

## 0. How the runs are cut

- **Paths.** Gateway work stays in `apps/device-gateway/cyclone_device_gateway/command/agent/**` (new) and
  `command/ai.py` (shrinks to a facade). Glass work stays in `apps/glass/src/manager/**`, `apps/glass/src/ui/orb/**`
  and `apps/glass/src/styles/manager.css`. None of these overlap the V5 reliability sprint (phone runtime, Lab).
- **Order.** Plumbing first (R1–R2), then the look (R3–R4), then what it can see and do (R5–R6), then autonomy and
  learning (R7–R9), then the overnight pass and polish (R10). Every run leaves Ask AI working.
- **Laws (every run).** No model call or key in `apps/glass`; fixed tools only, no shell, file or network tools; no
  secret in memory, playbooks, events or logs; proposals for every change; phone approvals untouched; spend inside the
  caps; audit chain entry for every applied change.
- **Checks (every run).** `python -m pytest apps/device-gateway/tests -q`, Glass `npm test` and `npm run build`,
  `python scripts/ci/glass_guard.py`, `python scripts/ci/release_versions.py --check`.

## R1 — The agent package (no visible change) · alpha.111

**Goal:** Hermes' layout under the existing behaviour.

- Split `command/ai.py` into `command/agent/`: `registry.py` (tools self-register with `kind` = read / proposal / ui,
  schema, availability check, error wrapping), `toolsets/workspace.py` (the 25 existing tools, unchanged), `prompt.py`
  (three tiers: stable rules → page context → volatile time and settings), `loop.py` (the current turn loop),
  `store.py` (settings, key, conversations, budgets — moved, not changed).
- `ai.py` becomes a thin facade so `/v1/cc/ai/*` routes and their tests stay as they are.
- Tests: every existing `test_*ai*` passes unchanged; new `test_agent_registry.py` (registration, kinds, unknown tool,
  schema shape); a guard test that no registered tool name matches shell/file/http patterns.
- Accept: diff in behaviour is zero; route snapshots equal.

## R2 — Streaming, queue and compression · alpha.112

**Goal:** replies stream; long chats stay inside context.

- `agent/events.py`: `cyclone.manager.events/1` (design §8) with `seq`, a 500-event ring per conversation.
- WebSocket `/v1/cc/ai/events` (auth and reconnect like `/v1/fleet/events`), resume with `afterSeq`.
- `loop.py` streams from OpenRouter (`stream: true`), emits `run.*`, `text.delta`, `tool.*`, `proposal.*`.
- One serial queue per conversation (a second message waits or steers; never two turns at once).
- Steps 10 → 25, budget still enforced per step. `agent/compress.py`: summarize the middle of a conversation past
  60% of the model's context; the summary is stored and shown as a collapsed "Earlier in this chat" row.
- Glass: `services/managerStream.ts` (socket, resume, backoff); `aiPanel.ts` switches from 900 ms polling to the
  stream, polling kept as fallback.
- Tests: stream ordering, resume after drop, queue serialization, compression keeps tool pairs intact, budget stop
  mid-stream emits `run.failed` with the reason.

## R3 — Design foundation · alpha.113 (Glass 1.0.0-alpha.62)

**Goal:** the components exist and look right before they are wired in.

- Tokens: `--mgr-*` and motion tokens in `tokens.css` / `motion.css` (design §4), light and dark.
- `ui/orb/`: `orbRenderer.ts` (WebGL2 port of Bloop's idea, seven poses, spring transitions), `orbFallback.ts`
  (Canvas 2D), static SVG; visibility- and reduced-motion-aware loop.
- `ui/reel.ts` (Handle Reel), `ui/trail.ts` (Timeline), `ui/heatgrid.ts` (GitHub Activity), `ui/statusDot.ts`,
  `ui/alertList.ts` (Notification List), `ui/checklist.ts` (Interactive Checklist), `ui/phoneAvatar.ts`.
- A developer gallery at `#/dev/manager` (hidden from the sidebar) showing every component in every state, light and
  dark, for visual review.
- Licence: pin the Space UI commit, add the MIT entry to `docs/OPEN_SOURCE_COMPONENTS.md`, origin headers on ports;
  `glass_guard.py` gains the origin-header rule.
- Tests: each component's states, keyboard, reduced motion (no rAF scheduled), orb stops when `document.hidden`.
- Accept: owner reviews the gallery screenshots (light/dark, 1440 and 400 px wide).

## R4 — Presence everywhere · alpha.114 (Glass alpha.63)

**Goal:** one Manager on every page.

- `manager/dock.ts` (orb + reel at the sidebar foot; pill on narrow screens), `manager/panel.ts` (peek/open/full,
  ⌘J, remembers width per tab in sessionStorage), the workspace panel replaced by the shared panel with page context.
- Conversation view: streamed text with blur reveal, work trails under each reply, proposal cards (single and batch),
  stop button, model and spend in the footer.
- `manager/palette.ts`: ⌘K with Go to / Do / Ask sections (design §5.3), local route index for instant jumps.
- Gateway: `status` event (reel items, pose hints) from existing data (phones, tasks, approvals, spend).
- Tests: panel open ≤ 100 ms with cached state (timed in jsdom as a regression bound), palette keyboard flow,
  page-context injection, proposal apply/discard from the panel.

## R5 — The Manager can move Glass · alpha.115 (Glass alpha.64)

**Goal:** it points at what it talks about.

- `toolsets/glass_ui.py`: `open_page`, `set_filter`, `highlight`, `open_run`, `scroll_to` (kind `ui`; no data
  change, no proposal needed). Arguments validated against a published route/filter list.
- Glass `manager/uiActions.ts`: executes `ui.action`, replies `ui.result` (ok / not found); `data-mgr-target`
  attributes added to Runs, Lab, Phones, Fleet, Apps and Workspace rows; highlight ring (design §5.10).
- The current page's visible summary (title, filters, top rows) is sent as context with each message.
- Tests: every route in the list resolves; unknown targets fail quietly; ring removed after 2.4 s; reduced motion
  shows the ring without animation.

## R6 — Project sight · alpha.116

**Goal:** it can answer "how close are we to V5?" with numbers.

- Read toolsets: `lab.py` (experiments, verdicts, arms, findings), `testbench.py` (local results and findings ledger,
  dashboard numbers), `runs.py` (runs, run inspector steps, cause of death), `fleet.py` (phones, health, root status,
  colours), `releases.py` (`release/version.toml`, release notes, the V5 gate scorecard), `github.py` (PRs and check
  runs for the configured repo, read-only, token in the encrypted store, off until set).
- Proposal tools: `lab.start_preset` (named presets only, owner applies), `routines.rerun` (existing).
- Outside text (run summaries, PR bodies, notes) reaches the model as tool results marked as information.
- Tests per toolset with fixtures; GitHub tool refuses any repo but the configured one and any write verb.

## R7 — Proactive: heartbeat, alerts, brief, pulse · alpha.117 (Glass alpha.65)

**Goal:** it speaks up only when it should.

- `agent/heartbeat.py`: every 30 min while the gateway runs, a check-model turn over the owner's checklist with read
  tools only; `HEARTBEAT_OK` is silent; findings become `alert.created`, deduplicated by kind + subject for 24 h.
- Settings: check model (separate from chat model), checklist editor (§5.8) with the six defaults, quiet hours.
- Phone notification for items marked urgent, through the existing delivery path.
- Morning brief: built at 06:00 local (or first open), stored, shown once; pulse heatmap from testbench history.
- Glass: alerts tab (§5.7), brief card, pulse on Home, orb `attention`.
- Tests: silence on nothing, dedup, quiet hours, caps cover heartbeat spend, brief shown once per day.

## R8 — Memory and session search · alpha.118 (Glass alpha.66)

**Goal:** it remembers you and the project.

- `agent/memory.py`: three capped stores — MEMORY (2,200 chars, facts), OWNER (1,400, preferences), GOALS (1,400,
  gates and this week's focus); tool actions `add` / `replace` / `remove`; a frozen snapshot per conversation (prompt
  cache stays warm); every write passes the secret screen and emits `memory.changed` with an undo id.
- `agent/sessions.py`: FTS5 index over stored conversations; tool `session_search` returns real messages, no summary.
- Glass: memory chips with undo; Settings → Manager → Memory (view, edit, clear).
- Tests: caps enforced, secret-shaped text refused, undo restores, snapshot frozen mid-conversation, search ranking.

## R9 — Playbooks · alpha.119 (Glass alpha.67)

**Goal:** it gets better at recurring work.

- `agent/playbooks.py`: Hermes-compatible `SKILL.md` (frontmatter: name, description, version, tags; sections When
  to use / Procedure / Pitfalls / Verification). Tools: `playbooks_list` (metadata only), `playbook_view`,
  `playbook_manage` (`create`, `patch`). Created after a non-trivial workflow, a fixed error or an owner correction.
- New and patched playbooks are **drafts** until the owner approves; a playbook that would create phone tasks must
  also pass its Lab preset once before it is used.
- Glass: playbook chips, Settings → Manager → Playbooks (draft / approved, diff of each patch, approve, delete).
- Tests: progressive loading, drafts never used, patch diff, Lab gate, secret screen on content.

## R10 — Overnight pass and polish · alpha.120 (Glass alpha.68)

**Goal:** it learns while you sleep and the whole thing feels finished.

- `agent/nightly.py` (Letta's sleep-time idea): merges duplicate memory lines, proposes playbook patches from the
  day's failures and corrections, prepares the brief. Runs once at night inside the caps; its own trail in the panel.
- Polish: performance budget check (orb ms per frame, panel bundle size), accessibility pass, empty and error states,
  copy review, both themes.
- Docs: Glass charter criterion 7 reworded to "no model calls in the browser code; the Manager lives in the runtime";
  Glass README; release notes; plan statuses.
- Accept: a week of daily use by the owner with the brief, alerts and memory on.

## Later (M5)

The same Manager from the phone's Ask screen; voice (orb `listen` / `speak`); read-only sub-agents for large triage.

## Risks

| Risk | Guard |
|---|---|
| Self-written playbooks drift unsafe ("Practice Makes Unsafe", Aug 2026) | Drafts + owner approval + Lab gate; no tools that can act outside proposals |
| Heartbeat noise | Silent by default, 24 h dedup, "stop telling me" from the inbox, quiet hours |
| Spend creep | One cap for chat, heartbeat and night; cheap check model; stop at the cap with a clear status |
| Prompt injection via run text, PR bodies | Tool results marked as information; no tool can approve, delete or reach secrets |
| Orb costs battery/GPU | Visibility-gated loop, idle 20 fps, static under reduced motion, fallback without WebGL2 |
| Scope during the V5 freeze | Separate paths; the owner decides timing (design §11) |
