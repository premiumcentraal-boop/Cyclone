# Plan 48: Cyclone Ports in Glass, the Port Hub and its dashboard

Date: 2026-10-01. **Run 1: built, released in alpha.96.** **Run 2: built** (bindings per port everywhere / per routine / per
app, conflicts, the Port map). Builds on plans 45 (Run Ports), 46 (Skill Studio) and 47 (review), and on the kit in
`tools/cyclone-ports-sdk`. Goal: the real Port Hub in the gateway, and a Ports dashboard in Glass that an admin feels
at home in, built in runs. Each run ships something whole and tested, with nothing half-done.

## 1. Where it lives

- **Gateway:** `cyclone_device_gateway/ports/`, the Port Hub.
  - It imports the kit's `cyclone_ports` (catalog, signing, validators, checker), so there is one contract and no copy.
  - Routes are `/v1/ports/*` (the Glass bearer) plus `/v1/ports/{runId}/{port}/deliver` (port tokens, run 3).
  - State: `runtime/ports/ports.db` (SQLite), and plugin keys kept apart, sealed with DPAPI on Windows (0600 file
    elsewhere).
- **Glass:** a **Ports** entry in the Command Center sidebar (`#/command/ports`), next to Connections and Vault, because
  that's where the admin already runs tasks, routines, accounts and secrets.
  - Plugin details open in a side sheet over the page.
  - Adding a plugin is one guided sheet.
  - Later runs add a Port map, Activity, Sources, and a timeline lane in Runs.

## 2. Design principles (the "Apple/Google bar")

1. **One clear next step.** Every plugin shows one status and one action that fixes it:
   - "Waiting for its key" → Copy key;
   - "Changed what it asks for" → Review;
   - "Can't reach it" → Run checks.
2. **Consent you can see.**
   - Every personal port is a visible switch.
   - Remote plugins start with them off.
   - Secret ports carry a lock and say who handles them (the hub, the vault).
3. **Secrets shown once.** A plugin key is shown once, with copy buttons for PowerShell and bash, and never again.
   Losing it means issuing a new one.
4. **Honest status:**
   - checks run against the real plugin, and a failed check comes with a human hint;
   - "last seen" is a real health check;
   - nothing is green unless it passed.
5. **Calm, dense, beautiful.**
   - It uses Glass's tokens and components only, with no page-level colours.
   - Each plugin gets a generated monogram tile.
   - Out and in ports read at a glance (↗ and ↙).
   - The light and dark themes are equal.
   - Motion is limited to the checks revealing one by one and the status settling.
6. **Keyboard and screen readers:**
   - sheets trap focus and close on Esc;
   - switches are real buttons with `aria-pressed`;
   - status is never shown by colour alone.

## 3. Runs

