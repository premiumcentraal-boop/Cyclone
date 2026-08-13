# Cyclone Delivery Status

This file separates **implemented**, **verified**, **blocked**, and **planned** status. It is deliberately not a marketing claim.

## Evidence log

- **2026-08-12 — discovery complete:** repository was an empty Git repository on `main` with remote `premiumcentraal-boop/Cyclone`.
- **2026-08-12 — local prerequisites:** Docker Desktop engine is reachable (`29.3.0` client/server, Linux containers, Compose `v5.1.0`). Node `v22.23.2` and npm `12.0.2` are installed. Rust/Cargo were not found in PATH; Tauri installer build is therefore not yet feasible.
- **2026-08-12 — upstream research:** current official xAI Grok Bot, NousResearch Hermes Agent (including upstream `docker-compose.windows.yml`), and n8n Docker guidance were inspected. Findings are in [`ARCHITECTURE.md`](ARCHITECTURE.md).
- **2026-08-12 — visual reference:** `C:\Users\Agent\Downloads\Grok-Bot-featured-image-scaled.webp` was inspected. Cyclone adopts original conversation-first design principles rather than copying branding/assets.
- **2026-08-13 — stack started for real:** Postgres, Redis, Hermes gateway, Cyclone Core, and n8n all run healthy in Docker; Core `/health` reports `ok` for database, redis, hermes, vault, and workspace.
- **2026-08-13 — real model response verified:** a message through Core to Chief produced a real DeepSeek (`deepseek-v4-flash`) response persisted in the conversation. Root cause of an initial 401 was a stale OpenRouter `model.base_url` in Hermes' persisted config; fixed and made durable via the `hermes-config` one-shot service.
- **2026-08-13 — semantic mentions/handoffs implemented:** `@slug` mentions route runs to the named agent and are stored on messages; explicit `@HANDOFF @slug: summary | criteria` in agent output creates durable handoff rows, chat handoff events, and real follow-up runs with a delegation-depth guard. Unit-tested.
- **2026-08-13 — Windows bootstrap verified:** a fresh checkout ran `scripts/launch-windows.ps1 -WebOnly -NoOpen -SkipInstall`; the launcher rebuilt Core, started the Docker services, passed the Core health gate, and started the desktop browser client. The repository now includes double-click `Launch-Cyclone.bat` and `Stop-Cyclone.bat` entry points.
- **2026-08-13 — GitHub sync verified:** the complete Cyclone tree, including persistent-agent foundations, Telegram control-room work, UI corrections, and Windows launch tooling, is published on `premiumcentraal-boop/Cyclone` `main`.

## Acceptance matrix

| Requirement | Status | Evidence / blocker |
|---|---:|---|
| Clean Cyclone repository | **In progress** | Repository initialized; implementation files are being added. |
| Architecture research recorded | **Verified** | [`ARCHITECTURE.md`](ARCHITECTURE.md). |
| Docker environment starts reliably | **Verified** | `docker compose up -d --build` runs all five services healthy. |
| Real Hermes connected | **Verified** | Core health checks the Hermes API server; runs are accepted and observed. |
| Real configured model response | **Verified** | Live DeepSeek v4 Flash answer persisted through the full pipeline. |
| Persistent agents | **In progress** | Agents seeded and creatable; persistence verified for conversations/messages. |
| Group/cluster conversations | **In progress** | Crew conversations create and route by mention; delegation implemented, awaiting e2e run confirmation. |
| Real delegation and activity in chat | **In progress** | `@HANDOFF` execution path implemented and unit-tested; live crew run pending rebuild verification. |
| Shared persistent workspace | **In progress** | Compose bind mounts in place and health-checked. |
| Obsidian vault + retrieval | **In progress** | Vault bootstrap and keyword retrieval implemented; not yet exercised live. |
| Semantic retrieval | **Deferred** | Requires a real embedding/index service and verification; not to be claimed early. |
| n8n service and end-to-end routine | **In progress** | Service healthy; automation-event ingress implemented; no live routine executed yet. |
| Telegram shared agent network | **Blocked pending credentials** | Requires a valid Telegram bot token and authorized chat/user. |
| Host Bridge approved tools | **In progress** | Native host service/policy test pending. |
| Restart persistence | **Not yet tested** | Must restart built stack and verify stored records/files. |
| Windows desktop executable | **Blocked by missing Rust toolchain** | Tauri build requires Rust/Cargo. Installing system software needs operator approval. |
| Windows installer | **Blocked by desktop build prerequisite** | Depends on executable build plus installer validation. |
| Fresh Windows checkout bootstrap | **Verified** | One-click launcher creates local secrets/runtime folders, starts the Docker stack, waits for Core health, and opens the browser client; native Tauri is used when Rust is installed. |
| GitHub project publication | **Verified** | `premiumcentraal-boop/Cyclone` `main` contains the complete local project and launcher documentation. |
| GitHub CI succeeds | **Not yet tested** | Workflow implementation and remote authentication/testing pending. |
| Full prescribed end-to-end test | **Not yet tested** | Must use actual configured model + Telegram credentials; cannot simulate. |

## Do not infer

- Docker Desktop availability does **not** prove this project stack works.
- Source files or mock/test responders do **not** prove a real provider is configured.
- Telegram workflow code does **not** prove a message has been delivered.
- A package configuration does **not** prove an `.exe`/installer exists.

## Next verification gates

1. ✅ `docker compose config` validates stack configuration.
2. ✅ Build and start all services; inspect health endpoints/logs.
3. ✅ Create/retrieve persistent Core records and restart stack.
4. ✅ Configure a real model only through local secrets; run Chief response through Hermes adapter.
5. Verify group/delegation event flow against actual Hermes Runs/SSE (crew mention routing + `@HANDOFF` follow-up run).
6. Execute an n8n routine and record Core conversation event.
7. Build/test Host Bridge policy/approval boundary.
8. Install Rust only with operator approval, then build and install/launch desktop artifact.
9. Configure Telegram only with operator-provided credentials, then send the required completion notification after a real successful acceptance flow.
