# Cyclone Mobile 5.0.0-alpha.109.dev3

Submitting a Secrets Card used to reject the entire screen when the keyboard, overlay or app content changed its fingerprint. The card now retains a process-local identity for the intended field before it opens and locates that field on a fresh screen read before filling.

Recovery requires the original app or browser origin, form labels, input identity, execution scope and controller epoch. Ambiguous fields and changed forms remain blocked. Recreated accessibility nodes and changed observation IDs, paths, bounds or focus do not alone invalidate a fill. Failures before writing can retry three times; an unverified write is never retried automatically.

Android password-control flags survive observation sanitization, while password values and editable text remain redacted. Secret input waits for delayed readback without using the clipboard or writing twice. Native field resolution checks package, class, resource and password state before dispatch and after focus changes.

Ordinary text replacement also separates field identity from tap clearance. A uniquely identified editable node may receive ACTION_SET_TEXT while a selection toolbar overlaps its screen rectangle. Taps, submission, non-editable controls, ambiguity and changed execution scopes retain their existing checks.

Account Setup now carries recorded format hints and picker choices into its runner prompt. SMS verification fields explicitly use native retrieval instead of treating the deliberately absent table code as a missing owner value.

Voice-mode overlay windows now leave the visible accessibility hit tree during host gestures and restore the current requested face afterwards. Overlay yield waits beyond the first frame callback so traversal can commit the input-window changes. Account Setup also treats post-creation verification as part of completion instead of appending another final create click.

Validation: mobile JVM tests cover refresh recovery, scope and origin changes, ambiguity, privacy, bounded retries and delayed verification. Physical Pixel 8 validation is pending installation of the signed candidate. This change does not claim guaranteed delivery across arbitrary app navigation or unavailable accessibility input.
