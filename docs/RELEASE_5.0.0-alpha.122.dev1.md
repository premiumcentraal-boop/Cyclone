# Cyclone V5 Alpha 122: Cloak can ask to open a profile, Main stays yours, and the PC switch is made safe

Developer alpha for owner testing. It builds on alpha.121 dev1 and includes it.

- **Mobile:** `5.0.0-alpha.122.dev1` (version code 276).
- **PC runtime:** gateway and MCP `5.0.0-alpha.122.dev1` (no PC changes; the version moves with the release).
- **Glass:** `1.0.0-alpha.65` (unchanged).

Your two decisions from alpha.121, built. The details are in plan 57 ("Alpha.122") and
`docs/handoff/CLOAK_CONNECTOR_COMPAT.md` (CC7, CC8).

## Cloak can ask to open another profile

- **Cloak asks; you decide on Cyclone's own screen.** Cyclone Cloak (or any approved connector with the new permission
  "Ask you to open a profile") can ask Cyclone to open Main or one of your profiles. Cyclone shows:

  > **Open Profile C?**
  > Cyclone Cloak asks Cyclone to switch this phone to Profile C. Nothing changes unless you tap Open.

  with **Open** and **Not now**.
- **Only your tap on Open switches**, the same way as from Profiles: the way back is armed, the switch is confirmed and
  it is written down.
- **On a locked phone** you only get a notification. Nothing opens over the lock screen.
- **Limits:**
  - one question at a time (it expires after 2 minutes);
  - one request every 10 seconds per app;
  - never while Cyclone is running a task or waiting for your review;
  - only for a profile that is ready and not already open.
- **For Cloak's developer:** connector contract minor 3, `profiles.open.request.v1`, SPEC §13. The client library has
  `requestOpenProfile()`.

## Cloak never binds apps in Main

As you decided: Cloak binds apps in every Cyclone profile, never in Main. Cyclone already refused that; a test and a CI
guard now keep it so.

## The switch from your PC got the same safety

**What was wrong:** switching profiles from Glass (and from `cyclone-testbench profiles`) used an older path. It had no
automatic way back, and it wasn't written in the switch journal.

**Now it matches a switch made on the phone:**
- the way back is armed first, only once Cyclone in the target profile is listening;
- Cyclone waits for Android patiently and confirms with the target's hello;
- every switch is in "Last switches", marked "(from the PC)".

It still doesn't carry memory or skills; that stays with switches made on the phone.

## Tests

- **`ProfileOpenRequest57Test` (4):**
  - a request reaches Cyclone's question, named for the profile;
  - the refusals (not ready, already open, no permission, busy, rate);
  - one question at a time, with its expiry;
  - Main refused for Cloak's bindings.
- **`ProfilePcSwitch122Test` (2):**
  - the way back is armed before the switch, and the switch is journaled;
  - a late confirmation, a failed switch, and a target that isn't listening.
- **Connector vectors:** hello says minor 3, and the request needs its permission. The schemas gain `BUSY`,
  `ALREADY_OPEN` and the new result.
- **CI guards:**
  - nothing in the request path switches; only the Open button does;
  - the screen isn't reachable from other apps;
  - only Cyclone's connector door can ask;
  - Main stays unbindable;
  - the PC switch arms the way back only after the target listens.

## Limits

- **Physical: UNVERIFIED.** Rows 2.3 and 5.6–5.9 of `docs/PROFILES_DEVICE_MATRIX.md` are the checks for this alpha.
- **Next:** the VMOS cloud-phone runs (plan 56), now numbered alpha.123–126.
