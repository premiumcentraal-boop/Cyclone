# Cyclone V5 + Glass 1.0

**Status:** generation plan (not current product)  
**Baseline to leave behind:** Mobile **4.8.0** (versionCode 140) · Cyclone One **1.5.5** · Gateway/MCP **4.1.0**  
**Target:** Mobile **5.0** · Cyclone Glass **1.0** · Gateway/MCP **5.0** as one contract  
**Written:** 2026-09-21

V5 is not a feature dump on 4.8. The phone grows an **atlas** and a **vault**. The PC gets **Cyclone Glass** — a local browser dashboard that is the developer's eyes on the phone's engine, not a second brain.

> **Owner correction, 2026-09-23.** Glass is a **local web app in the browser** (`apps/glass`), not the Cyclone One Tauri app and not built on Artemis. It shows what Cyclone knows (apps, maps per version, scenarios), what it did (every run, with a step-by-step autopsy of failures) and lets you control the phone. It has no intelligence of its own. Read [03](03-glass-v1.md) and [11](11-run-inspector.md) first; where older docs or orchestrator briefs disagree, **03 wins**.

> One button to learn the house. A vault for the keys. A Minitap-class board on the PC so the operator can *see* the house. Eyes every time the agent walks through a door.

## Orchestrators (start here to build)

Two team leads, GitHub-only, first handoffs already written:

| Orchestrator | Prompt | First brief |
|---|---|---|
| Mobile 5.0 | [`orchestrators/mobile/ORCHESTRATOR.md`](orchestrators/mobile/ORCHESTRATOR.md) | [`HANDOFF-000-start.md`](orchestrators/mobile/HANDOFF-000-start.md) |
| Glass 1.0 | [`orchestrators/glass/ORCHESTRATOR.md`](orchestrators/glass/ORCHESTRATOR.md) | [`HANDOFF-000-start.md`](orchestrators/glass/HANDOFF-000-start.md) |

How they run, branch names, and the pipe: [`orchestrators/README.md`](orchestrators/README.md) · [`CONTRACT.md`](orchestrators/CONTRACT.md)

Each orch issues **three** agent handoffs (drafted under `orchestrators/<team>/agents/`). Agents return under `returns/` with PR URLs.

## Read in this order

