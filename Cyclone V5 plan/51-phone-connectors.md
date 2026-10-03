# Plan 51: Phone connectors (scope)

Status: **scope, not built.** Written 2026-10-03 at alpha.103. Asked for by a team building a profiles companion module
that wants first-party contracts instead of reverse-engineering the app (their eight questions are answered in §9).

## 0. The decision in one paragraph

A **connector** is an ordinary Android app the owner installs next to Cyclone. It talks to Cyclone **on the phone,
over Binder** (no network, no ports, no tokens), only after the owner approved it by name and signing certificate in
Cyclone's Settings. Version 1 lets a connector **read** Cyclone's profiles, **keep its own small namespaced data** on a
profile, **add its own entries** to the profile selector (shown as the connector's, never as a real profile), open its
own screen from them, and **receive profile events**. It cannot create, switch, change or remove profiles, cannot reach
`PhoneToolExecutor`, the gateway, the vault or the Mind. Anything that would change the phone goes, later, through an
owner card via Task Kit. The contract is `cyclone.connector/1`, published with JSON Schemas and a small client library.

## 1. What exists today (and why it can't just be "opened up")

| Area | Today | Gap |
|---|---|---|
| Profile model | `CycloneProfileRecord` in private SharedPreferences (`ProfileRegistryStore.kt`): `id` `Cyclone_<16 hex>`, `label` (≤ 40, unique), `user`, `parent`, `secondary`, `packages`, `stage`, `ready`, `removed_at`, `emoji`, `color` | No schema version. Unknown keys are dropped on every save (records are rebuilt from known fields). No custom types. |
| Selector | Home `ProfileSlider` (`ui/v32/HomeR6.kt`, `HomeProfile`) and the Profiles page (`CycloneProfilesPage.kt`, `ProfileCluster`) | Built only from the registry and Android users. No contributions. |
| Phone ↔ other apps | Nothing. Exported components are system-facing (accessibility, notifications, Shizuku provider behind `INTERACT_ACROSS_USERS_FULL`, the camera viewer for an authorised ADB shell). | A new exported surface is new attack surface and must be designed as such. |
| Phone ↔ PC | Line JSON protocol `3.3` on the local socket `cyclone_gateway`, through `adb forward` (8766), with device trust (`GatewayTrustV33Store`) and op allow-lists. The PC reads profiles via `profiles.list/apps/switch/app` (`/v1/devices/{id}/profiles…`). | Internal channel; never for third-party apps. |
| Events | Ports run events to PC plugins only. | No profile events. |
| Published contracts | `cyclone.ports/1`, `cyclone.package/1`, `cyclone.index/1` (PC side, `tools/cyclone-ports-sdk`) | Nothing for the phone. |

**Profiles are separate Android users.** Cyclone in the owner user keeps the registry; each profile has its own Cyclone
install. v1 connectors therefore talk to **Cyclone in the owner user only** (where the registry and the selector the
owner sees live). A connector installed inside a profile sees nothing from v1.

## 2. Architecture

```
 Connector app (any signer, user-installed)             Cyclone (com.cyclone.mobile, owner user)
 ┌──────────────────────────────────────┐   Binder   ┌──────────────────────────────────────────────┐
 │ cyclone-connector-client (AAR)       │──────────►│ ConnectorService (exported, AIDL)             │
 │  • hello(contract, scopes)           │           │  1. who is calling: Binder.getCallingUid →   │
 │  • profiles(), setExt(), entries()   │◄──────────│     package + signing cert (lineage-aware)   │
 │  • events(sinceSeq)                  │  wake     │  2. approved by the owner for these scopes?   │
 │ <meta-data cyclone_connector.xml>    │ (no data) │  3. rate limit, size limits, secret screen   │
 │ <receiver ConnectorWake> (explicit)  │           │  4. ConnectorStore (own SQLite)              │
 │ <activity EntryScreen> (deep link)   │           │ Selector: real profiles + "From <connector>" │
 └──────────────────────────────────────┘           └──────────────────────────────────────────────┘
                                                      PC: profiles.list gains read-only connector entries
```

- **Discovery:** Cyclone lists apps that declare the intent action `com.cyclone.connector.CONNECT` (needs a `<queries>`
  entry for package visibility) and reads their static manifest `@xml/cyclone_connector` (§4).
- **Identity:** the calling UID's package and its signing certificate's SHA-256, checked on **every** call, accepting
  certificates in the app's own rotation lineage (`PackageManager.hasSigningCertificate`). A different signer = not
  approved. No tokens are issued or stored.
- **Approval:** Settings → Connectors shows each discovered connector: name, icon, package, certificate fingerprint,
  requested scopes in plain words. The owner switches it on; Cyclone records (package, cert, scopes, approvedAt).
  Revocation is one switch; uninstalling removes the record and its data.
- **Why Binder and not local HTTPS:** both apps are on the same phone. Binder gives the caller's identity from the
  kernel, needs no port, no certificate, no token storage, no reconnect logic, and works without network permission.

## 3. The contract `cyclone.connector/1`

**Negotiation:** `hello({contract: "cyclone.connector/1", minor, scopes})` → `{contract, minor, granted, profile_schema}`.
Cyclone supports the current major and the one before it; minors only add fields and calls; unknown fields are ignored
by both sides.

**Scopes (v1):**

| Scope | Allows |
|---|---|
| `profiles.read` | list profiles: `id`, `label`, `emoji`, `color`, `kind` (`owner`/`profile`), `state` (`ready`/`setting_up`/`in_trash`), `current`, app count. Not package lists by default. |
| `profiles.apps.read` | the package names per profile (separate because it reveals what the owner uses) |
| `profiles.ext` | read and write **its own** namespace `ext.<connectorId>` on a profile |
| `selector.contribute` | add its own entries to the selector |
| `events.profiles` | profile events (§3.3) |

Not in v1, on purpose: create, switch, rename, remove, install apps into a profile, anything through
`PhoneToolExecutor`. A later `profiles.request` scope may *ask*: Cyclone shows an owner card through Task Kit and
does it itself, with the usual approval boundaries.

### 3.1 Profile schema (versioned)

The registry gains `schemaVersion: 2` and an `ext` object, migrated in place (v1 records read as v2 with empty `ext`).
`ext` is `{ "<connectorId>": { …JSON… } }`:
- Cyclone stores it and returns it **untouched** to its owner connector, and to no one else (not Glass, not the PC, not
  the Mind, not diagnostics, not backups shared off the phone in v1).
- At most 4 KB per connector per profile and 32 keys; JSON values only; keys matching the existing secret patterns
  (`password`, `otp`, `token`, `api key`…) are refused, same screening as the gateway; values are never logged.
- A connector's `ext` is deleted when the connector is revoked or uninstalled, or the profile is permanently deleted.
- Saving a profile from Cyclone's own code must carry `ext` through (fixes today's "unknown keys dropped").

