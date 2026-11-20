# 76 · The Cyclone platform: a multi-stage plan for fleet, account, work and data management

**Status:** plan of record, written 2026-11-20 at dev alpha 155. It builds on plans 33 (Command Center), 43 (tables and
account setup), 44 and 56 (cloud fleet), 54–55 (Glass Manager), 59 (identities and hive), 61 (Safe), 71 (prove it did
it) and 75 (every tier, one spine). Nothing in this plan has been built. Every "today" fact below was checked in code on
the dev branch, with the file named.

**The owner's ask (2026-11-20):** a platform management system that manages fleet data very well, analyses accounts,
shows the status of work done inside each account, and offers high flexibility for any kind of app management and any
custom data structure. Built as one coding run per major point, with full attention and no skipped steps. The quality
bar is a billion-dollar system: correct, secure, measured, and honest about what is not yet proven.

---

## 0. How to read this plan

- **Stages are sequential gates, not a menu.** A stage starts only when the previous stage's exit gate has passed, with
  its evidence recorded. A run may not borrow from a later stage to finish an earlier one.
- **One major point per run.** A run is one branch, one draft pull request, one stage, and one exit report. The
  stage's own list is the scope; anything else goes to the backlog section (§11), not into the run.
- **"Done" means the exit gate passed.** Code that merges but fails its gate is not done. Physical or live evidence the
  gate needs and this environment cannot provide is marked **OWED**, never "passed".
- **Every stage keeps the rules in §2.** They are not re-argued per stage.

---

## 1. Baseline: what exists today (verified)

| Area | What is there | Where | What is missing for this plan |
|---|---|---|---|
| Storage | One SQLite file for the Command Center: accounts, routines, tasks, runs, approvals, audit, under one lock and a hash-chained audit | `command/center.py` (`sqlite3`, `CREATE TABLE`), `command/vault.py` | No migration framework beyond `CREATE TABLE IF NOT EXISTS`; no event ledger; no time-series tables; no full-text index |
| Custom data | Tables: typed properties, options, rows, views (table, board, timeline, calendar, gallery, list), relations (two-way), rollups (10 kinds), formulas (own parser, no `eval`), row history, undo, trash, CSV export. Limits: 200 tables, 60 properties, 10,000 rows, 30 views per table | `command/tables.py`, `command/formula.py` | Not generalised into entity types with schemas and versions; personal columns are marked, not encrypted at rest |
| Pages | Blocks, `@` mentions, versioned saves that refuse stale writes, backlinks, limits of 5,000 pages and 1,000 blocks each | `command/pages.py`, `apps/glass/src/workspace/editor.ts` | Pages cannot hold analytics queries or live dashboards over arbitrary data |
| Accounts | Phones → apps → accounts; sign-up learned as a map; create-accounts batch with pause and cancel. Owner decision 2026-09-30: no pre-send checks, app block list or pacing caps "for now" | `command/signup.py`, `mind/signup/`, `pages/createAccounts.ts` | No account lifecycle state machine; no duplicate detection; no account health; no credential rotation policy |
| Identities | Login plus identity details in encrypted vault items, bound to one profile, 30-day trash, "not bound" state kept | `command/identities.py` (`TRASH_DAYS = 30`), `identity/IdentityBook.kt` | Built as code; release status to confirm against release notes (plan 59 says unreleased) |
| Vault | Zero-knowledge: browser AES-256-GCM, passphrase PBKDF2, recovery key, per-item keys, audit chain | `command/vault.py` | No Windows Hello or DPAPI unlock for the Command Center vault (0 hits) |
| Sealed delivery | One secret to one phone for one task, single-use, opened only in the phone's Android Keystore | `command/delivery.py`, `secrets/Hpke.kt`, `secrets/DeviceKey.kt` | Verified on a phone: OWED |
| Fleet, many phones | One command to many phones: missions, dispatch, partial failure, pause, exclude, rollout, retry, CSV export | `desktop_runtime/fleet_orchestrator.py` (`/v1/fleet/...`), `fleet_store.py` | Fleet data is not a queryable model; no capacity planning; no bulk-operation dry run |
| Phone health | Heartbeat facts, quarantine after 3 infrastructure failures, alerts with 6-per-hour notify cap, 7-day success window (`SUCCESS_MIN_RUNS = 5`) | `command/health.py` | No history beyond the window; email alerts not built (0 SMTP code) |
| Cloud phones | Accounts, rent, renew, power, backup and restore routes; VMOS signing; keeper loop for remote ADB | `cloud_fleet/`, `vmos/`, `/v1/cloud/...` | File upload (`uploadFileV3`) and skill pack not built (0 hits) |
| Work status | Tasks and routines in the Command Center; results; Owner Moments | `command/center.py`, `command/steps.py` | No per-account timeline of what was done, with evidence |
| Analytics | Lab statistics only: Wilson intervals, Fisher exact, comparisons | `lab/stats.py`, `tools/cyclone-testbench/.../research.py` | Nothing for accounts, fleet, tasks or cost over time |
| Verification | Mind verifies on the phone; the Lab judges from phone state | `lab/verdict.py` | The verification desk for human checks (T8) is not built (0 hits) |
| Local screen copies | Lab missions keep a scrubbed local copy of what they see (alpha 153) | `mind/lab/` (recording sink) | Retention and deletion policy not written |
| AI | Glass Manager R1–R10 (release notes exist for alphas 112–117; plan headers still say R6–R10 planned) | `command/agent/`, `apps/glass/src/manager/` | Analysis and narrative over the ledger not built |

