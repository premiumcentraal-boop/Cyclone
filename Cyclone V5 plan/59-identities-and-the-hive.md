# 59 · Identities and the hive: accounts that are kept, bound to a profile, and shared by every phone

**Status:** plan, 2026-10-14. It replaces roadmap run 17 ("Vault, complete") in plan 35, with the owner's decisions
below. Builds 1–3 ship through the fast lane. Each says what is UNVERIFIED on a real phone.

## 0. The owner's decisions (2026-10-14)

1. **No automatic password changes.** A password stays as it is until the owner changes or deletes it.
2. **Phones keep accounts like a password manager.** A saved account stays until the owner deletes or changes it.
3. **An account is more than a password.** Its **identity card** holds:
   - first name, last name and full name;
   - birthday;
   - username;
   - email (Gmail included);
   - phone number;
   - the password.

   These are the details the sign-up templates (the Instagram starter) use today.
4. **An identity is bound to one Cyclone profile.** The Accounts overview shows the binding clearly. **Open** switches
   to that profile and launches the app.
5. **Deleting a profile never deletes its identities.** They stay saved and show as **Not bound to a profile**, with
   "was: <profile>", until the owner binds them again.
6. **Deleting an account in Cyclone moves it to the trash for 30 days**, then it is gone. **Deleting never touches the
   real account:** Cyclone never opens the app to close or delete it. It only removes its own saved copy.
7. **The hive.** Every phone shares one collective store of **identities and verified skills**, synced through the
   owner's PC.
   - A new account is saved to the hive **the moment it is created**, before anything else can go wrong.
   - The same holds for a skill the moment it is verified.
8. **Rented cloud phones** (VMOS and similar) get full hive access while they are rented. When the rental ends, the
   phone is removed from the hive. What it made was already saved to the hive, so nothing is lost.
9. **Memories stay local:** to one phone, and within it to one profile. They are not shared. With many phones and
   accounts, shared memories would become a mess.
10. **Windows Hello unlocks the Vault in Glass**, or the phone through the browser's QR passkey flow. The master
    password stays as the backup.
11. **Two kinds of "fingerprint":**
    - **Windows Hello** guards the Vault on the PC.
    - **The phone's own lock** (fingerprint, face or PIN) guards **viewing** a saved account on the phone. Using one
      inside a task never asks, so routines run while the owner is away.

## 1. Where things are kept, and why

| What | Master copy | On each phone | Why |
|---|---|---|---|
| Identity cards (details + password) | The owner's PC, encrypted the same way as the Vault: the PC holds ciphertext only | The **Main** profile's Cyclone, encrypted with an Android Keystore key, plus a list of what is bound where | Main is never deleted, so a deleted profile can't take identities with it. The PC copy survives a lost or expired phone |
| Verified skills | The owner's PC (the hive) | Every phone | Each phone can do what any one has learned |
| Memories (people, notes, app preferences) | Stay where they are: one phone, one profile | Not synced | Owner decision 9 |

**Binding** is a label plus a permission:
- a task may use an identity only in the profile it is bound to;
- a task on another phone or profile asks the owner first ("This account belongs to Food on Pixel 8. Use it here?").
  This stops two phones logging into one account at once.

**What never happens** (CI-guarded, as for every secret today):
- an identity's values never appear in logs, diagnostics, run records, debug files, Brain or learning stores, Android
  backups, or anything the phone sends to the PC except as sealed ciphertext;
- the PC never sees a value in the clear.

## 2. Build 1: accounts and identities (one phone + the PC)

1. **Identity store on the phone** (Main profile):
   - encrypted entries holding app, service, label, the identity fields, the password, the bound profile (id and label
     at binding time), times and version, and trash time;
   - the AES key lives in the Android Keystore and isn't bound to user authentication, so tasks can fill without asking;
   - a JVM-tested pure core with a thin Android edge.
2. **Saved when an account is made:**
   - when an Account Setup run finishes and the phone has verified the new profile, the phone saves the identity,
     bound to the profile the run used;
   - **at the same moment** it seals the identity to the Vault's public key and reports it to the PC, so the PC keeps
     the master copy before anything else happens (decision 7).
3. **Kept from the Vault:** any Vault login can be sent to a phone with **Keep on <phone> · <profile>**. It is sealed to
   that phone's device key, as sealed delivery works today, then kept rather than used once.
4. **Changes and deletes from Glass** reach the phone the next time it is connected. Deletes go to the 30-day trash on
   both sides.
