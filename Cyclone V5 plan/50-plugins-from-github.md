# Plan 50: Plugins from GitHub (alpha one)

Status: **final plan, not built.** Written 2026-10-03. Builds on the frozen Ports contract `cyclone.ports/1`
(`tools/cyclone-ports-sdk/SPEC.md`, plans 45, 47, 48). Supersedes nothing; plan 19 (phone marketplace) stays as is.

## 0. The decision in one paragraph

Cyclone core becomes a clean sheet: no plugin is built into the gateway or Glass. A plugin is a GitHub repository that
publishes a **release** containing one signed-off package file. The owner pastes the repo link (or picks it from the
Cyclone index); Cyclone downloads that exact release file, checks its hash, shows what it will be allowed to do, installs
it into the owner's own folder, starts it on this PC, runs the existing conformance checks, and connects it to the Port
Hub. Its settings appear in Glass from a schema it ships. Nothing is built, compiled or dependency-resolved on the
owner's PC, and nothing changes on the phone.

## 1. Goals, non-goals, and what "done" means

**Goals (alpha one)**
1. Paste `github.com/<owner>/<repo>` → a working, connected plugin, with no terminal and no copy-pasted key.
2. Every install, update and removal is **atomic**: a crash or power loss at any moment leaves the old state or the new
   state, never a mix.
3. A misbehaving plugin can never take the runtime, Glass or the phone down, and can never see what the Ports contract
   doesn't already give it.
4. Settings, including the plugin's own API keys, are set in Glass; secret values are write-only and never leave the
   owner's account except into that plugin's process.
5. A bad release can be stopped everywhere through the signed index (kill switch), even for owners who never update.
6. Core is clean: the built-in ID Generator starter and MRZ Studio discovery leave the gateway and Glass.

**Non-goals (alpha one)** — each is a later step, named so nobody builds it by accident:
- source installs (Python/Node from a repo without a prebuilt package), `git clone`, `pip install`, `npm install`;
- MCP-server plugins and tool libraries for Command Center agents (alpha two, the `mcp` feature reserved in SPEC §3);
- plugin UI panels inside Glass (alpha three); automatic updates; paid listings; a review queue for third parties;
- OS-level sandboxing (AppContainer) — see §6 for what protects the owner until then;
- macOS/Linux packages (the PC product is Windows-only today).

**Definition of done:** §11 launch criteria all pass on CI and the Windows publish runner; physical-device acceptance
is stated as UNVERIFIED until the owner runs §12.

## 2. Alternatives considered

| Option | Verdict | Why |
|---|---|---|
| **Prebuilt release package, hash-pinned** | **Chosen** | Same bytes for every owner; nothing runs before consent; works offline after install; one file to hash, attest and revoke. |
| `git clone` + build on the owner's PC | Rejected | Needs toolchains; build scripts run code before the owner agreed to anything; results differ per machine. |
| `pip install git+…` / `npm install` | Rejected | `setup.py`/postinstall scripts run at install; dependency confusion; resolver failures are the #1 cause of broken installs. |
| Docker images | Rejected | Docker Desktop is not on owner PCs and needs admin. |
| WebAssembly plugins | Later | Best sandbox, but the ecosystem for HTTP services with real libraries isn't there yet. Re-evaluate for alpha three. |
| Only remote (https) plugins | Kept as a kind | Safest (no code on the PC) but rules out plugins that must touch local files or apps. |

## 3. The package format: `cyclone.package/1`

A new, separate contract. `cyclone.ports/1` stays frozen and unchanged; a packaged plugin is still a Ports plugin.

**In the repo root:** `cyclone-plugin.toml` (source of truth, read by the build Action below).

```toml
package  = "cyclone.package/1"
name     = "run-logger"                 # = the Ports manifest name, ^[a-z][a-z0-9-]{1,40}$, never changes
version  = "1.2.0"                      # semver, = the git tag v1.2.0
title    = "Run logger"
summary  = "Writes every run event to a file."
kind     = "local"                      # local | remote
homepage = "https://github.com/acme/cyclone-run-logger"
license  = "MIT"

[local]                                  # kind = local only
entry    = "run-logger.exe"             # relative path inside the package; no arguments, no shell
platform = "windows-x64"

[remote]                                 # kind = remote only
endpoint = "https://logger.acme.dev"    # https only, same rules as normalize_endpoint()

[permissions]                            # shown on the permission card, checked where Cyclone can check them
network  = ["api.acme.dev"]             # hosts it says it talks to (declared; enforced in a later alpha)
files    = "own"                        # own = only its data folder (declared; see §6)

[settings]
schema   = "settings.schema.json"       # optional; §7
```

