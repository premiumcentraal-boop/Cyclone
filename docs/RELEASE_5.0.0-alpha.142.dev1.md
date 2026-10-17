# Cyclone Alpha 142: what only broke on the phone

Developer alpha for owner testing. It is built on Alpha 141 and installs over it as a normal update.

## Versions

- **Mobile:** `5.0.0-alpha.142.dev1` (version code 296).
- **Cyclone for Windows, gateway and MCP:** unchanged, `5.0.0-alpha.138.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.68`.

## Why this release

Alpha 138–140 broke every mission on the phone while all 2,865 unit tests passed. Unit tests run on a desktop Java
runtime; the phone runs Android's. This release hunts that class of bug and fixes the ones it found.

## What was broken, and what you saw

**Website tasks could not be confirmed done.**
- Affected tasks such as "open reddit.com and log in", "go to facebook.com and create an account" and "open ad.nl".
- Cyclone checks that the browser shows the site by looking for its address in the page's data. On Android that data
  is written with `/` escaped as `\/` (`https:\/\/reddit.com\/`), so the address never matched.
- The task kept going, or ended without confirming it was done.

**A model's empty fields became the word "null".**
- Models often send optional fields as `null`. On Android, reading such a field as text gives `"null"`, while the
  library the tests used gives an empty text.
- So on the phone:
  - a Mind step could expect "null";
  - a search could look for "null";
  - a OneMind plan step with no target could aim at a control called "null".
- Now an empty field is simply "not given" everywhere a model's answer is read: Mind tools, OneMind and Flash plans,
  the page agent, voice understanding, the quick agent, the decision box, the Pilot and the Decisions replies.

**Failures hid their cause.**
- "Cyclone could not run this mission: com.cyclone.mobile.mind.one.OneMindPrompt" named only a class.
- A failure now says its type and its root cause, for example `NoClassDefFoundError: … (caused by
  PatternSyntaxException: …)`, with anything that looks like a secret hidden.
- This applies to the mission summary, the run trace, a tool that fails inside Cyclone, and the "didn't answer"
  notice.

## So it can't happen again

- **Unit tests now use Android's own JSON code** (AOSP `org.json`, in the test-only `test-android-json` module)
  instead of a desktop lookalike. On it, 3 of 2,870 tests failed: the website bug above. All pass now.
- **CI compiles every regex in the app with Android's own regex engine (ICU)**, where the build machine has it (GitHub's
  runners do), not only the brace check from Alpha 141. All 832 compile.
- **New guards:** `test_android_json_guard.py` and `test_failure_text_guard.py`, plus the ICU check in
  `test_android_regex_guard.py`.

## Checked, and fine

- **Regex behaviour:** all 832 patterns were run on both engines over Dutch and multilingual phone text.
  - The phone matches slightly more: no-break spaces, non-Latin digits, and ligatures in case-insensitive matches.
  - This never breaks a task. Hiding secrets becomes stricter on the phone.
- **Dutch locale:**
  - no date formatting or parsing without a fixed locale;
  - no number formatted with a comma that is read back as a number;
  - no no-argument `toLowerCase`.
- **Signatures:** PC trust and share signatures cover plain text, not JSON, so `\/` cannot affect them.

## "App not installed" (Alpha 136 installs, Alpha 140 doesn't)

Nothing in the APK files explains it. Alpha 136 and 140 were compared byte by byte:
- these are the same: signing key, rotation chain, permissions, file layout and SDK levels;
- the version code only goes up.

The cause is most likely on the phone: an incomplete download, a different build already installed, Play Protect or
low storage. To find out, install with `adb install -r Cyclone-5.0.0-alpha.142.dev1.apk` while
`adb logcat -s PackageManager PackageInstaller` runs. The `INSTALL_FAILED_…` code it prints names the cause.

## Limits, stated plainly

- **Physical device: UNVERIFIED.** Run the alpha.142 rows (8.1–8.4) in `docs/LUNA_DEVICE_MATRIX.md`.
- The website fix covers how Cyclone confirms the site. It does not change how it navigates there.
