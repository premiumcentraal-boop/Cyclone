# 34 — Connector maker: connect any MCP, yourself

**Status:** plan, 2026-09-27. Builds on plan 33 C3 (alpha.56). Nothing here is built yet.

**The owner's ask:**
> "A self serve MCP connector maker to automatically connect custom MCPs." Local MCP servers on the PC are allowed,
> "make it clear in setup what this means, very short and human". Read and write. "It needs to be able to send
> requests to external services and get results back."

**In one sentence:** you paste a server address, a server config, or an API description, and Cyclone works out the
rest: how to reach it, how to sign in, which tools it has and which of them change things. You confirm, and tasks and
routines can use it to read and write in outside services and bring the results back.

---

## 0. Decisions (owner, 2026-09-27)

- **Local MCP servers on the PC are allowed.** Setup says plainly what that means (§3.1).
- **Read and write.** Connectors may both read and change things in outside services. Reading tools can be allowed in
  one click. Tools that send, post, delete, pay or change permissions still wait for your OK. This is the same
  approval boundary as everywhere else in Cyclone.
- **Outside services, both ways.** Connectors send requests to external services and bring the results back to the
  Command Center: text, data and files, as artifacts.

## 1. What the owner gets

- **Add a connection** takes any of three things:
  - a **server address** (`https://…/mcp`);
  - a **server config** copied from a README (the usual `mcpServers` JSON: `npx …`, `uvx …`, `docker …`);
  - an **API description** (an OpenAPI/Swagger URL or file) for services that have no MCP server.
- **Cyclone checks it step by step** and says each result in plain words: "Reached it", "It wants you to sign in",
  "It wants an API key in X-Api-Key", "12 tools: 8 read, 4 change things".
- **One screen to confirm:**
  - the tools, grouped as **Reads** and **Changes things**;
  - **Allow all reads** in one click;
  - changes are allowed per tool, each with its rule (ask every time, ask over the cap, or no asking up to the cap);
  - sends, deletes, payments and permission changes always ask;
  - a **Try it** button that runs one reading tool and shows the real answer.
- **Use it anywhere:** in the "First make or fetch with a connection" step of tasks and routines, and later from the
  coordinator (C4). The coordinator may suggest a connection but never add one.

## 2. Remote servers: automatic connect (M1)

- **Transport:** Streamable HTTP first, then the older HTTP + SSE transport.
- **Sign-in, chosen from what the server says:**
  - OAuth with self-registration (built in C3);
  - OAuth with a Client ID Metadata Document (the newer MCP auth rule; Cyclone hosts its document on the loopback
    gateway);
  - OAuth with a client ID and secret you paste, for servers that don't allow self-registration;
  - an **API key or custom header** (`Authorization: Bearer …`, `X-Api-Key: …`);
  - none.
- **Where keys live:** keys, headers and client secrets are sealed in the same DPAPI store as OAuth tokens (C3). They
  never go in the database, Glass after you type them, the audit, logs or a model.
- **Tool classes:** from the tool's own hints (`readOnlyHint`, `destructiveHint`), then its name and description
  words (delete, remove, send, post, publish, pay, transfer, grant, share). Unknown means *changes things*.
- **Per-tool rules:** a new `connection_tool` table (connection, tool, allowed, rule, class, pinned hash) replaces the
  per-connection approval rule. The per-connection daily cap stays as a total.
- **Auto-pairing:** a tool that returns a job id is paired with a reading tool that takes one, so background jobs
  (Higgsfield) are checked without choosing by hand.
- **Pinning:** the approved tool list and each tool's description and schema are hashed. If the server adds or changes
  a tool, that tool is switched off until you look at the change and approve it again. This guards against a server
  quietly swapping what a tool does.

## 3. Local servers on the PC (M2)

### 3.1 What setup says

Before the first run, one card, no scrolling:

> **Run this program on your PC?**
>
> `npx @modelcontextprotocol/server-filesystem@2.1.0`
>
> It works like any app you install: it can **read and change files** on this PC and **use the internet**.
> Cyclone runs only this exact version and stops it when Cyclone stops.
>
> Only add programs you trust.
>
> **[Run it]  [Cancel]**

- If the command or version changes later, the same card shows again with the change highlighted.
- **Keys:** the config's secret values (API keys in `env`) are asked for separately on a masked field. They go into
  the DPAPI store, never into the saved config.

### 3.2 How it runs