| Run | Ships | Done when |
|---|---|---|
| **1. Foundation: plugins** | Port Hub core in the gateway (plugin registry, pinned manifests, keys, checks with the kit's conformance suite, a signed test send, health monitor, metadata-only activity log, `/v1/ports/*` routes) · Glass Ports page: overview, plugin cards, **Add plugin** sheet (address → review and consent → key once → live checks → live), plugin sheet (ports and consent, health, checks, test, pause, new key, remove), port catalog, recent activity | gateway tests and Glass tests green; the three example plugins added end to end from Glass in a real browser |
| **2. Port map and bindings** | Which plugin serves each port, by default and per task, routine or Sign-up Map; conflicts (two plugins, one in port) resolved visibly; the **Port map**, a switchboard view of the catalog with live counts; extension ports listed under their plugin | a task's resolved bindings are visible and testable from Glass |
| **3. Live traffic** | The real out-port pipeline (envelopes, one-time artifacts, retries, failed-delivery list) and in ports (awaits persisted across restarts, deliver endpoint with port tokens, idempotent `deliveryId`); **Activity** view with filters and a per-run lane on the Run page | the Dev Hub scenarios pass against the gateway hub instead of the Dev Hub |
| **4. Phone side** | Gateway protocol ops `port.emit/await/cancel/status`; Mind tools `port_send` / `port_wait`; Account Setup verification points bound to `code.in`; codes sealed to the phone (plan 33) | an Android test run sends events to a plugin and waits on `value.in`; physical check stated honestly |
| **5. Sources, keys and resilience** | Registered SMS and inbox sources (add, confirm with a test message, remove); zero-downtime key rotation (`…_NEXT`); per-plugin rate limits and auto-pause after repeated failures; manifest-drift review with a diff | drift, rotation and failure flows covered by tests and screenshots |
| **6. Polish and ship** | Accessibility pass, empty and error states everywhere, dark-mode review, docs (README, Glass guide), release notes, physical acceptance checklist | released as an alpha; acceptance stated honestly |

Each run ends with: tests, a screenshot review in light and dark, a commit, and when it changes what the owner can use,
a release.

## 4. Run 1 detail

**Gateway `ports/`:**
- `store.py`: the SQLite tables `plugin` (pinned manifest and its hash, status, consent, kid, last check, health) and
  `activity` (metadata only), plus `KeyStore` (DPAPI or 0600).
- `hub.py`: `PortHub`:
  - `preview(endpoint)`, `add(endpoint, consent)` (issues `k1`, shown once), `check(name)` (the kit's conformance);
  - `set_consent`, `pause`, `new_key`, `remove`, `send_test` (a signed `run.event` or `log.line`);
  - `monitor_once()` (health plus manifest drift → `needs_review`), `approve_changes`, `overview()`.
- `api.py`: `/v1/ports/overview`, `/plugins/preview`, `/plugins`, `/plugins/{name}/check|consent|pause|test|key|approve|delete`.

Plugin states: `waiting_key` → `active` ⇄ `paused`; `needs_review` (manifest drift); `unreachable` (health failing).

Endpoint rules:
- loopback http, or https;
- LAN http is allowed with a warning chip, but personal ports are off by default;
- never `file:` or other schemes.

**Glass:**
- `services/ports.ts`: the client, types and the status, hint and copy helpers. These are pure and unit-tested.
- `pages/portsPage.ts` (the page), `pages/portsAdd.ts` (the Add sheet), `pages/portsPlugin.ts` (the plugin sheet).
- `styles/ports.css`, tokens only.
- Router `command/ports`, a sidebar entry, and tests.

## 5. Run 2 detail (built)

**Rules** (`ports/bindings.py`):
- **Out ports:**
  - automatic means every live plugin allowed the port (fan-out);
  - a choice names any number of plugins, and none means off.
- **In ports:**
  - one answer: with one live candidate it is automatic;
  - two or more is a conflict until the owner picks, and nothing answers meanwhile;
  - a choice names one plugin or none.
- **Scopes:** `routine:<id>` → `app:<package>` → `default`, and the first scope with a choice wins.
- **Unavailable:** a chosen plugin that isn't live leaves the port unavailable. It is never quietly re-routed.
- **Removing a plugin** takes it out of every choice; a choice it alone made goes back to automatic.

**Routes:**
- `GET /v1/ports/bindings?scope=` (the map for one scope, with what it inherits);
- `POST /v1/ports/bindings {scope, port, plugins|null}`;
- `GET /v1/ports/resolve?routine=&app=` (what a run reaches; run 3 sends along it).

**Glass** (`#/command/ports/map`): a switchboard of ports (left) and plugins (right).
- **Lines:**
  - solid: the routes runs use;
  - dashed: could serve but doesn't here;
  - amber: needs a choice.
- **Scope pills:** Everywhere, each routine or app with choices, and "For a routine or app".
- **Each port** opens a chooser:
  - Automatic / Same as Everywhere;
  - its plugins (checkboxes for out, radios for in);
  - Off.
- **The Plugins view** lists conflicts under "Needs you" with a link to the map.

