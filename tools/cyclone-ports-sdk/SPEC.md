# Cyclone Ports contract `cyclone.ports/1`

Status: **draft v1, frozen for plugin builders** (2026-10-01). Design: `Cyclone V5 plan/45-run-ports.md`.
The Dev Hub in this kit implements the hub side of this contract. The gateway's Port Hub (plan 45, P1–P5) will
implement the same contract. A plugin that passes `cyclone-ports-check` against the Dev Hub should work with the real
hub without changes.

"Must", "must not" and "should" are normative.

## 1. Parties

| Party | What it is |
|---|---|
| **Run** | A Cyclone task on the phone (the Mind, Account Setup, the Pilot or a recipe). It emits to ports and awaits ports by name. |
| **Hub** | The Port Hub in the PC gateway (or the Dev Hub). It routes each port to the plugin the owner bound, signs requests, issues port tokens, keeps the audit log, and seals secrets to the phone. |
| **Plugin** | Your HTTP service. It serves a manifest and some ports. It never talks to the phone. |

The phone never calls a plugin, and a plugin never calls the phone. Everything goes through the hub.

## 2. Ports

| Port | Way | Sensitivity | Plugin may serve | Payload |
|---|---|---|---|---|
| `run.event` | out | public | yes | `data.stage` ∈ started, page, step, needs_you, created, done, failed, cancelled; optional `reason` |
| `log.line` | out | public | yes | `data.text`, at most 500 characters |
| `screen.shot` | out | personal | yes | `artifactUrl` (PNG), `data.mime`, `data.bytes`, optional `width`/`height`; `pageKey` |
| `account.fields` | out | personal | yes | `data.fields`: name, dateOfBirth, email, username… **never** a password, PIN, OTP, token, card or CVV |
| `page.text` | out | personal | yes | `data.text`: visible text, secret-shaped values already hidden |
| `file.out` | out | personal | yes | `artifactUrl`, `data.name`, `data.mime`, `data.bytes` |
| `file.in` | in | personal | yes | delivery: `name`, `mime`, `base64`, `sha256` (≤ 20 MB) |
| `value.in` | in | personal | yes | delivery: `value` (any JSON, ≤ 64 KB) |
| `code.in` | in | **secret** | yes | delivery: `code` (3–12 of `A-Z a-z 0-9 -`), `source`, `from` |
| `link.in` | in | personal | yes | delivery: `url` (https only), `source` |
| `secret.out` | out | **secret** | **no (v1)** | hub and vault only: a password or API key sealed on the phone to the vault |
| `secret.in` | in | **secret** | **no (v1)** | hub and vault only: a vault item released for one run |

The catalog is closed in v1: a plugin can't invent ports. New ports come with a new contract version. The table lives
in code in `cyclone_ports/catalog.py`.

## 3. Manifest: `GET /cyclone-plugin.json`

```json
{
  "contract": "cyclone.ports/1",
  "name": "sms-codes",
  "version": "0.1.0",
  "title": "SMS codes",
  "description": "…",
  "endpoint": "http://127.0.0.1:8773",
  "serves": [{"port": "code.in", "way": "in"}],
  "needs": {"personal": false}
}
```

- `name`: `^[a-z][a-z0-9-]{1,40}$`, stable for the plugin's life. `version`: semver.
- `endpoint`: plain `http` only on loopback (`127.0.0.1`, `localhost`, `[::1]`). Anything else must be `https`.
- `serves`: ports from §2 with their fixed `way`. `secret.out` and `secret.in` are refused.
- `needs.personal` must be `true` when any served port is personal. The owner then ticks each personal port for the
  plugin in Glass. A remote (https) plugin never gets a personal port without that tick.
- The hub **pins** the manifest when the owner adds the plugin. If a later fetch differs (ports, endpoint, version),
  the plugin is paused until the owner approves it again.

`GET /health` must answer `200 {"ok": true, ...}`.

Schema: `schemas/manifest.schema.json`.

## 4. Hub → plugin requests (signed)

Every hub → plugin `POST` carries:

```
Content-Type: application/json
X-Cyclone-Contract: cyclone.ports/1
X-Cyclone-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>." + raw body)>
```

- The secret is per plugin. The owner sees it once when they add the plugin in Glass, and the plugin reads it from
  `CYCLONE_PLUGIN_SECRET`. For the Dev Hub you pass it with `--secret`.
- A plugin **must** refuse with `401` when the signature is missing or wrong, or `t` is more than 300 s away from its
  clock. It must compare in constant time. `cyclone_ports.verify` does this.
- A plugin must answer `404` for a port it does not serve.

### 4.1 Out ports: `POST /ports/{port}`

The body is an envelope (`schemas/envelope.schema.json`):

```json
{
  "v": 1, "runId": "run_8f2c", "taskId": "task_41", "rowId": "row_7",
  "port": "screen.shot", "way": "out", "seq": 12, "sentAt": "2026-10-01T14:03:22Z",
  "app": "com.instagram.android", "pageKey": "signup:birthday", "sensitivity": "personal",
  "data": {"mime": "image/png", "bytes": 48213, "width": 1080, "height": 2400},
  "artifactUrl": "http://127.0.0.1:8770/v1/artifacts/art_93a1c2?t=…"
}
```

- Answer `202 {"received": true}` quickly, and do slow work after answering. The hub waits at most 10 s, and a
  timeout counts as a failed delivery.
- Answer `422` for an envelope that fails validation.
- `seq` increases per run. The hub may retry, so de-duplicate on `(runId, seq)`.
- **Artifacts** (`screen.shot`, `file.out`) travel as a one-time `artifactUrl`, never inline. The link works for one
  `GET` within 120 s and then answers `410`. Fetch it once and don't store the URL.