**Honest measure of today's state:** the pieces are real and substantial. None of the account or fleet workflows has been
run end to end on a real phone or a real service, and the platform has no analytics beyond the Lab. That is the gap this
plan closes.

---

## 2. Rules that hold at every stage

These are inherited from `AGENTS.md`, plans 33, 59 and 61, and the owner's decisions. A stage that breaks one is not done.

1. **Zero knowledge for secrets.** The gateway, every model, every log and every export see ciphertext or slot names
   only. A secret value never enters an analytics table, a search index, a metric label or a diagnostic.
2. **Approvals stay human.** Pay, send, delete, permission and sign-in on a phone keep their gates. Bulk operations
   create proposals; the owner applies them. The fleet layer never answers an approval and never holds a model key.
3. **Models read the ledger, never the vault.** The AI analysis layer (stage 11) is owner-configured, reads bounded
   summaries with event ids, and returns citations. It cannot write, cannot call tools that change state, and cannot
   read secrets, passwords or raw screen content.
4. **Every number has a source.** Every metric shown in Glass links to the events that produced it. A metric without a
   source is not shown.
5. **Deleting a profile never deletes identities; deleting an account never touches the real account.** (Plan 59.)
6. **Physical honesty.** Every stage that touches a phone, a cloud phone or a real service states what is UNVERIFIED,
   and the exit report lists the OWED evidence. No gate passes on simulation alone when the stage claims a device
   behaviour.
7. **No generic shell, no arbitrary path, no arbitrary query.** Analytics and tables use typed queries with a fixed
   grammar, row and time limits, and no `eval`.
8. **Migrations are forward-only and tested.** Every schema change ships with a migration, a rollback note and a test
   that opens a copy of the previous version's database.
9. **Scale targets are measured, not assumed** (§12).
10. **Commits, PRs and code carry no model identifiers,** and secrets never go into the repository.

---

## 3. Owner decisions needed before stage 0 closes

These change the design. Each has a recommended default, which the plan uses unless the owner changes it.

| # | Decision | Default in this plan | Why it matters |
|---|---|---|---|
| D1 | Storage engine: keep the single SQLite file, or move the platform store to hosted Postgres | Keep SQLite (WAL, one writer) for the single-owner PC platform; design the store behind a repository interface so a hosted store is a later swap. Revisit at C6. | Scale, backups, and whether a hosted multi-owner product is wanted |
| D2 | Multi-user: is this one owner, or a team with roles | One owner, but every record carries an actor and an access scope so a second user is possible later | Access control design |
| D3 | Pacing caps and block lists for account creation: reinstate as owner-set defaults | Reinstate as **defaults the owner can change**, with a clear warning when they are lowered | Platform bans, legal and privacy exposure, and the owner's 2026-09-30 decision |
| D4 | Which services accounts may be created on, and which are blocked | Owner-maintained allow list; the platform blocks nothing by default | Terms-of-service risk is the owner's to accept, with the platform making it visible |
| D5 | Analysis model for stage 11 | Owner-configured OpenRouter model, the same settings as today's Ask AI | Cost and key handling |
| D6 | Cloud provider priority | VMOS first, DuoPlus second (as plan 56) | Stage 5 and stage 13 scope |
| D7 | Screen-copy retention for Lab missions (alpha 153) | Delete after 7 days or on request; never for owner missions | Privacy |
| D8 | Scale design target | 1,000 phones, 1,000,000 events a day, 10 years of retained aggregates, 5,000 accounts | Stage 12 measures against it |
| D9 | Unlock for the Command Center vault on Windows | Windows Hello plus the passphrase as fallback | Stage 8 |
| D10 | Signed Windows installer | Owner obtains a code-signing certificate before stage 14 | Stage 14 |

---

## 4. Target system (what "done" looks like)

The platform is one ledger with views on top of it. Everything the owner cares about is either an event in the ledger
or a schema-defined record that events refer to.

```
              ┌────────────────────────── Glass (browser, owner only) ───────────────────────────┐
              │  Pages · Tables · Dashboards · Accounts · Fleet · Work timeline · Approvals · AI  │
              └──────────────────────────────────────────┬────────────────────────────────────────┘
                                                         │ typed queries, actions as proposals
   ┌─────────────────────────────────────── Gateway (PC) ─┴──────────────────────────────────────────┐
   │  Schema registry ── Entity records ── Relations ── Vault refs (ciphertext only)                   │
   │  Event ledger (append-only, idempotent, hash-chained)  ◄── phones, cloud phones, tasks, runs, UI  │
   │  Projections: accounts, fleet, work, metrics (rebuilt from the ledger, versioned)                  │
   │  Analytics engine (deterministic, source-linked)  ·  Jobs engine (durable, retries, idempotency)   │
   │  AI analysis (owner-configured, read-only, cites event ids) · Audit chain · Backups                 │
   └──────────────────────────────────────────┬──────────────────────────────────────────────────────┘
                                              │ typed contracts, sealed secrets
                              Phones (Cyclone Mind) · cloud phones (VMOS, DuoPlus) · app packs
```

**Core concepts**

- **Entity type:** a named kind of record with a schema (typed fields, required flags, validation rules, a version, an
  owner-visible description). Examples: `account`, `phone`, `app`, `task`, `run`, `identity`, `cloud_phone`, plus any
  custom type the owner defines.
