# 76 · The Cyclone platform: the final plan

**Status:** final plan of record, 2026-11-21, written at dev alpha 155 (Glass 1.0.0-alpha.68). Nothing in this plan is
built yet. Every "today" fact was checked on the dev branch, with the file and line named. This version replaces the
draft of 2026-11-20.

**The owner's ask:**
- A management platform for Cyclone that handles fleet data extremely well.
- It analyses accounts and shows the status of the work done inside each account.
- It is flexible enough for any kind of app management and any custom data structure.
- It feels like using Notion.
- It is built one major point at a time, with full attention and no skipped steps, to the quality of a
  billion-dollar system.

**What this plan promises:**
1. A **ledger** that records every fact.
2. **Databases** the owner shapes freely.
3. An **editor and workspace** that feel like Notion.
4. Cyclone's own surfaces (accounts, identities, fleet, apps, work) rebuilt **as databases in that workspace**, not as
   separate screens.
5. **Analytics** where every number links to its source.
6. A **security model** that keeps secrets out of everything except their own encrypted cards.

It is delivered in seventeen stages, each with a measurable exit gate.

---

## Contents

0. How to read this plan
1. Baseline: what exists today (verified)
2. Rules that hold at every stage
3. Owner decisions
4. The target system
5. The experience: what "feels like Notion" means, measured
6. The Notion parity map (every capability, its stage)
7. The stages (0–16)
8. How each coding run is executed
9. Quality bar, measured
10. Risks
11. Retention
12. Scale and service levels
13. Backlog
14. Glossary

---

## 0. How to read this plan

- **Stages are sequential gates.** A stage starts only when the previous stage's exit gate has passed and its
  evidence is recorded. A run never borrows from a later stage to finish an earlier one.
- **One major point per run.** A run is one branch, one draft pull request, one stage (or one named part of a large
  stage) and one exit report. Anything outside the stage goes to the backlog (§13).
- **Done means the gate passed.** Merged code that misses its gate is not done.
- **OWED evidence.** Physical or live evidence that this environment cannot produce is marked **OWED**, never
  "passed".
- **Two tracks in every stage.** Each stage has a platform part (data, rules, API) and an experience part (what the
  owner sees, how it feels, how fast). A stage is not done until both parts pass.

---

## 1. Baseline: what exists today (verified)

### 1.1 Platform

| Area | What is there | Where | Missing for this plan |
|---|---|---|---|
| Storage | One SQLite file for the Command Center, one lock, a hash-chained audit | `command/center.py` (`sqlite3`, `CREATE TABLE IF NOT EXISTS`) | A migration framework; an event ledger; time series; a full-text index over everything |
| Databases | Tables with 19 property types (title, text, number, currency, percent, date, select, multi-select, status, checkbox, email, url, phone, created time, edited time, relation, rollup, formula, button). Six layouts: table, board, calendar, timeline, gallery, list. Server-side filters, sorts and groups; row history and undo-last; trash; CSV export | `command/tables.py`, `command/formula.py`, `glass/src/services/tables.ts:10-13` | Person, files, unique id and created-by properties; entity types with versions; encryption of personal columns; caps of 200 tables, 60 properties, 10,000 rows, with every row sent and rendered |
| Pages | Typed blocks (p, h1–h3, todo, bullet, number, quote, callout, divider, ref, view, board, table), `@` mentions of seven kinds, versioned saves, backlinks, templates (four, fixed) | `command/pages.py:54-66`, `glass/src/services/pages.ts` | Nesting of blocks, toggles, code, images and files, links, columns, page history, comments |
| Accounts | Phones → apps → accounts; sign-up maps; create accounts in batch. Owner decision 2026-09-30: no pre-send checks, block list or pacing caps for now | `command/signup.py`, `mind/signup/` | A lifecycle state machine; duplicate detection; account health; work status |
| Identities | Encrypted login plus identity details, bound to one profile, 30-day trash | `command/identities.py`, `identity/IdentityBook.kt` | A card experience; release status to confirm |
| Vault | Zero-knowledge: AES-256-GCM in the browser, PBKDF2 passphrase, recovery key, per-item keys, audit | `command/vault.py`, `glass/src/pages/vaultView.ts` | Windows Hello unlock; secret fields inside cards |
| Sealed delivery | One secret to one phone for one task, single-use, Android Keystore | `command/delivery.py`, `secrets/Hpke.kt` | Device verification: OWED |
| Fleet | One command to many phones, missions, dispatch, pause, rollout, CSV export; WebSocket live view | `desktop_runtime/fleet_orchestrator.py`, `glass/src/pages/fleetView.ts:792` | Fleet as queryable data; history; capacity; bulk dry runs |
| Health | Heartbeat facts, quarantine after 3 infrastructure failures, alerts (6 per hour), 7-day window | `command/health.py:47-56` | History beyond 7 days; email (0 SMTP code) |
| Cloud phones | Accounts, rent, renew, power, backup, restore | `cloud_fleet/`, `/v1/cloud/...` | File upload and skill pack (0 hits) |
| Analytics | Lab statistics only | `lab/stats.py` | Anything for accounts, work, fleet or cost |
| Verification | Phone-side proofs; Lab judging | `lab/verdict.py` | Verification desk (0 hits) |
| AI | Ask AI side panel with streaming and apply/discard cards; Cyber manager | `glass/src/workspace/aiPanel.ts`, `command/agent/` | Grounded analysis with citations over a ledger |

### 1.2 Experience (Glass workspace, measured against Notion)

**Already Notion-grade:**
- The slash menu is grouped, filters as you type, and works from the keyboard (`editor.ts:599`).
- Markdown block shortcuts (`services/pages.ts:261`).
- `@` mention chips that navigate.
- A hover gutter with **+** and a drag handle (`editor.ts:220`).
- Turn into, duplicate and delete on blocks.
- Page autosave with a 600 ms debounce and a real three-way block merge on conflict (`pageView.ts:18`,
  `services/pages.ts:438`).
- Breadcrumbs and backlinks.
- An 860 px document column with Notion-like heading sizes (`styles/workspace.css`).
- Database depth: six layouts, relations, rollups, formulas, a row peek with its own page and history.
- Ctrl/⌘K find with full-text snippets.
- The streaming Ask AI panel.

**The ten gaps the owner would notice first** (verified on dev):

