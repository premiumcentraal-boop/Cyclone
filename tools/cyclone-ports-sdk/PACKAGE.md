# Cyclone plugin packages `cyclone.package/1` and the index `cyclone.index/1`

Status: **v1** (alpha.103, plan 50). A packaged plugin is still a Ports plugin (`cyclone.ports/1`, [SPEC.md](SPEC.md));
this contract only says how it is shipped, installed, started and configured. The code is `cyclone_ports/package.py`
and `cyclone_ports/index.py`; Cyclone's runtime imports the same files.

"Must", "must not", "should" and "may" are normative.

## 1. The release file

One asset in a GitHub release, named exactly `<name>-<version>-windows-x64.cyclone.zip` (a local plugin) or
`<name>-<version>-remote.cyclone.zip` (a remote one). A release must carry only one `.cyclone.zip`.

| Path in the zip | Required | |
|---|---|---|
| `cyclone-plugin.toml` | yes | §2 |
| `cyclone-plugin.json` | yes | the Ports manifest; `name` and `version` equal the toml's; a remote plugin's `endpoint` equals `remote.endpoint` |
| `README.md`, `LICENSE` | yes | Glass shows the README as plain text |
| `settings.schema.json` | when `[settings]` is declared | §4 |
| `bin/…` | a local plugin only | a self-contained program; `local.entry` names it |

Nothing else may sit at the top level. Archive rules (refused, never repaired): at most 200 MB zipped and 500 MB
unpacked; at most 5 000 entries; paths of at most 240 characters with `/` only; no absolute paths, drives, `..`, `.`,
empty parts, `:`, backslashes, characters or names Windows can't use (`CON`, `NUL`, trailing dots or spaces…); no links
or special files; no encrypted entries; stored or deflated only; no duplicate names ignoring case; an entry over 1 MB
must not unpack to more than 100× its compressed size. Unpacking counts real bytes, not what the header claims.

## 2. `cyclone-plugin.toml`

```toml
package  = "cyclone.package/1"
name     = "run-logger"          # = the manifest's name
version  = "0.1.0"               # = the manifest's version and the tag v0.1.0
title    = "Run logger"          # 1-60 characters
summary  = "Writes every run event to a file."   # 1-200 characters
kind     = "local"               # local | remote
homepage = "https://github.com/acme/cyclone-run-logger"   # optional, https
license  = "MIT"                 # optional

[local]                           # local only
entry    = "bin/run-logger.exe"
platform = "windows-x64"

[remote]                          # remote only
endpoint = "https://logger.acme.dev"

[permissions]                     # shown to the owner before install
network  = ["api.acme.dev"]      # host names it says it talks to (at most 20)
files    = "own"                 # own: only its data folder · user: the owner's files

[settings]                        # optional
schema   = "settings.schema.json"
```

The table is strict: an unknown key makes the package invalid, so a typo never passes silently.

## 3. Starting: the handshake

Cyclone starts `local.entry` with **no arguments**, no shell, its data folder as working folder, a minimal environment
(`CYCLONE_PLUGIN_MANAGED=1`, `TEMP`/`TMP` inside the data folder, a short `PATH`), and on Windows inside a Job Object
(the plugin dies with Cyclone; memory 1 GB, 32 processes). It writes **one JSON line** to the plugin's stdin and closes it:

```json
{"package": "cyclone.package/1", "host": "127.0.0.1", "port": 53124, "key": "k1.…", "dataDir": "…", "settings": {…}}
```

The plugin must listen on `host:port` within 20 seconds and serve the manifest from its package (same `name`,
`version`, `contract` and `serves`). The key and the settings, secrets included, arrive only here: never print them.
Cyclone blanks the key and secret settings in the plugin's log anyway. With the SDK:

```python
from cyclone_ports import PluginServer, is_managed

if is_managed():
    server = PluginServer.managed(MANIFEST)        # reads the handshake
    folder = server.handshake["dataDir"]; settings = server.handshake["settings"]
```

Crashes restart after 1, 2, 4, 8 and 16 seconds; more than 5 restarts in 10 minutes and the plugin stays stopped until
the owner restarts it. Settings changes restart the plugin with the new handshake.

## 4. `settings.schema.json`

A small subset of JSON Schema that Glass draws as a form: `type: "object"`, 1-40 `properties`, optional `required`,
optional `title`/`description`, `additionalProperties` only `false`. Fields: `string` (`minLength`, `maxLength` ≤ 4 096,
`format` `uri`/`email`/`date`, `enum`), `integer` and `number` (`minimum`, `maximum`, `enum`), `boolean`; each with
optional `title`, `description`, `default`. No `$ref`, no `$schema`, no nested objects or arrays.

`"x-cyclone-secret": true` on a string makes it a secret: no default, no choices, stored sealed (DPAPI) on the PC,
never returned by any Cyclone route, never in a log, diagnostics or a model's context; it reaches the plugin only in the
handshake. `schemas/settings-vectors.json` holds the cases every implementation must agree on.

## 5. The Cyclone index

`index.json` in the Cyclone plugins repository, signed as raw bytes with Ed25519; `index.json.sig` is
`{"key": "<key id>", "sig": "<base64>"}` where the key id is the first 16 hex characters of the SHA-256 of the raw
public key.

```json
{"index": "cyclone.index/1", "serial": 42, "issuedAt": "…Z", "expiresAt": "…Z",
 "plugins": [{"name": "run-logger", "repo": "acme/cyclone-run-logger",
              "versions": [{"version": "0.1.0", "tag": "v0.1.0", "asset": "run-logger-0.1.0-windows-x64.cyclone.zip",
                            "sha256": "…", "minRuntime": "5.0.0-alpha.103.dev1"}]}],
 "revoked": [{"name": "run-logger", "version": "0.0.9", "sha256": "…", "reason": "Crashes on start."}]}
```

Cyclone trusts only public keys compiled into the runtime, refuses an index older (lower `serial`) than one it accepted,
treats an expired index as out of date for installs but keeps using it to stop revoked files, and installs a listed
plugin as **checked** only when the downloaded file's SHA-256 equals the listed one. Admission (attestation, hash,
unpack rules, conformance on Windows) happens in the index repository's CI before signing.

`cyclone-plugin index keygen|sign|verify` manage the key and the file. Keep the private key as a CI secret only.

## 6. Tools

| Command | Does |
|---|---|
| `cyclone-plugin pack <folder>` | builds the release file (deterministic: the same folder gives the same SHA-256) |
| `cyclone-plugin check <file>` | every rule above, as Cyclone applies them before installing |
| `cyclone-plugin-build --folder … --script …` | PyInstaller program + managed conformance run + pack (what the Action runs) |
| `cyclone plugin add <link or file>` | installs through a running Cyclone, from the owner's terminal |
