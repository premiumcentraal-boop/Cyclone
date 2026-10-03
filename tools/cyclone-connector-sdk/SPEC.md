# Cyclone phone connectors `cyclone.connector/1`

Status: **v1, first build** (Cyclone 5.0.0-alpha.104, plan 51 K1–K2). The selector shows connector entries from
alpha.105 (K3); the client library, JSON Schemas and the conformance app arrive in K4. Until then this file is the
contract, and the code is `apps/mobile/app/src/main/java/com/cyclone/mobile/connector/`.

"Must", "must not", "should" and "may" are normative.

## 1. What a connector is

An Android app the owner installs next to Cyclone (minSdk 33). It talks to Cyclone **on the same phone over Binder**:
no network, no ports, no tokens. It works only after the owner approves it in **Cyclone → Settings → Connectors**,
by package name and signing certificate, for the scopes its manifest asks for.

A connector can never create, switch, rename or remove profiles, use Cyclone's phone controls, reach the PC gateway,
or see passwords, codes or the vault.

## 2. Declare it

```xml
<!-- AndroidManifest.xml -->
<service android:name=".CycloneConnect" android:exported="true"
         android:permission="com.cyclone.mobile.permission.CONNECTOR_HOST">
  <intent-filter><action android:name="com.cyclone.connector.CONNECT" /></intent-filter>
  <meta-data android:name="com.cyclone.connector" android:resource="@xml/cyclone_connector" />
</service>
<queries><package android:name="com.cyclone.mobile" /></queries>
```

The service is a **marker**: Cyclone finds it, reads its manifest and never binds it. `CONNECTOR_HOST` is a signature
permission held only by Cyclone, so no other app can bind it either.

```xml
<!-- res/xml/cyclone_connector.xml -->
<cyclone-connector
    contract="cyclone.connector/1"
    id="acme-profiles"
    label="Acme Profiles"
    scopes="profiles.read profiles.ext selector.contribute events.profiles"
    entryActivity=".EntryActivity" />
```

| Attribute | Rule |
|---|---|
| `contract` | `cyclone.connector/<major>[.<minor>]`; major must be 1 |
| `id` | `^[a-z][a-z0-9-]{1,40}$`, unique on the phone, stable for the app's life |
| `label` | 1–40 characters (a string resource is fine) |
| `scopes` | space-separated (§4). Unknown names are shown to the owner and ignored |
| `entryActivity` | optional; the activity Cyclone opens when the owner taps one of your entries (alpha.105) |
| `wakeReceiver` | optional; reserved for event wakes (alpha.105) |

## 3. Call it

```aidl
package com.cyclone.connector;
interface ICycloneConnector { String call(String request); }
```

Bind with an explicit intent: action `com.cyclone.connector.SERVICE`, package `com.cyclone.mobile`. Every call is one
JSON request and one JSON answer:

```json
{"method": "profiles", "args": {}}
{"ok": true, "result": {…}}            {"ok": false, "error": {"code": "NOT_APPROVED", "message": "…"}}
```

Cyclone identifies you from Binder (your UID, your package, your signing-certificate lineage), never from the request.
Apps sharing a UID are refused. Limits: 20 calls per second, 64 KB per request.

| Method | Scope | Args → result |
|---|---|---|
| `hello` | none | `{contract}` → `{contract, minor, connectorId, approved, granted[], pending[], profileSchema, limits}` |
| `profiles` | `profiles.read` | → `{schemaVersion, profiles[]}` (§5) |
| `ext.set` | `profiles.ext` | `{profileId, value: object \| null}` → `{profileId, cleared}` |
| `entries.set` | `selector.contribute` | `{entries[]}` → `{count}` (§6) |
| `entries.get` | `selector.contribute` | → `{entries[]}` |
| `events` | `events.profiles` | `{since}` → `{events[], next, reset, more}` (§7) |

Errors:

| Code | Meaning |
|---|---|
| `NOT_A_CONNECTOR` | your app doesn't declare a valid connector, or shares its UID |
| `NOT_APPROVED` | the owner hasn't approved you, or you are signed by a key outside the approved lineage |
| `SCOPE_NOT_GRANTED` | the owner didn't approve that scope, or your manifest no longer asks for it |
| `UNSUPPORTED_CONTRACT` | a major version this Cyclone doesn't speak |
| `RATE_LIMITED` | too many calls |
| `BAD_REQUEST`, `BAD_EXT`, `BAD_ENTRIES`, `SECRET_REFUSED`, `NO_SUCH_PROFILE`, `UNKNOWN_METHOD` | as named |
| `INTERNAL` | Cyclone couldn't answer; no details are given |

## 4. Scopes

| Scope | What the owner reads in Settings |
|---|---|
| `profiles.read` | See your profiles' names and looks |
| `profiles.apps.read` | See which apps are in each profile |
| `profiles.ext` | Keep its own small notes on a profile |
| `selector.contribute` | Add its own entries to your profiles list |
| `events.profiles` | Hear when profiles are added, changed, switched or removed |

When an update asks for more scopes, the old approval keeps working for the scopes it covered. Settings shows the
new ones until the owner approves them.

## 5. Profiles (schema 2)

```json
{"schemaVersion": 2, "profiles": [
  {"id": "owner", "label": "This phone", "kind": "owner", "state": "ready"},
  {"id": "Cyclone_0123456789abcdef", "label": "Work", "kind": "profile", "state": "ready",
   "emoji": "🦊", "color": 4278255360, "appCount": 3,
   "packages": ["…"],            // only with profiles.apps.read
   "ext": {"tier": "gold"}}     // only with profiles.ext: your own data, or null
]}
```

- `id`: `owner` or `Cyclone_<16 hex>`.
- `state`: `ready` | `setting_up` | `in_trash`.
- `ext`: your own namespace on a Cyclone profile (not on `owner`). It holds a JSON object of at most 4 KB, at most 32
  keys in total and at most 4 levels deep.
  - Keys or values that look like secrets (password, token, API key, OTP…) are refused.
  - Cyclone stores it and returns it untouched, only to you. It never reaches the PC, Glass, a model or a log.
  - It is deleted when you are revoked or uninstalled, or when the profile is permanently deleted.

## 6. Entries

```json
{"id": "work-cloud", "type": "acme.cloud", "label": "Cloud work", "subtitle": "3 devices",
 "icon": "ic_cloud", "status": {"state": "attention", "text": "Sign in again"}}
```

Rules:
- At most 8 entries per connector.
- `id`: `^[a-z0-9][a-z0-9_-]{0,39}$`, unique.
- `type`: your own lowercase name.
- `label`: 1–40 characters, and never the name of one of the owner's profiles or "This phone".
- `subtitle`: ≤ 60 characters.
- `icon`: a drawable resource name from your app.
- `status.state`: `ready` | `attention` | `off`; `status.text` ≤ 60 characters.
- Unknown fields are refused.

From alpha.105, entries are shown after the owner's profiles, marked as yours ("From <label>"). A tap opens your
`entryActivity` with the extra `entryId`.

## 7. Events

`profile.created`, `profile.updated`, `profile.switched`, `profile.trashed`, `profile.restored`, `profile.removed`:
`{seq, type, profileId, at}`.

- Events carry ids only.
- Pull them with `events(since)`. They are ordered by `seq` and delivered at least once, so de-duplicate by `seq`.
- Keep `next` and send it as `since` next time. `more: true` means there is another page.
- `reset: true` means some events after your `since` are no longer kept (7 days or 1 000 events). Read `profiles`
  again.
- `profile.switched` names the profile Cyclone switched to (`owner` for this phone's own profile).

## 8. Versions

`hello` tells you the contract and minor Cyclone speaks. Minor versions only add methods, fields and scopes; both
sides ignore what they don't know. A new major is announced in the release notes at least two alphas ahead, and
Cyclone then answers both majors for at least two alphas.