1. **Editing safety.**
   - No undo or redo in the editor (`editor.ts`: no undo handling; `execCommand` used twice).
   - No selection across blocks: each block is its own `contenteditable` (`editor.ts:288`).
   - Paste is plain text only (`editor.ts:572`).
2. **Missing building blocks.**
   - No toggles, code blocks, images or files, links, columns or nested blocks.
   - The block model is flat.
3. **Tables feel slow and lack grid ergonomics.**
   - Every cell edit waits for the server, then redraws the whole view (`tableView.ts:193`).
   - The grid has no keyboard navigation and no virtualisation: every row is rendered.
   - No column resize or reorder, no row reorder, no bulk select or bulk edit, no row templates and no CSV import.
4. **Cyclone's own surfaces are separate screens, not databases.**
   - Accounts, Identities, Vault, Fleet and Health use bespoke `cc-` screens (`commandPage.ts:47-58`).
   - They cannot be filtered, grouped or re-viewed like tables.
5. **No full-page databases.** The router has no database route (`core/router.ts`), and a row cannot open as a full
   page.
6. **No page history, restore or duplicate.**
7. **Weak navigation.**
   - No favourites and no real recents.
   - Pages cannot be reordered among siblings in the sidebar; the API supports it but the UI never sends it
     (`sidebar.ts:106`).
8. **No live updates.** Pages poll every 4 s, tables and the sidebar every 15 s, and loading states are plain text.
9. **No comments and no notifications** beyond the approvals inbox.
10. **No theme choice, cover images or emoji search.** Icons are mixed glyphs.

### 1.3 Constraints that shape the design

- **No runtime npm packages** in Glass (`apps/glass/package.json`; `scripts/ci/glass_guard.py`). The editor, grid
  virtualisation, emoji picker and live updates are built in plain TypeScript, or vendored under decision D11.
- **No `innerHTML`, `eval` or `new Function`.** Rich paste is parsed with `DOMParser` into typed spans.
- **No `localStorage`.** Preferences (favourites, recents, theme, column widths) live on the gateway, per owner.
- **No model calls in Glass.** All intelligence runs in the gateway.
- **Gateway allowlists.** Every new block type, mark or field needs a matching server change. Unknown fields are refused.
- **Secret-shaped text is refused** everywhere except the vault. That is by design, and the experience must explain it
  well (§5.6).

---

## 2. Rules that hold at every stage

1. **Zero knowledge for secrets.** The gateway, every model, every log, every export, every search index and every
   metric see ciphertext or slot names only.
2. **Approvals stay human.** Pay, send, delete, permission and sign-in keep their gates on the phone. Bulk actions are
   proposals with a dry run. The fleet layer never answers an approval and never holds a model key.
3. **Models read the ledger, never the vault.** Model answers cite event ids or metric values. Models cannot change
   state and cannot read secrets, passwords, codes, personal columns or raw screen content.
4. **Every number has a source.** Any metric links to the events behind it, or it is not shown.
5. **Deleting a profile never deletes its identities. Deleting an account never touches the real account** (plan 59).
6. **Physical honesty.** Device and live behaviour is OWED until it is recorded on a real device.
7. **No generic shell, path or query.** Queries use a closed, typed grammar with limits, and nothing uses `eval`.
8. **Migrations are forward-only and tested** against a copy of the previous database.
9. **Never lose the owner's work.**
   - Every edit is undoable.
   - Every delete goes to the trash.
   - Every page has history.
   - A failed save is shown and retried, never dropped.
10. **No model identifiers in commits, pull requests or code. No secret in the repository.**

---

## 3. Owner decisions

The plan uses each default unless the owner changes it. Every decision is recorded before stage 0 closes.

| # | Decision | Default | Why it matters |
|---|---|---|---|
| D1 | Storage engine | Keep SQLite (WAL) behind a repository interface. Revisit for a hosted product. | Scale, backups, hosting |
| D2 | One owner or a team | One owner; every record carries an actor and a scope, so roles can come later | Access design |
| D3 | Pacing caps and block lists for account creation | Reinstated as owner-set defaults, with a warning when lowered | Bans and legal exposure; reverses the 2026-09-30 relaxation as a default only |
| D4 | Which services accounts may be made on | An owner-kept allow list; nothing blocked by default | Terms-of-service risk is the owner's to accept, made visible |
| D5 | Analysis model | The owner's OpenRouter model, as Ask AI uses today | Cost, keys |
| D6 | Cloud provider order | VMOS, then DuoPlus | Stage 9 and 15 scope |
| D7 | Lab screen-copy retention (alpha 153) | 7 days, then deleted and logged; owner missions never saved | Privacy |
| D8 | Scale target | 1,000 phones; 5,000 accounts; 1,000,000 events a day; 100,000 rows per database | Stage 14 measures it |
| D9 | Vault unlock on Windows | Windows Hello, passphrase as fallback | Stage 12 |
| D10 | Signed Windows installer | Owner gets a code-signing certificate before stage 16 | Release |
| D11 | Editor foundation | **Hand-built inside the Glass rules.** The alternative is to vendor a reviewed MIT editor core under an `Origin:` header and a guard exception. | The biggest speed-versus-supply-chain trade-off in the plan (§7, stage 2) |
| D12 | Preferences store | On the gateway, per owner (favourites, recents, theme, widths, sidebar state) | `localStorage` is banned |

---

## 4. The target system

One ledger. Typed records. Views over both. One workspace that shows all of it.

```
┌───────────────────────────── Glass (owner's browser) ──────────────────────────────┐
│ Sidebar: Favourites · Recents · Pages · Databases · Cyclone (system databases)      │
│ Pages (blocks, nesting, comments, history)   Databases (any type, six layouts)      │
│ Peek / full page   Ctrl+K   Dashboards   Inbox (approvals, verifications, comments) │
└──────────────────────────────────────────┬──────────────────────────────────────────┘
                 typed queries · operations · live events (one WebSocket channel)
┌──────────────────────────────────────────┴──────────────────────────────────────────┐
│ Gateway: schema registry · records · relations · secret references (ciphertext)      │
│ Event ledger (append-only, idempotent, hash-chained) ◄── phones, tasks, runs, owner  │
│ Projections · analytics engine · job engine · AI analysis (cited, read-only) · audit │
└──────────────────────────────────────────┬──────────────────────────────────────────┘
                                typed contracts · sealed secrets
                     phones (Cyclone Mind) · cloud phones · app packs
```

