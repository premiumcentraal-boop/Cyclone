# Cyclone 3.8.2 Pixel overlay acceptance (REDACTED template)

Fill this on the physical Pixel after Agent C typing and this overlay test PR have landed. Do not paste secrets, SMS bodies, account emails, payment details, pairing tokens, device serials, or keystore material. Replace any accidental private text with `[REDACTED]`.

**Build:** Cyclone 3.8.2 candidate (do not version-bump in this PR)  
**Device:** Pixel 8 (or `[REDACTED]` model)  
**Android:** `[REDACTED]`  
**APK SHA-256:** `[REDACTED]`  
**Source SHA:** `_fill after merge_`  
**Operator:** `[REDACTED]`  
**Date (Europe/Amsterdam):** `_YYYY-MM-DD_`  
**physical_pixel8:** UNVERIFIED until every required row is PASS or an honest skip

Allowed status values: `PASS` | `FAIL` | `SKIP` | `UNVERIFIED`

## Privacy redaction rules

1. Never commit screenshots of personal threads, contacts, photos, or payment cards.
2. Never record real phone numbers, OTPs, emails, addresses, IBAN, or card last-4. Use `[REDACTED]`.
3. Messages fixture uses canned text `Cyclone overlay fixture` only. Do not send.
4. Phone keypad uses `5550100` only. Do not place a call.
5. Chrome uses a public support query. Stop on search results. Do not tap ads, sign-in, or checkout.
6. Cart/pay: only a charge-free confirmation UI. If that is not possible without a transaction, mark **T6 UNVERIFIED**.
7. Do not pay, send, call, delete, or grant. GATE must stay on the phone; PC auto-approve must not dismiss it.
8. Do not paste Accessibility dumps that include personal node text. Quote overlay chrome strings only.

## Safe fixtures

| Fixture | Package (public) | Task to type (after Agent C) | Hard stop |
|---|---|---|---|
| Messages | `com.google.android.apps.messaging` | Open Messages compose and type overlay fixture text. Do not send. | Do not tap Send. |
| Phone keypad | `com.google.android.dialer` | Open Phone keypad and type 5550100. Do not place a call. | Do not tap Call. |
| Chrome search results | `com.android.chrome` | Open Chrome and search `Pixel 8 user guide site:support.google.com`. Stop on results. | Do not tap ads or checkout. |
| Cart / pay confirmation | merchant sandbox only | Reach a test cart or pay confirmation only if no charge and no order is placed. | If unsafe, leave T6 UNVERIFIED. |

## Overlay copy checklist (exact)

Confirm the chrome shows these strings and no improvised alternatives:

- [ ] Analysis
- [ ] Task automation
- [ ] I'm on it. I'll let you know when this is ready to complete. You can leave this screen.
- [ ] Working on this task
- [ ] View progress
- [ ] Do this
- [ ] Order this from *(commerce CTA only)*
- [ ] Stop task
- [ ] Take control
- [ ] Ask Cyclone
- [ ] Cyclone needs you to confirm before finishing this.
- [ ] Saved as a draft skill. Review it in Automations before it can run alone.

Stop task / Take control must pause Cyclone only. They must not click the host app.

## T0–T6

| ID | Check | Fixture | Overlay states | Status | Evidence (redacted) |
|---|---|---|---|---|---|
| T0 | Pairing + overlay idle chip | Cyclone Home / Accessibility overlay | IDLE shows **Ask Cyclone** | UNVERIFIED | |
| T1 | Task entry (depends on Agent C typing) | Type a fixture goal into Phone task | IDLE → ANALYSIS. Title **Analysis**. CTA **Do this** | UNVERIFIED | |
| T2 | Working chrome | Confirm **Do this** on Messages or Chrome fixture | ANALYSIS → WORKING. **Task automation**, working body, **Working on this task**, **View progress**, **Stop task**, **Take control** | UNVERIFIED | |
| T3 | Live + interrupt | **View progress**, then **Stop task** or **Take control** | WORKING → LIVE → IDLE. Cyclone pauses. Host app is not tapped by the chrome buttons | UNVERIFIED | |
| T4 | Done (non-gated) | Complete a non-pay/send/delete/grant path | LIVE or WORKING → DONE. **Saved as a draft skill. Review it in Automations before it can run alone.** | UNVERIFIED | |
| T5 | Gate (pay/send/delete/grant) | Drive toward send/pay/delete/grant but do not confirm the host action | WORKING or LIVE → GATE. **Cyclone needs you to confirm before finishing this.** PC auto-approve ignored | UNVERIFIED | |
| T6 | Cart / pay confirmation without a transaction | Sandbox cart only | If a charge-free confirmation UI is reachable, record GATE copy. Otherwise **UNVERIFIED** | UNVERIFIED | No charge-free cart/pay confirmation is assumed. Leave T6 UNVERIFIED rather than completing a transaction. |

T6 default for this template: **UNVERIFIED**.

## Result

- Unit overlay tests: see `docs/agent-system/V382_OVERLAY_COPY_TESTS.md`
- Physical Pixel: `_PASS / FAIL / UNVERIFIED_`
- Skipped gates: T6 unless a sandbox cart is used without a charge