### 3.2 Selector contributions

A connector contributes **entries**, not profiles: `{id, type, label ≤ 40, subtitle ≤ 60, icon, status, deepLink}`.
- `icon`: a drawable resource name from the connector's own APK (Cyclone loads it with the connector's resources) or
  a PNG ≤ 64 KB; nothing remote.
- `status`: `ready` | `attention` | `off` plus ≤ 60 characters.
- `type`: the connector's own string. Cyclone has no per-type code: every entry renders from its metadata with a
  generic card, so an unknown type can never break the selector.
- **Never mistaken for a real profile:** entries sit after real profiles, with the connector's name and a "From
  <connector>" label, and can't use the labels of existing profiles.
- **Tap** opens the connector's activity named in its manifest (explicit component, exported, owned by that package),
  with only `entryId` as an extra. Cyclone never opens arbitrary intents or URLs from a connector.
- At most 8 entries per connector; static ones from the manifest, dynamic ones via `setEntries()`.

### 3.3 Events

`profile.created`, `profile.updated` (label, look, apps), `profile.switched` (the current profile changed),
`profile.trashed`, `profile.restored`, `profile.removed`.
- Cyclone keeps a journal per approved connector: `{seq, type, profileId, at}`, no secrets and no package names unless
  `profiles.apps.read` is granted. 7 days or 1 000 events, whichever is first.
- **Delivery:** the connector pulls with `events(sinceSeq)`. Ordered by `seq`, **at least once**: the connector
  de-duplicates by `seq`. When the connector fell behind the retained window, the answer starts with `reset: true`
  and the connector re-reads `profiles()`.
- **Wake:** after new events Cyclone sends an explicit, data-free broadcast to the connector's declared receiver
  (allowed for explicit broadcasts). The connector then binds and pulls. No payload travels in the broadcast.

## 4. The connector manifest (static)

```xml
<!-- AndroidManifest.xml of the connector -->
<service android:name=".CycloneConnect" android:exported="false">
  <intent-filter><action android:name="com.cyclone.connector.CONNECT" /></intent-filter>
  <meta-data android:name="com.cyclone.connector" android:resource="@xml/cyclone_connector" />
</service>
<receiver android:name=".CycloneWake" android:exported="true"
          android:permission="com.cyclone.mobile.permission.WAKE_CONNECTOR" />
```