**The release asset:** `<name>-<version>-windows-x64.cyclone.zip` (or `-remote.cyclone.zip` for `kind = remote`), containing:

| Path | Required | Notes |
|---|---|---|
| `cyclone-plugin.toml` | yes | identical to the repo file at the tag |
| `cyclone-plugin.json` | yes | the Ports manifest the process will serve; must match the toml's `name`/`version` |
| `settings.schema.json` | if declared | §7 subset |
| `bin/…` | `local` only | the self-contained executable and its files |
| `LICENSE`, `README.md` | yes | shown in Glass |

**Hard limits** (refused, never truncated): zip ≤ 200 MB, unpacked ≤ 500 MB, ≤ 5 000 entries, entry path ≤ 240 chars,
no absolute paths, no `..`, no drive letters, no alternate data streams (`:`), no symlinks or reparse points, no
duplicate names (case-insensitive), compression ratio per entry ≤ 100:1 (zip bombs).

**The build Action (shipped in the SDK):** `tools/cyclone-ports-sdk/action/` — a reusable GitHub workflow that on a `v*`
tag: validates the toml and manifest, runs `cyclone-ports-check` against the built plugin, builds the self-contained
Windows binary (PyInstaller for Python; Node SEA for Node), produces the zip, uploads it to the release, and adds a
**GitHub build-provenance attestation** (`actions/attest-build-provenance`). Plugin authors copy one starter workflow;
they never hand-build a package.

## 4. Where plugins come from: the index and plain links

**The Cyclone index** — `premiumcentraal-boop/cyclone-plugins` (new repo), file `index.json`, plus `index.json.sig`.

```json
{ "index": "cyclone.index/1", "serial": 42, "issuedAt": "2026-10-03T12:00:00Z", "expiresAt": "2026-11-02T12:00:00Z",
  "plugins": [ { "name": "run-logger", "repo": "acme/cyclone-run-logger",
                 "versions": [ { "version": "1.2.0", "tag": "v1.2.0", "asset": "run-logger-1.2.0-windows-x64.cyclone.zip",
                                 "sha256": "…", "minRuntime": "5.0.0-alpha.103" } ] } ],
  "revoked": [ { "name": "run-logger", "version": "1.1.0", "sha256": "…", "reason": "Crashes on start." } ] }
```

- **Signed** with an Ed25519 key kept as a secret in the index repo's CI; the public key is compiled into the runtime
  (two slots, so the key can rotate without a broken window). Verified with `cryptography` (already a gateway dependency).
- **Admission is done in CI, not on the owner's PC:** a PR that adds a version runs `gh attestation verify` on the asset
  (must be built by the reusable Action from that repo at that tag), recomputes the sha256, unpacks with the same rules as
  §5, runs the conformance suite against the started plugin on a Windows runner, and only then signs the new index.
- **Freshness and rollback protection:** the runtime refuses an index whose `serial` is lower than the last one it
  accepted, or whose `expiresAt` has passed (it keeps using the last good one and says the list is out of date).
- Fetched at start and every 6 hours from `raw.githubusercontent.com`; works offline with the last good copy.

**Plain links (not in the index)** are allowed, with honesty:
- Glass labels them **Unverified** and the permission card says "This runs code from a source Cyclone hasn't checked,
  with the same access as your Windows account." The owner must tick that sentence.
- Cyclone resolves the repo's **latest release** (never a branch), requires exactly one asset matching the naming rule,
  and pins its sha256 on first install (trust on first use). When GitHub reports the asset's digest, it must match.
- The revoked list still applies to them by sha256.

## 5. Install, update, remove: the state machine

```
            resolve          download          verify            unpack          start+check        connect
 (link) ─► RESOLVING ─► DOWNLOADING ─► VERIFYING ─► STAGED ─► STARTING ─► CHECKING ─► ACTIVE
              │              │             │           │           │            │
              └──────────────┴─────────────┴───────────┴───────────┴────────────┴──► FAILED (reason shown, staging deleted,
                                                                                         previous version still active)
 ACTIVE ─► (update) ─► same pipeline side by side ─► flip on success / stay on old on failure
 ACTIVE ─► CRASHED (restart budget spent; owner sees why and can restart)   ACTIVE ─► REVOKED (stopped, kept for removal)
 ACTIVE ─► (remove) ─► STOPPED ─► files and key gone, data kept or deleted by owner choice ─► gone
```

