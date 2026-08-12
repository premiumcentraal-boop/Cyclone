# Cyclone Delivery Status

This file separates **implemented**, **verified**, **blocked**, and **planned** status. It is deliberately not a marketing claim.

## Evidence log

- **2026-08-12 — discovery complete:** repository was an empty Git repository on `main` with remote `premiumcentraal-boop/Cyclone`.
- **2026-08-12 — local prerequisites:** Docker Desktop engine is reachable (`29.3.0` client/server, Linux containers, Compose `v5.1.0`). Node `v22.23.2` and npm `12.0.2` are installed. Rust/Cargo were not found in PATH; Tauri installer build is therefore not yet feasible.
- **2026-08-12 — upstream research:** current official xAI Grok Bot, NousResearch Hermes Agent (including upstream `docker-compose.windows.yml`), and n8n Docker guidance were inspected. Findings are in [`ARCHITECTURE.md`](ARCHITECTURE.md).
- **2026-08-12 — visual reference:** `C:\Users\Agent\Downloads\Grok-Bot-featured-image-scaled.webp` was inspected. Cyclone adopts original conversation-first design principles rather than copying branding/assets.

## Acceptance matrix

| Requirement | Status | Evidence / blocker |
|---|---:|---|
| Clean Cyclone repository | **In progress** | Repository initialized; implementation files are being added. |
| Architecture research recorded | **Verified** | [`ARCHITECTURE.md`](ARCHITECTURE.md). |
| Docker environment starts reliably | **Not yet tested** | Compose stack is still being implemented. |
| Real Hermes connected | **Not yet tested** | Official image/API design selected; requires live Compose run. |
| Real configured model response | **Blocked pending credentials** | No model/provider credentials assumed or exposed. |
| Persistent agents | **In progress** | Core schema/API implementation pending. |
| Group/cluster conversations | **In progress** | Core schema/API implementation pending. |
| Real delegation and activity in chat | **In progress** | Must be backed by Hermes Runs/SSE and delegation events. |
| Shared persistent workspace | **In progress** | Compose bind/volume design pending. |
| Obsidian vault + retrieval | **In progress** | Vault bootstrap and lexical retrieval pending. |
| Semantic retrieval | **Deferred** | Requires a real embedding/index service and verification; not to be claimed early. |
| n8n service and end-to-end routine | **In progress** | Service and event contract pending. |
| Telegram shared agent network | **Blocked pending credentials** | Requires a valid Telegram bot token and authorized chat/user. |
| Host Bridge approved tools | **In progress** | Native host service/policy test pending. |
| Restart persistence | **Not yet tested** | Must restart built stack and verify stored records/files. |
| Windows desktop executable | **Blocked by missing Rust toolchain** | Tauri build requires Rust/Cargo. Installing system software needs operator approval. |
| Windows installer | **Blocked by desktop build prerequisite** | Depends on executable build plus installer validation. |
| GitHub CI succeeds | **Not yet tested** | Workflow implementation and remote authentication/testing pending. |
| Full prescribed end-to-end test | **Not yet tested** | Must use actual configured model + Telegram credentials; cannot simulate. |

## Do not infer

- Docker Desktop availability does **not** prove this project stack works.
- Source files or mock/test responders do **not** prove a real provider is configured.
- Telegram workflow code does **not** prove a message has been delivered.
- A package configuration does **not** prove an `.exe`/installer exists.

## Next verification gates

1. `docker compose config` validates stack configuration.
2. Build and start all services; inspect health endpoints/logs.
3. Create/retrieve persistent Core records and restart stack.
4. Configure a real model only through local secrets; run Chief response through Hermes adapter.
5. Verify group/delegation event flow against actual Hermes Runs/SSE.
6. Execute an n8n routine and record Core conversation event.
7. Build/test Host Bridge policy/approval boundary.
8. Install Rust only with operator approval, then build and install/launch desktop artifact.
9. Configure Telegram only with operator-provided credentials, then send the required completion notification after a real successful acceptance flow.
