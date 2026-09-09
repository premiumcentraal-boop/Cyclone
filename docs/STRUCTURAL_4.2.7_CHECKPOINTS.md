# Structural reliability lane — work in progress

Base: b3f499416df7e569940827eb2250e95e1c0d9eb9 (preserved 4.2.6).
Branch: agent/427-structural-reliability. No publication authorized in this lane.

Checkpoint 1 source: ordinary overlay and in-app phone requests select foreground;
queued ordinary requests use the same route. Explicit background retains workspace checks.
Profile intent remains fail-closed pending exact-user routing and provisioning work.

Remaining: exact profile target resolution, cheap verified launch, terminal task cleanup,
launcher resilience, multi-profile ownership registry/provisioning/switching and full CI.
Version remains 4.2.6 until the structural lane passes. Physical testing UNVERIFIED.

Checkpoint 2 source: cancel commands bind the exact task after service recreation;
cleanup joins execution, releases the session, clears the active pointer, and cancels
its notification. Closed results have a separate bounded in-memory history (not disk).
Background panel cleanup restores the compact launcher; repeated attachment renders
through the existing controller instead of creating another stack.

Validation pending CI; local Gradle distribution download is network-blocked.
