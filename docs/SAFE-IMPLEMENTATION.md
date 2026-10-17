# Cyclone Safe — Alpha 135 implementation

Local development candidate for Plan 61, based on Alpha 134 (`7a266ba8`), in
`C:/Users/Agent/Cyclone-safe-alpha135`, branch `codex/cyclone-safe-alpha135`.
The original specification is `Cyclone V5 plan/61-cyclone-safe.md` (PR #221).

**Physical uninstall/reinstall verification: UNVERIFIED.**
Owner authorized the normal signed prerelease lane: “Continue until released” (2026-10-16).
Do not uninstall the owner's Alpha 132: this work is not installed on that phone.
The existing 132 → 134 installation failure has not been diagnosed or changed.

## What is now connected

- Settings → Privacy → Cyclone Safe opens setup, root/folder save status, PC receipt,
  pending profile users, recovery words shown once and a persisted SAF folder picker.
  Recovery words use the pinned BIP-39 English list and production scrypt parameters.
- A 256-bit per-phone Safe key has independent local Keystore, root, newest-hive and
  recovery wrappings. Root storage is fixed at `/data/adb/cyclone-safe`, with a
  root-owned 0600 keyfile. Root transport has no model shell surface and does not
  route key material through the bootstrap step journal. Folder-only setup is an
  explicit choice, requires a folder, and does not mistake denied root for empty storage.
- `SafeManifest` classifies preferences, five databases, file stores, transient state
  and every Keystore alias. Unknown stores fail capture instead of being omitted.
  Databases use `VACUUM INTO`. Preferences must be settled and valid UTF-8 XML,
  with no DTD/entities. Symlinks and unknown restore destinations fail closed.
- Identity, hive, vault, provider key and Mind memory are opened only in app memory,
  encrypted into the Safe and resealed under the new installation's keys. Mind's
  `CYM1:` envelope is preserved. Old PC authorization is revoked; a fresh explicit
  pairing remains active without duplication. Device/signing/bootstrap private keys
  are never exported. Hive and identity capture share their existing locks so the
  captured sealed stores and hive wrapping agree.
- Snapshots use `CYCSAFE1`, chunked AES-256-GCM with authenticated headers/terminal
  frame and per-entry SHA-256. `.part`, fsync, rename and authenticated read-back
  precede retention. Root, selected folder and PC keep three copies per user.
  Folder retention and deletion preserve other phones' copies.
- Main obtains each unlocked profile's ciphertext through a fixed private worker;
  only that profile process opens its Keystore. Main commits shared root/folder copies.
  Stopped/locked/unavailable users remain explicitly pending. A reinstalled empty
  profile cannot replace its existing external Safe. On restore, existing users are
  installed/started through fixed commands; locked or missing users retain their
  copies. An intact profile installation is preserved rather than overwritten.
- JobScheduler saves after a ten-minute settled change, when the app backgrounds,
  and at 03:30 local while charging. Jobs persist across restart; file observers
  reconnect on process start. Clock/timezone changes reschedule the nightly job.
- The startup Restore card precedes normal Activity/runtime initialization. A
  process barrier also holds accessibility runtimes, vault initialization and
  ordinary gateway actions; pairing and private Safe transfer remain available.
  Authenticated previews show user, date, size and counts, never account values.
  Restore uses the newest complete matching snapshot and does not merge snapshots.
  Partial application pins that sequence and keeps normal startup blocked for retry.
- PC Command Center uses one bounded ciphertext relay worker and transactional
  archive storage. Explicit returning-phone approval rebinds the archived identity
  only to a freshly trusted signing key. Glass can unlock the hive wrapping and
  HPKE-seal the Safe key to the phone's new device key. Without a hive wrapping,
  Glass transfers ciphertext and the owner enters recovery words on the phone.
- Owner-only Delete all Cyclone data requires typing `DELETE` and Android
  `DEVICE_CREDENTIAL`. PC signed-token acknowledgement precedes folder, root,
  hive, profiles and Main deletion. It stops at the first failure. The optional
  hive-account checkbox is off by default and uses existing encrypted tombstones
  and acknowledgements. The model/MCP/fleet/TaskCommands cannot mint this token;
  the browser cannot initiate deletion. Expired pending deletes cannot cause a
  previously deleted PC archive to be uploaded again.
- `hasFragileUserData` and cloud/device-transfer exclusions are installed. The
  signer configuration, package, SDKs, shared UID and permission set are unchanged.
  CI includes the SafeManifest and owner-delete boundary guards.

## What protection means

This preserves **the last verified snapshot** outside the sandbox. Android can
still uninstall the app. Unsaved work and changes made after the last successful
snapshot are not guaranteed recoverable. A pending or failed save is not protection.
The setup screen records success only after authenticated read-back; PC receipts
and pending profile users are shown separately.

## Remaining verification and limits

- Physical update, keep-data uninstall, ordinary uninstall/reinstall, all-user
  uninstall, fresh Keystore behavior, per-user root approvals, SAF provider fsync/
  rename, 03:30 charging delivery, native credential confirmation, PC pairing/
  HPKE delivery and real multi-profile restore are **UNVERIFIED**. No device actions
  were taken. Windows installer/signed CI packaging has not been invoked.
- Folder-only mode protects the current Main snapshot. Automatic capture of other
  Android users requires root and their unlocked profile processes; these users
  are reported pending rather than silently treated as protected.
- Capture/authentication currently buffer selected entry bodies in app memory
  (wire limits: 512 MiB per entry, 2 GiB total). Large-library heap/scalability
  validation remains outstanding. The PC relay also buffers one ciphertext file.
- Restore-as-new-profile for a deleted Android user remains the explicitly deferred
  Plan 61 feature. Its existing Safe is kept. Provider/transport failures keep older
  backups; no broad cleanup, reboot or data reset is used for recovery.
- Historical opaque `profile-backups` are preserved as bytes. Their embedded old
  Keystore ciphertext is not retroactively made recoverable; current live stores
  have independent rekey adapters.
- Full deletion requires every recorded PC to acknowledge and selected storage to
  be available. If that fails, deletion pauses; already completed steps are not
  undone. Archive deletion is not a claim of forensic secure erasure on SSD/flash.

## Validation

See `HANDOFF-2026-10-16-CYCLONE-SAFE-INTEGRATION.md` for exact commands, counts,
artifacts and the scope of the filesystem uninstall simulation. Physical results
must be recorded separately; these tests are not package-manager evidence.
