# Plan 61: Cyclone Safe. Deleting the app never deletes your data

Status: **designed, not built** (2026-10-16). Builds on Alpha 134 (`5.0.0-alpha.134.dev1`, code 288) on the dev branch
`claude/cyclone-v5-handoff-review-9qrs40`. Ships as Alpha 135 (code 289).

## 1. The owner's rule

1. **Uninstalling Cyclone, however it happens, keeps all of Cyclone's data safely.** That covers Settings → Uninstall,
   uninstall for all users, `pm uninstall`, and an update that fails and needs a reinstall. Installing Cyclone again
   brings everything back.
2. **The only way to fully delete Cyclone's data is inside Cyclone**: Settings → Privacy → **Delete all Cyclone
   data**. It deletes every copy (the phone, its profiles, the safe copies and the PC's copy) after the owner confirms.
3. Nothing about this may weaken the existing rules (AGENTS.md):
   - no password, OTP, API key or typed secret in plain text anywhere outside the app's own sandbox, in Brain, learning
     stores, diagnostics or logs;
   - approval boundaries stay as they are;
   - the agent can never reach the delete.

## 2. Why data is lost today (Alpha 134)

Data map: `runtime/workspaces/PortableSettings.kt` (prefs) and the inventory below.

- Everything lives in the app sandbox (`/data/user/<u>/com.cyclone.mobile`, `/data/user_de/<u>/…`). Android deletes it on
  uninstall, in every Android user Cyclone is installed in.
- The secrets are sealed with **Android Keystore** keys:
  - `cyclone.identities.aes.v1` (saved accounts);
  - `cyclone.hive.aes.v1`;
  - `cyclone.vault.secrets.aes.v1`;
  - `cyclone.openrouter.api_key.v1`;
  - `cyclone.mind_memory.v1`;
  - `cyclone-device-key-v1`;
  - `cyclone_gateway_v33_phone_identity`;
  - `cyclone.profile.bootstrap.v1`.

  Android deletes those keys on uninstall, and they can never be exported. So even a copy of the files can't be
  opened afterwards.
- `android:allowBackup="true"` without `dataExtractionRules`: Google backup copies files, but the sealed ones are
  useless after a restore, and `adb backup` skips apps that target Android 12+.
- The only copies off the phone are hive accounts (from 133 on), as ciphertext on the PC, opened by Glass. Everything
  else (Brain, skills, routines, maps, missions, run logs, settings, profiles) exists only on the phone.

## 3. The design: one encrypted Safe, kept where uninstall can't reach

### 3.1 What goes in the Safe

A **Safe snapshot** is one file per Android user Cyclone runs in (Main and each Cyclone profile):

- `safe-<userId>-<seq>.cyc`: AES-256-GCM, with a random 96-bit nonce per chunk;
- a header: format version, Android user, Cyclone profile id, phone id, time, `seq`, and a SHA-256 for each entry.
  The header is authenticated as AAD.
- Contents: a zip of every store, as listed by a new `SafeManifest` table (§3.6).
  - Databases are copied with `VACUUM INTO` (consistent).
  - Prefs are copied as their XML.
  - Files are copied as they are.
- **Keystore-sealed stores are opened in memory and written into the snapshot as their plain values**, so the Safe
  holds them under the Safe key, not the dying Keystore keys. The plain values exist only inside the encrypted stream,
  never on disk unencrypted, never in a log.
  - Stores: identity book, hive keys, vault slots, OpenRouter key, Mind memory, and the gateway trust records.
  - The device key and phone signing key are **not** exported (they never leave the TEE). A restore makes new ones and
    re-pairs (§3.5).

### 3.2 The Safe key

The Safe key is a random 256-bit **Safe key `S`**, made once per phone. `S` is kept in several wrappings, so any one
of them can open the Safe after a reinstall:

| Wrapping | Where | Opens without the owner doing anything? |
|---|---|---|
| Keystore alias `cyclone.safe.v1` | app sandbox | Daily use only; it is gone after uninstall |
| **Root keyfile** | `/data/adb/cyclone-safe/safe.key`, mode 0600, root-owned (written with `su`; Magisk/KernelSU phones) | **Yes**, on this phone after reinstall |
| **Hive key** (newest version) | inside the Safe header on the PC | Yes, once Glass is unlocked and this phone is added back to the hive |
| **Recovery code** (24 words, shown once at setup, scrypt N=2^17, r=8, p=1) | written down by the owner | Yes, typed on the Restore card; the only route on a phone without root and PC |

On rooted phones (the owner's), the root keyfile is what makes "delete the app, data stays" fully automatic.

Security note: on a rooted phone the root boundary already protects the sandbox, and root is assumed trusted. A
keyfile readable only by root therefore adds no new reader.

### 3.3 Where the Safe is kept: three places, newest three snapshots each

1. **Root storage (rooted phones):** `/data/adb/cyclone-safe/u<userId>/`. Android never touches `/data/adb` on
   uninstall. Main writes every user's snapshot with the existing root path (`ProfileBootstrapRuntime`'s `su
   --mount-master` executor), as a fixed, validated command set like `ProfileSetupPlan`: no generic shell.
2. **A folder the owner picks** (every phone): Storage Access Framework, `ACTION_OPEN_DOCUMENT_TREE`, default
   suggestion `Documents/Cyclone Safe`, with a persisted URI permission. Shared storage survives uninstall. After a
   reinstall, the owner picks the same folder again on the Restore card (Android doesn't give a reinstalled app its
   old files without that).
3. **The paired PC:** new bridge ops `safe.put` / `safe.list` / `safe.get` / `safe.delete`.
   - The gateway stores ciphertext only under its data dir (`safe/<phoneId>/u<userId>/`) and keeps the newest three.
     It can't open it (like hive items).
   - Glass shows "Phone safe: saved 12 min ago, 41 MB" per phone. There is no download of the plain contents.

**When:** 10 minutes after a change has settled (debounced), when Cyclone goes to the background, and nightly at
03:30 local while charging. Before any update or reinstall Cyclone itself starts (none today), too.
- Each write goes to `.part`, then fsync, then rename.
- A snapshot that fails to read back (GCM tag, SHA-256) is deleted and retried. The older ones are kept.

### 3.4 Layer zero: keep-data uninstall

Add `android:hasFragileUserData="true"` to `<application>`. Android's own uninstall dialog then offers "Keep app data";
a reinstall with the same signer keeps the sandbox.

- This is a free extra, not the guarantee: the box is the owner's choice, and the Safe covers when it isn't ticked.
- Verify on a device that Keystore keys survive a kept-data uninstall. If they don't, the Safe restore covers it.

Also add `dataExtractionRules` / `fullBackupContent` that **exclude** the sealed prefs (the `IdentityVault.kt` header
already promises its prefs are never backed up; today that is false).

### 3.5 Restore after a reinstall

On first start, before onboarding, `SafeRestore.find()` looks in order:
1. root storage;
2. the PC, if this phone's gateway can still be reached on the LAN (the pairing record is gone, so it asks the PC via
   the existing pairing screen; Glass shows "This looks like your Pixel 8 coming back: restore its safe?" and the
   owner approves in Glass);
