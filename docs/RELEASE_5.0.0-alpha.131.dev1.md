# Cyclone Alpha 131: fleet health, smarter routines, saved accounts bound to profiles, and the hive

Developer alpha for owner testing. It is built on the published Alpha 130 and installs over it as a normal update.

These features were meant to be Alpha 130. Another session published its own Alpha 130 first (companion provisioning
and bootstrap acknowledgements), so the publisher skipped this one. That release's fixes are merged in here, so
nothing from it is lost.

## Versions

- **Mobile:** `5.0.0-alpha.131.dev1` (version code 285).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.131.dev1.exe` (runtime, gateway and MCP `5.0.0-alpha.131.dev1`).
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

## The hive: one store of accounts for every phone (plan 59, runs 2a and 2b)

The hive is off until you start it in **Vault → Hive**, with the vault unlocked.

**Joining:**
- **Start the hive** makes the hive key in your browser. The PC keeps it locked by the vault key.
- **Add to hive** seals the key to that phone's trusted device key; the phone takes it the next time it is connected.
- **Remove from hive** makes the phone wipe its hive key and the accounts that came from other phones. A new key is
  made for the phones that stay.
- The PC only ever holds the hive's contents encrypted.

**Accounts sync:**
- An account a phone saves goes to the hive at once. Every other phone in the hive gets it within seconds; a phone that
  was offline catches up when it reconnects.
- Binding, the trash, restoring and delete forever sync too. When two phones change the same account, the later change
  wins. The PC keeps the last 10 versions. Delete forever reaches every phone, and the account doesn't come back.
- On the phone, an account bound on another phone shows as **"Food on Galaxy"**, with a new **Other phones** filter.
  Open works only for profiles on that phone.
- In Glass, **Vault → Hive accounts** shows every hive account. You can see its details, bind it to a profile on any
  phone, delete, restore and delete forever, go back to an earlier version, and **Add from the vault** (the identity
  cards saved in the vault).

## Phones log in with their own saved accounts (plan 59, run 2c)

- At a login screen, a task uses an account this phone keeps for that app, without the PC.
  - The agent sees account names only, never a password. When several fit, it names one.
- **The account belongs to this profile on this phone:** it fills at once. In a profile other than Main, Main hands
  over that one value, sealed, for that one request.
- **The account belongs anywhere else** (another profile, another phone, or not bound): a **confirmation card** first.
  - It shows the account, where it's bound, where the agent wants to use it, and the agent's reason.
  - Answer it on the phone or in Command Center → Approvals. It asks every time, is never part of Approve all, and no
    answer is never a yes.
- The phone's Accounts page shows where each account is really used ("Used by Food tasks · 2 days ago").
- Glass → Vault → Hive accounts → **Add and link from the vault** also links the phones' accounts to the Command
  Center's records.
- With no saved account for the app, the Secrets Card and the PC's Vault work as before.

**Not built yet:** skills sync (2d), and rented phones removed automatically when the rental ends (2e).

## A steady overlay

The overlay and its work card no longer blink while Cyclone acts or switches.
- **It stays on screen during actions.** Until now the whole overlay was hidden for every tap, swipe, scroll, Back and
  Home, and stayed hidden until the screen settled: 0.35 to 1.8 seconds each time. Now a host action only makes it
  step back:
  - it takes no keyboard focus, so the app keeps focus and Back reaches it;
  - it leaves the accessibility tree, so Cyclone reads the app cleanly;
  - it still takes your touch, so Stop works while Cyclone acts.
- **It hides only when a simulated stroke would land on the glass itself**, and only for that stroke. A tap elsewhere
  on the screen leaves it in place.
- **The keyboard position doesn't jump** while focus is with the app during an action.
- **The blur behind the card no longer pulses** when the card grows or shrinks by a line of text. It follows in place
  and steps aside only for a real move, such as appearing, sliding or the keyboard glide.
- **Mode and tab swaps no longer show an empty frame.** The new surface fades in on top before the old one fades out.
  This covers the Ask bar and island, composer and gate, and the check-in cards.
- **The trace field** fades back in over 120 ms after a screen capture, instead of popping.
- **No window re-creation.** A render right after the overlay first appears no longer tears down and re-creates every
  window.

## Merged from the published Alpha 130

- The companion provisioning lookup fails closed on malformed or ambiguous connector identities.
- Profile bootstrap acknowledgements are committed atomically, and a refusal reports its stage and reason.
- Cold start waits longer for the receiving profile.

## Upgrade notes

- **New phone permission:** `USE_BIOMETRIC`. It is a normal permission with no dialog, used only for the fingerprint or
  PIN check before account details show.
- **New PC route without the gateway bearer:** `POST /v1/hooks/{token}`. The link is its own credential, and the agent
  MCP servers can't call it.
- **New phone ops:** `hive.join`, `hive.leave` and `hive.sync`. The account book gains an outbox; older books open as
  before.

## Validation

- **Gateway:** `pytest apps/device-gateway/tests` passed (1,176, 3 skipped), including the health, routines, identities,
  hive and hive-sync tests.
- **Guards:** `scripts/ci/tests` passed (355), including `test_identity_guard.py` with the hive and account-use checks.
- **Glass:** `npm test` passed (351), as did the build, `glass_guard.py` and `test_glass_hosting.py`.
- **Android:** `:app:testDebugUnitTest` passed (2,681 tests, 0 failures).
- **Release metadata:** `release_versions.py --check` is coherent.

## What is UNVERIFIED on a real phone

- The new heartbeat facts.
- An alert notification arriving on the picked phone.
- Quarantine and Release with a real phone.
- A routine started after another routine, or from its link.
- An approval timing out on the phone.
- An account created in a non-Main profile appearing in Main's Accounts.
- **Open** landing in the right profile and app.
- The fingerprint or PIN check.
- A phone joining the hive, an account reaching a second phone, and removal wiping it.
- A login filled from a saved account, the hand-over from Main to another profile (it uses root), and the confirmation
  card on the phone and in the Command Center.
- The merged Alpha 130 bootstrap fixes on this build.
- The steady overlay: a tap or swipe Cyclone makes near the card (the card should hide only for that stroke), Stop
  pressed mid-action, and Back during a run.

The rows to check are in `docs/FLEET_ACCEPTANCE.md`.
