# Cyclone V5 Alpha 110: Instagram account creation starter

This developer alpha ships an Instagram account-creation table from the first launch of Cyclone for Windows. Open **Accounts → Instagram account creation → Open starter table**, add your details, choose whose account it is and one connected phone, then set the row **Ready**. **Create accounts** reviews the rows before starting them. Set up the private vault once in Command Center → Vault; passwords are generated there and delivered sealed to the phone.

The packaged workflow contains twelve observed signup pages and seven field definitions, plus birthday-picker, SMS, optional-onboarding and already-signed-in entry guidance. It is based on one completed Instagram phone-number signup on app version 449.0.0.52.84. It includes no personal account details, sample credentials or queued account rows. The username-first entry route was inspected without creating another account; its later pages and the email alternative remain untested. Instagram can change its flow, and Cyclone adapts to the actual screen.

On upgrades, an existing Instagram signup table is reused without replacing its rows, phone map or guide. Installation is recorded once; restarting or updating does not duplicate the example, overwrite edits or recreate a removed starter. The phone carries the same schema as a fallback for Account Setup; a locally learned map takes precedence. The fallback does not pretend the phone has already learned these screens.

## Input and signup reliability

- Secrets Card retains the intended app, form and field and uniquely identifies that input again after harmless page refreshes. Recovery retries only failures before writing; delayed verification never repeats a secret write. Changed apps, forms, execution scope or ambiguous inputs still require fresh intent. Password-control flags survive sanitization.
- Direct text replacement can work with the Android Cut toolbar visible, while touch-overlap checks still protect taps.
- Account Setup receives recorded field hints and choices. Native SMS retrieval runs before asking the owner to read a code, and signup continues through later verification and onboarding before verifying the new signed-in profile.
- Voice and main overlays yield during host actions and their result settling, including global Back. An empty host observation gets one bounded read while yielding; human control and visible Secrets Cards are excluded.
- Instagram Account Setup knows the signed-in entry route and the username-first variation. It skips optional contacts, photos and follows, declines optional cookies and checks the resulting screen after SMS autofill.

The release also retains the manual, provenance-checked private signed owner-test artifact workflow introduced during this experiment. Public publishing continues through the existing paired Android, Glass and Windows release lane.

Glass also updates its locked build dependency source-map-js to 1.2.2, the patched version for [GHSA-68fv-2mgg-jv7q](https://github.com/advisories/GHSA-68fv-2mgg-jv7q). The dependency audit is clean after the patch.

## Versions and evidence

- Product / Android / gateway / MCP: **5.0.0-alpha.110.dev1**; Android version code **258**.
- Glass: **1.0.0-alpha.61**. The retired desktop-window component remains unchanged.
- Validation for this exact source: recorded in the release PR and linked CI runs; publication waits for the exact merged commit's Mobile CI and Windows package smoke checks.
- Previous signed owner-test build 109.dev4: the connected Pixel 8 completed recovery, declined optional cookies and verified the new signed-in profile. SMS retrieval/fill worked three times across the experiment. The original display indicator was restored after cleanup.
- The exact Secrets Card refresh case has unit coverage and has not been physically reproduced on the updated release. A second account was not created to test the packaged starter. Exact-build physical acceptance remains separate from unit/CI and publisher evidence.

No approvals, account-ownership checks or credential boundaries were removed. The default table is empty and never creates an account automatically.
