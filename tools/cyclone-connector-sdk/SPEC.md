# Cyclone phone connectors `cyclone.connector/1`

Status: **v1, minor 1 alpha** (Cyclone 5.0.0-alpha.106), building on alpha.105 / plan 51 K1–K4. This file is the contract. The code is
`apps/mobile/app/src/main/java/com/cyclone/mobile/connector/`. Every release carries the kit (§9):
`Cyclone-Connector-Client-<version>.aar`, `Cyclone-Connector-Sample-<version>.apk` and
`Cyclone-Connector-Schemas-<version>.zip` (this file, the JSON Schemas and the test vectors), each listed with its
SHA-256 in the release's `release-manifest.json`.

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
    entryActivity=".EntryActivity"
    wakeReceiver=".CycloneWake" />
```

| Attribute | Rule |
|---|---|
| `contract` | `cyclone.connector/<major>[.<minor>]`; major must be 1 |
| `id` | `^[a-z][a-z0-9-]{1,40}$`, unique on the phone, stable for the app's life |
| `label` | 1–40 characters (a string resource is fine) |
| `scopes` | space-separated (§4). Unknown names are shown to the owner and ignored |
| `entryActivity` | optional; the activity Cyclone opens when the owner taps one of your entries (§6). It must be exported and in your package |
| `wakeReceiver` | optional; the receiver Cyclone pokes when new events wait (§7). It must be exported and protected by `com.cyclone.mobile.permission.WAKE_CONNECTOR` |

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
| `profiles` | `profiles.read` | → `{schemaVersion, current, profiles[]}` (§5) |
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
{"schemaVersion": 2, "current": "Cyclone_0123456789abcdef", "profiles": [
  {"id": "owner", "label": "This phone", "kind": "owner", "state": "ready"},
  {"id": "Cyclone_0123456789abcdef", "label": "Work", "kind": "profile", "state": "ready",
   "emoji": "🦊", "color": 4278255360, "appCount": 3,
   "packages": ["…"],            // only with profiles.apps.read
   "ext": {"tier": "gold"}}     // only with profiles.ext: your own data, or null
]}
```

- `current`: the profile in front: `owner`, a profile id, or `null` when Cyclone can't tell (for example, after someone
  switched users outside Cyclone). It is a hint for your UI, not a lock.
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

Entries are shown in the home slider and in Profiles after the owner's profiles, marked as yours ("From <label>").
A tap opens your `entryActivity` with the string extra `com.cyclone.connector.ENTRY_ID`. Cyclone never switches
anything for an entry: what a tap does is up to your app.

Glass on the owner's PC shows your label and your entries' `id`, `type`, `label`, `subtitle` and status (phone op
`connectors.list`, read only). Nothing else of yours travels: not your profile data, package name, icon or key.

## 7. Events

`profile.created`, `profile.updated`, `profile.switched`, `profile.trashed`, `profile.restored`, `profile.removed`:
`{seq, type, profileId, at}`.

- Events carry ids only.
- Pull them with `events(since)`. They are ordered by `seq` and delivered at least once, so de-duplicate by `seq`.
- Keep `next` and send it as `since` next time. `more: true` means there is another page.
- `reset: true` means some events after your `since` are no longer kept (7 days or 1 000 events). Read `profiles`
  again.
