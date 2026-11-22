# Cyclone Alpha 124 — profile creation you can finish

Built on the published Alpha 123 source. Android version code: 278.

- Removed the Creator, Store and Personal name presets.
- Choose apps, Confirm apps and Create profile stay visible below the scrolling content, above the keyboard and navigation bar.
- Back moves through the setup steps without losing the name or app selections. Restored picker/review pages reload their app catalogue.
- Cyclone, the installed root manager (including hidden Magisk), Shizuku, discovered Cyclone Cloak, Android Settings, permission controller, system launcher, system keyboard and installed owner cornerstones are automatically included. Their checked controls cannot be turned off.
- Essential apps do not consume the 50 additional-app allowance; a profile can be created with essentials alone. Creation independently rediscovers and verifies essentials rather than trusting picker flags.
- Cloak, when installed, is required during later bootstrap too. System basics never inherit root policies. Apps use fresh per-profile data; Android settings and sign-ins are not cloned.

Glass remains Alpha 65; gateway/MCP remain Alpha 123. Publication uses the established CI build/signing lane and update-compatible Android signer. No app data or existing profile is deleted by this change.

Validation: Android unit tests and profile/security/product guards. Physical reproduction confirmed the old picker hides its action after the app list. New-profile creation and physical post-update acceptance remain **UNVERIFIED** until exercised on a device; CI results do not imply physical acceptance.