3. "Pick your Cyclone Safe folder".

A **Restore your Cyclone** card shows:
- what was found: per Android user, date, size and counts (accounts, routines, skills, maps), never values;
- how it will open: automatically with root, with Glass, or with the recovery code.

Restoring:
1. Write the stores back in this order: databases, files, prefs. Re-seal every secret under the new Keystore keys.
2. **Device key and pairing:** new device key. The PC recognises the phone id from the Safe and asks the owner in
   Glass to trust the new key (the same flow as pairing). After that, the hive key is resealed to the new device key
   (the existing "Add to hive" flow).
3. **Profiles:**
   - The Android users and their other apps survive a Cyclone uninstall (Cyclone deletes nothing).
   - Main reinstalls Cyclone into each profile with `install-existing` (as today) and restores that profile's snapshot
     through the bootstrap.
   - If a profile is gone, its snapshot is offered as "Restore as a new profile" (later) or kept.
4. Restore is idempotent and never merges two snapshots: the newest valid snapshot per user wins, and the others are
   kept until the next good save.

### 3.6 `SafeManifest`: nothing gets forgotten

Add a table, `runtime/safe/SafeManifest.kt`, next to `PortableSettings`. Every store has an entry: SAFE (in the
snapshot), SAFE_SEALED (opened and re-sealed), or NEVER, with a one-line reason.

NEVER is for:
- caches;
- `noBackupFilesDir/profile-debug`;
- the sealed leases cache;
- transient queue state.

