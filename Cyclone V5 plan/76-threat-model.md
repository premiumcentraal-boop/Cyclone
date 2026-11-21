# 76 · Threat model for the Cyclone platform

**Scope:** the Command Center on the owner's PC (gateway, its SQLite file, Glass in the browser) and its links to
phones, cloud phones, MCP connections and the owner's model provider. Written at stage 0 (dev alpha 155). Every
control below names the code that implements it today, or the plan 76 stage that will. A threat with no control yet
is marked **GAP**.

Review this document at the end of every stage that adds a store, a route, a model call or a secret path. Stage 12
ends with an independent review of it.

---

## 1. Assets

| # | Asset | Where it lives | Worth to an attacker |
|---|---|---|---|
| A1 | Vault secrets: passwords, recovery codes, two-factor seeds | Ciphertext in `command.db` (`command/vault.py`); plaintext only in the owner's browser after unlock | Takes over accounts |
| A2 | Identity details: names, birthday, email, phone | Encrypted vault item fields (`command/identities.py`, plan 59) | Identity theft, account recovery attacks |
| A3 | Account metadata: service, handle, owner basis, 2FA method, binding | Plain columns in `command.db` | Maps which accounts exist and where |
| A4 | The gateway bearer token | DPAPI on Windows, a `0600` file elsewhere (`tooling_seam.py`); per tab in Glass (memory and `sessionStorage`, `glass/src/core/session.ts`) | Full control of the Command Center API |
| A5 | Phone device keys and trust | Phones' Android Keystore; public keys and trust state on the PC (`command/delivery.py`) | Lets an attacker receive sealed secrets |
| A6 | The hive key | Wrapped by the vault key and sealed to each trusted phone (`command/hive.py`) | Opens shared identities and skills |
| A7 | Workspace content: pages, tables, rows | Plain text in `command.db` | Business information; personal columns (marked, not encrypted) |
| A8 | Model provider and connection credentials | DPAPI-protected stores (`command/ai.py`, `command/agent/store.py`); cloud accounts (`cloud_fleet/vault.py`) | Spend, data access, cloud phone control |
| A9 | Audit chain | `audit` table, hash-chained (`command/center.py`, `_audit`, `verify_audit`) | Hiding what happened |
| A10 | Lab screen copies | The phone's app folder (alpha 153) | Shows the owner's apps |

## 2. Actors

| # | Actor | Capability assumed |
|---|---|---|
| T1 | A remote attacker on the network | Can reach the PC's network interfaces |
| T2 | Another local program or user on the PC | Can make HTTP requests to `127.0.0.1` and read files its account can read |
| T3 | Malicious content in a web page, a phone screen, an MCP tool result or a pack | Can put text in front of the AI layer or into Glass |
| T4 | A stolen or lost PC disk, or a copied `command.db` | Has the file at rest |
| T5 | A compromised or swapped phone | Has a paired phone's identity, or claims one |
| T6 | A compromised dependency (npm, PyPI) | Can run code at build or runtime |
| T7 | The model provider | Sees whatever the gateway sends it |
| T8 | A mistake by the owner or the build | Bulk actions, bad migrations, a deleted record |

## 3. Trust boundaries

1. **Browser ↔ gateway:** loopback HTTP and WebSocket, bearer token.
2. **Gateway ↔ SQLite file:** the file is plaintext SQLite. Secrets inside it are ciphertext.
3. **Gateway ↔ phone:** the typed contract over ADB or Wi-Fi; secrets travel sealed to the phone's device key.
4. **Gateway ↔ model provider:** HTTPS with the owner's key; only bounded, secret-free context.
5. **Gateway ↔ MCP connections and cloud providers:** HTTPS with OAuth or provider keys; allowlisted tools only.
6. **Packs and imported data ↔ the platform:** validated data, never code.

## 4. Threats, controls and tests

