# Cyclone

Cyclone is a Windows-first agent operating environment: a native desktop experience over a persistent Docker-based agent computer. It combines:

- **Cyclone Desktop** — a Tauri + React desktop application for conversations, agent rosters, clusters, tasks, approvals, routines, and diagnostics.
- **Cyclone Core** — the control plane and API that owns structured application state, orchestration, memory pipeline, audit history, n8n integration, and the Hermes adapter.
- **Hermes Agent** — the agent runtime for provider-agnostic model execution, tools, skills, sessions, delegation, gateway messaging, schedules, and Telegram.
- **Obsidian vault** — normal Markdown files on Windows, mounted into the stack as the durable human-readable knowledge layer.
- **n8n** — deterministic event and automation processing, integrated only through Cyclone Core.
- **Cyclone Host Bridge** — a localhost-only, authenticated Windows service with narrow, auditable host capabilities and approval enforcement.

> **Status: foundation implementation in progress.** The repository is being built vertically and verified at each stage. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/STATUS.md`](docs/STATUS.md) for the evidence-backed design and live verification status.

## Design principles

Cyclone is intentionally not a generic AI dashboard. The default experience is a conversation with a team of persistent agents:

- named agents instead of anonymous model sessions;
- work, handoffs, decisions, approvals, and results rendered naturally in chat;
- one persistent shared workspace for project artifacts, while agent identities and runtime state remain distinguishable;
- real structured task state beneath the conversational surface;
- least privilege and approval boundaries for consequential work;
- Docker is the agent computer; Windows control goes only through an explicit Host Bridge.

## Repository layout

```text
apps/
  cyclone-core/       FastAPI control-plane service
  desktop/            Tauri + React desktop client
  host-bridge/        Windows-native restricted host capability service
services/
  hermes/             Hermes profiles, templates, and container configuration
  n8n/                n8n bootstrap assets and routine templates
packages/
  protocol/           Shared API/event contracts
  ui/                 Shared visual primitives
  shared/             Cross-service utilities

docker/
  docker-compose.yml  Local private Docker stack
vault/
  templates/          Obsidian vault templates
scripts/              Development, verification, and packaging helpers
docs/                 Architecture, security, operations, and acceptance evidence
```

## Prerequisites

- Windows 10/11
- Docker Desktop with Linux containers and Docker Compose v2
- Node.js 22+
- Rust stable (required only to build the Windows Tauri executable)
- A model provider configured in Hermes (for example `DEEPSEEK_API_KEY`)

Cyclone detects Docker Desktop at runtime. It does **not** silently install Docker Desktop, model credentials, or Telegram credentials.

## Quick start (development)

1. Copy `.env.example` to `.env` and set strong local secrets. Do **not** commit `.env`.
   Compose reads the `.env` file next to the compose file (`docker/.env`); when keeping
   it at the repository root, pass it explicitly (all examples below do).
2. Create the real Obsidian vault at `C:\Users\<you>\Documents\CycloneVault` using the documented structure, or override `CYCLONE_VAULT_HOST_PATH` in `.env`.
3. Start the environment:

   ```bash
   docker compose -f docker/docker-compose.yml --env-file .env up --build
   ```

   The `hermes-config` one-shot aligns the Hermes gateway's default provider with
   your credentials automatically: a `DEEPSEEK_API_KEY` selects the DeepSeek native
   API with `deepseek-v4-flash` as default; otherwise an `OPENROUTER_API_KEY`
   selects OpenRouter. Neither key present leaves image defaults untouched.

4. Open the Cyclone Core health endpoint at `http://127.0.0.1:8787/health`.
5. Open n8n locally at `http://127.0.0.1:5678` if you enabled its UI port.

Hermes is an internal service. Cyclone Core is its sole application-facing adapter and uses authenticated, private Docker-network traffic.

## Security posture

- Host ports are bound to `127.0.0.1` by default.
- No Docker socket is mounted into agent containers.
- Hermes API access is bearer-authenticated and not called directly by the UI.
- Cyclone Host Bridge is localhost-only, token-authenticated, allowlisted, time-bounded, and audit logged.
- Consequential actions require a policy decision/approval; Telegram follows the same policy path.
- Secrets live in local `.env` / Docker secrets-compatible deployment config, never source control.

## Current scope and honest limits

Cyclone will not claim a configured LLM, Telegram delivery, an installed `.exe`, or a complete acceptance scenario until those components have actually run with local credentials and environment prerequisites. The project records implemented, verified, and blocked items separately in [`docs/STATUS.md`](docs/STATUS.md).

## Sources

The product and architecture research was based on official sources, recorded with precise citations in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md):

- xAI Grok Bot documentation
- NousResearch Hermes Agent documentation and current upstream source
- n8n Docker deployment documentation

## License

Proprietary — Northstar Labs. Third-party components remain subject to their own licenses.
