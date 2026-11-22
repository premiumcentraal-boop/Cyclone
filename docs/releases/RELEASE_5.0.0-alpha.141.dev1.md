# Cyclone Alpha 141: missions run again

Developer alpha for owner testing. It is built on Alpha 140 and installs over it as a normal update.

## Versions

- **Mobile:** `5.0.0-alpha.141.dev1` (version code 295).
- **Cyclone for Windows, gateway and MCP:** unchanged, `5.0.0-alpha.138.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.68`.

## What was broken

Since Alpha 138 (the OneMind switch-over), every Flash and OneMind mission failed at once:

> Cyclone could not run this mission: com.cyclone.mobile.mind.one.OneMindPrompt

Instant still worked because it doesn't use OneMind.

**Cause:**
- OneMind's instructions (`OneMindPrompt`) list the crew's direct tools from `ToolSteps`.
- `ToolSteps` builds the regex `\{([a-z][a-z0-9_]{0,40})}` for `{fact}` placeholders when it first loads.
- The desktop Java regex, which every unit test uses, accepts the lone closing `}`.
- Android's regex is ICU, which rejects it (`U_REGEX_RULE_SYNTAX`).
- So on the phone `ToolSteps` failed to load, `OneMindPrompt` failed with it, and each mission reported that class
  name as its error.

## What changes

- `ToolSteps` escapes the brace (`\}`), so missions run again.
- Two older patterns had the same flaw and are fixed too:
  - the app dictionary's `{0}`-style placeholder filter (`AppLexicon`);
  - legacy automation capsules' `${input}` references (`LegacyAutomationCapsuleAdapter`).
- **New guard** (`scripts/ci/tests/test_android_regex_guard.py`, run by Mobile CI): every regex literal in the app
  must be one Android can compile. Outside a character class, `{` and `}` only appear as `{n}`, `{n,}` or `{n,m}`;
  a literal brace is escaped.
  - It was checked against ICU itself on all 832 literals.
  - It flags exactly the three patterns above on Alpha 140, and none after the fix.

## What did not change

Everything else: the overlay, modes, routing, plans and approvals.

## Validation

- **Android:** `:app:testDebugUnitTest` passed.
- **Guards:** `scripts/ci/tests` passed, including the new regex guard.
- **Release metadata:** `release_versions.py --check` is coherent.

## What is UNVERIFIED on a real phone

- A Flash and a OneMind mission running end to end.
- The "App not installed" update problem is separate. This release does not change the signer, package or SDK.
