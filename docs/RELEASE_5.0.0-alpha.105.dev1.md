# Cyclone V5 Alpha 105: Phone connectors, complete

Developer alpha for owner testing. It builds on Alpha 104 and includes it. Plan: `Cyclone V5 plan/51-phone-connectors.md`
(steps K3 and K4). Contract for connector authors: `tools/cyclone-connector-sdk/SPEC.md`.

Versions:
- **Mobile:** `5.0.0-alpha.105.dev1` (version code 250).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.105.dev1.exe` (runtime `5.0.0-alpha.105.dev1`).
- **Glass:** `1.0.0-alpha.59`.
- **Connector kit:** `Cyclone-Connector-Client-5.0.0-alpha.105.dev1.aar`, `Cyclone-Connector-Sample-5.0.0-alpha.105.dev1.apk`
  and `Cyclone-Connector-Schemas-5.0.0-alpha.105.dev1.zip`, each with its SHA-256 in `release-manifest.json`.

## What's new

**Connector entries in your profiles list.** Approved connectors can add their own entries to the profile slider on
Home and to Profiles, under "From your connectors":
- They always come after your own profiles and are marked "From <connector>", so they can never pass for one of
  yours.
- They show the connector's icon and status, such as "Sign in again".
- Tapping one opens the connector's own screen. Cyclone doesn't switch anything for it.

**Connectors know which profile is in front.** `profiles` now says which one is current: this phone, a Cyclone
profile, or "can't tell" when the switch happened outside Cyclone.

**Connectors hear about changes promptly.** After a profile change, Cyclone pokes approved connectors that listen
for events. The poke carries no data, and only Cyclone can send it. The connector then reads the events itself.

**Glass shows your connectors.** The profiles bar has a read-only "From your connectors" row: each connector's name
and its entries with their status. Nothing else of a connector reaches the PC: not its data on your profiles, its
package or its key. A phone older than this one shows nothing there.

**The connector kit, for teams building connectors.** Every release now carries:
- **Client library** (`.aar`): connect, then one call per method; refusals come back as errors with a code.
- **Sample connector** (`.apk`): a small debug-signed app with a button for every call. It asks for every
  permission, has an entry screen and listens for pokes.
- **Schemas and test vectors** (`.zip`): the spec, JSON Schemas for every answer, and `vectors.json`, a fixed set of
  requests with the exact answers Cyclone gives. Cyclone's own build checks every vector, so the file is what
  Cyclone answers.

## Tests

- **New:**
  - `ConnectorTest` +2:
    - `current` when Cyclone knows it, and `null` when it doesn't;
    - the PC report carries names and entries only.
  - `ConnectorVectorsTest`: every published vector (17) is what Cyclone answers.
  - `HomeProfilesTest` +1: connector entries come after your profiles.
  - Gateway:
    - `test_connectors_list.py`: exact validation, and an older phone reads as `supported: false`;
    - `test_connector_schemas.py`: every schema is valid and describes every vector, and the schemas refuse fields
      Cyclone never sends.
  - Glass: parsing the connectors answer.
  - Guards (`test_connector_guard.py`, `ConnectorKitGuard`):
    - the client's AIDL is the app's;
    - the kit never links Cyclone itself;
    - the sample protects its service and receiver with Cyclone's signature permissions;
    - CI builds and publishes the kit with checksums;
    - the vectors are checked on both sides.
- **Results:** the phone suite, lint, the gateway suite, Glass and the CI guards (see the commit).

## Physical acceptance

UNVERIFIED. On a phone with Cyclone profiles:
1. Install this Cyclone and the sample connector. In Settings → Connectors, approve "Connector sample".
2. In the sample, tap **Hello**: it shows approved and the granted scopes. Tap **Profiles**: it shows This phone,
   your profiles and `current`.
3. Tap **Add two entries**. They show on the Home slider and in Profiles as "From Connector sample". Tap one: the
   sample's entry screen opens with the entry id.
4. Switch profiles in Cyclone, then tap **Last wake** in the sample: it shows the time of Cyclone's poke and the
   events it read. **Events since the start** shows `profile.switched`.
5. With the PC paired, open Glass. The profiles bar shows the sample's entry, read only.
6. Revoke the sample: its entries disappear from Home, Profiles and Glass.
