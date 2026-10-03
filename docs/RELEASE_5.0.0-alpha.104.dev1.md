# Cyclone V5 Alpha 104: Phone connectors, first half

Developer alpha for owner testing. It builds on Alpha 103 and includes it. Plan: `Cyclone V5 plan/51-phone-connectors.md`
(steps K1 and K2). Contract for connector authors: `tools/cyclone-connector-sdk/SPEC.md`.

Versions:
- **Mobile:** `5.0.0-alpha.104.dev1` (version code 249).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.104.dev1.exe` (runtime unchanged from alpha.103:
  `5.0.0-alpha.103.dev1`).
- **Glass:** `1.0.0-alpha.58` (unchanged).

## What's new

**Settings → Connections → Connectors.** Apps installed on this phone can now ask to work with Cyclone. Each one is
listed with:
- its name, package and signing-key fingerprint;
- what it asks for, in plain words. For example "See your profiles' names and looks" or "Keep its own small notes on
  a profile".

Approve it with two taps, revoke it with one. Until you approve, a connector can only ask Cyclone which version it
speaks.

- **Updates:**
  - an update that asks for more shows "It now also asks to …"; until you approve, it keeps only what you already
    approved;
  - a connector signed by a different key than the one you approved is blocked until you approve it again.
- **Revoking or uninstalling** a connector removes its approval, its entries and its data on every profile at once.

**What an approved connector can do (contract `cyclone.connector/1`):**
- **Read profiles:** names, emoji, colour, state (ready, setting up, in Recently deleted) and app count. The app list
  itself needs a separate approval.
- **Keep its own small data on a Cyclone profile:**
  - at most 4 KB;
  - nothing that looks like a password, token, API key or code;
  - it is never sent to the PC, Glass or a model, and never logged.
- **Store its own entries** for the profiles list. Showing them on Home and in Profiles comes in Alpha 105.
- **Hear profile events:** added, changed, switched, moved to Recently deleted, restored, removed. They come in order;
  a connector that fell more than 7 days behind is told to read the profiles again.

**What a connector can never do:**
- create, switch, rename or remove a profile;
- use Cyclone's phone controls;
- reach the PC gateway;
- see passwords, codes or the vault.

It talks to Cyclone only on this phone, over Android's own app-to-app channel (Binder): no network, no ports, no
tokens. Cyclone checks who is calling from Android itself, not from anything the app says.

**Profiles keep everything they're given.** The profile list now has a schema version (2). Saving a profile no longer
drops data Cyclone doesn't use itself; that's what lets a connector's data survive renames, new looks and setup steps.

## Not in this build

- Connector entries on Home and in Profiles, opening the connector's screen from them, waking a connector when events
  arrive, and showing connector entries to the PC and Glass: Alpha 105 (plan 51 K3).
- Which profile is in front (`current`): Alpha 105. Until then `profile.switched` events report switches.
- The client library, JSON Schemas, a sample connector and a conformance app: plan 51 K4.

## Tests

- **New:**
  - `ConnectorTest` (13), covering:
    - profile schema 1 → 2 with data kept through renames;
    - strict connector manifests;
    - approvals that follow package, id and signing lineage;
    - the rate limit;
    - data limits and secret refusal;
    - entries that can't pose as profiles;
    - registry changes as events;
    - the journal's order, pages and reset;
    - approval and scope on every call;
    - profiles that show only what the scopes allow and only the connector's own data;
    - setting and clearing data;
    - entries and events through the calls;
    - internal errors that say nothing.
  - Guard `test_connector_guard.py`:
    - connector code reaches nothing powerful;
    - one exported door, and signature permissions;
    - identity from Binder, every call checked;
    - connector data never reaches the gateway, diagnostics, the Mind or Brain;
    - the contract is one JSON call.
- **Results:** the phone suite and lint (below); CI guards: 298 pass; release versions coherent.

## Physical acceptance

UNVERIFIED. Needs a test connector app (the sample comes with K4). On a phone with Cyclone profiles:
1. Install the connector. Settings → Connectors lists it with its key and what it asks for.
2. Before approving, its calls answer `NOT_APPROVED`. Approve. `profiles` lists This phone and your profiles.
3. Save data on a profile, rename the profile in Cyclone, and read the data back unchanged.
4. Switch profiles; the connector's `events` show `profile.switched`.
5. Revoke, then reinstall: the data and entries are gone. Uninstall: same.