**Core concepts:**
- **Entity type:** a schema with typed fields, validation, relations and a version.
  - System types: account, identity, phone, cloud phone, app, task, run, work item, routine, connection.
  - Custom types: any type the owner defines.
- **Record:** one instance of a type. Every record is also a page, with blocks under its properties.
- **Event:** an immutable fact with a type, an actor, a time, a subject, a payload, a source and an idempotency key.
- **Projection:** a rebuildable read model, such as an account's status or a phone's 30-day success rate.
- **View:** a saved query over records, events or metrics, shown in one of the layouts or as a chart.
- **Secret reference:** a field that holds a vault item id. In Glass it shows as a locked chip. The value is only ever
  decrypted in the browser, after the vault is unlocked.

---

## 5. The experience: what "feels like Notion" means, measured

Notion feels the way it does because of a small set of habits held everywhere. This plan writes them down as rules,
with numbers, so that "feels like Notion" can be tested.

### 5.1 The seven habits

1. **Everything is a block, a page or a database.** There is no fourth kind of screen. Cyclone's accounts, phones and
   apps are databases, so they can be filtered, grouped, linked, mentioned and given pages like anything else.
2. **Type first, then shape.** Writing starts immediately. Structure comes from `/`, markdown shortcuts and `@`, not
   from forms.
3. **Nothing waits for the server.**
   - Every edit shows at once and reconciles in the background.
   - A conflict merges where it can, and otherwise shows both versions to the owner.
4. **Every action can be undone.** Ctrl/⌘Z works after any edit, move, delete, property change or bulk action.
5. **One way to open things.** Click opens a peek on the right. Ctrl/⌘-click or "Open as page" opens it full page.
   This works for records, rows, pages and system items alike.
6. **The keyboard can do everything.**
   - Ctrl/⌘K, `/` and `@`.
   - Arrows and Enter in grids.
   - Esc selects the current block, and shift-arrows extend the selection.
   - Ctrl/⌘/ shows the full shortcut list.
7. **Quiet until needed.** Hover reveals controls. Nothing moves for decoration. Errors say what happened and what to
   do, in one line.

### 5.2 Speed budgets (gates from stage 1 on)

| Interaction | Budget |
|---|---|
| Keystroke to screen | under 16 ms (one frame), p95, on the reference PC |
| Open a page already seen in this session | under 150 ms |
| Open a cold page | under 400 ms |
| Open a database view of 10,000 rows | first rows under 300 ms; scrolling at 60 fps |
| Cell edit shown | immediately (optimistic); confirmed under 1 s |
| Change made elsewhere (AI, phone, another tab) appears | under 500 ms over the live channel |
| Ctrl/⌘K results | under 100 ms for titles; under 300 ms for full text |
| Undo | under 50 ms |

Stage 0 builds a Glass interaction benchmark that measures these in a real browser (Playwright on Chromium;
setting it up in CI is part of stage 0), with the results committed as text each stage.

### 5.3 Look

- **One icon set.** Every glyph in the editor and grid (⋮⋮, ▸, ▦) is replaced with the `ui/icons.ts` set.
- **Theme choice:** system, light or dark, stored per owner (D12).
- **Pages:** a full-width option and a small-text option per page.
- **Page head:**
  - a cover image (from the artifact store);
  - an icon chosen with emoji search or an uploaded image.
- **Database colours** use Notion's option colours, as today.
- **Loading states** are skeletons in the shape of what is coming, never "Loading…".
- **Motion** is under 150 ms and only shows a change of state.

### 5.4 Never lose work

- **Autosave.** Operation-level saves replace whole-page saves (stage 2). The status line shows Saved, Saving, or
  "Offline, will retry".
- **History.** Page history is snapshot every 10 minutes of editing and at every AI change, with restore and a diff
  view.
- **Trash.** One trash covers pages, records, databases and views, with restore for each.
- **Drafts.** If the gateway is unreachable, edits queue in memory and the status line says so. Closing the tab with
  unsaved edits warns first.

### 5.5 Finding anything

- **Ctrl/⌘K searches everything:**
  - pages, records of every type and their pages, databases and views;
  - phones, apps, accounts and identities (names only, never secrets);
  - commands ("New account", "Run routine…").
- **Ranking:** recents first, then fuzzy title matches, then full text.
- **An empty box** shows recents and favourites.
- **The sidebar:** Favourites, Recents, Pages, Databases, and **Cyclone** (the system databases: Accounts, Identities,
  Phones, Cloud phones, Apps, Work, Tasks, Routines).

### 5.6 Secrets in cards, the Notion way

Owner's requirement: account set-ups are remembered with all their details, and their secrets live in the same card.

- **Identity card:** an identity is a record with a page.
  - Its properties hold the details from plan 59: names, birthday, username, email, phone.
  - Its secret fields (password, recovery codes, two-factor seeds) are **secret references**.
- **A secret reference shows as a locked chip.** The chip offers three actions:
  - **Reveal.** Unlocks the vault if needed, shows the value in the browser for 30 seconds, then masks it again.
  - **Copy.** Copies to the clipboard, which clears after 30 seconds (`ui/clipboard.ts`).
  - **Send to phone.** A sealed delivery to one phone for one task (plan 33 C2).
- **Where secrets never go:** search, exports, AI context, metrics or history diffs. History records that a secret
  changed, never its value.
- **Pasting a secret into an ordinary page** is refused, with one line offering "Save it to the vault as a secret field
  of a card", which creates the field in the right place.

### 5.7 The experience acceptance script

Each experience stage ends with the owner (or a stand-in tester, recorded) doing a fixed set of tasks without help.
The time and the errors are recorded. The script grows each stage. A task fails the gate if it needs explaining.

---

## 6. The Notion parity map

Every capability the owner would expect, today's state, and the stage that delivers it.