**Layout** (all under the owner's own `%LOCALAPPDATA%\Cyclone One\plugins\`):
```
plugins\
  staging\<random>\                 downloads and unpacking; wiped on every runtime start
  run-logger\
    versions\1.2.0\                 immutable after the flip (read-only attribute set)
    versions\1.1.0\                 previous version, kept for rollback, at most one
    data\                           the plugin's own folder; survives updates
plugins.db                          the record of truth (SQLite, WAL), see below
```

**Atomicity rules**
1. Nothing outside `staging\` is touched until bytes are verified and unpacked.
2. Moving `staging\<random>` to `versions\<v>` is a single same-volume rename.
3. The switch to a new version is **one SQLite transaction** (`installed.current = '1.2.0'`); there are no symlinks or
   "current" folders to get half-written.
4. On start, the runtime reconciles: folders without a DB row are deleted; DB rows pointing at a missing folder are marked
   `FAILED` with "files missing — reinstall"; `staging\` is emptied.
5. Every step is idempotent and safe to repeat after a crash.

**Downloads**
- Only from `api.github.com`, `github.com` and GitHub's release-asset host(s); redirects followed **only** to that
  allow-list (the existing `_NoRedirect` opener stays for plugin calls). https with system trust via the existing proxy.
- Streamed to disk with a running sha256 and a hard size cap; resumable is not needed (≤ 200 MB).
- Timeouts: connect 10 s, idle 30 s, total 10 min. Retries: 3, exponential backoff with jitter, only on network errors
  and 5xx — never on 4xx or a hash mismatch.
- GitHub API rate limits (60 requests/hour unauthenticated) are handled: one `releases/latest` + one asset download per
  install, `ETag` caching, and a clear "GitHub is limiting requests; try again in N minutes" message.

**Updates (manual in alpha one)** — Glass shows "Update available" from the index (or `releases/latest` for unverified).
The new version runs through the full pipeline beside the old one, on a new port. Only when it is `ACTIVE`-ready does the
DB flip and the old process stop; if any step fails, the old version keeps running untouched. When the new manifest adds
ports, personal access or permissions, the update waits for the owner (the existing `approve_changes` review). The owner
can roll back to the kept previous version in one click (same flip, reversed).

## 6. Running plugins: the Plugin Host

A new gateway component, `apps/device-gateway/cyclone_device_gateway/plugins/` (owner of this plan), separate from the
Port Hub (which keeps routing, signing, consent and audit).

**Starting a local plugin**
- `subprocess.Popen([exe], shell=False)` with an explicit, minimal environment (`SYSTEMROOT`, `TEMP`/`TMP` pointed at
  the plugin's `data\tmp`, `PATH` limited to the plugin's own `bin\` and `System32`); working directory = `data\`.
  No inherited handles except stdin/stdout/stderr.
- **Handshake on stdin** (one JSON line, then stdin is closed):
  `{"package":"cyclone.package/1","host":"127.0.0.1","port":<assigned>,"key":"k1.<secret>","dataDir":"…","settings":{…}}`.
  The plugin's Ports key and secret settings never touch the command line, the environment, a file or a log.
  The SDK gains `PluginServer.managed()` that reads this; existing `--port` / `CYCLONE_PLUGIN_SECRET` keep working for
  developers running a plugin by hand.
- The port is picked by the host (bind to `127.0.0.1:0`, read the port, release, pass it); the plugin must answer on it
  within 20 s and serve a manifest whose `name`, `version` and `serves` equal the package's `cyclone-plugin.json`
  (the existing `pin_hash` is computed from the package, not from what the process says). Any mismatch: stop, `FAILED`.
- Every plugin runs in a **Windows Job Object** with `KILL_ON_JOB_CLOSE`, a memory limit (default 1 GB, owner can raise
  to 4 GB) and a process-count limit (32). If the runtime dies, its plugins die with it — no orphans holding ports.
- stdout/stderr go to a rotating log per plugin (2 × 2 MB) with the existing secret-shaped-value redaction applied before
  writing; Glass shows the tail.

**Keeping it alive**
- Health: the existing monitor (`GET /cyclone-plugin.json`, `/health`) every 30 s.
- Crash or failed health 3 times in a row → restart with backoff 1, 2, 4, 8, 16 s; more than 5 restarts in 10 minutes →
  `CRASHED`, Port Hub marks it unavailable (runs waiting on it time out the normal way), Glass says why with the last log
  lines and a Restart button.
- Plugins start **after** the runtime is serving, in parallel, so a slow plugin never delays Glass or the phone link.
- Stop: `POST /shutdown` if the plugin lists the `graceful-stop` feature, wait 5 s, then terminate the job.

**What protects the owner (honest list)**
- What a plugin can **receive** is decided by the Ports contract, not by the plugin: only ports the owner ticked,
  never `secret.out`/`secret.in`, codes only sealed through the hub, never the phone. Unchanged from plans 45/47.
- What a plugin can **do on the PC** is, in alpha one, whatever the owner's Windows account can do. The mitigations are
  provenance (attested builds, signed index, pinned hashes), the revoked list, the Job Object limits, the minimal
  environment, and the honest Unverified card. `network`/`files` permissions are declared and shown; enforcement
  (AppContainer + firewall rule per plugin) is the alpha-three isolation step.

## 7. Settings from a schema

`settings.schema.json` is a deliberately small subset of JSON Schema, validated with the same rules on the gateway and
in Glass:
- top level `type: object`, at most 40 `properties`, no `$ref`, no remote anything, no `patternProperties`;
- field types: `string` (with `maxLength` ≤ 4 096, optional `enum`, `format` ∈ `uri`/`email`/`date`), `integer`,
  `number` (with `minimum`/`maximum`), `boolean`;
- `x-cyclone-secret: true` on a string makes it a **secret field**: stored with DPAPI (`PortStore`'s existing
  `protect`), never returned by any GET (Glass shows "Set · Change · Clear"), never logged, never in diagnostics, Brain
  or a model's context, delivered only in the stdin handshake;
- `title`, `description`, `default`, `required` for the form.

Glass draws the form from the schema with no plugin-specific code. Saving validates on the gateway, then restarts the
plugin with the new settings (simple and deterministic; hot reload is not needed). A schema that breaks the subset makes
the package invalid at admission and at install.

## 8. Clean core: the ID Generator leaves

In the same alpha, remove the built-in starter and its coupling so core holds no plugin:
- gateway: `ports/id_generator.py`, the `/v1/ports/starters/id-generator*` routes, `IdGeneratorStarter` in `PortHub`,
  the `id_generator` skills path in `/v1/ports/skills` (skills then come only from installed plugins' manifests),
  `command/mrz.py` and `MrzDiscovery` in `command/connections.py`;
- Glass: `pages/idGeneratorPage.ts`, `services/idGenerator.ts`, its route, the starter card in `portsPage.ts`, and the
  MRZ entries in `connectionsView.ts`/`services/command.ts`;
- tests and docs that only cover the starter (`test_ports_id_generator.py`, `test_ports_mrz_acceptance.py`,
  `starters/id-generator/`, `docs/ID_GENERATOR_PORTS_BUILD.md`);
- `starter_settings` table: left in place, unused (no destructive migration on owners' data); dropped in a later cleanup.

Cyclone does not repackage or publish that generator as a marketplace plugin. The first plugins published through this
path are the SDK examples (`run-logger`, `pc-images`, `sms-plugin`), each moved to its own repo using the build Action.

## 9. Interfaces

**Gateway routes** (all `dependencies=[Depends(auth)]`, guarded):

| Route | Does |
|---|---|
| `GET  /v1/plugins` | installed plugins with state, version, update available, verified/unverified, health |
| `GET  /v1/plugins/index` | the index as the runtime last accepted it (serial, age, entries) |
| `POST /v1/plugins/resolve` `{source}` | link or index name → what would be installed: release, asset, size, sha256, verified, permissions, ports, settings form. Downloads nothing. |
| `POST /v1/plugins/install` `{source, sha256, accept}` | runs §5; `sha256` must equal the one `resolve` returned (no switch between look and install); returns a job id |
| `GET  /v1/plugins/jobs/{id}` | step, progress, error |
| `POST /v1/plugins/{name}/update` `{sha256, accept}` | §5 update |
| `POST /v1/plugins/{name}/rollback` | flip to the kept previous version |
| `POST /v1/plugins/{name}/settings` | validate and save; secrets write-only |
| `GET  /v1/plugins/{name}/settings` | form + non-secret values + which secrets are set |
| `POST /v1/plugins/{name}/restart` · `/stop` · `/start` | lifecycle |
| `GET  /v1/plugins/{name}/log` | redacted tail |
| `POST /v1/plugins/{name}/delete` `{keepData}` | remove |

Ports routes (`/v1/ports/*`) are unchanged; an installed plugin appears there exactly like a hand-added one, marked
`managed: true` (its endpoint and key are not editable by hand).

**CLI:** `cyclone plugin add <link|name>`, `list`, `update <name>`, `remove <name>`, `logs <name>` — thin clients of the
routes above.

**Glass:** Command Center → Ports gains a **Plugins** tab: the index as cards (verified badge, summary, version),
"Add from GitHub link", the permission card, install progress per step, settings form, health, logs, update/rollback,
remove. Hand-added plugins keep working as today.

**Phone:** no change. No new op.

## 10. Testing

| Layer | What | Where |
|---|---|---|
| Format | toml + manifest + schema subset validation, good and bad fixtures | SDK tests + gateway |
| Unpack | zip-slip, absolute paths, `..`, ADS, symlink/reparse, duplicate case, bombs, size and count caps; property-based fuzz over generated archives | gateway |
| Index | signature, wrong key, key rotation slot, expired, lower serial (rollback attack), revoked by sha | gateway |
| Download | allow-listed redirects only, hash mismatch, truncated stream, 4xx no retry, 5xx retry, rate-limit message — against a local fake GitHub | gateway |
| Atomicity | **fault injection: kill the runtime at every pipeline step and at every line of the flip**; on restart the state is always old-or-new and `staging\` is empty | gateway |
| Process | handshake, wrong manifest, slow start, crash loop → `CRASHED`, Job Object kills children when the runtime exits, secrets absent from argv/env/log | gateway (Windows-only cases on the Windows runner) |
| Settings | subset validator parity Glass ↔ gateway (shared vectors file), secrets never in GET | gateway + Glass |
| Glass | Plugins tab: index, unverified card tick, install progress, failure text, settings form, update/rollback, remove with confirm | Glass |
| End to end | the v5-publish Windows smoke installs `run-logger` from a release built by the Action in the same workflow (local file source, so no network flake), checks it's `ACTIVE`, sends a test event through the Port Hub, updates to a second build, rolls back, removes it | `.github/workflows/v5-publish.yml` |
| Guards | `scripts/ci/tests/test_plugins_guard.py`: no `shell=True`; downloads only through the one allow-listed client; every `/v1/plugins` route authed; no secret setting in any GET model; no plugin name, port or route hard-coded in core (ID Generator gone); `cyclone.ports/1` files unchanged | CI |

## 11. Launch criteria (all required)

1. Every row of §10 passes in CI; full gateway, Glass, phone and guard suites green.
2. Fault-injection suite: 0 mixed states over every injection point, repeated 50× each.
3. Windows publish smoke installs, updates, rolls back and removes a real packaged plugin.
4. A runtime start with 5 plugins installed adds < 200 ms before Glass serves (plugins start after).
5. A plugin that crash-loops, hangs on start, leaks memory past its limit or serves a different manifest is contained
   and explained in Glass, with the runtime and the phone link unaffected.
6. Revoking a version in the index stops it on a test runtime within one index refresh.
7. Versions coherent (`release_versions.py --check`), release notes written, physical acceptance listed as UNVERIFIED.

## 12. Rollout and rollback

- Ships in one alpha (target **alpha.103**) behind no flag: the old hand-added path is untouched, so the new path can't
  break existing setups.
- Index starts with the three SDK examples only. Third-party admissions open after the owner has used it for one week.
- Rollback of Cyclone itself: a previous runtime ignores `plugins\` and `plugins.db` entirely; hand-added plugins keep
  working. The ID Generator removal is the only one-way change and is deliberate (§8).
- **Physical acceptance (owner, UNVERIFIED until done):** on the Windows PC, Glass → Ports → Plugins → install
  `run-logger` from the index; set its output folder; start a phone run and see its events arrive; install an
  unverified plugin by link and see the warning; update and roll back; pull the network mid-download and see a clean
  failure; remove it.

## 13. Build order (one alpha, four steps)

1. **P1 Format + index:** `cyclone.package/1` validator, schema subset (shared vectors), index signing/verification,
   the reusable build Action; move the three examples into packaged form. SDK tests.
2. **P2 Installer:** resolve, download client, verify, unpack, layout, DB, flip, reconcile, update, rollback, remove;
   fault-injection suite.
3. **P3 Plugin Host:** Job Objects, handshake, health, restart policy, logs; Port Hub `managed` plugins; routes + CLI.
4. **P4 Glass + clean core + release:** Plugins tab and settings form; §8 removals; guards; Windows publish smoke;
   versions and release notes.

Alpha two (separate plan): MCP-server plugins for Command Center agents, with per-tool allow lists and the existing
approval classes. Alpha three: plugin panels in a locked-down iframe and OS isolation (AppContainer + per-plugin
firewall rules) that enforces the declared `network`/`files` permissions.