- **Launchers:** `npx`, `uvx`, `docker run`, and `node` or `python` with a file path. Any other launcher is refused.
- **No shell:** the command is an argument list only, so `&&`, pipes and redirects are refused.
- **Pinned version required:**
  - `pkg@1.2.3` for npm;
  - `pkg==1.2.3` for Python;
  - an image digest, or a tag you confirm, for Docker.
  - The approved command is stored with its hash.
- **Process:**
  - The program is started by the gateway with its own working folder (`runtime/connectors/<id>/`).
  - It runs inside a Windows Job Object, so it closes with the runtime.
  - It is restarted at most 3 times in 10 minutes.
  - stdout carries MCP messages only; stderr is kept as a redacted log with a size limit.
- **Network:** it may reach external services. That is what it is for, and the card says so.
- **Who can add or launch one:** only you, from Glass. The model, the agent MCP servers and the coordinator can never
  add, change or launch one. CI guards this, as it does for `/v1/cc` today.
- **Why this doesn't break "no generic shell for the model":**
  - the model never supplies a command;
  - a program runs only after the owner's explicit approval of that exact, pinned command;
  - the program is then used only through its MCP tools, under the same allowlist, caps and approvals.

## 4. APIs without an MCP server: the maker (M3)

- **Import:** an OpenAPI 3 / Swagger 2 URL or file. Each operation becomes a tool, with its parameters and body as
  the tool's schema. You tick which to include.
- **Declarative:** the gateway sends the HTTP request itself. No code is generated or run.
- **Classes:** GET and HEAD are *reads*; POST, PUT, PATCH and DELETE *change things*. You can override per tool, for
  example a POST search that only reads.
- **Auth:** from the spec's security schemes: API key (header or query), bearer, basic, or OAuth2 authorization code
  (the C3 flow).
- **Results:**
  - JSON answers are kept as the call's result, shown in Glass and available to the next step;
  - files (media, PDF, CSV) become artifacts.
- **Where requests go:** only the spec's own `servers` hosts, pinned, over https, never a private network address.
  This uses the same checks as artifact downloads.

## 5. Results back from outside services

- **Every call returns:** its text and structured result, kept on the call (bounded, secret-shaped values hidden), and
  any files as artifacts.
- **Chaining:**
  - A step's result can fill the next step's inputs, with `{step.field}` placeholders checked against the result.
  - Example: "fetch today's orders from the shop API, then have the phone reply to each order message".
- **Treated as outside content:** results and tool descriptions come from outside Cyclone. When a model sees them
  (the phone's Mind in a goal, or the coordinator in C4), they are framed as quoted, untrusted content, as run text is
  today. They never become instructions or approvals.

## 6. Connector cards (M4)

- **Export** any working connector as a card: the address, config or spec, the sign-in *method*, tools, classes,
  rules, pairings and the pinned hash. A card holds no keys or tokens.
- **Import** a card, sign in or paste the key, and it's ready. A local-server card still shows the §3.1 card.
- **Curated cards** ship with Glass: Higgsfield (after a live check), plus a few common services with reading tools
  pre-selected.

## 7. Safety summary (CI-guarded where marked)

- Nothing is enabled without your click. Sends, deletes, payments and permission changes always ask. *(guard)*
- Keys and tokens only in the DPAPI store. *(guard)*
- Local servers:
  - only you can add or launch them *(guard)*;
  - allow-listed launchers, argument lists, pinned versions, and re-approval on any change *(guard)*.
- Changed tools are switched off until re-approved.
- Requests from API connectors go only to the spec's hosts, and never to private addresses.
- Outside results are data, never instructions.

## 8. Releases

| Release | Contents | Exit criteria |
|---|---|---|
| **M1: Connect any remote MCP** | Probe steps, SSE fallback, API key/header, manual OAuth client, Client ID Metadata Documents, per-tool classes and rules, Allow all reads, auto-pairing, pinning, Try it | Five servers with different sign-ins connect by pasting the address; a changed tool is switched off until re-approved |
| **M2: Local servers on the PC** | Config import, the setup card, launchers, pinning, Job Object lifecycle, keys in DPAPI, restart limits, redacted logs | A pinned filesystem server and one internet-using server run from an approved config on Windows; a changed command asks again |
| **M3: API maker** | OpenAPI import, declarative calls, auth schemes, result chaining | A routine reads from an API, a phone task uses the result, and a POST asks first |
| **M4: Cards** | Export, import, curated cards | A card exported on one PC works on another after sign-in |

M1 and M2 can ship together as one milestone. Physical acceptance (Windows, real servers) is stated as owed until the
owner tests it.
