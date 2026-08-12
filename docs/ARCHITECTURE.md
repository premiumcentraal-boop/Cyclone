# Cyclone Architecture

> **Decision status:** Implemented foundation decision.  
> **Last evidence review:** 2026-08-12.  
> **Scope:** Windows desktop product, private local Docker stack, Hermes Agent runtime, Obsidian vault, n8n, and a restricted Windows Host Bridge.

## 1. Executive decision

Cyclone is a **conversation-first agent operating environment**, not a wrapper around a chat completion endpoint. It uses a hybrid architecture:

```text
Cyclone Desktop (Tauri / React, Windows)
             │ localhost HTTP + SSE
             ▼
Cyclone Core (control plane / API; Docker)
  ├── PostgreSQL: structured state, tasks, approvals, audit references
  ├── Redis: bounded event fan-out and work coordination
  ├── Hermes Adapter: authenticated private API integration
  │     └── Hermes: model execution, sessions, skills, tools,
  │                 delegation, messaging gateway, cron
  ├── Memory pipeline: Markdown vault writes + keyword retrieval
  ├── n8n Adapter: approved workflow trigger/event ingress
  └── Host Bridge Adapter: authenticated localhost control plane
             │
             ├── Obsidian vault bind mount (/vault)
             └── Persistent shared workspace bind mount (/workspace)

Cyclone Host Bridge (Windows native, loopback-only)
  └── narrow approved host tools: files, process list, app launch,
      PowerShell, git, browser, screenshots, window listing
```

**Core decision:** Cyclone Core owns the product model and policy. Hermes remains the agent brain; n8n remains deterministic automation; Obsidian remains durable human-readable knowledge. Cyclone does not reimplement their reliable functionality.

## 2. Required investigation findings

### 2.1 Grok Bot product principles adopted

Official xAI documentation describes Bots as persistent named AI teammates, messageable like colleagues, that keep users updated in conversations, collaborate independently, and share a persistent computer.[1] It explicitly distinguishes shared working state from a security boundary: files, browser sessions, and command-line credentials are shared by a user’s Bots, so separate Bots must not be treated as separate privilege domains.[1][2]

Cyclone adopts these **product principles**, not proprietary implementation or branding:

| Principle | Cyclone implementation |
|---|---|
| Persistent named teammates | `Agent` records with name, avatar, role, instructions, model selection, skills, runtime profile, workspace, memory scope, and status. |
| One durable agent computer | Private Docker stack with named data volumes and a shared project workspace. Restarts preserve database, Hermes data, n8n data, workspace, and vault. |
| Conversations as the work surface | Chat streams show ordinary messages plus explicit activity, task, handoff, approval, and result events. |
| Independent collaboration | Cyclone coordinator creates structured tasks and invokes Hermes delegation; background child activity arrives as events. |
| Routines | Reusable Cyclone records that pair an optional Hermes instruction/skill set with an n8n workflow trigger. |
| Human control at sensitive boundaries | Policy and approval state are enforced before host actions, external delivery, destructive actions, publishing, money, credential/OTP entry, or production operations. |

xAI recommends using structured connectors before browser automation, and specifies that passwords, passkeys, 2FA, CAPTCHAs, payments, and human-only checkpoints should be handed to the user instead of bypassed.[2] Cyclone adopts that tool order and escalation rule.

### 2.2 Screenshot visual study

A supplied Grok Bot reference image was located at `C:\Users\Agent\Downloads\Grok-Bot-featured-image-scaled.webp` and visually inspected. It demonstrates a restrained split-pane conversation product: a persistent agent roster/sidebar, color-coded but quiet agent avatars, a wide work conversation, rich artifacts inline, scannable checkmark/action summaries, a high-contrast human bubble, and a lightweight final receipt.

Cyclone’s original visual system uses that **interaction hierarchy**:

- conversation first; infrastructure remains under Diagnostics;
- compact searchable agent/chat rail with identity, recency, and a one-line outcome preview;
- calm off-white surfaces, restrained borders, readable sans typography, and one purposeful accent per agent;
- task progress as plain-language action receipts and small state chips—not animated fake activity;
- evidence/artifacts visible in the thread alongside the message that produced them;
- explicit approval cards that name target, scope, inputs, expected effect, and policy reason.

**Uncertainty:** the supplied asset is a promotional/reference composition, not a complete interaction specification. Visual behavior not visible in the asset is designed independently and should not be represented as observed Grok behavior.

### 2.3 Hermes capabilities reused

Current Hermes documentation and upstream checkout directly support the core runtime requirements:

| Capability | Evidence | Cyclone decision |
|---|---|---|
| Persistent mutable state under a mounted `/opt/data` directory | Hermes Docker documentation lists config, sessions, memories, skills, profiles, cron, and logs under the mount.[3] | Mount a dedicated `hermes_data` named volume; never share it concurrently across separate Hermes gateway containers. |
| Windows Docker Desktop compose support | Upstream includes `docker-compose.windows.yml`; it removes host networking, uses explicit port mapping, and mounts `${USERPROFILE}/.hermes:/opt/data`.[4] | Follow its Windows/Docker Desktop direction but integrate Hermes as one service inside Cyclone’s private bridge network. |
| Supervised multi-profile gateways | Hermes documents one container holding multiple profiles with s6-managed profile gateways.[3] | Initial vertical slice uses a single Cyclone “Chief” profile; Cyclone agents map to profiles/configuration only where isolation is needed, rather than creating one uncontrolled container per agent. |
| OpenAI-compatible plus Hermes-native API | Hermes exposes authenticated Responses, Runs (SSE/cancel/approval), Sessions, Skills, Toolsets, Model options, health, and Jobs endpoints.[5] | Cyclone Core is the sole adapter; Desktop never calls Hermes directly. Use Runs/SSE for activity and child delegation lifecycle events; Core persists normalized event records. |
| Provider configurable per request | Hermes supports request-scoped provider/model selection on native routes and configured model routing.[5] | Store non-secret provider/model preference per Cyclone agent; resolve credentials only in Hermes config/secrets. |
| Delegation/subagents | Hermes `delegate_task` creates isolated child agents; background completion is delivered durably after completion; delegation has lifecycle visibility and child summaries.[6] | Coordinator uses the Hermes native delegation rather than inventing its own LLM worker runtime. Cyclone maintains structured task/handoff state around the real results. |
| Telegram gateway | Hermes has native Telegram support, authorization controls, group mention routing, topic sessions, approvals, and delivery mechanisms.[7] | Telegram is an ingress/egress channel connected to the same Core conversation and policy model, not a separate bot intelligence system. |
| Docker terminal backend | Hermes documents a persistent Docker sandbox backend distinct from running Hermes inside Docker.[3] | Agent tool environments are Docker-based. No Docker socket is mounted into general-purpose agent containers. |

### 2.4 n8n capabilities reused

n8n recommends Docker Compose for current self-hosted deployment and documents persistent `/home/node/.n8n` storage even when PostgreSQL is used, because it contains encryption keys, logs, and other instance assets.[8] n8n also documents PostgreSQL configuration via environment variables and explicitly calls out timezone configuration for schedule behavior.[8]

Cyclone therefore uses n8n as an internal, persistent deterministic automation worker:

```text
Approved Core routine → n8n webhook → workflow executes deterministic steps
n8n workflow → signed Core event endpoint → Core records automation event
Core → relevant conversation event + optional Hermes agent follow-up
```

No UI component owns arbitrary n8n webhooks. Core holds the integration contract.

## 3. Service architecture

### 3.1 Docker services

| Service | Purpose | Persistent storage | Host exposure |
|---|---|---|---|
| `cyclone-core` | Product API, orchestration, policy/approval gate, memory pipeline, Hermes/n8n adapters | PostgreSQL + vault/workspace bind mounts | `127.0.0.1:8787` |
| `hermes` | Official Hermes gateway / agent execution | `hermes_data` | **none**; Core uses private network |
| `n8n` | Deterministic workflow automation | `n8n_data`, PostgreSQL | `127.0.0.1:5678` only when local workflow editing is enabled |
| `postgres` | Durable structured app data and n8n DB | `postgres_data` | none |
| `redis` | Short-lived coordination/event fan-out only | `redis_data` (AOF) | none |
| `browser-worker` (later) | Isolated browser automation environment | named browser profile/workspace volumes | none |

All communicate only over a non-internal private bridge network. Only intentionally user-facing development/control ports bind to `127.0.0.1`. The Docker socket is never mounted into `hermes`, `cyclone-core`, or browser worker containers.

### 3.2 Data model (logical)

The Core owns these durable entities:

- `agents`: persistent named agent configuration, permission profile, Hermes profile binding, status.
- `conversations`: DM, group/cluster, and channel thread identity.
- `conversation_members`: human and agent membership/roles.
- `messages`: authored content and normalized rich event references—not raw tool logs as permanent memory.
- `tasks`: objective, owner, dependency, lifecycle, retry/escalation state, verification requirement.
- `handoffs`: agent-to-agent task/context transfer record.
- `approvals`: proposed capability/action, scope, expiry, policy rationale, decision, audit link.
- `routines`: reusable work definitions, manual/scheduled/event trigger routing, n8n workflow binding.
- `automation_events`: authenticated n8n ingress records.
- `memory_candidates` / `knowledge_entries`: controlled vault pipeline and retrieval index metadata.
- `audit_events`: immutable references to security-sensitive decisions/actions.