| Capability | Today | Stage |
|---|---|---|
| Undo and redo for every edit | Missing | 2 |
| Multi-block select, cut, copy, paste with formatting | Missing | 2 |
| Nested blocks, indent and outdent with Tab | Missing | 2 |
| Toggle, code, image and file, bookmark, columns, table of contents | Missing | 2 |
| Links and inline markdown (`**`, `` ` ``, `~~`) | Missing | 2 |
| Floating format toolbar, text and background colour | Missing | 2 |
| Page history with restore and diff | Missing | 2 |
| Duplicate page, "Move to…" picker, sidebar reorder | Partial | 2 |
| Favourites, recents, theme, full width, covers, emoji search | Missing | 2 |
| Live updates on pages, databases and the sidebar | Polling | 1 |
| Full-page databases and "open row as page" | Missing | 3 |
| Optimistic cell edits; virtualised grid at 100,000 rows | Missing | 3 |
| Keyboard grid (arrows, Enter, Tab, copy and paste ranges) | Missing | 3 |
| Column resize and reorder, row reorder, sticky header, frozen first column | Missing or partial | 3 |
| Bulk select and bulk edit | Missing | 3 |
| Row templates; CSV import with column mapping | Missing | 3 |
| Person, files, unique id, created by, secret reference, phone and app reference properties | Missing | 3 |
| Database automations ("when Status changes to X, do Y") | Missing | 13 |
| Charts and dashboards as blocks | Missing | 10 |
| Comments on blocks and records; an inbox for mentions and replies | Missing | 14 |
| Template gallery (pages, databases, app packs) | 4 fixed | 11 |
| Cyclone surfaces as databases (accounts, phones, apps, work) | Separate screens | 4 |
| Search across records, rows and their pages | Pages only | 3 |
| Shortcut sheet | Missing | 2 |

---

## 7. The stages

Every stage lists:
- the goal;
- the platform part;
- the experience part;
- tests;
- acceptance criteria;
- OWED evidence;
- the exit gate;
- a size: **S** is one focused PR, **M** is two or three PRs, **L** is several PRs on one branch with one exit report.

### Stage 0 · Foundations and decisions (M)

**Status (2026-11-21):** runs 0a and 0b built (alpha 156, draft PR; reports `76-stage-reports/s00a.md` and
`s00b.md`). Baseline: 7 of 13 speed budgets met; acceptance script 7 passed, 5 missing. Gate open: D1–D12 await the
owner.

**Goal:** make everything after it cheap to build correctly and possible to measure.

**Platform:**
- Decision records D1–D12, answered by the owner.
- A migration framework: numbered migrations, a schema version in the file, and an upgrade test on a copy of the
  previous database.
- A repository interface in front of SQL, with a guard test that fails on raw SQL outside it.
- A seeded data generator at the D8 scale, and a gateway benchmark (p50, p95 and p99 per typical query).
- A threat model: assets, actors, trust boundaries and abuse cases, with each threat mapped to a control.
- A test-data policy: synthetic fixtures only, and a synthetic secret corpus used by every guard.

**Experience:**
- A Glass interaction benchmark in a real browser that measures every §5.2 budget against today's code. This is the
  baseline.
- A design audit: every glyph, colour and spacing value outside the tokens is listed for removal.
- The experience acceptance script, first version (§5.7), run against today's Glass and timed.

**Acceptance:** owner answers recorded; both benchmarks committed as text; guards in CI; the upgrade test passes on a
copy of the current database.

**Gate:** all of the above.

### Stage 1 · The event ledger and the live channel (L)

**Status (2026-11-22): gate met.** Run 1a built the ledger (`76-stage-reports/s01a.md`): 100,000-event replay
matches, append p95 0.3 ms. Run 1b built the live channel (`76-stage-reports/s01b.md`): a change made elsewhere shows in
364 ms (it took 2,306 ms), and the workspace has no polling timers left.

**Goal:** one source of truth for every fact, pushed live to Glass.

**Platform:**
- An append-only event table: id, type, schema version, actor, subject, time occurred, time recorded, source,
  validated payload, a unique idempotency key, and a hash chained to the previous event.
- Event types for every existing domain:
  - accounts and identities;
  - phones and health facts;
  - tasks, dispatch, progress and outcomes;
  - runs;
  - approvals and leases;
  - page and record changes;
  - owner actions.
- An idempotent backfill of today's state, marked `source = backfill`.
- Projections v1: accounts, phones, tasks. A replay tool that rebuilds them from zero and checks equality.
- **The live channel:** one authenticated WebSocket from the gateway to Glass.
  - It carries change events for the pages, records, views and sidebar the owner has open.
  - It reuses the bearer subprotocol Fleet already uses (`fleetView.ts:792`).
  - On reconnect it resumes from the last event id, so no update is missed.

**Experience:**
- Polling is removed from pages, databases, the sidebar and Home. Changes from the AI, a phone or another tab appear in
  under 500 ms.
- The connection state shows once, quietly: "Reconnecting…" only after 3 seconds offline.

**Tests:**
- Hash-chain tamper detection.
- Idempotency.
- Replay equality on 100,000 generated events.
- Schema validation for every type.
- Payloads with secret-shaped values are refused.
- Live channel: resume after a drop, ordering, back-pressure (a slow tab cannot stall the gateway), authorisation per
  subscription.

**Acceptance:**
- Replay equality holds.
- Append p95 is under 5 ms.
- A live update lands in under 500 ms (measured in the browser benchmark).
- Polling timers in the workspace are gone, with a guard that counts them.

**Gate:** tests and benchmarks green, numbers recorded.

### Stage 2 · The editor, rebuilt to Notion standard (L, several runs)

**Goal:** writing in Glass feels like writing in Notion. Nothing is lost, everything can be undone, every common block
exists.

**Foundation (D11):** the default is a hand-built editor inside the Glass rules. The work is split into these runs,
each with its own exit report.

**Status (2026-11-22):** run 2a built (`76-stage-reports/s02a.md`): pages are trees of versioned blocks, every edit is
an operation applied per page with per-block versions, the whole-page save is retired, and migration 4 converted every
page with no loss. Runs 2b–2e not started.

**Run 2a · Document model and operations.**
- Blocks gain `children`, so nesting is possible.
- Edits become **operations**:
  - insert, delete, move, set type and set properties for blocks;
  - text insert and delete, and mark changes, within a block.
- The gateway applies operations transactionally per page, with a per-block version.
- The existing three-way merge stays as the fallback.
- The whole-page save is retired.
- A migration converts every existing page with no loss, checked block by block.

**Run 2b · Undo, redo and selection.**
- An undo stack of inverse operations, per page and per session. It covers split, join, move, delete, turn into, paste,
  property change and AI-applied edits.
- Ctrl/⌘Z and Ctrl/⌘Shift+Z work.
- Selection across blocks: one editing surface manages the selection, while each block keeps its own render.
- Esc selects the current block. Shift+arrows and shift-click extend the selection. Selected blocks can be deleted,
  moved, duplicated, turned into another type or dragged as a group.

**Run 2c · Clipboard.**
- Copy writes the internal block format plus plain text and a safe HTML rendering. The HTML is built with DOM APIs,
  never `innerHTML`.
- Paste reads the internal format first, then HTML through `DOMParser` into typed spans and blocks, then markdown,
  then plain text.
- Pasting a URL on a selection makes a link. Pasting a URL alone offers a bookmark or a mention.

**Run 2d · Blocks and marks.**
- New blocks: toggle (and toggle headings), code (with a language label and a copy button), image and file (stored as
  artifacts, plan 33 C3), bookmark, columns (2–4), table of contents, and a simple table.
- New marks: link, underline, text colour and background colour.
- Inline markdown: `**`, `*`, `` ` ``, `~~`, plus `--` and `->` autoreplace.
- A floating format toolbar on selection.
- Tab and Shift+Tab indent and outdent.
- Every new type is added to the gateway allowlist and to the secret refusal check.

**Run 2e · Pages around the editor.**
- Page history: snapshots every 10 minutes of editing and at every AI change, with a diff view and restore.
- Duplicate page; a "Move to…" picker; sidebar reorder among siblings (sending `before`).
- Favourites and recents (D12).
- Full width and small text per page; covers; emoji search for icons.
- Theme choice; the shortcut sheet (Ctrl/⌘/); skeleton loading.
- Glyphs replaced by the icon set.

**Tests:**
- Operation round trips.
- Property tests: random operation sequences, undo of all of them returns the original document.
- Conflict tests: two clients, interleaved operations.
- Paste fixtures from real HTML and markdown exports (synthetic content).
- The migration test on every existing page.
- Secret refusal for every new block and mark.
- No `innerHTML` (guard).

**Acceptance:**
- Undo restores the exact document after any sequence of 1,000 random operations.
- Keystroke latency meets §5.2 on a 1,000-block page.
- Every existing page migrates with no lost block.
- The acceptance script's writing tasks pass with no help.

**Gate:** all runs reported; benchmarks and acceptance recorded.

### Stage 3 · Databases as first-class, at speed (L, several runs)

**Goal:** any custom data structure, with a grid that is as fast and keyboard-friendly as Notion's at 100,000 rows.

**Run 3a · Schema registry.**
- Entity types with fields, validation, relations, versions and migrations, including a dry run that reports the rows
  it will change.
- Tables become views over entity types. Every existing table migrates with a row-by-row comparison.
- New property types:
  - person (the owner today; scoped for D2);
  - files and media;
  - unique id with a prefix;
  - created by and edited by;
  - **secret reference** (§5.6);
  - phone reference, app reference and account reference.

**Run 3b · Full-page databases and peek.**
- A database route, and databases in the sidebar.
- "Open as page" for every row; Ctrl/⌘-click opens full page; peek on click everywhere.
- Linked views of a database inside any page, as today.

**Run 3c · Grid speed.**
- Virtualised rows and columns.
- Paged server queries with a cursor, so only visible rows are fetched.
- Optimistic cell edits that reconcile over the live channel.
- A sticky header and a frozen first column.

**Run 3d · Grid ergonomics.**
- Keyboard navigation: arrows, Enter to edit, Tab to move, Esc to leave.
- Copy and paste of cell ranges.
- Column resize by drag; column and row reorder by drag.
- Bulk select and bulk edit, through the dry-run rule for system databases.
- Row templates.
- CSV import with column mapping and a preview.
- Restore from trash for tables and rows, from the UI.
- Reordering cards within a board column; timeline drag to move and resize.

**Run 3e · Search.**
- Ctrl/⌘K covers records and their pages.
- The full-text index (SQLite FTS5) covers pages, records and comments, but never secret references or personal
  columns.

**Tests:**
- Type migrations with dry-run fidelity.
- Every property type, with adversarial input.
- Virtualisation correctness: no gaps or duplicates while scrolling and editing.
- Optimistic edit conflicts.
- CSV import mapping.
- The secret-reference guard: no API ever returns a value.

**Acceptance:**
- 100,000 rows scroll at 60 fps, and the first rows show in under 300 ms.
- A cell edit shows immediately.
- Every existing table migrates with no lost cell.
- The acceptance script's database tasks pass.

**Gate:** runs reported; numbers recorded.

### Stage 4 · Cyclone's surfaces become databases (L)

**Goal:** accounts, identities, phones, cloud phones, apps, tasks, routines and work are **system databases** in the
workspace. They are filtered, grouped, linked, mentioned and given pages like any other database.

**Platform:**
- System entity types backed by the ledger projections from stage 1.
- Writes to system fields go through their domain rules (for example an account's binding changes only through its
  owner action). The rest of a record (custom properties, its page) is free.
- The owner can add custom properties to a system type ("Niche", "Campaign", "Client") without touching its system
  fields.

**Experience:**
- The bespoke `cc-` screens for Accounts, Identities, Fleet, Health, Tasks and Routines are replaced by default views
  over the system databases.
- Specialised actions stay as buttons and peeks, not separate screens.
- The Vault stays its own isolated unlock screen for crypto reasons. Inside records, secrets appear as secret-reference
  chips (§5.6).
- Default views ship as templates: "Accounts by app", "Accounts needing a person", "Phones by health", "Today's work".

**Tests:**
- Domain rules hold through every edit path; for example a binding cannot change through a bulk edit.
- Custom properties never collide with system fields.
- Projections and records agree after replay.

**Acceptance:**
- Every Command Center surface has a database view.
- The bespoke screens are retired, with their behaviour covered by tests.
- The acceptance script's account and fleet tasks pass.

**Gate:** as above.

### Stage 5 · Accounts as a lifecycle (L)

**Goal:** for any account, the platform knows its state, binding, owner, health and history.

**Platform:**
- **The state machine.**
  - Main path: `draft → ready → creating → created → active`.
  - Branches: `paused`, `needs_person`, `locked`, `suspected_restriction`, `retired`, `deleted_in_trash`.
  - Every transition writes an event with its reason.
- **Binding.** Each account belongs to one app on one phone profile. The binding history is kept.
- **Duplicates.** The same service and handle, or the same email or phone across identities, raises a finding.
  Matching uses normalised keys and never logs values.
- **Ownership.** Mine, company, client or test; required.
- **Credentials.** A per-account credential policy: the vault item, when it was last set, and an owner reminder. The
  platform never changes a password itself (plan 59).
- **Account health.** Derived from events: last good use, failures, needs-person waits, restriction signals and age.
  Every value links to its events.
- **Pacing and caps.** Owner-set defaults per service (D3), with the warning shown in the experience.

**Experience:**
- The account page shows the identity card (§5.6), the status with its reason, the health with sources, the timeline,
  and "Open on phone" (switch profile and launch; the approval rules are unchanged).
- Board views by lifecycle state, with drag allowed only for legal transitions. An illegal drop explains why in one
  line.

**Tests:**
- Every transition, legal and illegal.
- Binding history.
- Duplicate detection on 5,000 synthetic accounts, including near-duplicates.
- The no-secret-in-records guard.
- Health replay.

**Acceptance:** every transition is tested; duplicates are all found with no false positive on the seeded set; health
reproduces exactly from events.

**OWED:** "Open on phone" on a real Pixel.

**Gate:** tests green; OWED recorded.

### Stage 6 · Work done inside accounts (L)

**Goal:** for each account, what was done, when, by which phone and task, with proof, and what is still open.

**Platform:**
- **Work items:** a status (`planned`, `running`, `waiting_for_person`, `done`, `failed`, `skipped`), a due date and a
  proof.
- **No proof, no `done`.** A `done` needs evidence: a screen check, an artifact hash, or a confirmed server result.
  Evidence is stored as a reference with a redacted label.
- **Checklists** per app or flow, applied to accounts. Progress is derived from work items.
- **Failure analysis per account:** the stage where a run failed, from the run record, linked to its finding.

**Experience:**
- The account page gets a checklist block and a timeline.
- Every item opens its events and proof in a peek.
- A "Work" database across all accounts, with views for Today, Overdue, Waiting for a person, and Failed this week.

**Tests:**
- Proof required on every path.
- Redaction of evidence labels.
- Checklist replay on 5,000 accounts.
- Timeline ordering with out-of-order events.

**Acceptance:** no `done` without proof (a test per path); progress reproduces exactly.

**OWED:** one real checklist driven by a phone on a test account.

**Gate:** as above.

### Stage 7 · The fleet as data (L)

**Goal:** every phone and cloud phone is a record with history, capacity, assignment and cost, and bulk actions are
safe.

**Platform:**
- Phone health facts as time series: full resolution for 7 days, hourly aggregates for 2 years.
- Capacity: free, busy, quarantined or down, by profile, app and provider.
- The cloud phone lifecycle (rented, connected, expiring, renewed, released), with cost per day.
- **Bulk actions:**
  1. A typed selection.
  2. An action from a fixed list.
  3. A dry run listing every item and its effect.
  4. The owner confirms.
- Irreversible actions (wipe, delete, purchase) need a typed confirmation and are never selected by default.
- Budgets per day, owner-set, with warnings.

**Experience:**
- The Phones database, with a health sparkline per row and charts in each phone's page.
- A capacity dashboard.
- Bulk actions from the grid selection (stage 3), with the dry run shown in a side panel.
- Live status over the channel (stage 1).

**Tests:**
- Aggregates equal raw data.
- Each dry run equals its real run (checked by diff).
- Irreversible actions are refused without typed confirmation.
- Cost arithmetic.

**Acceptance:**
- A 1,000-phone dry run returns within budget.
- No bulk action runs without its dry run.

**OWED:** three physical phones and one cloud phone.

**Gate:** as above.

### Stage 8 · Automation and orchestration (L)

**Goal:** routines, buttons and bulk actions run on a durable engine that never repeats a consequential action.

**Platform:**
- **Jobs:** an idempotency key, dependencies, bounded retries with backoff and jitter, a timeout, a cancel rule and a
  compensating action where one exists.
- **Unknown outcomes:** a consequential action whose outcome is unknown becomes `needs_check`, never a second attempt.
- **Pacing and caps** apply per service, phone and account.
- **One hold at a time:** one consequential task per phone, and one job per account (plan 33).
- **Routines** become records: a schedule, a selection, a template, and a mapping of results into work items.

**Experience:**
- A Jobs database with its state, retries and a dependency graph.
- Each job's page shows its event trail.
- Buttons in any database run through the engine, with a toast that links to the job.

**Tests:**
- A repeated key never repeats the action.
- Failure injected at every step.
- Unknown-outcome handling.
- A 72-hour soak.

**Acceptance:** zero repeated consequential actions in the soak, checked against the ledger.

**OWED:** a real routine on one test account and phone.

**Gate:** as above.

### Stage 9 · Cloud fleet completion (M)

**Goal:** a cloud phone is ready to work in one click, inside the plan 56 rules.

**Platform:**
- The VMOS file upload, and the skill-pack chain through the fixed provisioning catalogue.
- Purchases and resets are owner-only, with confirmation.
- DuoPlus follows if D6 says so.

**Experience:**
- "Add cloud phone" is a guided record template.
- Progress shows live in the phone's page.

**Tests:**
- Provisioning catalogue guards: no command comes from a model or a field.
- Lease renewal.
- Cost recording.

**OWED:** a real rental on the owner's account, approved by the owner.

**Gate:** as above.

### Stage 10 · Analytics and dashboards (L)

**Goal:** deterministic analytics over the ledger, shown as blocks in any page, with every number traced to its source.

**Platform:**
- **A metric registry:** each metric has an id, a version, a written definition, its events, a window rule and a unit.
- **A typed query grammar** with limits and a cost estimate shown before a query runs.
- **Cohorts and comparisons** with intervals, reusing Wilson and Newcombe from the Lab.
- **Deterministic anomaly rules.** Each anomaly names its metric, window and events.
- **Exports** carry the metric version and leave personal columns out.

**Experience:**
- New blocks: **chart** (line, bar, stacked bar, number, table) and **metric card**.
- Each is configured from a picker, not code.
- Clicking any number opens a peek with its events.
- Dashboards are ordinary pages.
- Starter dashboards: Account creation, Work this week, Fleet health, Cloud cost.

**Tests:**
- Golden metrics with hand-computed values.
- Grammar fuzzing.
- Seeded anomalies are detected; seeded noise is not.
- Drill-down returns exactly the events behind each number.

**Acceptance:**
- Every metric has a golden test.
- A month of 1,000,000 events a day queries within budget, or is refused with its cost estimate.

**OWED:** 20 real values checked against the owner's records.

**Gate:** as above.

### Stage 11 · App management and packs (L)

**Goal:** every app on every phone is known, and a new kind of app data arrives as a pack, not as platform code.

**Platform:**
- **App records:** package, version, install state, map (plans 04 and 36), skills, supported account types, health.
- **A pack:** a versioned bundle of entity types, sign-up map, checklists, metrics, database templates, page templates
  and skills.
- **Pack validation.** A pack cannot declare a shell, a secret reader or a model key. It is reviewed like code.
- **App history.** The installed inventory per phone, with version changes recorded as events.
- **Map freshness.** A map is marked stale when the app version changes.

**Experience:**
- An Apps database.
- Each app's page shows its accounts, maps, skills, metrics and packs.
- A **template gallery** for pages, databases and packs, with a preview before install.

**Tests:**
- Validation refuses unsafe, malformed and oversized packs.
- Version diffs from replay.
- Staleness.

**Acceptance:** two packs install, work and uninstall in the test suite without a platform release.

**OWED:** one real pack on the owner's Pixel.

**Gate:** as above.

### Stage 12 · Security hardening (L)

**Goal:** a reviewer would sign off on the platform's handling of secrets and personal data.

**Platform:**
- **The key hierarchy**, written down and tested path by path: passphrase, vault key, recovery key, item keys, device
  keys and hive key.
- **Personal columns encrypted at rest.**
- **Windows Hello unlock** (D9), with the passphrase as fallback.
- **Rehearsed operations:** passphrase change, recovery-key rotation and vault reset. The owner runs one recovery drill
  and records it.
- **Encrypted backups**, verified by restoring them into a scratch location.
- **An audit viewer** that shows whether the chain verifies.
- **Retention and deletion** (D7, §11).
- **An independent security review.** Every critical or high finding is resolved before the gate.

**Experience:**
- The vault unlock is a single prompt, offering Hello first.
- Every secret chip follows the §5.6 rules.
- Settings has a "Security" page showing backup age, the last drill, and the audit chain status.

**Tests:**
- Every key path.
- Backup and restore.
- Retention.
- The synthetic secret corpus never appears in logs, metrics, exports, search or AI context.

**Acceptance:** threats mapped to controls and tests; restore reproduces every record; review findings resolved.

**OWED:** the recovery drill and Hello unlock on the owner's PC.

**Gate:** as above.

### Stage 13 · Database automations (M)

**Goal:** the Notion-style "when this happens, do that", on the stage 8 engine.

**Platform:**
- Triggers:
  - a property changes;
  - a record is added;
  - a schedule fires;
  - a work item fails;
  - an anomaly fires.
- Actions:
  - set properties;
  - add a record;
  - run a routine, skill or prompt on a phone (the approval rules are unchanged);
  - notify.
- Every automation run is a job, with a trail.
- Loops are detected and stopped, and the automation is paused with its reason.

**Experience:** an automation editor on each database, a history of runs, and pause or resume.

**Tests:**
- Each trigger and each action.
- Loop detection.
- Consequential actions respect approvals.

**Acceptance:** no automation can bypass an approval (a guard test).

**Gate:** as above.

### Stage 14 · Verification desk, comments and inbox (M)

**Goal:**
- A person handles what only a person can do.
- Comments make the workspace a place to work.
- One inbox gathers all of it.

**Platform:**
- **The verification queue** (plan 43 T8): codes, CAPTCHAs, selfies, IDs and calls.
  - Jobs wait without blocking other work.
  - The person's answer is delivered sealed to one phone for one step.
  - The platform never solves or bypasses a challenge.
- **Comments** on blocks and on records, with replies, resolve, and `@` mentions.
- **One inbox** for approvals, verifications, comments, mentions and alerts. Batch answers are allowed only for
  identical low-risk items; consequential approvals are never batched.

**Experience:**
- A comment bubble on hovered blocks.
- A comments side panel.
- An inbox in the sidebar with unread counts.
- Verification items show exactly what the person must do, with one action.

**Tests:**
- No challenge resolves without a person (a guard over the step types).
- Sealed answer delivery.
- Comment permissions.
- The batch rule.

**OWED:** one real verification answered by the owner on a test account.

**Gate:** as above.

### Stage 15 · Intelligence layer (M)

**Goal:** ask the platform anything and get an answer with sources. Get proposals, never surprises.

**Platform:**
- **Grounded answers** from typed queries and metrics. Every claim cites event ids or metric values. An unsupported
  claim is labelled as opinion.
- **Proposals** (bulk actions, routine changes, pack updates) carry a dry run. Nothing applies without the owner.
- **Anomaly explanations** with citations.
- **Bounded, reproducible context.** A daily spend cap; the cost of each answer recorded as an event.
- **A golden set** of 100 questions, run before each release of this layer.
- **Prompt-injection tests** built from hostile event payloads.

**Experience:**
- "Ask about this" in any page, database or record. The answer appears in the Ask AI panel with clickable citations.
- Proposals arrive as apply/discard cards, as today.

**Tests:**
- Citations are correct on the golden set.
- Questions that need secrets are refused.
- No proposal applies itself.
- Spend caps hold.
- Injection resistance.

**Acceptance:**
- 100 of 100 golden answers cite correct sources, or the failures are fixed before the gate.
- A guard proves the context builder cannot reach the vault, secrets or screen content.

**OWED:** the live run on the owner's model.

**Gate:** as above.

### Stage 16 · Scale, reliability, live acceptance and release (L, owner-assisted)

**Goal:** the platform holds its targets, works on real devices and accounts, and the owner can run it alone.

**Scale and reliability:**
- Load tests at 1× and 2× the D8 targets, with the §12 service levels measured.
- Fault injection:
  - a gateway restart mid-job;
  - a full disk;
  - a phone dropping mid-task;
  - a cloud phone expiring;
  - a provider outage;
  - the live channel dropping during an edit.
- Each fault has an expected outcome and a test.
- A 72-hour soak.
- A guard that keeps secret and personal values out of the log schema.

**Live acceptance** (owner's devices and chosen test accounts):
- Three phones and one cloud phone.
- An account lifecycle end to end, including a verification.
- A checklist with proof.
- A bulk action with its dry run.
- The plan 75 Lab gate table measured on the Pixel.

**Release:**
- A signed installer (D10).
- Owner documentation, and runbooks for restore, rebuild, key rotation, quarantine and verification.
- Every capability claim links to its test or evidence.
- The experience acceptance script, final version: every task passes with no help, timed.

**Gate:**
- Service levels met at 1×.
- At 2×, the platform degrades without corrupting data (replay after load).
- Every live item passed, or failed with a finding.
- A clean install on a second Windows account passes the runbook.
- The owner signs off.

---

## 8. How each coding run is executed

1. **Read.** This plan, the stage, its decisions, and the code it touches.
2. **Branch.** `platform/s<nn>[<run>]-<slug>` from the dev tip.
3. **Scope.** List the deliverables and tick each one. Anything else goes to §13.
4. **Design note.** Data model, operations or API, the experience (sketched as text), threats, tests. Written before
   any code.
5. **Tests first** for every §2 rule and §5 budget the run touches. A test that cannot fail is not a test.
6. **Guards.** Add one for each new invariant.
7. **Validate in order, stopping at the first failure:**
   - `python -m pytest apps/device-gateway/tests -q`;
   - Glass `npm test`, `npm run build`, `python scripts/ci/glass_guard.py`;
   - `python scripts/ci/release_versions.py --check` and `python scripts/ci/mobile_product_guard.py`;
   - the gateway benchmark and the Glass interaction benchmark, with the output committed;
   - the stage's acceptance tests, by name;
   - the experience acceptance script for the stage's tasks.
8. **Self-review** the diff against §2 and §5, rule by rule, in writing.
9. **Exit report** at `Cyclone V5 plan/76-stage-reports/s<nn>.md`: what was built, every acceptance value, the OWED
   items, the risks accepted, the decisions taken, and the before-and-after benchmark numbers.
10. **Pull request.** A draft into dev, using the template, with the exit report linked. Merging is the owner's call,
    because a merge publishes.
11. **Update this plan.** Set the stage's status line to built, built with OWED items, or failed, with the date.

**Stop rules:**
- An acceptance criterion that cannot be met in scope stops the run and goes to the owner with the evidence.
- A §2 rule that would break stops the run.
- A test is never marked skipped to move on.

---

## 9. Quality bar, measured

| Measure | Bar |
|---|---|
| Correctness | Every acceptance criterion has a test that fails before its change |
| Integrity | Replay equality and chain verification hold after every stage |
| Security | The synthetic secret corpus never appears outside the vault (guards in CI) |
| Speed | §5.2 budgets in the browser; §12 service levels on the gateway |
| Feel | The experience acceptance script passes with no help, and gets faster stage by stage |
| Never lose work | Undo, history and trash cover every edit; a failed save is never silent |
| Honesty | Every claim links to a test or recorded evidence; OWED items are listed |
| Operability | Backups verified by restore; a runbook for every owner operation |
| Review | A written self-review every run; independent reviews for stages 12 and 15 |

---

## 10. Risks

| Risk | Containment |
|---|---|
| The hand-built editor takes longer than planned (D11) | Stage 2 is split into five runs with their own gates. If run 2b misses its gate twice, D11 is reopened with the evidence. |
| Moving from whole-page saves to operations loses data | Block-by-block migration checks, the three-way merge kept as fallback, history snapshots |
| A big grid stays slow in plain TypeScript | Virtualisation and cursor paging in 3c, measured against §5.2 before 3d starts |
| Accounts at volume cause bans or legal exposure (D3, D4) | Owner-set defaults with warnings; exposure shown, and accepted in writing |
| Secrets leak through search, analytics, logs or the model | Zero-knowledge rule, the secret-reference type, guards, the synthetic corpus |
| The model invents facts | Grounded answers, citations, golden set, refusal and injection tests |
| Retiring bespoke screens drops a behaviour | Stage 4 covers every retired behaviour with a test before the screen goes |
| Costs run away (cloud, model) | Budgets, dry runs, owner approval, recorded spend |
| Device behaviour differs from simulation | OWED items; stage 16 on the owner's devices |
| Scope creep | One point per run, §13 backlog, an out-of-scope list in every stage |

---

## 11. Retention (proposed; owner confirms D7)

| Data | Kept | Then |
|---|---|---|
| Phone health facts | Full resolution, 7 days | Hourly aggregates, 2 years |
| Events | Full history | Personal values are vault references, never copies |
| Page history | Every snapshot, 90 days | Daily snapshots, 1 year |
| Lab screen copies | 7 days | Deleted, deletion logged |
| Owner-mission screen copies | Never saved | — |
| Audit chain | Full history | Never deleted; verified at every start |
| Backups | 30 daily, 12 monthly | Older deleted after a restore check |

---

## 12. Scale and service levels (proposed; measured in stage 16)

| Target | Value |
|---|---|
| Phones | 1,000 |
| Accounts | 5,000 |
| Rows per database | 100,000 |
| Events per day | 1,000,000 |
| Dashboard query p95 | under 300 ms |
| Append p99 | under 20 ms |
| Job start delay p95 | under 5 s |
| Projection freshness | under 10 s behind the ledger |
| Live update to Glass | under 500 ms |
| Backup age | under 24 h |

---

## 13. Backlog (out of these stages on purpose)

- Teams and roles beyond one owner (D2 keeps the door open).
- A hosted multi-owner product (plan 35 C6, depends on D1).
- Email, and routines triggered by phone notifications (plan 33 §12.8).
- Skill Studio (plan 46).
- Real-time co-editing between people (operations in stage 2 make it possible later).
- A mobile-native workspace.
- Cloud providers beyond VMOS and DuoPlus.

Each needs its own plan.

---

## 14. Glossary

- **Ledger:** the append-only, hash-chained events; the source of truth.
- **Projection:** a rebuildable read model of the ledger.
- **System database:** a database whose type Cyclone defines (accounts, phones, apps…) and the owner can extend.
- **Secret reference:** a field that holds a vault item id. It shows as a locked chip, and only the browser can open it.
- **Operation:** one small, invertible edit to a page or record; the unit of saving, syncing and undo.
- **Peek:** the right-side panel a record or page opens into.
- **Proof:** the evidence that makes a work item done.
- **OWED:** evidence the gate needs that only a real device, account or person can give.
- **Pack:** a versioned bundle of an app's types, maps, checklists, metrics, templates and skills.