- `profile.switched` names the profile Cyclone switched to (`owner` for this phone's own profile).
- With a `wakeReceiver` and the `events.profiles` scope, Cyclone sends it the broadcast `com.cyclone.connector.WAKE`
  (explicit, no data, at most one per half second) when new events wait. Then call `events`. The wake is a hint: it
  can be late or missed, so read `events` when your app starts too.

## 8. Versions

`hello` tells you the contract and minor Cyclone speaks. Minor versions only add methods, fields and scopes; both
sides ignore what they don't know. A new major is announced in the release notes at least two alphas ahead, and
Cyclone then answers both majors for at least two alphas.

## 9. The kit

**Client library** (`Cyclone-Connector-Client-<version>.aar`, source `apps/mobile/connector-client`). Add it to your
app. It declares the `<queries>` entry for you.

```kotlin
// Off the main thread: connect() waits for the bind.
CycloneConnector.connect(context).use { cyclone ->
    val hello = cyclone.hello()                 // approved? granted? pending?
    val profiles = cyclone.profiles()           // {schemaVersion, current, profiles[]}
    cyclone.setEntries(listOf(CycloneConnector.Entry("work-cloud", "acme.cloud", "Cloud work", "3 devices")))
    val page = cyclone.events(since = lastSeq)
}
```

A call that Cyclone refuses throws `CycloneConnectorException` with the error `code` from §3.

**Sample** (`Cyclone-Connector-Sample-<version>.apk`, source `apps/mobile/connector-sample`). It is a debug-signed
app to read and try, not a product. It declares every scope, an entry activity and a wake receiver, and has a button
for each call. Install it, approve it in Cyclone → Settings → Connectors, and press the buttons.

**Schemas and test vectors** (`Cyclone-Connector-Schemas-<version>.zip`, source `tools/cyclone-connector-sdk/schemas`).
- JSON Schemas (draft 2020-12) for the request, the answer envelope and each method's result.
- `vectors.json`: a fixed state (approvals, profiles, entries, events), a caller and the exact answer Cyclone gives
  to each request. Error messages are for people and may change; the codes may not.
- Cyclone's own build runs every vector against its connector code, so the file is what Cyclone answers. Run the
  same file against your fakes.


## 10. Profile config provider (minor 1, alpha.106)

This API is independent of startup callbacks. Requesting `profiles.config` adds a dedicated approval in the existing
Connectors settings. Existing approvals do not silently gain it. No additional UI is introduced.

| Method | Arguments | Result |
|---|---|---|
| `config.get.v1` | `{profileId, androidUserId, packageName}` | `{version:1, profileId, androidUserId, packageName, storageKey, value, state}` |
| `config.set.v1` | same tuple + `value: object or null` | same result; null clears the blob |
| `config.status.v1` | same tuple + `state` | same result |

`profiles` now includes `androidUserId` on registry profiles (null while unassigned). The tuple must match a ready, non-trashed registry profile and one of its scoped packages. User id is an integer,
not a string. Each connector installation has a separate namespace derived from its authenticated connector id
and Binder UID's Android user. The synthetic `owner` entry is not a registry profile and is outside this API. The target tuple's Android user is independent of the caller's Android user.
An installation may keep settings for another registered profile; it cannot read another connector installation's data.

`value` is an opaque JSON object, limited to **4096 UTF-8 bytes** after serialization. Cyclone checks structure and
size only, never keys or behavior. Whole Binder requests are limited to 64 KiB and 32 nesting levels. Do not put
credentials in this generic settings store; it is not a vault. No blob or config reference reaches logs, models,
Glass or the PC. `state` is `unknown` (default), `ready`, `degraded` or `failed`.

The provider persists atomically in Cyclone's private `noBackupFilesDir`, with at most 256 tuples per connector
installation. `storageKey` is lowercase SHA-256 of UTF-8 `profileId + "\n" + androidUserId + "\n" + packageName`.
It is a logical identity, not a path into Cyclone. Renames and updates preserve it. Revocation/uninstall removes the
namespace; permanent profile deletion or removal of a package from the registry removes its tuples. Trashing and
restoring a profile preserves its data. Clearing app data removes it; it is excluded from Android backup.

For connector-owned state, the client library provides
`ProfileBehaviorProvider.stateDirectory(context, profileId, androidUserId, packageName)`. It returns the connector's
own private `noBackupFilesDir/cyclone-profile-state-v1/<storageKey>`. Cyclone neither reads nor manages that directory.
The same connector installed under different Android users gets Android-isolated private directories.

## 11. Profile startup provider (minor 1)

Ask for `profiles.startup` and obtain explicit approval, independently of `profiles.config`. Check `hello.minor >= 1`
before using the new appended Binder transaction. The original `ICycloneConnector.call` transaction and descriptor
are unchanged. Register a live `IProfileBehaviorProvider` using
`ICycloneConnector.registerProfileProvider("{\"version\":1}", provider)` (or the SDK helper). Null unregisters.
The result envelope contains `{version:1, registered:boolean}`. Unsupported registration versions are refused.

The connector registers with Cyclone **in its own Android user**. Identity is kernel UID + package + signing lineage
+ manifest + approved scope, checked at registration and again before every dispatch. One provider is held per full
UID; re-registration replaces it. Binder death, revocation and uninstall remove it. Re-register after either process
restarts; registrations are intentionally not persisted. Each user must approve its own connector installation.
An owner-user provider is never substituted for a provider in a different Android user. If Cyclone cannot reach a
provider in the target user, the app launch proceeds without one; no privileged cross-user bridge is introduced.

Cyclone calls the provider before its scoped app launch dispatch, using the oneway callback:

```aidl
oneway interface IProfileBehaviorProvider {
    void beforeLaunch(String event, IProfileBehaviorResult result);
}
oneway interface IProfileBehaviorResult {
    void complete(String response);
}
```

```json
{"contract":"cyclone.profile-startup/1","version":1,
 "profileId":"Cyclone_0123456789abcdef","androidUserId":10,"packageName":"com.acme.target",
 "eventType":"cold_start","deadlineElapsedRealtimeMs":123456}
```

Events cover Cyclone's foreground phone-tool launches, workspace launches (including background and second-window
launches), and its app-opening controls. They do not cover Android launcher taps, third-party launches, task adoption,
or system-restored processes. `eventType` describes Cyclone's dispatch: `cold_start` is the first observed dispatch for
that tuple in this Cyclone process, `relaunch` is a subsequent one (also second-window starts), and `profile_switch`
is a workspace switch launch or the first dispatch after a recorded profile switch. These are dispatch hints, not
proof of Android process state. The tracker is bounded and resets on process restart.