The initial implementation keeps data in a small, explicit schema rather than claiming an untested enterprise workflow engine.

## 4. Agent and cluster orchestration

### 4.1 Conversation behavior

A user creates or opens a conversation. Core selects the target agent (or Chief in a cluster), creates a Core task/event, and starts a Hermes run via the private adapter. The conversation subscribes to Core SSE.

For a cluster, the Chief’s instructions require it to decompose only when useful; specialist work is initiated through actual Hermes delegation. Core translates actual start/complete/failure signals into conversation events and links them to structured tasks. It does not fabricate typing indicators or mock “agent activity.”

### 4.2 Task state machine

```text
queued → running → awaiting_review → completed
                 ↘ blocked → queued | cancelled | failed
awaiting_approval ↔ running
```

- A handoff records source agent, target agent, task objective, relevant artifact locations, and explicit completion criteria.
- Review work is a real task with an acceptance criterion—not a decorative status label.
- Retry/cancel/escalation decisions are explicit Core operations with audit events.
- The UI’s activity feed is derived from task, run, approval, and automation event records.

### 4.3 Runtime boundaries

Persistent agents are identities/configuration in Core, not necessarily permanently running model processes. Hermes profiles are used selectively for true configuration/memory/tool separation. Shared workspaces are an intentional convenience boundary, never a privilege boundary. Sensitive domains may instead use separate workspace/mount/credential scopes.

## 5. Obsidian knowledge and memory

### 5.1 Vault contract

The vault remains a normal Windows Obsidian vault at:

```text
C:\Users\<user>\Documents\CycloneVault
```

It is bind-mounted read/write to `/vault` for the Core memory pipeline. The initial folders are:

```text
Agents/       Projects/   People/      Research/    Decisions/
Knowledge/    Routines/   Sessions/    Tasks/       Skills/
System/       Inbox/      Archive/
```

### 5.2 Pipeline

```text
conversation/task outcome
    → candidate detection
    → classify and ask/obtain approval when required by policy
    → concise Markdown record with source/context links
    → write to a bounded vault category
    → extract keyword metadata and content fingerprint
    → retrieve via lexical relevance + recency + project/agent scope
```

The first implementation provides tested keyword retrieval and a provider-neutral retrieval interface. Semantic embedding is an optional worker extension; it is not claimed as active until its model/index service has actually run. Raw conversations are not automatically dumped into long-term memory.

## 6. n8n routines

A routine has a Cyclone owner agent, instructions, allowed trigger types, optional Hermes skills, approval policy, n8n workflow reference, and delivery targets.

The first end-to-end routine is intentionally small and real:

```text
Manual / scheduled n8n trigger
  → POST authenticated automation event to Core
  → Core creates Automation activity message + task
  → Core starts the Chief through Hermes adapter (when configured)
  → result is persisted and emitted to conversation SSE
```

n8n never bypasses Core policy. A routine can request a capability, but Core decides whether it is already allowed, requires approval, or must be denied.

## 7. Telegram

Hermes owns the platform transport and native pairing/authorization. Cyclone does not recreate Telegram polling, bot credential management, or media delivery.

The integration pattern is:

```text
Telegram ↔ Hermes Gateway ↔ Cyclone Core adapter ↔ Core conversation/task model
                                      ↕
                               same approval policy
```

### Initial practical limitation

The initial local Docker integration exposes Core’s outbound notification endpoint and models inbound channel events. Full bidirectional synchronization requires an authenticated Hermes gateway hook/plugin or event adapter verified against the configured Telegram credentials. No completion notification is sent unless a valid token/authorized recipient exists and a delivery actually returns success.

## 8. Windows Host Bridge

Docker must not impersonate the Windows desktop. The Host Bridge is a separate native local service that supports an allowlisted capability contract:

```text
filesystem.read / filesystem.write
app.launch / process.list
powershell.execute / git.execute
browser.open / browser.navigate
screenshot.capture / window.list
```

### Required controls

- bind only to loopback;
- per-install generated bearer token or OS-bound secret;
- Core-only allowlisted origin/client identity;
- named tool permissions and path/application/command allowlists;
- timeouts and cancellation;
- request correlation and append-only audit log;
- destructive/consequential action approval requirement;
- no unrestricted unauthenticated shell;
- Telegram requests use the exact same Core policy evaluation.