5. **Settings → Accounts on the phone:**
   - every account with app icon, username, email and a **profile badge** ("Food", or "Not bound");
   - filters by profile, app or Not bound, and a search;
   - **Open** switches to the bound profile with the existing staged switch and its way back, then launches the app.
     If it's already the profile in front, it just opens the app;
   - **Details** open only after the phone's own lock (BiometricPrompt: fingerprint, face or PIN);
   - **Bind to profile… / Move to profile…, Delete (to trash), Trash** (Restore, Delete forever).
6. **A deleted profile** turns its identities into Not bound ("was: Food"). Nothing is erased.
7. **Glass → Command Center → Accounts** shows the same overview from the PC's copy:
   - by phone and profile, with the same badges, the trash, and **Open on phone**;
   - the template's personal details move out of the table cells into the encrypted Vault. The table keeps only the
     account name and its status.
8. **Tests and guards:**
   - the identity store's pure core;
   - redaction tests on every new store;
   - a guard that no identity field name is ever logged or sent in the clear;
   - the trash expiry;
   - profile-deletion survival.

### 2.1 As built (1a + 1b, not yet released)

**1a (PC):**
- `command/identities.py` stores bindings (phone, profile id and label), an origin and the trash time. It holds no
  values.
- Account Setup binds the new account to the profile the phone reports in `setup.profile`.
- A bound profile missing from its phone's roster turns the identity "Not bound (was X)".
- The trash pauses the account, and the job loop purges after 30 days. A purge removes Cyclone's account row, vault
  items and binding only.
- Glass:
  - Create accounts writes the row's identity card into the vault login, encrypted, the moment the password is made;
  - the vault shows the card with the password behind the same check;
  - Accounts → Identities shows profile badges, Bind / Change profile, Switch phone and the trash.

**1b (phone):**
- `identity/`:
  - `IdentityBook`, the pure core;
  - `IdentityVault`, one AES-GCM Keystore box in Main, with its prefs classified NEVER;
  - `IdentitySaver`, which saves an account when Account Setup reports it created.
- **The password a created account gets:** SealedDelivery keeps a copy, in memory only, when vault_fill takes it in
  an Account Setup run. It is wiped when the run finishes.
- **An account created in another profile** is sealed for Main's Keystore key and handed over through
  `ProfileBootstrapRuntime.handIdentityToMain`. These are fixed root steps, with the same cipher as a carry, bound to
  Main and a nonce. Main's `ProfileBootstrapService` (`IDENTITY_ACTION`) takes it in.
- **Settings → Profiles → Accounts** (in Main):
  - filters by profile, Not bound and Trash, plus a search;
  - **Open** uses the owner's staged switch, then `launchAppIn` opens the app;
  - **Details** show after BiometricPrompt (strong biometric or device credential), for 30 s, with Copy password
    (marked sensitive, cleared after 30 s);
  - Bind / Move to profile, and Delete, Restore and Delete forever.
- Guard: `scripts/ci/tests/test_identity_guard.py`.

