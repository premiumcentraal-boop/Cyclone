# Cyclone Alpha 130 — companion provisioning and bootstrap acknowledgements

Built on published Alpha 129. Android version code: 284.

- Companion provisioning lookup is testable and fails closed for malformed or ambiguous connector identities. Cloak remains a required Cornerstone when discovered; the existing per-user install/enable/start/unlock sequence is unchanged.
- Bootstrap acknowledgements are committed atomically. Missing, transient, partial and stale reads retry within a bounded budget; only the expected nonce and Android user can complete the transfer.
- A correlated negative acknowledgement now reports its fixed stage and reason immediately instead of being silently converted into “couldn't verify the receiving profile.” Failure replies retain the receiving user and transfer nonce. Shareable collection diagnostics contain only `ok`, `user`, `stage` and `reasonCode`.
- Cold-start preparation has a longer bounded wait for the receiver. No user reboot, data reset, binding changes, new permissions or connector wire-protocol changes are introduced.

These are local bootstrap/provisioning checks, not a platform compatibility verdict. Physical-device acceptance is **UNVERIFIED**: no device was attached during preparation. Earlier device evidence does not verify this build.

Gateway/MCP remain Alpha 123; Glass remains Alpha 65. Standard release CI verifies the exact phone commit, preserves the established signing certificate/lineage gate, and builds the normal Windows and Glass packages.

Upgrade through the normal signed app update. Retain app data. On a receiving-profile error, retry the normal profile switch; no clear-data or reboot workaround is required. Full local results and exact commands are recorded in the bootstrap handoff.
