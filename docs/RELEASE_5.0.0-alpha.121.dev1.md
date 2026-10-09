# Cyclone V5 Alpha 121: Cyclone Cloak connected right, profile health in Glass, and a switch test you can run

Developer alpha for owner testing. It builds on alpha.120 dev1 (complete profiles) and includes it.

- **Mobile:** `5.0.0-alpha.121.dev1` (version code 275).
- **PC runtime:** gateway and MCP `5.0.0-alpha.121.dev1`. The gateway gains the profile debug route.
- **Glass:** `1.0.0-alpha.65`, with Profiles health on Home.

This alpha is plan 57 run P3 (`Cyclone V5 plan/57-hardened-profiles.md`), the last profiles run. It also delivers CC1–CC6
of the Cloak handoff (`docs/handoff/CLOAK_CONNECTOR_COMPAT.md`).

## Cyclone Cloak's approval follows you

- **Approve once, in any profile.** That approval is carried on every switch.
- **The profile you switch into checks it again first:**
  - the same Cloak app (package);
  - the same connector id;
  - the signing key you approved, in the signing history of the Cloak installed **there**;
  - only the permissions that Cloak still asks for.
- **If any check fails, nothing is approved, and Profiles says why:** "signed with a different key here; approve it
  again", "not installed in this profile", and so on.
- **Your "no" stays a no.** If you revoked Cloak in a profile after it was approved, a later switch doesn't approve it
  again there. Uninstalling isn't counted as a revoke.
- **Only Cyclone Cloak** works this way. Other connectors are still approved in each profile.

The "has" line shows the result: "Cloak ✓ approved ✓", or "approved ✗" with the reason underneath.

## Cloak's bindings stay put

- **When a binding is removed:**
  - when the profile is permanently deleted;
  - or when Android itself says the app is gone from that profile. The main profile's Cyclone checks this on each
    switch, and only from a real package list.
- **No longer removed** when Cyclone's own app list for the profile is out of date, for example after you added an app
  with the app manager.
- **A profile restored under a new Android user number** keeps its bindings. Each one is rewritten and read back.
- **One strict reading everywhere:** a malformed binding no longer counts as bound on one screen and not on another.

## The Rooted pill shows Cloak's health

Cloak reports per app whether its binding is working, and the pill follows the worst one:

| Pill | Cloak says |
|---|---|
| **Rooted** | ready, or not checked yet |
| **Rooted · check** | degraded |
| **Rooted · not working** | failed |
| **Native** | no binding |

## Root status for Cloak (connector contract minor 2)

- **New call, `root.status.v1`,** behind its own permission ("See whether root works in your profiles"). It answers
  with:
  - the root manager;
  - Cyclone's profile room;
  - whether root was proven in each profile on the last switch into it.
- **It runs no command** and names no package, path or version. A CI guard checks this.
- **The kit is updated:** SPEC §12, `CycloneConnector.rootStatus()`, a JSON schema and a test vector.

## Profile health and the debug file in Glass

- **Home → Profiles health → Check profiles.** The phone collects its profile debug file. Glass shows one line per
  profile, marked **Complete** or **Needs a look**.
- **Download debug file** saves it as JSON.
- **Redacted twice:** once on the phone, and again by your PC gateway, keyword and value together. The PC then runs the
  same fail-closed secret check it runs on everything the phone sends.
- **Size:** the oldest steps are left out if the file would be too large, and Glass says so.

## A switch test you can run: `cyclone-testbench profiles`

- **What it does:** with the phone connected, `cyclone-testbench profiles --rounds 17` makes 51 switches through every
  pair: Main→B, B→C, C→Main and back.
- **What it records** for each switch:
  - ✓ ended where asked;
  - ↩ came back by itself;
  - ✗ stuck or refused.
- **Then** it saves the phone's debug file next to the results.
- **The checklist:** `docs/PROFILES_DEVICE_MATRIX.md` covers creating profiles, the switches, the way back (including
  a PIN-locked profile), complete profiles, Cloak and Glass. Each row is UNVERIFIED until you run it.

## Tests

- **Mobile:**
  - `CloakConnect57Test` (8): approval checks, revoke wins, bindings kept and pruned only as Android says, user-id
    migration, root status facts only, minor 2, the "approved" line, the carry report;
  - new cases in `CycloneCloakProfileBindingTest`: the strict reading and the pill's worst state;
  - `GatewayV5ProfilesAdapterTest`: the debug answer's shape, trimming and health;
  - the connector vectors: hello minor 2, and `root.status.v1` needing its permission.
- **PC gateway:** the debug answer's shape, redacted twice, the fail-closed check, and the bearer-only route.
- **Glass:** Profiles health on demand, and the downloaded file matching what the phone sent.
- **Testbench:** the plan covers every pair; stuck and refused switches fail the suite; only profile routes are used.
- **Guards:** the connector guard (root status runs no command) and the existing profile and connector guards.
- **Locally:** 46 profile and Cloak tests ran, plus the gateway, Glass and testbench suites.

## Limits

- **Physical: UNVERIFIED.** No phone was available for this build.
- **The owner's first check** is `docs/PROFILES_DEVICE_MATRIX.md`, starting with `cyclone-testbench profiles --rounds 17`
  and Glass → Profiles health.
- **Not in this alpha:**
  - **Opening a profile from Cloak** (CC7) needs your sign-off. It would be a request only you can accept, on Cyclone's
    own screen.
  - **Cloak bindings for apps in Main** (plan 57 question 3) are still open.
- **Next:** VMOS runs V1–V4 (plan 56), from alpha.122.