Reply once with `{version:1, configRef:null|string, state?:enum}`. An omitted `configRef` is also no reference.
The entire response is at most 4096 UTF-8 bytes; references are at most 1024 UTF-8 bytes. References are opaque:
Cyclone never resolves, executes or displays them. Unknown fields are ignored; unknown major versions are refused.
No reply is required. SDK providers may return null for a ready response with no reference.

All providers share a **250 ms total dispatch deadline**, expressed in the device's monotonic elapsed-realtime clock.
Providers run on Binder workers and must not wait on the UI thread or synchronously request a Cyclone launch.
Cyclone uses a bounded dispatch pool; failure or timeout never vetoes launch. No response by the deadline means
`degraded`; malformed reply or transport failure means `failed`. Only the registered UID can reply; duplicates and
late responses are discarded. The SDK checks the deadline before invoking connector logic.
`startup.status.v1` (same tuple, `profiles.startup` scope) returns the last ephemeral `{version:1,state,configRef}` for
that connector installation and tuple, or `unknown` with no reference. Status is bounded and may be lost on restart.

Example (connector-owned implementation; no behavior is supplied by Cyclone):

```kotlin
val provider = object : ProfileBehaviorProvider(context) {
    override fun beforeLaunch(event: JSONObject): JSONObject? = null
}
CycloneConnector.connect(context).use { cyclone ->
    check(cyclone.hello().getInt("minor") >= 1)
    cyclone.registerProfileProvider(provider)
    // Keep provider alive; re-register when Cyclone reconnects.
}
```

JSON Schemas express JSON types/code-point limits; the UTF-8 byte and serialized-size limits above are also enforced
at runtime. `vectors.json` includes config and version negotiation cases executed by Cyclone's unit tests.
