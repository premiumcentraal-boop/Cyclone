# Profiles device matrix (plan 57)

Everything here is **UNVERIFIED** until it is run on the owner's phone. Mark each row with the date, the build and the
result. Attach the debug file (Profiles → Save debug file, or Glass → Home → Profiles health → Download debug file)
to anything that isn't ✓.

**Phone:** ______ · **Android:** ______ · **Root manager:** Magisk / hidden Magisk / KernelSU / APatch ·
**Cyclone:** 5.0.0-alpha.122.dev1 or newer.

## 1. Creating profiles (by hand, on the phone)

Creating stays on the phone by design; no PC route can create or delete a profile.

| # | Steps | Expected | Result |
|---|---|---|---|
| 1.1 | Empty phone: create **B**, then **C**, then **D** | Each is created with the name you gave. The "has" line shows Cyclone ✓, root manager ✓, root ✓ | UNVERIFIED |
| 1.2 | Full phone (Android's limit reached): create one more | "User limit reached" (or "No room for …"), with the places in use and the fixes. Never "Profile B already exists" | UNVERIFIED |
| 1.3 | Rooted, full: **Allow more profiles** → 8, then create | The limit reads back as 8; the profile is created; it survives a reboot | UNVERIFIED |
| 1.4 | **Restore default** | The module is gone and the limit is back as before; existing profiles stay | UNVERIFIED |
| 1.5 | Start one, kill Cyclone mid-setup, open **+** | "Finish Profile X / Discard and start new"; **Clean up** removes only Cyclone's unfinished user | UNVERIFIED |

## 2. Switching (`cyclone-testbench profiles --rounds 17`)

Run it on the PC with Cyclone and the phone connected. It makes 51 switches through every pair (Main→B, B→C, C→Main,
and back), then saves the phone's debug file to `testbench-results/profiles/`.

| # | Expected | Result |
|---|---|---|
| 2.1 | Every switch ends where asked (✓). A ↩ (came back by itself after about 45 s) is safe, but report it | UNVERIFIED |
| 2.2 | No ✗ stuck and no refused switch while no task is running | UNVERIFIED |
| 2.3 | The debug file's "Last switches" shows each one as "… (from the PC)" with `arm_return✓ confirm✓` and `DONE` (alpha.122: the PC switch has the way back too) | UNVERIFIED |

## 3. The way back

| # | Steps | Expected | Result |
|---|---|---|---|
| 3.1 | In B, the "You're in B — tap to go back to Main" notice | Opens the rescue screen; Main is one tap away | UNVERIFIED |
| 3.2 | Set a PIN on C. Switch to C and wait at the lock screen for over a minute | Cyclone does **not** switch back by itself while you type the PIN (no return is armed for a locked profile) | UNVERIFIED |
| 3.3 | Profile room → Android's user switcher | Shows on/off; **Turn on** works and reads back | UNVERIFIED |

## 4. Complete profiles

| # | Steps | Expected | Result |
|---|---|---|---|
| 4.1 | Mark an app (an LSPosed manager, for example) as Cornerstone, then switch to B | It is installed in B; on Magisk its grant is shared when it had one | UNVERIFIED |
| 4.2 | Change Hands and Fast Path in B, switch to Main | Main has the same settings | UNVERIFIED |
| 4.3 | Make a routine in Automation Studio in C, switch to Main | Main has the routine; a routine only Main had is still there | UNVERIFIED |
| 4.4 | Install a Market recipe in Main, switch to B | B has it; a recipe with a secret-looking input stays in Main | UNVERIFIED |

## 5. Cyclone Cloak

| # | Steps | Expected | Result |
|---|---|---|---|
| 5.1 | Approve Cloak in Main only, switch to B | The "has" line for B reads "Cloak ✓ approved ✓"; Cloak in B gets `approved: true` from `hello` | UNVERIFIED |
| 5.2 | Revoke Cloak in B, switch Main → B again | It stays revoked in B ("revoked in this profile") | UNVERIFIED |
| 5.3 | Bind an app in B from Cloak in Main, report `ready`, then `degraded` | The pill reads Rooted, then "Rooted · check" | UNVERIFIED |
| 5.4 | Add an app to B with the app manager, bind it, change B's apps | The binding stays (only an uninstall from B removes it) | UNVERIFIED |
| 5.5 | Cloak calls `root.status.v1` with `device.root.read` | `rootManager` and each profile's `rootProven` match the "has" lines; no package names or paths | UNVERIFIED |
| 5.6 | In B, Cloak calls `profiles.open.request.v1` for C (scope `profiles.open.request`) | Cyclone shows "Open Profile C?"; **Not now** changes nothing; **Open** switches to C with the way back; Cloak gets `profile.switched` | UNVERIFIED |
| 5.7 | Lock the phone, then Cloak asks again | Only a notification; nothing opens over the lock screen | UNVERIFIED |
| 5.8 | Cloak asks twice within 10 s; asks for B while in B | `RATE_LIMITED`; `ALREADY_OPEN` | UNVERIFIED |
| 5.9 | Cloak tries `config.set.v1` for `owner` | Refused (`NO_SUCH_PROFILE`): Cloak never binds apps in Main | UNVERIFIED |

## 6. Glass

| # | Steps | Expected | Result |
|---|---|---|---|
| 6.1 | Home → Profiles health → **Check profiles** | One line per profile ("Complete" or "Needs a look") | UNVERIFIED |
| 6.2 | **Download debug file** | A JSON file with no keys, codes, passwords or app data. Search it for your own PIN and password: nothing | UNVERIFIED |