**CI guard** (`scripts/ci/tests/test_cyclone_safe_guard.py`):
- every SharedPreferences name, Room/SQLite database name and `filesDir` subfolder used in `apps/mobile` must have an
  entry, so a new store without one fails;
- every Keystore alias must be SAFE_SEALED or NEVER with a reason.

## 4. Delete all Cyclone data (the only full delete)

**Where:** Settings → Privacy → **Delete all Cyclone data** (Main only; profiles show "Ask on Main").

**Screen:** it lists exactly what goes:
- this phone and N profiles' Cyclone data;
- the Safe copies in root storage, in the picked folder, and on PC "Office-PC";
- this phone leaves the hive, so the hive key is wiped here. Saved accounts stay on your other phones and in Glass,
  and one checkbox, off by default, also deletes them from the hive.

**Confirm:** the owner types `DELETE`, then the phone's own screen lock (BiometricPrompt with `DEVICE_CREDENTIAL`;
`USE_BIOMETRIC` is already in the manifest since 134).

**Order:** each step is reported, and the run stops on the first failure with "Nothing more was deleted":
1. the PC copy (`safe.delete`, which the PC confirms);
2. the picked folder's Safe files;
3. the root Safe copies and `safe.key`;
4. `hive.leave`, and the hive items if ticked;
5. each profile with `pm clear --user <u> com.cyclone.mobile` via the fixed root plan;
6. Main: delete every Keystore alias, then `ActivityManager.clearApplicationUserData()`.

**Never reachable by the agent:**
- the screen is Activity-only and owner-touch only;
- no tool, MCP route, gateway route, Lab mission, fleet path or `TaskCommands` entry reaches it;
- the Lab can't approve it (AGENTS.md: delete is approval-sensitive).

A guard test (`test_cyclone_safe_guard.py`) asserts that nothing outside `ui/settings/DeleteAllDataScreen` and
`runtime/safe/SafeDelete` calls `SafeDelete`.

`safe.delete` on the gateway answers only a phone-signed request carrying the delete confirmation token the phone
minted after the screen-lock check. Glass can't delete a safe.

## 5. Builds (Alpha 135)

1. **Phone core** (`apps/mobile/**`):
   - code: `runtime/safe/` (SafeManifest, SafeSnapshot writer/reader, SafeKey with its wrappings, SafeRootStore,
     SafeFolderStore, SafeScheduler using WorkManager or the existing scheduler, SafeRestore, SafeDelete);
   - manifest: `hasFragileUserData` and the backup rules;
   - settings: the Safe status row (last save per place, recovery code shown once, folder pick) and the Delete screen;
   - the Restore card on first start.
2. **Gateway** (`apps/device-gateway/**`):
   - `safe.*` bridge ops, the storage (ciphertext, newest three, per phone and user), the v5 contract shapes, the
     "phone coming back" pairing prompt and the Glass status line;
   - version bump for gateway/MCP/PC, and Glass if its page changes (AGENTS.md versioning).
3. **Tests:**
   - snapshot round trip (every store kind, the sealed ones re-sealed under a new key);
   - a tamper/truncate test, which must fail closed;
   - root command plans validated like `ProfileSetupPlanTest`;
   - restore order;
   - delete order and its stop-on-failure;
   - the guard above;
   - gateway storage keeps three and stores ciphertext only;
   - the delete token check.
4. **Release:** `docs/RELEASE_5.0.0-alpha.135.dev1.md`, with physical-device verification **UNVERIFIED**.

## 6. The owner's phone today (Alpha 132), honestly

- 132 has no Safe. Its data is protected only while the app stays installed. **Do not uninstall 132.**
- An update keeps the sandbox, so installing 135 over 132 keeps everything, and 135 makes the first Safe at once.
- That update is currently blocked by "App not installed" (134 has the same signer and lineage as 132, and its manifest
  only adds `USE_BIOMETRIC`, so the cause is on the device). 135 must not make that worse:
  - **no new signer, no new permission beyond what is needed, no `sharedUserId`, same SDKs**;
  - `hasFragileUserData` is a plain manifest flag.
- When the owner is back at the phone and PC, `adb install -r` (or Glass → Update phone → Details) shows the exact
  `INSTALL_FAILED_*` code; fix that first. Until then, 132's data stays only as long as 132 stays installed.
