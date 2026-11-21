# Cyclone Alpha 154: on a calculator, Enter means equals

Developer alpha for owner testing. It installs over Alpha 153 as a normal update. The change is small and phone-only.

## Versions

- **Mobile:** `5.0.0-alpha.154.dev1` (version code 308).
- **Cyclone for Windows, gateway and MCP:** unchanged from `5.0.0-alpha.151.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.68`.

## Why this release

In the night's one-hour batch (`exp-20261021-234918-1ade`, calculator and timer missions), the approval gate declined a
press of Enter on the calculator seven times. The model tapped the equals key 33 times, and when the result didn't change
it fell back to Enter, which the gate treats as a send. The calculator missions gave up in nine of their runs.

## What changed

- On a calculator screen, a press of Enter in a field now taps the equals key instead, when the screen has one.
  Enter in any other app behaves exactly as before.
- Equals is recognised by its common labels: `=`, `equals`, `equal`, `equal sign`.
- Code: `mind/CalculatorKeys.kt` (the pure check) and `mind/PhoneMindToolbox.kt` (the routing).

## What did not change

- The approval gate, its rules, and the list of what counts as a send or a delete.
- Enter outside a calculator, and every other key.
- The verdicts, judges, scoring and missions of the Lab.

## Validation

- Local unit tests: `CalculatorKeysTest` 4 of 4 pass; the `mind.one` package builds and its tests run.
- Release metadata: `scripts/ci/release_versions.py --check` coherent at 154 (308); `mobile_product_guard.py` passes.
- Mobile CI on the pull request runs the full unit suite on Linux.

## Lab gate table

| Measure | Alpha 153 (before) | Alpha 154 (after) |
|---|---|---|
| Calculator missions, passing | 1 of 8 (one-hour batch, all calculator and timer runs) | NOT MEASURED |
| Enter declined by the gate, calculator | 7 in that batch | NOT MEASURED |

## What is UNVERIFIED on a real phone

- Everything here is UNVERIFIED on a real phone until the next calculator batch runs on this build.
