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

Research (input, not plan): [Jev + Astra hybrid control](research/jev-astra-hybrid-control.md) — fast System-1 selector + async frontier planner, compared with Cyclone's loop.

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