- Out ports are fire-and-forget for the run. A failing plugin never blocks or fails a run; the hub records the
  failure in the timeline.

### 4.2 In ports: `POST /ports/{port}/await` and `/cancel`

When a run needs something, the hub asks the plugin (`schemas/await.schema.json`):

```json
{
  "v": 1, "runId": "run_8f2c", "taskId": "task_41", "rowId": "row_7", "app": "com.instagram.android",
  "port": "code.in", "awaitId": "aw_5b1e09", "match": {"from": "Instagram", "pattern": "\\b\\d{6}\\b"},
  "timeoutS": 180, "sentAt": "2026-10-01T14:03:40Z",
  "deliverUrl": "http://127.0.0.1:8770/v1/ports/run_8f2c/code.in/deliver",
  "token": "q2…"
}
```

- Answer `202 {"accepted": true}` at once, then deliver when you have the data (§5). Never hold the request open.
- `match` holds hints that depend on the port:
  - `code.in`: `from` (sender or app name, case-insensitive) and `pattern` (a regex for the code);
  - `link.in`: `from`;
  - `file.in`: `kind` (`image`, `pdf`…);
  - `value.in`: `ask` (what the run wants).

  Unknown keys must be ignored.
- `timeoutS` is at most 600. After it, the hub sends `POST /ports/{port}/cancel` with
  `{"v":1,"runId","port","awaitId","reason"}`. Answer `200` and forget the request. A cancel can also come early: the
  run finished, the owner stopped it, or another source answered.
- The `token` is single-use and only valid for this `runId`, `port` and `awaitId` until the timeout. Don't log it.

## 5. Plugin → hub: `POST {deliverUrl}`

```
POST /v1/ports/{runId}/{port}/deliver
Authorization: Port <token>
Content-Type: application/json
X-Cyclone-Contract: cyclone.ports/1
```

Bodies (`schemas/delivery.schema.json`):

| Port | Body |
|---|---|
| `code.in` | `{"v":1, "code":"482913", "source":"my-second-phone", "from":"Instagram"}` |
| `link.in` | `{"v":1, "url":"https://…", "source":"owner-gmail"}` |
| `value.in` | `{"v":1, "value": …}` |
| `file.in` | `{"v":1, "name":"avatar.png", "mime":"image/png", "base64":"…", "sha256":"<hex>"}` |

| Status | Meaning | What the plugin does |
|---|---|---|
| `200 {"accepted": true}` | Taken. | Forget the data. |
| `401` | Bad or missing token. | Don't retry. |
| `403` | `code.in`/`link.in` from a `source` the owner didn't register. | Don't retry; tell the owner. |
| `404` | No run is waiting on this port. | Don't retry. |
| `409` | Already delivered. | Don't retry. |
| `410` | The wait expired or was cancelled. | Don't retry. |
| `422` | The body is invalid (`problems` lists why, e.g. sha256 mismatch). | Fix it; retry once at most. |

Retry only on network errors and `5xx`, with backoff, and never after the await's timeout.

## 6. What the run sees

| Port | The Mind and the run timeline get |
|---|---|
| `code.in` | `filled` and the code's length. **The code is sealed to the phone and typed by Cyclone into the bound field. No model, log or timeline sees it.** |
| `file.in` | The file saved to the run's folder (name, type, size). |
| `value.in` | The value, framed as quoted, untrusted data. |
| `link.in` | The link opens in the run, after the hub checks it is https and its host belongs to the app being set up. |

A plugin's data is never an instruction. If a value says "approve the payment", the run treats it as text.

## 7. Security rules for plugins (must)

1. **Codes:**
   - hold a code, or the message it came in, in memory only, for at most 180 s;
   - drop it once delivered;
   - never write it to disk, a log, a database, analytics or crash reports.
2. **Sources:** deliver `code.in`/`link.in` only from phones, numbers or inboxes the owner registered. Report the
   registered label as `source`. The hub refuses others (`403`).
3. **No secrets on other ports:**
   - never send passwords, PINs, OTPs, API keys or card data on `value.in`, `file.in` or any other port;
   - passwords and API keys go only through `secret.out`/`secret.in`, which the hub and the vault handle.
4. **Logs:** log metadata only: run id, port, status, sizes. Never log bodies, tokens, codes or artifact URLs.
5. **Personal data:** keep `account.fields`, `screen.shot` and `page.text` only where the owner asked for them, and
   delete them on the owner's request.
6. **No control:** a plugin can't approve, pay, send, delete or grant permissions. The hub doesn't offer it. Don't
   try to drive the phone through `value.in`.
7. **Network:** listen on loopback unless the plugin must be reachable, for example by an SMS forwarder on the LAN.
   Use https off-machine. Check the signature on every hub request and the forwarder token on every webhook.
8. **Account Setup boundaries** (plan 43 §6.3):
   - only accounts the owner owns or manages;
   - no regulated identity (KYC) apps;
   - no number or device spoofing;
   - no bulk creation beyond what the app allows.
   CAPTCHA, selfie and ID checks always go to a person, and no plugin solves them.

## 8. Versioning

- `cyclone.ports/1` changes only by adding optional fields. Plugins must ignore unknown fields.
- A breaking change becomes `cyclone.ports/2`. The hub will keep serving `/1` plugins for at least one release after
  that.
- Feedback and open questions: `Cyclone V5 plan/HANDOFF-run-ports-plugins.md` §6.
