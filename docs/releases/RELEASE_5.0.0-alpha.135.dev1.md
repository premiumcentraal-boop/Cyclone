# Alpha 135 — Cyclone Safe

Version `5.0.0-alpha.135.dev1`, Android code **289**; Glass `1.0.0-alpha.67`.
Owner authorized the normal signed prerelease lane on 2026-10-16.

- Adds encrypted per-user recovery snapshots, independent Safe-key wrappings,
  root and owner-selected folder storage, and ciphertext replication to the PC.
- Adds Safe setup/status, a startup Restore card, authenticated count previews,
  fresh-key resealing and explicit pending states for unavailable profile users.
- Adds returning-phone recovery through ordinary pairing: unlocked Glass can
  send an HPKE-sealed key; recovery words can instead be entered only on the phone.
- Adds debounced/background/nightly charging saves, verified read-back before
  pruning, and protection against a fresh empty profile replacing an older Safe.
- Adds owner-only full deletion, gated by typed confirmation and native screen
  lock, with signed PC acknowledgement, ordered deletion and stop-on-failure.
- Adds Android's keep-data uninstall flag and backup exclusions for sealed stores.
  No new signer, shared UID, SDK or permissions. Existing approval boundaries stay.

This protects the **last verified snapshot**, not work newer than that snapshot,
and does not prevent Android uninstall. Current live sealed stores are resealed
on recovery; historical opaque backup ciphertext is retained without promising
that an old Keystore key can be recovered.

**Physical-device verification is UNVERIFIED.** The owner's Alpha 132 remains
unprotected by this uninstalled candidate and must not be uninstalled. Existing
132 → 134 install compatibility is not diagnosed by these changes. Locked/missing
profile users and unavailable backup destinations remain pending. Large-library heap/scalability remains unverified. Windows packaging and APK signing are gated by release CI.

Local checks and artifact details: `HANDOFF-2026-10-16-CYCLONE-SAFE-INTEGRATION.md`.
Implementation boundaries: `SAFE-IMPLEMENTATION.md`.