- **Event:** an immutable fact with a type, an actor, a time, a subject (entity id), a payload that matches the type's
  schema, a source (phone, run, owner, system), and an idempotency key.
- **Projection:** a derived table or index rebuilt from events (for example "the status of account X" or "the success
  rate of app Y on phone Z over 30 days"). Projections are versioned; a projection can be dropped and rebuilt.
- **Work item:** a task, a run or a checklist step, with its outcome and the evidence that proves it (a screen check,
  a Lab check, a file hash). Plan 71's rule holds: "done" needs proof.
- **Metric:** a named, versioned calculation over events with a stated definition, a time window and a source link.
- **View:** a saved, typed query over entities, events or metrics, shown as a table, board, timeline, chart or dashboard.

---

## 5. Stages

Each stage lists: goal, why now, scope, out of scope, deliverables, data and API, tests, acceptance criteria, evidence
owed, exit gate, and the run size. Run size is a judgement: S (one focused PR), M (two to three PRs in one run), L (a
run of several PRs with a shared branch and a single exit report).

### Stage 0 · Foundations and decisions (size M)

**Goal:** make the next twelve stages cheap to build correctly.

**Scope:**
- Decision records for D1–D10 (§3), signed off by the owner before stage 1 starts.
- Migration framework for the Command Center store: a numbered migration table, forward-only migrations, a test that
  upgrades a copy of the previous database, and a schema version written to the file.
- Repository interface in front of the store, so the rest of the gateway talks to repositories, not raw SQL.
- A performance harness: a seeded generator for phones, accounts, tasks and events at the D8 scale, and a benchmark
  script that prints p50, p95 and p99 for each typical query.
- A test-data policy: all fixtures are synthetic; no real account, name, address or secret appears in any fixture.
- A threat model for the platform (assets, actors, trust boundaries, abuse cases), committed as a document.

**Out of scope:** any new user-visible feature.

**Deliverables:** `docs/adr/` decisions (or plan 76 appendices), the migration framework and its tests, the repository
interface, the benchmark harness with a baseline run committed as text, the threat model.

**Acceptance criteria:**
- Migration upgrade test passes on a copy of the current production-shaped database.
- The benchmark runs on the seeded generator and records a baseline for the current store.
- No raw SQL outside the repository layer (a guard test fails the build if it appears elsewhere).
- Every decision D1–D10 has an owner answer recorded.

**Evidence owed:** none (no device behaviour).

**Exit gate:** owner answers recorded; baseline benchmark recorded; guard in CI.

---

### Stage 1 · The event ledger (size L)

**Goal:** every fact about accounts, phones, tasks, runs and owner actions is an event, in one append-only ledger that
projections can be rebuilt from.

**Why now:** account status, fleet history and analytics all need one source of truth. Today the facts live in several
tables, and nothing records the history of a status.

**Scope:**
- Event table: id, type, schema version, actor, subject, occurred-at, recorded-at, source, payload (validated against the
  type's schema), idempotency key (unique), and a hash chained to the previous event.
- Idempotent append: the same key twice returns the same event and writes nothing.
- Event types for the existing domains: account created or changed, identity bound or unbound, phone seen or lost, health
  fact recorded, task created, dispatched, progressed, finished, run recorded, approval asked and answered, lease issued
  and used, audit entry.
- Backfill: a one-time, idempotent import of today's state as events, marked `source = backfill`.
- Projections v1: accounts (current state), phones (current facts), tasks (current status).
- A replay tool that rebuilds projections from zero and checks they match the incremental result.
- Retention policy for events: full history kept; payloads with personal values are stored as references to vault items.

**Out of scope:** analytics, dashboards, new domains.

**Data and API:** `GET /v1/cc/events` (typed filter by subject, type and time, paginated, bounded); no write route
except the internal append path used by the domains.

**Tests:** property tests for the hash chain (any edit breaks verification); idempotency tests (duplicate keys);
replay test (projections from zero equal incremental projections on a generated history of at least 100,000 events);
schema validation tests for every event type; an adversarial test that an event payload with a secret-shaped value is
refused.

**Acceptance criteria:**
- Replay equality on 100,000 generated events.
- Append p95 under 5 ms on the stage-0 baseline machine.
- Any tampering with an event row is detected by chain verification.
- The backfill of the current database is idempotent (running twice changes nothing).

**Evidence owed:** none.

**Exit gate:** chain verification in CI; replay equality test in CI; backfill run recorded on a copy of a real
database owned by the owner (their own copy, never committed).

---

### Stage 2 · Schema registry and custom data types (size L)

**Goal:** the owner can define any entity type they need, with typed fields and validation, and get tables, forms,
views and relations for it without a code change.

**Why now:** "any kind of app management and custom data structure" needs more than the fixed tables of today. Tables
already have typed properties; this stage makes the type itself a first-class, versioned object.

**Scope:**
- Entity type registry: name, description, fields (type, required, default, options, validation), relations to other
  types, a version and a migration path between versions.
- Field types: text, long text, number, money (with currency), date, time, duration, boolean, choice, multi-choice,
  url, email, phone number, relation, rollup, formula, file reference, secret reference (a vault item id only, never a
  value), phone reference, app reference, status (with transitions).
- Validation rules: required, range, pattern (from a fixed set, no regular expressions from the owner that could be
  slow; a bounded matcher), uniqueness within a type, cross-field rules in the formula grammar.
- Migrations between versions of a type: rename, add with default, split, merge, with a dry run that reports affected
  rows before anything changes.
- Tables become views over an entity type, with the same features as today; existing tables migrate automatically
  through a tested migration.
- Glass: a type editor (fields, validation, relations), a generated form for each type, and a migration preview.

**Out of scope:** analytics over the types (stage 6), app-specific packs (stage 7).

**Tests:** round-trip tests for every field type; validation tests including adversarial inputs (very long text,
deep nesting, unicode, control characters); migration tests with a dry run that matches the real run; a test that a
secret reference never resolves to a value in any API response.

**Acceptance criteria:**
- Existing tables migrate with no lost cell, checked by a row-by-row comparison.
- A type with 60 fields, 100,000 rows and 5 relations renders its table view within the stage-0 p95 target.
- A migration dry run reports exactly the rows a real run changes (checked by diff).

**Evidence owed:** none.

**Exit gate:** migration of every existing table verified; dry-run fidelity test; performance target met on the
generator.

---

### Stage 3 · Accounts as a lifecycle (size L)

**Goal:** every account is a record with a known state, a known binding to a phone, profile and app, a known owner, and
a history of changes. The platform can say, for any account, what state it is in and why.

**Scope:**
- Account lifecycle state machine: `draft → ready → creating → created → active`, with branches `paused`, `needs_person`
  (a code, CAPTCHA, selfie, ID or phone call), `locked`, `suspected_restriction`, `retired`, and `deleted_in_trash`.
  Transitions are explicit, and each transition writes an event with its reason.
- Binding model: an account belongs to one app on one phone profile; the binding can change only by an owner action,
  and the old binding is kept in history.
- Duplicate detection: the same service and handle, or the same email or phone number held in two identities, raises a
  finding (not a block). Matching uses normalised keys, never raw values in logs.
- Ownership: each account records whose it is (mine, company, client, test), as plan 43 §6 specifies. The field is
  required.
- Credential policy per account: how the password is kept (vault item), when it was last set, and an owner-set reminder
  to rotate. The platform never changes a password on its own (plan 59 decision 1).
- Account health: a derived value from events (last successful use, failed attempts, needs-person waits, restriction
  signals reported by the app, age). Shown with the events that produced it.
- Account views: a list with filters, a per-account page with its timeline (stage 4), its identity card, its binding and
  its health, and "open on phone" (switch profile and launch, with the approval rules unchanged).

**Out of scope:** the work timeline (stage 4), analytics (stage 6).

**Tests:** state-machine tests (every legal transition; every illegal transition refused); property tests for the
binding history; duplicate detection on synthetic data including near-duplicates; a test that no account record holds a
secret value (scanned by a guard over the schema and the sample data).

**Acceptance criteria:**
- The state machine has a test for every transition in its table.
- Duplicate detection finds every seeded duplicate in a 5,000-account synthetic set with no false positive on the
  seeded non-duplicates.
- Health values reproduce exactly from the events (replay test).

**Evidence owed:** a live check that "open on phone" switches to the right profile on a real Pixel: OWED.

**Exit gate:** state machine, duplicate detection and health tests in CI; the guard for secret values in account records
passes; the OWED item recorded.

---

### Stage 4 · Work done inside accounts (size L)

**Goal:** for every account, the owner sees what was done, when, by which phone and task, with the proof, and what is
still open. This is the "status of work done inside accounts" requirement.

**Scope:**
- Work item model: a unit of work on an account (for example "verify email", "set profile photo", "post draft prepared",
  "follow-up due"), with a status (`planned`, `running`, `waiting_for_person`, `done`, `failed`, `skipped`), a due date
  and a proof reference.
- Proof: each `done` must cite evidence from the phone (a Lab-style check on the screen, a file hash of an exported
  artifact, a confirmed server result) and is refused without evidence. Evidence is stored as a reference and a short
  redacted label.
- Account timeline: every event for the account, grouped by work item, filterable by phone, task, app and outcome.
- Checklists per app type: a reusable list of work items for an app or a sign-up flow, applied to an account; each
  account's checklist progresses as its work items finish.
- Failure analysis per account: failed work items with the stage where they failed (from the run record) and the
  finding that tracks them.
- Glass: the account page shows the checklist, the timeline and the open work, and each item links to its events.

**Out of scope:** the analysis across accounts (stage 6), app packs (stage 7).

**Tests:** proof-required tests (no `done` without evidence); evidence redaction tests (no secret or private content in
evidence labels); checklist tests (progress equals the count of done items, replayed from events); timeline ordering
tests with clock skew and out-of-order events.

**Acceptance criteria:**
- A `done` without evidence is refused in every path, checked by a test per path.
- The checklist progress of 5,000 synthetic accounts reproduces exactly from events.

**Evidence owed:** one real account checklist driven by a phone on a test account the owner provides: OWED.

**Exit gate:** proof-required and redaction tests in CI; the OWED item recorded with its evidence reference.

---

### Stage 5 · The fleet as data (size L)

**Goal:** the fleet is a queryable model: every phone and cloud phone, its state over time, its capacity, what it is
assigned to, and what it cost, with bulk operations that show a dry run before anything changes.

**Scope:**
- Phone entity (from stage 2) with the fleet facts as events: online, battery, storage, network, app version,
  accessibility state, notification state, quarantine state, cloud lease state, cost per day.
- Time series for health facts, kept at full resolution for 7 days and as hourly aggregates for 2 years (retention
  policy in §9). Queries return bounded windows only.
- Capacity view: how many phones are free, busy, quarantined or down, per profile, app and provider; how many accounts
  each can take based on policy.
- Cloud phones (VMOS first, D6): lifecycle states (rented, connected, expiring, renewed, released), cost, and their
  remote-ADB keeper health, as events and projections.
- Bulk operations: a typed selection (a filter over phones or accounts), an action from a fixed list (for example
  "run this routine", "exclude", "release from quarantine", "renew", "power off"), a dry run that lists every affected
  item and its expected effect, and an owner confirm. Irreversible actions (wipe, delete, purchase) need the owner's
  explicit typed confirmation and are never part of a selection by default.
- Cloud costs: spend per phone and per day, budget warnings (owner-set), and no purchase without owner approval.
- Glass: a fleet dashboard with the capacity view, a phone list with time series charts, cloud phone lifecycle, and the
  bulk-operation builder.

**Out of scope:** the cloud file upload and skill pack (stage 13 owner-run items); analysis narratives (stage 11).

**Tests:** time-series retention and aggregation tests (aggregates equal the raw data they summarise); bulk operation
tests (a dry run equals the real run's set, checked by diff; an irreversible action is refused without typed confirm);
cost arithmetic tests with currency and rounding rules; quarantine tests carried over from plan 33.

**Acceptance criteria:**
- Aggregates match raw data exactly for a generated month of health facts.
- A dry run of a 1,000-phone selection returns within the stage-0 p95 target.
- No bulk action runs without its dry run being shown first (a test per action).

**Evidence owed:** a real fleet check with at least three physical phones and one cloud phone (owner-provided):
OWED.

**Exit gate:** retention, aggregation, bulk and cost tests in CI; OWED items recorded.

---

### Stage 6 · Analytics engine (size L)

**Goal:** deterministic analytics over the ledger: accounts, work, fleet, apps and cost, with every number traceable to
its events and every definition written down.

**Scope:**
- Metric registry: each metric has an id, a version, a definition in text, the events it reads, a time window rule, and
  a unit. Examples: account creation success rate, time to create, work items done per day, needs-person rate per app,
  phone uptime, cloud cost per active account, failure rate per app and phone.
- Query language: a closed, typed grammar for filters, groups, aggregates and time windows. No `eval`, a row limit, a
  time-window limit and a cost estimate before a query runs.
- Cohorts and comparisons: groups by app, phone, profile, provider, week, or the owner's custom type; comparisons with
  intervals (reusing the Wilson and Newcombe methods already in the Lab).
- Anomaly detection: deterministic rules first (for example a rate that moves outside its interval over the last N
  windows, a sudden jump in needs-person waits). Each anomaly names its metric, its window and its events.
- Source drill-down: every number opens the events behind it.
- Exports: CSV and JSON with the metric version in the header; no personal columns unless the owner asks for them.
- Glass: dashboards built from metrics and typed queries, saved as pages (stage 2 views).

**Out of scope:** the AI narrative over these numbers (stage 11).

**Tests:** metric golden tests (a fixed synthetic history with hand-computed expected values); query grammar tests with
adversarial inputs; cohort tests; anomaly tests on seeded anomalies (detected) and seeded normal noise (not detected at
the stated false-positive rate); drill-down tests (the events returned equal the events that produced the number).

**Acceptance criteria:**
- Every metric has a golden test with hand-computed values.
- A query over a month of 1,000,000 events returns within the stage-0 p95 target, or is refused with its cost estimate.
- Seeded anomalies are detected at the stated rate on the synthetic set.

**Evidence owed:** a review of 20 real metric values against the owner's own records: OWED.

**Exit gate:** golden tests, grammar tests and anomaly tests in CI; OWED recorded.

---

### Stage 7 · Flexible app management (size L)

**Goal:** every app on every phone is known: its version, its screens and maps, its accounts, its skills and its
health. A new app, or a new kind of app data, is added as an app pack, not as platform code.

**Scope:**
- App entity per phone and per app package: package name, version, install state, the Cyclone map (plan 04, 36), skills
  available, account types it supports, and health.
- App pack: a versioned bundle of an app's entity types (stage 2), its sign-up map (plan 43 §6), its checklists (stage
  4), its metrics (stage 6) and its skills. Packs are data, reviewed like code, and installable per owner.
- Pack validation: a pack is checked against the schema registry and the safety rules before it can be installed. A pack
  cannot add a generic shell, a secret reader or a model key.
- App inventory per phone, with installed versions and a diff over time (an app updated or removed is an event).
- Map freshness: a map is marked stale when the app version changes; the owner sees which maps are stale.
- Glass: an app catalogue (all apps across the fleet), an app page with its accounts, maps, skills, metrics and packs,
  and a pack manager.

**Out of scope:** new skills (plan 46 Skill Studio, unbuilt, stays on its own plan).

**Tests:** pack validation tests (malformed, oversized, unsafe packs refused); version-diff tests from replayed events;
map staleness tests; a guard that no pack can declare a shell, a secret reader or a model key.

**Acceptance criteria:**
- A new app pack can be installed, used and removed without a platform release (demonstrated with two packs in the
  test suite).
- Refused-pack tests cover every unsafe declaration the guard knows.

**Evidence owed:** one real app pack installed on the owner's Pixel: OWED.

**Exit gate:** validation and guard tests in CI; OWED recorded.

---

### Stage 8 · Secrets and security hardening (size L)

**Goal:** the platform's secrets and personal data meet a security bar a reviewer would accept: layered keys, a verified
recovery path, hardware-backed unlock where the owner has it, bounded retention and an audit trail the owner can read.

**Scope:**
- Key hierarchy written down and implemented: passphrase to vault key, recovery key, per-item keys, device keys for
  phones, hive key. A diagram in the threat model; a test per key path.
- Personal columns encrypted at rest (today marked only, plan 43 T1 "not built yet").
- Windows Hello or DPAPI unlock for the Command Center vault (D9), with the passphrase as fallback.
- Passphrase change, recovery-key rotation and vault reset rehearsed in tests; a recovery drill that the owner runs once
  and records.
- Backups: encrypted, verified by restore into a scratch location, with a restore test in CI using synthetic data.
- Audit trail review: a viewer for the hash-chained audit with verification status.
- Retention and deletion for screen copies (D7) and for events that hold personal references; deletion removes the copy
  and records that it did.
- An independent security review of the platform: a written report with findings, each resolved or accepted by the
  owner.

**Out of scope:** formal certification.

**Tests:** key path tests (encrypt, decrypt, rotate, recover, reset); backup and restore tests; retention and deletion
tests (the copy is gone and the deletion event exists); a test that no log, metric or export contains a value that
matches a secret pattern in the synthetic corpus.

**Acceptance criteria:**
- Every key path has a test; the threat model maps each threat to a control and a test.
- Restore of a synthetic backup reproduces every record.
- The security review's critical and high findings are resolved before the gate.

**Evidence owed:** the owner's recovery drill recorded on their machine; Windows Hello unlock on the owner's PC: OWED.

**Exit gate:** key, backup and retention tests in CI; security review report committed; OWED recorded.

---

### Stage 9 · Automation and orchestration (size L)

**Goal:** routines, tasks and bulk operations run on a durable engine that retries correctly, never repeats a
consequential action, and can be dry-run and paused.

**Scope:**
- Job model: a job has an idempotency key, a dependency list, a retry policy (bounded attempts, exponential backoff with
  jitter), a timeout, a cancel rule and a compensating action where one exists.
- Consequential actions are never retried blindly: a retry first checks the outcome (plan 33 rules), and an unknown
  outcome becomes `needs_check`, not a second action.
- Pacing and caps: owner-set defaults (D3) per service, per phone and per account, with warnings when lowered.
- Routines as entity types: a schedule, a target selection, a template, and a result mapping into the account's work
  items (stage 4).
- Buttons and rules (plan 43 T3) moved onto the same job engine.
- Concurrency rules: one phone runs one consequential task at a time; one account is held by one job at a time (plan 33).
- Glass: a jobs view with state, retries, dependency graph and the event trail for each job.

**Out of scope:** new trigger kinds (phone notifications, email) beyond what plan 33 §12.8 already lists; those need a
separate decision.

**Tests:** idempotency tests (a repeated key never repeats the action); retry tests with injected failures at every
step; unknown-outcome tests (no second action); pacing tests; concurrency tests (no double hold); a soak test of 72 hours
of simulated jobs with random failures.

**Acceptance criteria:**
- Zero repeated consequential actions across the soak run, checked against the event ledger.
- Every job's trail reconstructs its final state exactly (replay).

**Evidence owed:** a real routine run on one test account and one test phone: OWED.

**Exit gate:** idempotency, retry, unknown-outcome and soak tests in CI; OWED recorded.

---

### Stage 10 · Verification desk and approvals (size M)

**Goal:** when a step needs a person (a code, a CAPTCHA, a selfie, an ID, a phone call), the right person is asked, the
job waits without blocking other work, and the answer is recorded without the platform ever solving the challenge.

**Scope (completes plan 43 T8):**
- Verification queue: each needs-person item with the account, the step type, the phone, the time it has waited and
  what the person must do.
- Routing: the owner, or a named holder (through MCP where the holder has a tool), with the same approval rules.
- Non-blocking: the job waits; other accounts and phones keep working.
- Answer capture: the code or the confirmation is entered by the person into a secure field and delivered sealed to the
  phone for one step, as plan 33 C2 does. The platform never stores the answer in plain text.
- Approvals inbox upgrade: grouping, batch answers for identical low-risk items, and the rule that consequential
  approvals are never batched.

**Out of scope:** solving CAPTCHAs or evading challenges, now or later (plan 43 §7 rule).

**Tests:** queue tests; routing tests; non-blocking tests (other work proceeds); answer-capture tests (plain text never
stored, delivery sealed to the right phone, single use); batch rule tests (consequential items refused in a batch).

**Acceptance criteria:**
- A needs-person item never resolves without a person's answer (test per step type).
- No code path solves or bypasses a challenge (a guard test over the step types).

**Evidence owed:** one real verification answered by the owner on a test account: OWED.

**Exit gate:** tests and guards in CI; OWED recorded.

---

### Stage 11 · Intelligence layer (size M)

**Goal:** the owner can ask questions of the platform and receive answers that cite their sources, and receive proposed
actions they can apply. The model adds reasoning, never facts and never authority.

**Scope:**
- Grounded answers: a question is answered from typed queries and metric results; every claim links to the event ids or
  metric values it rests on; an answer with no source is shown as an opinion, marked as such.
- Proposals: suggested bulk operations, routine changes or pack updates, each shown as a proposal with its dry run.
  Nothing applies without the owner.
- Anomaly explanations: for each anomaly from stage 6, a short explanation citing its events.
- Context budget: summaries are bounded, deterministic and reproducible from the ledger.
- Owner-configured model (D5); the fleet layer stays model-free (AGENTS rule).
- Cost controls: a per-day spend cap, shown in Glass, with the cost of each answer recorded as an event.
- Evaluation: a fixed set of 100 questions with expected answers, run before each release of this layer; the answers must
  cite the correct sources.

**Out of scope:** any model access to secrets, screen content, passwords, codes or personal columns.

**Tests:** citation tests (every claim in a golden answer maps to a source); refusal tests (questions that need secrets
are refused); proposal tests (no proposal applies without an owner action); spend-cap tests; a prompt-injection test set
built from hostile event payloads.

**Acceptance criteria:**
- 100 of 100 golden questions cite correct sources, or the failures are listed and fixed before the gate.
- No model call can reach the vault, a secret or screen content (a guard test over the context builder).

**Evidence owed:** none beyond the golden set, which is run on the owner's configured model: OWED for the live run.

**Exit gate:** golden set, citation, refusal and injection tests in CI; live run recorded.

---

### Stage 12 · Scale, reliability and observability (size L)

**Goal:** the platform holds the D8 scale target with stated service levels, and the owner can see its health.

**Scope:**
- Load tests at 1× and 2× the D8 target: phones, accounts, events per day, concurrent Glass sessions.
- Service levels, stated and measured: dashboard p95 latency, append p99 latency, job start delay, data freshness of
  projections, backup age.
- Error budgets and an operations page in Glass: uptime of the gateway, failed jobs, quarantined phones, backup status.
- Fault injection: gateway restart mid-job, disk full, a phone dropping mid-task, a cloud phone expiring, a provider
  outage. Each has an expected outcome and a test.
- Projection rebuild under load without stopping the platform.
- Logging and tracing that never contain secret or personal values (a guard over the log schema).
- 72-hour soak on the reference machine.

**Out of scope:** multi-region hosting (D1 decides whether it is ever needed).

**Tests:** load tests (scripted and recorded); fault injection suite; log-schema guard; soak run with a report.

**Acceptance criteria:**
- The D8 target met at 1× with the stated latencies; at 2× the platform degrades by its stated rules and never corrupts
  data (checked by replay after the load).
- Every fault has its expected outcome observed in the test run.

**Evidence owed:** the soak and load reports committed as text; the real-phone version of the fault suite on the owner's
fleet: OWED.

**Exit gate:** load, fault and soak results recorded; log guard in CI; OWED recorded.

---

### Stage 13 · Live and physical acceptance (size L, owner-assisted)

**Goal:** the platform works end to end on the owner's real phones and cloud phones, on accounts the owner has chosen
for testing, with every step's evidence recorded.

**Scope (each item needs the owner's machine, phones or accounts):**
- Fleet: at least three physical phones paired, one cloud phone rented on the owner's VMOS or DuoPlus account (D6) with
  the upload and skill-pack chain, if the owner approves the purchase.
- Account lifecycle on a test service the owner chooses: create, verify (through stage 10), use, pause, retire.
- Work checklist driven by a phone, checked against proof (stage 4).
- Bulk operation on the fleet with a dry run and a confirmed run (stage 5).
- The Lab's gate table (plan 75 §8.3) measured on the Pixel, with the numbers recorded.

**Out of scope:** any service the owner has not explicitly chosen; any account not created for testing.

**Evidence:** every item has a recorded run, with the experiment or run id, the date, the device, and a pass or fail.

**Exit gate:** each item passed or recorded as failed with a finding; no item marked passed without its evidence.

---

### Stage 14 · Release hardening, documentation and handover (size M)

**Goal:** the owner can install, run, back up, recover and understand the platform without help from the build process.

**Scope:**
- Signed Windows installer (D10), with the signing certificate handled outside the repository.
- Owner documentation: install, pairing, the vault and its passphrase, backups, recovery drill, troubleshooting, what each
  screen means, what is UNVERIFIED.
- Runbooks: restore a backup, rebuild projections, rotate keys, quarantine and release phones, handle a needs-person item.
- Release lane: the fast lane (plan 35) with the stage gates added to its checklist.
- Public-facing claims reviewed: every capability statement matches its test or its recorded evidence.

**Exit gate:** a clean install on a second Windows account passes the runbook, timed; every documented claim links to
evidence; the owner signs off.

---

## 6. How each coding run is executed (the protocol)

Every run follows this checklist. A run that skips a step is not done, and the exit report says so.

1. **Read the stage.** Read this plan, the stage section, the decision records it cites, and the code it touches.
2. **Branch.** A branch named `platform/s<nn>-<slug>` from the current dev tip. Never a shared branch.
3. **Scope check.** List the stage's deliverables and tick each one; anything outside the list goes to §11.
4. **Design note.** A short written design in the run's folder before any code: data model, API, UI, threats, tests.
5. **Build with tests first** for each rule in §2 that the stage touches. A test that cannot fail is not a test.
6. **Guards.** Update or add a guard for every new invariant (for example "no raw SQL outside the repository", "no secret
   value in an account record").
7. **Validation, in order, stopping at the first failure:**
   - `python -m pytest apps/device-gateway/tests -q`;
   - the Glass checks (`npm test`, `npm run build`, `python scripts/ci/glass_guard.py`) when Glass changes;
   - `python scripts/ci/release_versions.py --check` and `python scripts/ci/mobile_product_guard.py`;
   - the stage's benchmark or load script, with its output committed as text;
   - the stage's own acceptance tests, listed by name in the exit report.
8. **Self-review.** Re-read the diff against §2 rule by rule. Write the result down.
9. **Exit report.** In `Cyclone V5 plan/76-stage-reports/s<nn>.md`: what was built, the acceptance criteria with their
   measured values, the OWED items with reasons, the risks accepted, and the decisions taken.
10. **Pull request.** A draft into the dev branch, the body from `.github/PULL_REQUEST_TEMPLATE.md`, the exit report
    linked. Merge only when the owner decides, because a merge publishes.
11. **Update this plan.** The stage's status line changes to `built`, `built with OWED items`, or `failed`, with the date.

**Stop rules for a run:** a failing acceptance criterion that cannot be fixed in the stage's scope stops the run and goes
to the owner with the evidence. A rule in §2 that would have to be broken stops the run. No run continues past a
known-broken test by marking it skipped.

---

## 7. Quality bar, measured

These apply to every stage. They are gates, not aspirations.

| Measure | Bar |
|---|---|
| Correctness | Every acceptance criterion has a test that fails before its change |
| Data integrity | Replay equality and chain verification hold after every stage's tests |
| Security | No secret value in any record, log, metric, export or model context (guard in CI) |
| Performance | The stage-0 baseline; the D8 targets from stage 12 |
| Honesty | Every claim links to a test or to recorded evidence; OWED items are listed, never hidden |
| Operability | Backups verified by restore; a runbook for each operation the owner performs |
| Review | A written self-review and, for stages 8 and 11, an independent review |

---

## 8. Risks and how the plan contains them

| Risk | Containment |
|---|---|
| Account creation at volume causes platform bans or legal exposure (D3, D4) | Owner-set defaults with warnings; the platform shows exposure, and the owner accepts it in writing |
| Data corruption during migration | Forward-only migrations, dry runs, row-by-row comparison, restore tests |
| Secrets leak through analytics, logs or the model | Zero-knowledge rule, guards, the synthetic secret corpus in every relevant test |
| The model invents facts about accounts | Grounded answers only, citations, golden set, refusal tests |
| Scale targets are missed late | Measured from stage 0; stage 12 at 2× |
| Fleet or cloud costs run away | Budgets, dry runs and owner approval for every purchase |
| Physical behaviour differs from simulation | OWED items with evidence, stage 13 on the owner's devices |
| Scope creep into "everything" | One major point per run; §11 backlog; each stage's out-of-scope list |

---

## 9. Retention policy (proposed; owner confirms under D7)

| Data | Kept | Then |
|---|---|---|
| Health facts (phones) | Full resolution 7 days | Hourly aggregates 2 years |
| Events | Full history | Personal values are references to vault items, never copies |
| Lab screen copies | 7 days | Deleted, and the deletion recorded |
| Owner-mission screen copies | Never saved (as today) | — |
| Run records | As today's Lab reports | Owner decides |
| Audit chain | Full history | Never deleted; verified on every start |
| Backups | Last 30 daily, last 12 monthly | Older deleted after restore check |

---

## 10. Where the time goes (indicative only)

Sizes are relative effort, not dates. A typical run is one focused session of a few hours. The large stages (1, 2, 3,
4, 5, 6, 7, 8, 9) each need several runs. Stages 0 and 14 are the bookends. Stage 13 depends on owner-supplied phones,
accounts and purchase decisions, so it is scheduled by the owner, not by the coding runs.

---

## 11. Backlog (out of the stages on purpose)

- Multi-user collaboration and roles beyond the single owner (D2 says the design allows it; building it is later).
- A hosted multi-owner product (plan 35 run 19–20, C6), which depends on D1.
- Email and notification-triggered routines (plan 33 §12.8 gap).
- Skill Studio (plan 46) and the skill pack chain (plan 56 V2).
- Windows Hello for anything other than the Command Center vault.
- Any new cloud provider beyond VMOS and DuoPlus.
- Mobile-native management UI; the platform stays a PC dashboard in this plan.

Each backlog item needs its own plan before it is built.

---

## 12. Scale and service-level targets (proposed; measured in stage 12)

| Target | Design value (D8) |
|---|---|
| Phones in the fleet | 1,000 |
| Accounts | 5,000 |
| Events per day | 1,000,000 |
| Dashboard query (typical, p95) | under 300 ms |
| Append (p99) | under 20 ms |
| Job start delay (p95) | under 5 s after its trigger |
| Projection freshness | under 10 s behind the ledger |
| Backup age | under 24 h |
| Gateway availability on the owner's PC | measured, target stated after the first soak |

---

## 13. Glossary

- **Ledger:** the append-only, hash-chained list of events; the source of truth.
- **Projection:** a rebuildable view of the ledger for fast reads.
- **Proof:** the evidence that makes a work item `done` (a screen check, a file hash, a confirmed result).
- **OWED:** an acceptance item this environment cannot produce, recorded with the evidence still needed.
- **Pack:** a versioned, validated bundle of an app's entity types, maps, checklists, metrics and skills.
