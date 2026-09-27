# 33 — Cyclone Command Center: one dashboard for your accounts, tasks, routines and phones (research)

**Status:** research, 2026-09-27. Not scheduled; plans 24–32 come first.

**The owner's ask:**
> "Credentials can be safely sent to the phone in tasks, and managed and saved in the dashboard. It should hold all
> this data safely and manage my accounts, new tasks and routine automations in one dashboard. AI should be able to
> manage the fleet of phone agents doing legitimate work on the phones."

**In one sentence:** a Notion-like workspace on top of a password vault and a job system, where you (and an AI
coordinator you supervise) hand work to your own Cyclone phones. The phones fill secrets themselves, report results
back, and never show a password to any model.

---

## 1. What already exists and carries over

| Need | Cyclone today |
|---|---|
| A worker on each phone | The Mind, missions, `PhoneToolExecutor`, direct-first tools, background screens |
| Asking a human | Owner Moments (question, values, approval, secret, handover), GATE on pay/send/delete, Task Kit |
| Secrets | Vault slots: values live in the phone's Keystore; the wire and the model only see slot names (plan 05) |
| Proof that work happened | Verified completion, run records, cause of death, the run inspector (plan 11) |
| Quality | Lab: measured missions and suites (plan 18) |
| Reusable work | Recipes (plan 19), grounded skills and routines (plan 23) |
| A dashboard | Glass, served by the local gateway (plans 03, 31) |
| Outside tools | MCP in both directions (agent MCP, Remote MCP; phone-side MCP planned in plan 19 Phase 3) |

The Command Center is mostly these, **centralised**:
- one vault instead of one per phone;
- one schedule instead of routines per phone;
- one place where every result lands;
- many phones instead of one.

## 2. The workspace (what you see)

**Five sections, each a database with table, board, calendar and gallery views, and pages that embed live blocks:**

| Section | Rows | Useful views |
|---|---|---|
| **Accounts** | One per account you own: service, login name, which vault item holds its secret, which phones may use it, 2FA type, notes, last used, health | Table; "needs attention" (password expired, login failed) |
| **Tasks** | One-off work: goal sentence, inputs, target phone or "any phone that has app X", accounts it may use, due time, status | Board by status; calendar by due date |
| **Routines** | Repeating work: schedule or trigger, recipe, accounts, which phones, pause switch | Calendar; "next 24 h" |
| **Phones** | Every device: model, Android, Cyclone version, online/battery/storage, apps, current task | Grid of live tiles; health table |
| **Results** | Every run: outcome, steps, screenshots (secrets masked), files produced, cost | Timeline; per-task history; "failed this week" |

**Pages** mix text and live blocks. For example, a "Monday social posts" page holds:
- your notes;
- an embedded routine;
- the last three results with their screenshots;
- a Run now button.

Templates make repeat set-ups one click.

**The approval inbox** is one queue for everything waiting on you, across all phones:
- a send to approve (with the exact text);
- a question;
- a verification code to enter;
- a login that needs your fingerprint.

Every item is answered once, from the dashboard, your phone or a notification.

## 3. Credentials: stored, sent and used safely

This is the heart of the ask. The design goal is that **no model, log, screenshot or report ever contains a secret
value**, while phones can still log in on their own.

### 3.1 Where secrets live

- **The central vault** holds each account's secrets (password, TOTP seed if you choose to store it, recovery codes,
  passkey where the service allows).
- **Zero-knowledge encryption** (the Bitwarden/1Password model): items are encrypted with keys derived on your devices.
  The server stores ciphertext only, so a database leak does not expose passwords.
- **Unlock** with a passkey or biometrics.
- **Every read** is written to an append-only audit log (who, which item, which task, when).

### 3.2 How a phone gets a secret for a task

1. **The task names vault items, never values:** "log in to Instagram as @mybrand using `vault:ig-mybrand`."
2. **A lease is issued.** When a phone starts that task, the control plane issues a short lease (for example
   15 minutes, one use) for exactly those items to exactly that phone.
3. **The secret travels end-to-end encrypted:**
   - each phone has a device key pair in its hardware Keystore, created at enrollment;
   - the secret is encrypted to that phone's public key, so the server and network only relay ciphertext.
4. **The phone decrypts inside Cyclone's vault layer.** It fills the field through the existing `vault_fill` path (the
   Mind asks for "fill slot ig-mybrand.password"; it never sees the value), then wipes it from memory when the lease
   ends.
5. **Masking:** screenshots of password fields are masked before they leave the phone, and the run record shows
   "filled from vault".

### 3.3 Two-factor codes