| # | Doc | What it is |
|---|---|---|
| 0 | [Overview](00-overview.md) | Picture, Louella demo, what 4.8 already is |
| 1 | [Headset and laws](01-headset-and-laws.md) | Why this is a headset, not a harness |
| 2 | [Mobile 5.0](02-mobile-v5.md) | Workstreams M0–M6 |
| 3 | [Glass 1.0](03-glass-v1.md) | **Owner charter.** Local web dev dashboard: Apps, Map, Scenarios, Versions, Runs, Knowledge, Phone |
| 4 | [App Maps canvas](04-app-maps-canvas.md) | **Map board** + Scenarios lens + per-version maps, Minitap-class |
| 5 | [Secrets and vault](05-secrets-vault.md) | The card, Keystore, no values on the wire |
| 6 | [Atlas and mapper](06-atlas-and-mapper.md) | One-button crawl, dummy vs live, never-pay |
| 7 | [Ask compiler](07-ask-compiler.md) | Sentence stays law; atlas whispers |
| 8 | [Protocol and gateway](08-protocol-gateway.md) | `atlas.*` `mapping.*` `secrets.*` `ask.*` |
| 9 | [Cuts and milestones](09-cuts-and-milestones.md) | Alpha → RC → 5.0 / Glass 1.0 |
| 10 | [Reuse and refusals](10-reuse-and-refusals.md) | Steal 4.8. Do not rebuild. What we will not ship |
| 11 | [Run inspector](11-run-inspector.md) | The autopsy: every run step by step, cause of death, fix |
| 16 | [Cyclone Mind](16-cyclone-mind.md) | One model, one conversation, one mission |
| 17 | [Structure](17-structure.md) | Task Kit, Owner Moments, the building blocks |
| 18 | [Cyclone Lab](18-cyclone-lab.md) | Measured missions on the real phone, A/B |
| 19 | [Marketplace](19-marketplace.md) | Recipes, connections, and the road to a community store |
| 20 | [App mapping build plan](20-app-mapping-build-plan.md) | **Final build plan alpha.36–39**: Learn (36), map-guided runs (37, built), mapping missions |
| 21 | [Mind hands and desk](21-mind-hands-and-desk.md) | **Hands built (alpha.41)**: reliable text delivery; mission desk scratchpad (after Drive) |
| 22 | [Glass Atlas and mapping missions](22-glass-atlas-and-mapping-missions.md) | **Built in alpha.38**: semantic-zoom map, coverage, mission control, safe mapping identities |
| 23 | [One map, grounded skills and routines](23-one-map-grounded-skills.md) | **Built in alpha.39**: mapping passes teach runs, skills and routines anchored on the map, skills on Glass's map |
| 24 | [Cyclone Drive: voice assistant](24-cyclone-drive-voice.md) | **Plan**: driver mode AI button, Siri-like AI mode, voice pipeline on OpenRouter (alpha.49–50, after Planes, Hands, Background always, the Glass overlay, stable background, the web-only PC and parallel sessions) |
| 25 | [Planes: background and foreground](25-planes-background-foreground.md) | **First step built (alpha.40)**: the Mind in the background, one-tap switch pill, transactional plane switches, automatic plane choice, self-healing background |
| 26 | [Background that always works](26-background-always.md) | **Built (alpha.42)**: switch it on once, start from Recents, background parity, start preference, app leases for the same-app case, tier 0, see-to-approve, Lab planes suite; stabilised in plan 28, parallel sessions alpha.48 |
| 27 | [The overlay, redesigned](27-overlay-redesign.md) | **Built (alpha.43)**: tilt-lit glass, the one-word plane pill above card and island, four heights (card, island, notification only, idle), app logos, a background switch that goes home and is never silent, notification Show and Android 16 step segments |
| 28 | [Background that stays working](28-background-stable.md) | **Built (alpha.44)**: an audit of the whole background path and its fixes (interrupts no longer close background screens, a lock no longer pauses them for good, approvals work in the background, only steps that need the screen move the task, apps with several tasks, dialogs from other apps, a second route for gestures) and the one-tap Background Check |
| 29 | [Direct first, then the background screen](29-one-tap-engine.md) | **Layer 1 built (alpha.45)**: calendar, contacts, timers and alarms with no screen on every phone, access asked by Android's own dialog. **Layer 2**: stays on Shizuku, no Cyclone engine (decided) |
| 30 | [Setup cards](30-setup-cards.md) | **Built (alpha.46)**: a short guided setup in plain words, one Tilt Glass card per important setting (Shizuku included), with ⓘ in Settings to see a card again |
| 31 | [Web-only PC](31-web-only-pc.md) | **Built (alpha.47)**: one-line install, type `cyclone`, Glass in the browser with Remote MCP and ChatGPT Attach; the Cyclone One desktop window is retired |
| 32 | [Cyclone Drive build](32-cyclone-drive-build.md) | **D1 (alpha.49), D2 (alpha.50), "faster" (alpha.52) and D3 (alpha.53) built**: Driver mode, the voice orb and AI mode; questions, details and sends by voice with a word-for-word readback; newest speech-to-text, instant commands, JEV watching; message announcements, the car's Bluetooth microphone. Next: the owner's car test, then the Lab voice suite and JEV promotion if earned |
| 33 | [Command Center](33-command-center.md) | **Final plan; C0–C3 built (alpha.51–56), C5 Pages and the workspace redesign built (alpha.61), the AI project manager with OpenRouter in the dashboard (alpha.62)**: one dashboard for accounts, tasks, routines, phones, results and approvals; zero-knowledge vault with sealed one-use delivery to a phone; MCP connections (Higgsfield first); an AI coordinator that can't see secrets or approve. Releases C0–C6. **C0 built in alpha.51, C1 (vault) in alpha.54, C2 (sealed delivery) in alpha.55, C3 (connections, Higgsfield, pre-authorised leases) in alpha.56** |
| 34 | [Connector maker](34-connector-maker.md) | **M1+M2 built (alpha.57), M3+M4 built (alpha.58)**: connect any MCP yourself: paste an address, a server config or an API description; Cyclone finds the transport and sign-in, sorts tools into reads and changes, and pins them. Local MCP servers on the PC are allowed behind a short plain-words setup card; read and write; results come back from outside services, chain into the next step and reach a phone as quoted data; connectors export and import as cards without keys. Releases M1–M4 |
| 35 | [Everything left, run by run](35-roadmap-to-5.0.md) | **Roadmap after alpha.65 built**: about 21 build runs to 5.0 — connector maker, parallel sessions, the mission workspace (plan 37), fleet health, coordinator, smarter routines, the complete vault, mission desk, Pages, hosted, then RC and 5.0 — plus the owner's tests that gate each |
| 36 | [The App Manual](36-app-manual.md) | **App dictionary built (alpha.59); the rest planned (M1–M3)**: a smart mapper that learns apps by itself from generic UI patterns, safe experiments and a self-quiz. It writes every screen, panel, list and category in the app's own words (never your content); lists by how they work (order, groups, how to find one), categories and sub-categories found automatically; an index of abilities with verified paths so an agent walks there with no model call; JEV watches close calls. Model picker on the PC (default: the phone's model), screenshots switch |
| 37 | [The mission workspace](37-mission-workspace.md) | **W1+W2 built (alpha.66), W3 built as memory v2 (alpha.67); W4 planned; revised for flexibility**: guidance not gates, no new tools, nothing scrapped; every model call built from eight owned modules (rules, brief, your world, a journal of app stays, the current stay, a live state, the app's section of map and manual, one full screen) at about 25–30k tokens however long the run; `switch_app` with a journal block per app; each action states what it expects and the harness checks it; a definition of done checked at finish; diversions shown and asked when they change who, how, money or what is public; the owner's encrypted People & notes; recipes and traps that compound. Behind a setting and promoted only when the Lab shows it wins every suite |
| 38 | [Steer, queue, parallel](38-steer-queue-parallel.md) | **Built in alpha.68**: while Cyclone works, send shows Steer / Queue / Parallel and an empty bar gives Pause and a two-tap Stop; a steer is a goal version and forces a re-plan; the model decides diversions on its own, and only serious actions confirm; plan versions drawn as a branch on the plan card; a Lab divert suite |
| 39 | [Handoff: the remaining builds](39-handoff-next-builds.md) | **Working brief (2026-09-29, at alpha.70)**: how to ship a build, the laws, and per build (recipes and traps, fleet health, coordinator, routines, vault, mission desk, hosted, RC) the code to start from, what to build, tests, traps and the owner decisions needed |
| 40 | [Profiles as a workhorse](40-profiles-workhorse.md) | **P1 and P2 built in alpha.75** (Recently deleted for 7 days, automatic backup before delete, Cyclone Carry with people memory labelled by profile); deep dive and plan (2026-09-29, at alpha.73): how root profiles work today, what doesn't follow you, Cyclone Carry (hub and spoke, shared / per profile / never, an encrypted bundle, merge), friendly lifecycle with Remove → Recently removed → backup before delete, what makes it great, P1–P5 and the owner decisions |
| 41 | [Fast mode: decision models](41-fast-mode-decisions.md) | **Parallel Pilot built in alpha.76** (Fast mode in Settings, off by default); research plan (2026-09-29, at alpha.75): OpenAI's Decisions API (and JEV) for very fast runs; the lessons Fast mode must keep; three builds compared (Referee, **Pilot** recommended, Routes and forks); the question catalogue, images and privacy gate, Settings, proof and phases F0–F4 |
| 42 | [Cyclone Modes and Live voice](42-modes-instant-flash-mind.md) | **Built in alpha.77 (M1–M4, most of M5, Flash as a quick Mind mission)**; final build plan for "Cyclone Live" (2026-09-29): smart mode routing; Instant (local grammar and multi-question decision boxes acting with Cyclone tools in 0.05–0.6 s); Live voice (on-device partials, early commit, prewarm, silent success, 8 s ping-pong, answers within 10 s); Flash (fast planner + the parallel Pilot); the Mind; promotion with a baton; M1–M9 |
| 43 | [Tables, action buttons and Account Setup](43-tables-actions-account-setup.md) | **T1 (Tables core) built in alpha.79; T2 (relations, rollups, formulas, timeline and calendar) in alpha.80; T5 + T6 (Accounts rebuilt, sign-up mapping; no profiles) in alpha.81; T7 (Create accounts) in alpha.82; T3 (action buttons) in alpha.83; T4 (profiles from the PC) in alpha.84; mapping fix in alpha.85**; build plan (2026-09-30): Notion-grade tables in the Command Center, cross-references, action buttons for you and agents, phones › profiles, Accounts as phones → profiles → apps → accounts, Account Setup mode, and the Verification desk with scoped hand-over over MCP; T1–T9 |
| 50 | [Plugins from GitHub](50-plugins-from-github.md) | **Built in alpha.103** (index key still to be set up by the owner): core holds no plugin; paste a GitHub link or pick from the signed Cyclone index, and a hash-pinned, attested release package installs atomically into the owner's folder, runs under the Plugin Host (Job Object, stdin handshake, restart budget), connects to the Port Hub, and gets a Glass settings form from its schema with write-only secrets; kill switch through the index's revoked list; the ID Generator starter leaves core; P1–P4 |
| 51 | [Phone connectors](51-phone-connectors.md) | **K1+K2 built in alpha.104** (approvals, the Binder door, profile schema 2, events), **K3+K4 in alpha.105** (selector entries, `current`, wakes, Glass, the client library, sample, schemas and vectors): companion apps on the phone talk to Cyclone over Binder after the owner approves them by package and signing certificate; v1 reads profiles, keeps its own namespaced `ext` data, adds its own selector entries with a deep link, and pulls ordered profile events; no profile changes, no `PhoneToolExecutor`; `cyclone.connector/1` with schemas, AIDL and a client library; K1–K4, about two alphas |
| 52 | [Human Hands](52-human-hands.md) | **Runs 1–4 built in alpha.111** (Settings › Hands: Precise / Natural / Relaxed); plan (2026-10-07): deep dive into Human Gesture V0.3 (what is built: cubic planner, profiles, dispatch, evidence, lab; what is not: speed curves, varied caller geometry, touch-first taps, keyboard never shown, key-by-key typing, rhythm, owner templates, drag/pinch/draw, device proof) and runs 1–7 to make Cyclone move and type like the owner |
| 54 | [Glass Manager: design](54-glass-manager-design.md) | **Design (2026-10-06, alpha.110); R1–R5 built in alpha.112**: Ask AI grows into Cyber, a Manager on every Glass page: a character with a pose per state, status reel, Ctrl/⌘K, work trails, proposal cards, project pulse heatmap, alerts, memory and playbook chips; Hermes Agent structure in the gateway, Space UI (MIT, free tier) as a design reference; event contract `cyclone.manager.events/1` |
| 55 | [Glass Manager: build runs](55-glass-manager-runs.md) | **R1–R5 built in alpha.112** (agent package; streamed answers, message queue, summaries of long chats; Cyber's character, reel, trail, pulse; Cyber on every page with the dock, panel and Ctrl/⌘K palette; Cyber opens pages, filters and points at rows in Glass), R6–R10 planned (alpha.113–117): project sight, heartbeat and brief, memory, playbooks, overnight pass |
| 56 | [VMOS fleet](56-vmos-fleet.md) | **Plan (2026-10-08)**: VMOS Cloud phones that arrive ready to work: one button from "Add" to Ready (Cyclone installed through VMOS `uploadFileV3`, set up and trusted over ADB with no tap), kept connected over VMOS remote ADB, skills shared across phones through a fleet library (`cyclone.skillpack/1`), owner-confirmed spin-up of new phones, then groups and staged updates; run 0 probes the owner's account first; picks up plan 44 runs 2–6 |
| 57 | [Hardened profiles](57-hardened-profiles.md) | **P0 built in alpha.118** (truthful errors, room screen, Allow more profiles on rooted phones, clean start, debug file); **P1 built in alpha.119** (staged switch, dead-man return, root proven from the profile, profile list carried); **P2 built in alpha.120** (cornerstone apps incl. Cloak and owner-marked apps, every settings file classified, routines/Market/skills/manuals carried, "Profile C has" report); **P3 built in alpha.121** (Cloak approval carried and re-verified, bindings follow their profile, health in the pill, root.status.v1, debug file and profile health in Glass, the switch-matrix suite); **alpha.122** (Cloak asks to open a profile on Cyclone's own screen, contract minor 3; Main stays unbindable; the PC switch gets the way back and the journal); plan (2026-10-09): why "Profile B already exists" appeared for Profile C (Android's "Maximum number of that type already exists" read as a duplicate; Recently deleted profiles still use user slots), 14 defects, Cyclone Cloak's connection reviewed, a redacted debug file, **profile room on rooted phones** (Android's two limits told apart; `fw.max_users` raised with `resetprop` and kept by a Cyclone profiles module, owner-approved, read back, reversible); runs P0–P3 (alpha.118–121) and the alpha.118 build in ten work packages; VMOS V1 moves to alpha.123 |
| 58 | [Luna Decision Box](58-luna-decision-box.md) | **Plan (2026-10-09)**: every request triaged by `openai/gpt-6-luna-decisions` (text + screenshot) in one call: difficulty score, capability choice, risk flags; then Bind (the target, with sight), Step (Flash/Pilot) and Verify boards; a capability registry for Instant; per-capability bars from Lab calibration; L0 checks a likely wire bug (`choices` sent where the schema requires `criteria`); runs L0–L7 |

## Identity

| Surface | Today | V5 generation |
|---|---|---|
| Android | Cyclone Mobile 4.8.0 | Cyclone Mobile **5.0** |
| PC (any OS) | Cyclone One 1.5.5 (Windows) | **Cyclone Glass 1.0** — local web app in the browser (`apps/glass`), served by the local gateway. Cyclone One stays a separate Windows console |
| Pipe | Gateway / MCP 4.1.0 | Gateway / MCP **5.0** (lockstep with mobile, not later) |

Invariant that does not move: **the phone thinks and mutates. Glass shows, inspects, and sends commands. GATE still owns pay / send / delete.**

## Folder rule

This folder is the generation plan. It does not describe the shipping 4.8 product. Current-product docs stay in [`docs/`](../docs/). When a V5 cut ships, promote the matching slice into `docs/` and leave this folder as the source plan.

Orchestrators and agents work **inside GitHub** under `Cyclone V5 plan/orchestrators/`. They do not invent a parallel docs tree.
