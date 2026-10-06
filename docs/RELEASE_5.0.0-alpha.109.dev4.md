# Cyclone Mobile 5.0.0-alpha.109.dev4

Includes the dev3 secret-field recovery, direct form input, signup context and voice-overlay corrections.

Host actions now keep Cyclone overlays hidden through action dispatch and result settling, rather than restoring focus as soon as a gesture callback completes. Global Back follows the same path, so a focused Ask composer cannot consume a host navigation command. Approval, ownership and after-state checks remain in the canonical executor.

When a host observation has no readable root under agent control, one bounded read yields the overlay and tries again. Existing readable roots are unchanged. Human control and visible Secrets Cards are excluded. The main overlay hides accessibility descendants as well as its root during yield.

Unit tests cover nested gesture/verification lifetime, exception restoration, canonical wiring and the observation recovery exclusions. Physical verification remains separate; the dev3 update did not resolve all onboarding taps. An exposed button-edge tap advanced to Android's optional contacts prompt, where observations were empty and global Back still needed recovery. No universal stability guarantee or public release is claimed.