Tool preference is: structured API/MCP → browser automation → accessibility/UI automation → raw pointer/keyboard only as a last resort. CAPTCHAs, authentication, payment, and identity steps stop for operator handoff.

## 9. Desktop architecture and installer approach

Cyclone Desktop uses Tauri + React/TypeScript. Its responsibilities are user experience and local lifecycle guidance, not direct database/container access.

1. detect Docker Desktop/engine availability;
2. present a clear guided requirement state if absent (do not silently install it);
3. call a restricted local lifecycle helper for `docker compose up/down/status`;
4. check Core, Hermes, n8n, memory, and Host Bridge health;
5. show human language startup stages: *Starting Cyclone → Starting agent environment → Hermes online → Automation engine online → Memory online → Ready*;
6. communicate only with Cyclone Core for application activity.

The production Tauri installer is built only once a Rust toolchain and Windows packaging prerequisites are present. Build/installer claims must cite actual artifact output.

## 10. Security decisions

| Threat / failure mode | Decision |
|---|---|
| Browser UI directly controls agent tools | Desktop only calls Core; Core calls private Hermes with bearer auth. |
| Agent container controls Docker host | No Docker socket mount. Lifecycle remains an explicit Desktop/host action. |
| Shared workspace mistaken for isolation | Workspace sharing is documented as collaboration convenience; sensitive agents need separate scopes. |
| Telegram bypasses safeguards | All requests resolve through same Core policy and approval records. |
| Wrongly exposed local services | Host mappings bind to `127.0.0.1`; Docker network is private; outbound exposure is opt-in. |
| Sensitive secrets leaked to source | `.env` excluded; configuration template has placeholders only. |
| Unsafe computer use | Structured tool/API priority, scoped Host Bridge, approval checkpoints, audit log. |
| Unreviewed n8n actions | n8n enters/exits through one Core integration layer and cannot bypass policy. |

## 11. Vertical delivery sequence

1. **Foundation:** Core health/state API, Compose, PostgreSQL/Redis, official Hermes container, one Chief, live model response through Hermes when credentials are configured.
2. **Persistent collaboration:** Core conversations, agents, group/cluster, structured task/handoff/review event path.
3. **Knowledge:** vault bootstrap, controlled Markdown memory writes, keyword retrieval, optional verified semantic index.
4. **Automation:** n8n persistent service, signed Core event contract, one real routine.
5. **Channels and host:** Telegram adapter with shared policy; Host Bridge capability server and approval test.
6. **Desktop and package:** original conversation-first Tauri UI, Docker lifecycle UX, Windows installer, CI.
7. **Acceptance:** execute—not simulate—the supplied multi-agent scenario, and record each component’s proof or blocker.

## 12. Open risks and uncertainty

1. **Screenshots:** one supplied promotional visual was inspected; it is insufficient to infer every interactive behavior. Cyclone uses its own components and branding.
2. **Model credentials:** no DeepSeek/model provider credential is assumed. A real response test is blocked until configured locally.
3. **Telegram:** no token or authorized recipient is assumed. No message will be sent without them.
4. **Host Bridge:** Windows packaging/tooling and security hardening must be verified against the actual desktop build environment; it must not be replaced with an agent-accessible generic shell.
5. **Semantic memory:** lexical retrieval can be verified without an embedding service. “Semantic retrieval works” is deferred until a real indexer/model runs and has retrieval tests.
6. **n8n:** workflow import/version details change upstream; tested lifecycle health and actual workflow execution are required before acceptance.
7. **Docker Desktop:** detected installed locally during investigation, but no claim is made that a complete stack starts until the compose file builds and health checks pass.

## Sources

[1] xAI, **Grok Bot overview** — https://docs.x.ai/grok-bot/overview

[2] xAI, **Use the computer and apps** — https://docs.x.ai/grok-bot/computer-and-apps

[3] Nous Research, **Hermes Agent — Docker** — https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/docker.md

[4] Nous Research, **`docker-compose.windows.yml` (upstream)** — https://github.com/NousResearch/hermes-agent/blob/main/docker-compose.windows.yml

[5] Nous Research, **Hermes API Server** — https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/api-server.md

[6] Nous Research, **Hermes Subagent Delegation** — https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/delegation.md

[7] Nous Research, **Hermes Telegram integration** — https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/messaging/telegram.md

[8] n8n, **Install with Docker** (points to Compose as current recommendation and documents persistent data/PostgreSQL/timezone settings) — https://docs.n8n.io/deploy/host-n8n/install-options/install-with-docker