The options, safest first:
- **Passkeys or app approvals on your own device:** the task pauses with an approval ("Approve this login on your
  phone").
- **A TOTP seed you chose to store:** the phone computes the code locally at fill time, the same as the password.
- **SMS or email codes:** the task asks you through the approval inbox, or reads it from your own inbox only if you
  connected that mailbox and allowed it for that account.

Codes are never stored after use.

### 3.4 New accounts you sign up for

When a task signs you up to a service:
1. The phone asks you to approve the sign-up.
2. It generates a strong password inside the vault layer.
3. It fills the form.
4. It saves the new item to your vault with its source ("created by task X on phone Y").

Verification steps (CAPTCHAs, ID checks, SMS) are handed to you as approvals, and Cyclone does not try to get around
them. The Accounts table gains the new row automatically.

### 3.5 Rotation and hygiene

- A routine can change a password: the vault makes a new value, the phone applies it, and the vault keeps the old one
  until the login is verified.
- A health view flags reused, weak or old passwords, and accounts with no 2FA.

## 4. The AI coordinator

**What it does:** an assistant in the dashboard that plans and supervises work across phones, while the phones'
Minds still do the tapping.

**What it can do**, through a fixed set of tools, the same way Glass talks to the gateway today:
- `tasks.create`, `routines.create/pause`, `runs.list/get`, `phones.list/status`;
- `approvals.list` (read only);
- `reports.write` (a page in the workspace).

**What it cannot do:**
- read secret values (it only sees vault item names);
- approve its own requests;
- change who may use an account;
- run any generic shell.

Approvals stay with you (GATE and the approval inbox).

**How it chooses phones:** a scheduler picks a phone that has the app, is online, has the account's lease available,
and is not busy. Each account is used by one phone at a time.

**What it reports:** a daily page of what ran, what failed and why (from the cause of death), and suggested fixes. It
can re-run a failed task once, with the same inputs.

**Guarding against instructions in content:** text the phones read from apps, messages and web pages is treated as
data, never as instructions. This is the framing the Mind already uses for untrusted content.

## 5. Connections (MCP) to other platforms

Tasks can call outside tools, for example making a video with **Higgsfield**. Higgsfield has an official hosted MCP
server at `mcp.higgsfield.ai/mcp` with OAuth sign-in.

**How it works:**
- **One MCP gateway in the control plane** holds each connection's OAuth grant. Phones and the coordinator call tools
  through it and never hold the tokens.
- **Per connection, you set:**
  - which recipes and tasks may use it;
  - a spending cap (credits per day);
  - whether each call needs your approval.
- **Results become artifacts,** for example the video file. A later step can hand the artifact to a phone ("post this
  video to @mybrand with this caption"), with the post approved like any send.
- **Candidate open-source gateways:** IBM ContextForge, Obot, Microsoft MCP Gateway. Each adds a registry, OAuth
  brokering and policy.
- **The other direction:** the Command Center itself can be an MCP server, so your other agents (Claude, Codex) can
  create tasks or read results with the same permissions and approvals.

## 6. Architecture

```
            Command Center (cloud or your own server)
   ┌──────────────────────────────────────────────────────────────┐
   │ Workspace UI (pages + databases)     AI coordinator (tools) │
   │ API + auth (passkeys, roles) ─ Policy (who may use what)     │
   │ Jobs & schedules (durable workflows) ─ Approval inbox        │
   │ Vault (zero-knowledge items, leases, audit)                  │
   │ MCP gateway (OAuth grants, caps)  ─  Results store + search │
   └───────────────▲──────────────────────────────▲──────────────┘
          outbound mTLS (phones/gateways dial out; nothing listens on your network)
   ┌───────────────┴──────────────┐   ┌───────────┴──────────────┐
   │ Edge gateway (a PC at home/  │   │ Cloud phones (e.g. VMOS)  │
   │ office: today's cyclone      │   │ via their own gateway     │
   │ gateway, grown up)           │   └──────────────────────────┘
   └───────┬──────────────────────┘
           USB / Wi-Fi
   Cyclone phones: Mind + PhoneToolExecutor + GATE + vault layer (device key in Keystore)
```

**Principles:**
- **The phones decide and act; the center assigns and records.** A phone that loses the uplink finishes its current
  task and queues its report.
- **Durable jobs:** each task is a workflow that survives restarts. Every step is idempotent, and "done" means
  verified, not "the tap was sent".
- **One source of truth per thing:**
  - accounts and secrets in the vault;
  - schedules in the job system;
  - results in the results store;
  - the workspace pages link to them rather than copying them.

## 7. Open-source building blocks

These are the candidates to evaluate. **Check each licence before adopting;** several changed licence in 2024–2026.

| Layer | First choice | Alternatives | Notes |
|---|---|---|---|
| Vault engine | **OpenBao** (the MPL fork of Vault) | Infisical; Vaultwarden (the Bitwarden server API) for the password-manager UX | Zero-knowledge item encryption stays client-side, whatever the engine |
| Password-manager UX reference | Bitwarden clients (GPL) | — | Study, don't copy: item model, sharing, passkeys |
| Auth / SSO | Keycloak | Ory (Kratos/Hydra), Zitadel | Passkeys (WebAuthn) first |
| Permissions | OpenFGA or SpiceDB (Zanzibar-style: "phone P may use account A for recipe R") | Cerbos, OPA/Cedar for policies | — |
| Durable jobs | **Temporal** | Hatchet, Inngest, Prefect | Timers, retries and per-task history for free |
| Messaging to edges | NATS JetStream (leaf nodes at each site) | MQTT (EMQX), Redis Streams | Outbound-only connections |
| Database | PostgreSQL (row-level security per workspace) | — | The workspace databases are Postgres tables |
| Results and events | ClickHouse | TimescaleDB | Fast "failed this week" queries |
| Files and screenshots | S3-compatible storage (Garage, SeaweedFS, or a cloud bucket) | — | Content-addressed artifacts |
| Notion-style editor | BlockNote or Tiptap on Yjs (Hocuspocus for sync) | AFFiNE and AppFlowy as whole-app references | Blocks that embed live tasks, runs and phones |
| Database views | Build on your own tables; Baserow and NocoDB as references | Refine / TanStack Table for the grid | Keep one data model; don't bolt on a second app |
| Live phone screens | Today's scrcpy path; LiveKit (WebRTC) to reach the cloud | — | View only, unless someone takes over |
| Device enrolment and updates | Android Management API (for company-owned phones), Headwind MDM | Today's release lane for the Cyclone app | Device-owner mode makes installs silent |
| Virtual phones | Cloud phones you already use (VMOS), Redroid or Cuttlefish for test devices | — | — |
| Observability | OpenTelemetry, Grafana, Langfuse (model calls) | SigNoz | Secrets are redacted before any log |
| MCP gateway | IBM ContextForge, Obot | Microsoft MCP Gateway | OAuth 2.1 per the MCP spec |
| Billing (if sold) | Lago or OpenMeter for metering | Stripe | Per phone-hour + runs |

## 8. What makes it rock solid (how a top company would run it)

- **Reliability:**
  - service levels per phone ("task picked up within 60 s, 99% of the time");
  - canary rollout of Cyclone and recipe updates to 5% of phones first, gated by the Lab success rate;
  - automatic quarantine of a phone that fails three tasks in a row;
  - every failure has a cause-of-death code.
- **Security:**
  - zero-knowledge vault, device keys in hardware, short single-use leases;
  - passkeys for people;
  - least-privilege roles (viewer, operator, admin, vault admin);
  - an append-only audit log;
  - independent penetration tests;
  - SOC 2 and GDPR once others use it.
- **Safety:**
  - GATE approvals for pay, send and delete stay on;
  - per-account rate limits that follow each platform's rules;
  - one kill switch that pauses every phone;
  - automations run only on accounts you own or manage.
- **Operations:**
  - a status page;
  - backups with tested restores (the vault is exported encrypted);
  - on-call alerts for "phones offline" and "success rate dropped".
- **Product quality:**
  - every recipe carries a measured success rate (Lab);
  - results always link to the run inspector;
  - the AI coordinator's actions are visible and undoable where possible.

## 9. Use cases this serves

- **Your brands' social accounts:** make content (Higgsfield via MCP), post on schedule, answer comments with
  approval, weekly report.
- **Business apps that only exist on mobile:** daily data entry, order checks, exporting reports.
- **Personal admin:** bills (read only, paying approved), appointments, calendar, messages.
- **QA of your own apps:** run test recipes across phones and Android versions on every release.
- **Monitoring:** check key mobile flows every hour, and alert when a login or checkout breaks.

## 10. Build order (Cyclone-sized steps)

| Step | What | Builds on |
|---|---|---|
| C0 | **Glass as a mini Command Center for your own phones:** Accounts list (vault item names from the phones), Tasks and Routines across connected phones, Results page | Glass, the gateway, phone vault slots, routines |
| C1 | **Central vault:** zero-knowledge items, device keys at enrolment, leased end-to-end delivery to `vault_fill`, audit log | Plan 05 vault; phone Keystore |
| C2 | **Durable jobs and the approval inbox:** Temporal-backed tasks and routines, one inbox for every phone's Owner Moments | Owner Moments, Task Kit |
| C3 | **The MCP gateway:** connections with OAuth, caps and approvals; artifacts; Higgsfield as the first | The Remote MCP pages |
| C4 | **The AI coordinator:** fixed tools, scheduler, daily report page | The run inspector, Lab |
| C5 | **Multi-site and cloud:** edge gateways dialling out, cloud phones, the Notion-style editor, roles | Web-only PC (plan 31) |

**Start with C0 + C1.** Together they deliver the core of the ask: your accounts and secrets in one safe place, and
phones that can log in on their own for your tasks, without a model ever seeing a password.
