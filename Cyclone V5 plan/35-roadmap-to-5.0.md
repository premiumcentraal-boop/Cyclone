# 35 — Everything left, run by run, to 5.0

**Status:** roadmap, 2026-09-27, after alpha.56; the App Manual (plan 36) added as runs 3–5 on 2026-09-28. One row is one build run: a release through the fast lane, with its
own tests, guards, release notes and an end-to-end check. The order is a recommendation; the owner can reorder it.

## Where we are

- **Built and published:**
  - the phone Mind, planes and background (alpha.40–46);
  - web-only PC and one-click setup (47–48);
  - Drive (49–53);
  - Command Center C0 shell (51), C1 vault (54), C2 sealed delivery (55), C3 connections with pre-authorised leases (56);
  - the connector maker: any MCP and local servers (57), the API maker, result chaining and cards (58).
- **Planned, not built:** parallel sessions (plan 26 §6, deferred since alpha.47),
  the mission desk (plan 21), C4 coordinator, C5 Pages, C6 hosted (plan 33), and the road to 5.0 (plan 9).

## The owner's tests (not build runs, but they gate "done")

These are all stated as UNVERIFIED in their release notes:
1. **Windows:** install with `Cyclone-Setup-…exe`, open Glass, `cyclone update`. The DPAPI sign-in store only runs
   here.
2. **Command Center on a real phone:**
   - C0: a routine on two phones;
   - C2: a vault login fills on a phone that never had the password;
   - C3: a Higgsfield video made, then posted behind the Share approval.
   - Send the Higgsfield tool list after signing in, so its connector card can be verified.
3. **Drive:** the car test (plan 32). This decides whether JEV is promoted.

## Build runs

| # | Release | What it delivers | Plan |
|---|---|---|---|
| 1 | **alpha.57: Connect any MCP** (built) | Any remote server: SSE fallback, API key/header, a pasted OAuth client, Client ID Metadata Documents. Tools sorted into reads and changes, **Allow all reads**, per-tool rules, auto-paired job checkers, pinning, **Try it**. **Local MCP servers on the PC:** config import, the plain-words setup card, pinned launchers, Job Object lifecycle, keys in DPAPI | 34 M1+M2 |
| 2 | **alpha.58: API maker and cards** (built) | OpenAPI → connector with no code; GET reads, writes ask; results chained into the next step (`{step.field}`); connector cards (export, import, curated set incl. Higgsfield once verified) | 34 M3+M4 |
| 3 | **alpha.59: App Manual M1: own words, lists and categories** | The app's own words kept (APK lexicon + chrome filter), never your content (canary guard). A generic UI pattern library; safe probes (reveal, switch category, scroll, one sample); lists recorded by how they work (order, groups, how to find one) and category sets with sub-categories; real names in Glass; model picker on the PC (default: the phone's model) and a screenshots switch | 36 |
| 4 | **alpha.60: App Manual M2: describer, abilities, self-quiz** | Hypothesise → probe → revise per screen; abilities ("add a connector to a chat") with paths and paraphrases; the self-quiz finds gaps and Map deeper targets them; Learn and teaching write abilities; Abilities tab, Markdown export, read-only `app_manual` MCP tool | 36 |
| 5 | **alpha.61: App Manual M3: rapid navigation** | On-phone ability index, Tier 0 walks with no model call, `find` and `go_to(ability)`, manual excerpts for the Mind, JEV watching ability picks, the Lab find-the-feature suite, diff passes, self-healing selectors | 36 |
| 6 | **alpha.62: Parallel sessions** | The phone runs up to 2 background missions plus your screen. Each has its own task card, inbox, plane and leases. Per-session notifications and pill; the Lab concurrency suite. The Command Center then sends a phone more than one task at a time | 26 §6 |
| 7 | **alpha.63: Fleet health and alerts** | Phone heartbeat (battery, heat, storage, network, versions), quarantine after 3 infrastructure failures, "phones behind on updates". Alerts to your phone or email: phone offline over 10 minutes, success rate under 80%, a login failing twice, a cap reached. Metrics per recipe, account and phone | 33 §10–11 |
| 8 | **alpha.64: Coordinator (C4)** | The AI coordinator in the runtime. Fixed tools: accounts, phones, recipes, tasks, routines, runs, artifacts, connections — no vault values, no approving, no adding connections. Also: budgets (tasks and credits a day), a daily or weekly report page, the Lab coordinator suite, and Cyclone as an MCP server so Claude or Codex can create tasks behind the same approvals | 33 §7, §8 |
| 9 | **alpha.65: Smarter routines** | Richer schedules (monthly, "first Monday"), triggers (a notification, an email, a webhook, another task's result), approval batching for identical kinds, per-kind approval timeouts (the run pauses, never auto-approves) | 33 §5–6 |
| 10 | **alpha.66: Vault, complete** | Passkey unlock (Glass served at `localhost`, which WebAuthn allows). "Remember on this phone" with remote revoke. SMS and email codes read on the phone and filled, never stored. Password rotation as a recipe with 7-day rollback. Sign-up for accounts you own, with the new password sealed back to the vault. Encrypted export (Argon2id) | 33 §4.3–4.5 |
| 11 | **alpha.67: Mission desk + Drive finish** | The mission desk (write long text once, deliver it exactly). The Drive Lab voice suite. JEV promotion if the car test earned it | 21, 32 |
| 12 | **alpha.68: Pages (C5)** | A block editor with live blocks (task, routine, run list, phone tile, approvals, artifact gallery), saved views (table, board, calendar, gallery), and templates (Weekly content, Daily app check, Password health) | 33 §9 |
| 13–14 | **alpha.69–70: Hosted, multi-site (C6)** | Cloud Command Center (Postgres, a durable job engine), the PC as an outbound edge, members and roles, SSO. A second site's phones take tasks from the cloud; the vault stays zero-knowledge. Two runs | 33 §12 |
| 15–16 | **5.0.0-rc.1 → 5.0.0 / Glass 1.0** | Hardening: a full physical pass on the Pixel and Windows, plan 9's exit criteria (a secrets fill, a Gmail map, a Chrome-host map, an Ask using a live slot, overlay yield, the pay-block GATE). Also: a signed Windows installer (today `CI_UNSIGNED`), a stable channel next to development, and docs. One RC run, then fixes and 5.0 | 9 |

**Total:** about **16 build runs**, plus the owner's tests. Runs 1–2 are built. Runs 3–5 (the App Manual, plan 36) are next, at the
owner's request. Runs 6–11 make the fleet dependable and complete. Runs 12–14 are the bigger product steps. Runs 15–16
ship 5.0.

## Decisions still open

- **Pages (run 12):** the plan names BlockNote on Yjs, but Glass's guard forbids runtime dependencies. Either write a
  small in-house block editor (recommended; it keeps the guard) or allow a vetted dependency.
- **Hosted (runs 13–14):** where it runs and who pays for it, whether it is wanted at all, and SSO provider.
- **Windows signing (run 15):** a code-signing certificate is needed for a signed installer.
- **Stable channel (run 15):** when development builds stop going to your daily phone.

## What stays true through every run

- PhoneToolExecutor is the only thing that changes the phone.
- Approvals stay for pay, send, delete, permission and sign-in steps.
- No passwords, codes or keys in logs, learning stores or models.
- No generic shell for the model; local MCP servers run only after your approval of an exact, pinned command.
- Owned or authorised accounts only.
- Physical acceptance is stated as owed until you test it.
