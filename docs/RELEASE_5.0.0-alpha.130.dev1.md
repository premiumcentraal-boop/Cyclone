# Cyclone Alpha 130: fleet health, smarter routines, and saved accounts bound to profiles

Developer alpha for owner testing. It is built on published Alpha 129 and installs over it as a normal update.

## Versions

- **Mobile:** `5.0.0-alpha.130.dev1` (version code 284).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.130.dev1.exe` (runtime, gateway and MCP `5.0.0-alpha.130.dev1`).
- **Glass:** `1.0.0-alpha.66`.

## Fleet health and alerts (roadmap run 14)

**The phone's heartbeat now also sends:**
- battery temperature and Android's heat level;
- free storage;
- whether Android may stop Cyclone (battery optimisation);
- whether missions can run behind your screen;
- whether notifications are allowed.

**Command Center → Health shows:**
- every phone the Command Center has seen, including phones that dropped off, which show as offline;
- the alerts, which clear themselves once the cause is gone:
  - **needs you:** a phone offline for more than 10 minutes, a phone in quarantine, a login that failed twice in a row;
  - **warning:** a recipe or routine under 80% over 7 days, a connection's daily cap reached, a hot phone, a low battery
    that isn't charging, less than 1 GB free;
  - **info:** a phone behind this PC's version.

**Quarantine.** Three phone failures in a row take a phone out of rotation until you press **Release**. Phone failures
are: unreachable, lost the mission, Cyclone restarted, or refused a task for a reason that isn't about the task. A task
failure doesn't count. A run that is already going is never stopped.

**Alerts on your phone.** Pick the phone that gets alerts and press Send a test.
- At most 6 alerts an hour.
- The notification carries no action.
- Email alerts are not built.

**The week:** success per phone, recipe or routine and account, with the median time to pick up and the median
approval wait.

## Smarter routines (roadmap run 16)

**New schedules:**
- **Monthly:** a day of the month, or the last day. A short month runs on its last day.
- **A weekday of the month:** for example the first Monday or the last Friday.

**Triggers:**
- **After a routine:** runs when another routine succeeds, fails, or finishes either way.
  - It runs once per run of the routine before it, even when that routine runs on several phones.
  - Loops and chains longer than 5 are refused.
  - Deleting the routine at the head of the chain pauses its followers.
- **From a link:** a secret web address a program on your PC can call to start the routine.
  - It runs only the routine's own goal; whatever is sent to the link is ignored.
  - It works at most once a minute.
  - It is shown once, and only a scrambled copy is kept. **New link** replaces it.

**Approval time limits:**
- **Defaults:** 1 hour for sending, paying, deleting, posting and publishing; 24 hours for everything else.
- Change each one under Approvals → "How long approvals wait". No limit is an option.
- When a limit runs out, the run **stops without approving** and the task says why. A connection call that waited too
  long is declined.

**Approve all.** When 2 to 10 identical approvals wait (same kind, all fully shown), **Approve all N** asks once more,
then answers each one for its own request.

## Saved accounts bound to a Cyclone profile (plan 59, build 1)

**On the PC:**
- When Glass creates accounts from a sign-up template, the account's **identity card** is saved encrypted in the Vault,
  next to the new password, at the moment it is made. The card holds the name, birthday, username, email and phone.
- The Vault shows the card with the password, behind the same check.
- **Command Center → Accounts → Identities** lists every account with a **profile badge**, Bind / Change profile, Switch
  phone, and a **30-day trash** (Restore, Delete forever).

**On the phone:**
- A new account is saved the moment it is created, bound to the profile it was made in.
- Accounts are always kept in **Main**, encrypted with an Android Keystore key, so deleting a profile never takes its
  accounts. An account made in another profile is sealed and handed to Main in the background.
- A deleted profile's accounts show as **Not bound (was …)**, with Bind to profile.
- **Settings → Profiles → Accounts:**
  - profile badges, filters and search;
  - **Open** switches to the account's profile and opens the app there;
  - **Details** show after your fingerprint, face or PIN, for 30 seconds;
  - **Copy password** clears the clipboard after 30 seconds;
  - Bind / Move to profile, and Delete to the 30-day trash.

**Deleting never touches the real account in the app.** It only removes Cyclone's copy.

**Not in this release (plan 59, build 2, the hive):**
- "Keep on phone" from Glass;
- keeping edits and deletes in step between the PC and phones;
- phones using a saved account inside a task while the PC is off.

Until then, tasks get passwords from the PC's Vault as before.

## Upgrade notes

- **New phone permission:** `USE_BIOMETRIC`. It is a normal permission with no dialog, used only for the fingerprint or
  PIN check before account details show.
- **New PC route without the gateway bearer:** `POST /v1/hooks/{token}`. The link is its own credential, and the agent
  MCP servers can't call it.

## Validation

- **Gateway:** `pytest apps/device-gateway/tests` passed, including 30 new tests for health, routines and identities.
- **Guards:** `scripts/ci/tests` passed, plus the new `test_identity_guard.py`.
- **Agent MCP:** tests passed.
- **Glass:** `npm test` passed (347), as did the build and `glass_guard.py`.
- **Android:** `:app:testDebugUnitTest` passed (2,648 tests, 0 failures).
- **PR CI:** premiumcentraal-boop/Cyclone#218 was green on mobile build, Windows companion and Glass + gateway.

## What is UNVERIFIED on a real phone

- The new heartbeat facts.
- An alert notification arriving on the picked phone.
- Quarantine and Release with a real phone.
- A routine started after another routine, or from its link.
- An approval timing out on the phone.
- An account created in a non-Main profile appearing in Main's Accounts.
- **Open** landing in the right profile and app.
- The fingerprint or PIN check.

The rows to check are in `docs/FLEET_ACCEPTANCE.md`.