| # | Threat (actor → asset) | Control today | Test or guard | Stage that closes the gap |
|---|---|---|---|---|
| 1 | T1 reaches the API from the network → A1–A9 | The gateway binds `127.0.0.1` (`config.py`, `host: str = "127.0.0.1"`) | Configuration tests in the gateway suite | — |
| 2 | T2 calls the loopback API without the bearer | Every Command Center route depends on the bearer (`command/api.py`) | Route tests with and without the bearer | — |
| 3 | T2 reads the bearer from disk | DPAPI on Windows; a `0600` file elsewhere (`tooling_seam.py`) | `tooling_seam` tests | — |
| 4 | T2 or T4 reads vault secrets from the file → A1, A2 | Zero-knowledge vault: AES-256-GCM in the browser, per-item keys, PBKDF2 passphrase wrap (`command/vault.py`) | `tests/test_command_vault.py` | Stage 12 adds Windows Hello unlock |
| 5 | T4 reads workspace content and personal columns → A7 | **GAP:** plaintext at rest; personal columns are only kept out of exports | — | Stage 12: personal columns encrypted at rest |
| 6 | T4 reads account metadata → A3 | **GAP:** plaintext at rest by design (it is not secret, but it is sensitive) | — | Stage 12 decides whether the whole file is encrypted (DPAPI or SQLCipher-class) |
| 7 | Secret text typed into an ordinary field → A1 leaks into plaintext | `INLINE_SECRET` refusal in every Command Center text (`desktop_runtime/v5_contract.py`, `command/center.py` `_clean_text`); Glass warns while typing | `tests/test_platform_secret_corpus.py` (stage 0) | Stage 3: the secret-reference property |
| 8 | T3 injects HTML or script into Glass → A4 | Glass builds all UI with DOM APIs; `innerHTML`, `eval` and `new Function` fail the build (`scripts/ci/glass_guard.py`); typed blocks and spans on the gateway (`command/pages.py`) | `glass_guard.py`, page tests | Stage 2 keeps this for rich paste (`DOMParser` into typed spans) |
| 9 | T3 prompt-injects the AI layer into a harmful action | AI changes arrive as apply/discard proposals; the fleet layer holds no model key; phone approvals are untouched | Agent registry and proposal tests (`tests/test_agent_*.py`) | Stage 15: citations, refusal and injection test set |
| 10 | T7 receives secrets in context → A1, A2 | Secret-shaped text is refused before storage, so it cannot be in context; vault plaintext never reaches the gateway | Secret corpus test | Stage 15: a guard over the context builder |
| 11 | T5 receives a sealed secret meant for another phone → A1 | Sealed delivery to one trusted device key, single-use leases, bound task and slot, replay refused on the phone (`command/delivery.py`, `secrets/Hpke.kt`) | `tests/test_command_delivery.py` | Device verification OWED (stage 16) |
| 12 | T5 joins the hive without the owner → A6 | The owner adds each phone in Glass; the hive key is sealed to the trusted device key (`command/hive.py`) | `tests/test_command_hive*.py` | Device verification OWED |
| 13 | T3 abuses an MCP connection (cost, data exfiltration) → A8 | Owner allowlist per tool, daily caps, approval rule, one waiting call per connection (`command/connections.py`) | `tests/test_command_connections.py` | — |
| 14 | T6 ships malicious code in Glass | No runtime npm packages (`glass_guard.py`, `apps/glass/package.json`) | `glass_guard.py` | D11 decides whether a vendored editor gets a narrow exception |
| 15 | T6 ships malicious code in the gateway | Pinned build dependencies, CI on every change | CI | Stage 16 reviews the dependency list |
| 16 | T8 runs a bulk action on the wrong selection | Fleet missions and routines are owner actions; approvals stay human | Fleet tests | Stage 7: dry runs for every bulk action; typed confirmation for irreversible ones |
| 17 | T8 or a bad migration corrupts the file | Numbered, checksummed, forward-only migrations in one transaction each; a copy before any change (`command/store/migrations.py`) | `tests/test_platform_store.py` (stage 0) | Stage 12: encrypted, restore-verified backups |
| 18 | Anyone edits the audit trail to hide an action → A9 | Hash chain over every entry, verified on demand (`verify_audit`) | Command Center tests | Stage 1: the event ledger's own hash chain |
| 19 | A newer database is opened by an older Cyclone and damaged | The migrator refuses an unknown schema before writing anything | `test_a_file_from_a_newer_cyclone_is_refused_at_start` | — |
| 20 | Lab screen copies outlive their use → A10 | Scrubbed of secrets; Lab missions only; owner missions never saved | Phone unit tests (alpha 153) | Stage 12: retention per D7 |
| 21 | Accounts made at volume get the owner banned, or break a service's terms | **GAP:** caps and block lists were removed on 2026-09-30 | — | Stage 5 and 8, with D3 and D4 |
| 22 | A verification challenge is solved or bypassed by the platform | A person's step pauses the row and is never solved (plan 43 §7) | Signup tests | Stage 14: a guard over the step types |

## 5. What this model does not cover yet

- Physical attacks on the phone, and anything inside Android beyond the Keystore guarantees.
- A malicious owner. The platform serves one owner and trusts them.
- Denial of service against the owner's own PC.

## 6. Open questions for the stage 12 review

- Should the whole `command.db` be encrypted at rest, or only the personal columns (threat 6)?
- Should the bearer token rotate on a schedule, and should Glass sessions expire on idle?
- How are pre-migration copies (`command/backups/`) protected? Today they have the same protection as the file itself.
