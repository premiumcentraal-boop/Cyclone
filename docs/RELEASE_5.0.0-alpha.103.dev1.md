# Cyclone V5 Alpha 103: Plugins from GitHub

Developer alpha for owner testing. It builds on Alpha 102 and includes it. Plan: `Cyclone V5 plan/50-plugins-from-github.md`.

Versions:
- **Mobile:** `5.0.0-alpha.103.dev1` (version code 248). No phone changes besides the version.
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.103.dev1.exe` (runtime `5.0.0-alpha.103.dev1`).
- **Glass:** `1.0.0-alpha.58`.

## What's new

**Glass → Command Center → Ports → Install:** add a plugin by pasting its GitHub link.
1. Cyclone downloads the plugin's release file from GitHub and checks it. Nothing is unpacked or run yet.
2. You see the card: what the plugin is, where it came from, what it says it talks to, which files it may touch, the
   ports it serves (personal ones stay off until you switch them on), its settings form and its README.
3. A plugin Cyclone hasn't checked is marked **Unverified**: you tick that you trust its source.
4. Install: Cyclone unpacks it, starts it on this PC, runs the Ports checks against it, and only then switches over.
   The plugin appears under Ports like any other and gets its key automatically; nothing to copy or paste.

**Installed plugins** show their state in plain words: running, restarting, stopped after crashes, needs settings,
blocked by Cyclone. For each one:
- **Settings:** a form drawn from the plugin's schema. Secret fields (an API key) are write-only: Glass shows "Set", and
  the value never comes back from any Cyclone route, log or diagnostics.
- **Update:** checks for a newer release. The new version runs side by side and takes over only after it passes the
  checks; if anything fails, the old one keeps running.
- **Back to the earlier version** in one click.
- **Turn off / on, Restart, Log** (with the plugin's key and secret settings blanked out).
- **Remove**, with a confirm step.

**In a terminal:** `cyclone plugin add <github link | file>`, `cyclone plugin list`, `cyclone plugin update <name>`,
`cyclone plugin remove <name>`, `cyclone plugin logs <name>`.

**For plugin authors:** the SDK gains the package format `cyclone.package/1` (`tools/cyclone-ports-sdk/PACKAGE.md`):
- a GitHub Action that builds, checks, attests and attaches the release file;
- `cyclone-plugin pack|check`;
- the start handshake (`PluginServer.managed()`).

The run logger and the PC image picker examples are packaged this way.

## How it stays reliable

- **Atomic:** nothing outside a staging folder changes until the file is verified and unpacked. A version is placed
  with one rename. The switch to it is one database transaction. Every start tidies up what a crash left.
- **Tested by crashing it:** the tests crash the runtime at every step of an update, 50 times per step. The next start
  always lands on the old version or the new one, never a mix.
- **Contained:**
  - Each plugin is its own process, started without a shell, with no arguments and a minimal environment.
  - On Windows each plugin runs in a Job Object: when the runtime stops, its plugins stop too, and memory and process
    counts are capped.
  - A plugin that crashes restarts after 1, 2, 4, 8 and 16 seconds. After 5 restarts in 10 minutes it stays stopped
    and Glass says why.
- **Only GitHub:**
  - Plugin files come only from GitHub's own hosts, and redirects are checked on every hop.
  - Size is capped, and the hash is checked against GitHub's digest when GitHub gives one.
  - Retries happen only on network errors and server errors.
- **Safe archives:**
  - Refused: paths that escape the folder, drive letters, `:`, names Windows can't use, links, duplicate names and
    zip bombs.
  - Unpacking counts real bytes.
- **A running version is never replaced in place:** a different file claiming the same version is refused.

## Core holds no plugin

The built-in ID Generator starter and MRZ Studio discovery are removed from the gateway and Glass, with their routes,
pages and tests:
- `/v1/ports/starters/id-generator*`
- `/v1/cc/integrations/mrz*`

Saved Ports and connections stay. The phone protocol for plugin skills stays, with nothing advertised by core.

## Not in this build

- **The Cyclone list (checked plugins):** the index format, signing tools and checks are built and tested, but the
  runtime trusts no index key yet. Until the owner creates the `cyclone-plugins` repository and its signing key, Glass
  says the list isn't set up, and every plugin installs as Unverified.
- **OS-level isolation** (AppContainer, a firewall rule per plugin) enforcing the declared network and file
  permissions: plan 50, alpha three. Today a local plugin can do what your Windows account can do; the card says so.
- **Source installs** (Python or Node without a release file), **MCP-server plugins** (alpha two), plugin panels inside
  Glass, automatic updates.
- **The SMS forwarder example isn't packaged:** a managed plugin listens on this PC only, and the forwarder needs to be
  reachable from another phone on the LAN.

## Tests

- **New:**
  - `test_plugins.py` (54):
    - sources and the GitHub client: redirects, caps, retries, rate limits;
    - install from a link and from a file, update, rollback, remove with and without data;
    - three kinds of bad update;
    - zip-slip, digest mismatch, two plugin files;
    - a crash at every step: remote 50× each, local once each with real processes;
    - write-only secrets and log redaction;
    - restart backoff and giving up;
    - a mismatched manifest, a minimal environment;
    - the signed index: checked installs, revocation, rollback protection, a lying hash, no keys means no network;
    - reconcile, routes, `cyclone plugin`.
  - `test_package.py` (73): the toml, the archive rules, extract, the shared settings vectors, the handshake, index
    signing and its refusals, the CLI, the build.
  - `plugins.test.mjs` (5): the shared settings vectors in Glass, defensive reading, the install card and its checks,
    installed plugins with write-only secrets and a confirmed remove, the form.
  - Guard `test_plugins_guard.py`:
    - bytes only from GitHub through one client;
    - no shell, and secrets only through the handshake;
    - every route needs the token;
    - atomic by construction;
    - compiled-in index keys only;
    - core holds no plugin;
    - the Ports contract is unchanged.
- **Windows publish smoke:** builds two versions of the run logger with PyInstaller. On the installed runtime, it then:
  installs one, sees it pass the Port Hub's signed checks, updates it, rolls it back, kills the runtime, and checks
  that no plugin process survives.
- **Results:**
  - gateway: 839 collected, all pass (1 skipped);
  - Glass: 292 pass, guard clean;
  - Ports kit: 94 pass;
  - CI guards: 293 pass;
  - release versions coherent.

## Physical acceptance

UNVERIFIED. On the Windows PC with this build:
1. Glass → Ports → Install: paste the link of a repository that publishes a `.cyclone.zip`, or run
   `cyclone plugin add <path to run-logger-0.1.0-windows-x64.cyclone.zip>`.
2. Read the card, tick the trust box, set the folder and install. Ports → Plugins shows it Active.
3. Start a phone run and see its events in the plugin's `runs.jsonl` (in Cyclone's runtime folder, under `plugins\run-logger\data`).
4. Update to a newer release, then go back. Pull the network mid-download and see a clean failure.
5. Close Cyclone and check in Task Manager that no `run-logger` process is left. Remove the plugin.