**Moved to Build 2** (they are syncing, which is the hive's job):
- **Keep on <phone · profile>** from Glass;
- edits and deletes kept in step between the PC and phones;
- using a phone-held account inside a task while the PC is off;
- linking the phone's copy to the PC's identity record.

Until then, tasks still get passwords through sealed delivery from the PC, as before.

## 3. Build 2: the hive (every phone)

**Owner decisions (2026-10-14, second round):**
- **A verified skill** is one the owner saved from a run that finished as done, and that has since run successfully at
  least once more.
- **Every phone gets every account.** Each phone still knows clearly whether an account is really needed there: its
  binding, and whether a task asks for it.
- **A cross-profile login by the agent gets a confirmation card with the reason.** This is the case where an account is
  bound to another profile or phone. The card is an Owner Moment: the owner sees the account, where it is bound, where
  the agent wants to use it, and the agent's own reason. Nothing logs in until the owner says yes.

### 3.1 The hive key

The PC's Vault is locked with a key only the owner's browser holds, so the PC can't hand a password to a phone while
Glass is closed. The hive has its own symmetric key instead:
- **Who holds it:** only the owner's phones and the owner's browser. Every hive item is stored on the PC encrypted with
  it, so the PC keeps ciphertext only, as with the Vault.
- **How a phone gets it:** when a phone joins, Glass (unlocked once) seals the hive key to that phone's device key.
  This is the same HPKE path as sealed delivery. The phone keeps it in its Keystore.
- **How Glass keeps it:** the hive key is kept wrapped by the Vault key, so Glass can read the hive whenever the Vault is
  unlocked.
- **When a phone leaves** (removed, or a rental ended), Glass makes a new hive key and re-encrypts. The phones that stay
  receive the new key sealed to them. The phone that left can't read anything written after it left.

### 3.2 The runs

| Run | What it delivers |
|---|---|
| **2a. Joining** | Glass → Hive: every phone, whether it is in the hive, its last sync, own or rented. **Add to hive** (seals the hive key, Glass unlocked once) and **Remove from hive** (wipe on reconnect, new key). The gateway keeps members and items, ciphertext only. New phone ops: `hive.join`, `hive.pull`, `hive.push`, `hive.leave` |
| **2b. Accounts sync** | A created account is pushed at once, encrypted with the hive key. Every phone pulls when connected, and offline phones catch up. Edits, binding, trash and restore sync. The newest version wins and older versions are kept in history. Glass Identities reads the hive. The vault identity card from build 1a is folded into the hive item |
| **2c. Using accounts on the phone** | vault_fill can use the phone's own saved account, so no PC is needed. In the bound profile, Main hands the login to that profile's Cyclone, sealed, for that task only. **Anywhere else** (another profile or phone), the agent must ask through a **confirmation card**: the account, its binding, where the agent wants to use it, and the agent's reason. Only after a yes is it handed over, for that task. The Accounts page shows per account where it is used ("Used by Food tasks" / "Not used on this phone yet") |
| **2d. Skills sync** | A skill becomes **verified** after its second successful run (decision above), then is pushed at once and installed on every phone. App maps and manuals stay per phone, because they depend on the app version. Memories stay local (decision 9) |
| **2e. Rented phones** | A rented phone joins with full access. When the VMOS lease expires or the phone is released, it is removed from the hive automatically and the key is replaced. What it made was already pushed when it was made |

### 3.2.1 Run 2a as built (not yet released)

- **Gateway:** `command/hive.py` (`HiveStore`) keeps three tables:
  - `hive_key`: versions, each wrapped by the vault key;
  - `hive_member`: own or rented, and joining / member / leaving / left;
  - `hive_envelope`: the hive key sealed to one phone, relayed by the job loop once that phone is reachable, retried
    every 30 s, and kept 7 days.

  An envelope is accepted only when it is bound to the phone's **trusted** device key, its id, the current key version,
  and a future time. A key version may only go up by one. After a phone is removed, it gets `hive.leave`,
  `rotationNeeded` turns on, and `needsKey` lists the members still on an older key.
- **Routes:** `GET /v1/cc/hive`, `POST /v1/cc/hive/key`, `POST /v1/cc/hive/members/{id}/add|remove`.
- **Phone ops:** `hive.join` and `hive.leave`, handled by `identity/HiveKeyring.kt`.
  - The keyring opens the HPKE envelope (`cyclone-hive-key/v1`) only for this phone's device key, this join id, a
    version no older than the one it has, and a time not yet passed.
  - It keeps every version wrapped by its own Keystore AES key, in Main only (`cyclone_hive_v1`, NEVER carried).
  - `hive.leave` wipes all of it.
- **Glass:** Vault → **Hive** card, while the vault is unlocked. It has Start the hive, Add to hive and Remove from
  hive. It replaces the key by itself after a phone leaves, and seals the current key to every member that lacks it.
- **Guard:** `HiveGuard` in `scripts/ci/tests/test_identity_guard.py`.
- **Physical device:** UNVERIFIED.

### 3.3 Rules that hold through every run

- The PC never holds a hive value in the clear. A CI guard checks the hive tables and routes, as it does for the Vault.
- Nothing is pulled from the hive into a memory store, a log, a debug file or a backup.
- A confirmation card never approves itself and never times out into a yes. The approval timeouts of run 16 apply:
  no answer means the run stops.
- Deleting is still the 30-day trash, everywhere, and never touches the real account.

## 4. Build 3: Windows Hello for the Vault

- Glass is served at `localhost`, so browsers allow passkeys. The old address redirects.
- The Vault unlocks with Windows Hello, or a phone through the QR passkey flow. This uses the WebAuthn PRF extension
  where the browser supports it. Where it doesn't, the passkey can't unlock the encryption, and the master password
  is used as today.
- Showing a password or identity in Glass needs a fresh check, stays visible for 30 seconds, and is audited.

## 5. Riding along (small fixes from the 2026-10-13 handoff)

- The Speed default contradiction.
- `MIND_WORDS` "mail" sends simple requests to the Mind.
- `allowBackup=true`.
- A non-constant-time token compare.
- Loose Lab approval matching.

## 6. Not in this plan

- Automatic password changes (decision 1).
- Shared memories (decision 9).
- Email alerts and routines started by notifications (plans 33 §12.7–12.8, later).