```xml
<!-- res/xml/cyclone_connector.xml -->
<cyclone-connector contract="cyclone.connector/1" id="acme-profiles" label="Acme Profiles"
    scopes="profiles.read profiles.ext selector.contribute events.profiles"
    entryActivity=".EntryActivity" wakeReceiver=".CycloneWake" />
```

`WAKE_CONNECTOR` is a signature permission held only by Cyclone, so no other app can fake a wake.

## 5. Packaging, SDK and publication

- **Connectors** live in their own repositories, minSdk 33 (Cyclone's), any signer, any update channel. A signer
  change outside the lineage needs re-approval.
- **`tools/cyclone-connector-sdk/`** in this repo:
  - `SPEC.md`;
  - `schemas/*.schema.json` for every call, event and the manifest;
  - an AIDL file and a Kotlin client library (AAR) published from release tags;
  - a sample connector built in CI (never distributed);
  - a conformance test app that exercises every call against a fake Cyclone.
- Schemas and the AAR are attached to Cyclone's GitHub release, with their SHA-256 in `release-manifest.json`, so
  connector CI can fetch exact versions.

## 6. Security and privacy rules (guarded in CI)

- `ConnectorService` is the only new exported component. It checks identity and scope on every call, rate-limits
  (e.g. 20 calls/s), caps payload sizes and never throws internal details back.
- No connector call reaches `PhoneToolExecutor`, the gateway socket, the vault, sealed codes, the Mind, Brain or
  learning stores. A guard test enforces the import graph.
- `ext` never leaves the phone and never reaches a model. Diagnostics log call names and outcomes only.
- Task buttons for anything a connector *requests* later go through Task Kit, keeping approval boundaries.

## 7. Build plan

| Step | What | Size |
|---|---|---|
| **K1** Model | registry `schemaVersion` 2 + `ext` carried through every save, migration, `ConnectorStore` (approvals, entries, event journal), events emitted from `ProfileLifecycle`/registry changes. Pure + Robolectric tests. | medium |
| **K2** Service | AIDL `ConnectorService`, discovery with `<queries>`, identity + lineage check, scopes, limits, secret screening, `hello` negotiation. Settings → Connectors with the approval card, revoke, data cleanup on uninstall (package-removed receiver). | medium–large |
| **K3** Selector | connector entries in `ProfileSlider` and the Profiles page ("From <connector>"), deep-link launch, wake broadcasts with the signature permission. PC: `profiles.list` gains read-only entries (`connector`, `label`, `status`). Glass shows them. | medium |
| **K4** SDK + guards | `tools/cyclone-connector-sdk` (spec, schemas, AIDL, AAR, sample, conformance app), guards (one exported service, no forbidden imports, `ext` never in diagnostics/PC payloads), release assets, docs. | medium |

About **two alphas** for K1–K4. **Physical acceptance is required** (multi-user phones, package visibility, signer
lineage, OEM background limits on wakes): until it's done, the release notes say UNVERIFIED.

## 8. Owner decisions before building

1. **Allow on-phone third-party connectors at all?** Recommendation: yes, v1 read + own data + entries + events only.
2. **`ext` payload:** allow (4 KB, own namespace, phone-only)? Recommendation: yes.
3. **Package lists:** behind their own scope `profiles.apps.read`? Recommendation: yes.
4. **Later `profiles.request` (ask Cyclone to switch, via an owner card):** in v2, not v1.
5. **Client library distribution:** GitHub release assets of this repo (recommended) vs. a Maven registry.

## 9. Answers to the companion team's questions (as this plan would make them)

1. **Profile model:** schema v2 with `schemaVersion` and a per-connector `ext` namespace stored and returned untouched
   (§3.1). Custom "types" are selector **entries** rendered from connector metadata, never real profiles (§3.2).
2. **Selector:** static entries in the manifest plus runtime `setEntries()`, gated by `selector.contribute`; deep link
   to the connector's declared activity (§3.2, §4).
3. **Transport:** Binder on the phone (no ports, mDNS, TLS or tokens). The PC gateway socket stays internal (§2).
4. **Pairing/auth:** owner approval in Cyclone Settings by package + signing certificate; scopes per connector;
   revoke anytime; nothing stored on the connector side (§2).
5. **Events:** pull by `seq`, ordered, at least once, data-free wake broadcast (§3.3).
6. **Packaging:** connector repos are separate; minSdk 33; any signer (lineage-aware); any update channel (§5).
7. **Versioning:** `hello` negotiation, majors N and N−1, additive minors; schemas + AAR as release assets (§3, §5).
8. **Roadmap:** none of this exists today. A spec PR from the team is welcome **against this plan**: the contract
   lands as a first-party extension point owned by `apps/mobile`, after the owner decisions in §8.
