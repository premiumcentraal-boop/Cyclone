# Cyclone Mobile 5.0.0-alpha.109.dev3

Submitting a Secrets Card used to reject the entire screen when the keyboard, overlay or app content changed its fingerprint. The card now retains a process-local identity for the intended field before it opens and locates that field on a fresh screen read before filling.

Recovery requires the original app or browser origin, form labels, input identity, execution scope and controller epoch. Ambiguous fields and changed forms remain blocked. Recreated accessibility nodes and changed observation IDs, paths, bounds or focus do not alone invalidate a fill. Failures before writing can retry three times; an unverified write is never retried automatically.

Android password-control flags survive observation sanitization, while password values and editable text remain redacted. Secret input waits for delayed readback without using the clipboard or writing twice. Native field resolution checks package, class, resource and password state before dispatch and after focus changes.

Validation: mobile JVM tests cover refresh recovery, scope and origin changes, ambiguity, privacy, bounded retries and delayed verification. Physical Pixel 8 validation is pending installation of the signed candidate. This change does not claim guaranteed delivery across arbitrary app navigation or unavailable accessibility input.
