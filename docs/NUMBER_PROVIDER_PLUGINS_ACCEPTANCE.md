# Number provider plugins — development acceptance

Recorded 2026-10-03. Built on the latest published Cyclone Alpha 102 / Glass Alpha 57 (`079085ce`), rebased through the documentation-only upstream `bef41004`. This is tested source on `codex/number-provider-plugins`; no new release, installed runtime, paid provider account or physical phone mission is claimed.

## Checkpoints

- `33972b02`: provider adapters, durable order/approval service, native signed Ports endpoints, scope policy, SDK checks and initial regression tests.
- `3dd89e30`: real Glass provider settings/quote workflow, Numbers sidebar entry, Android targeted private code waits, stale approval withdrawal and uncertain-response handling.
- Acceptance checkpoint: final provider-side active-rental/expiry checks and this evidence record.

## Verification

| Check | Result |
| --- | --- |
| Broad gateway regression suite | 844 passed, 1 skipped, 2 warnings |
| Final focused provider suite, including live-rental expiry rejection | 31 passed |
| Glass suite and production build | 297 passed; build succeeded |
| Android debug unit tests | 2,362 passed; zero failures/errors/skips |
| Ports SDK | 28 passed |
| Phone MCP | 191 passed |
| Agent MCP | 93 passed |
| CI guard tests | 286 passed |
| Version metadata, Glass and mobile product guards | Passed |
| Native plugin conformance | Both providers passed actual SDK checks over local HTTP against isolated fixtures |
| Source checkout without an installed Ports SDK | 31 provider tests passed using the adjacent SDK fallback |

The final focused suite followed a small read-only ownership/expiry hardening change; the broad gateway result precedes that last check. An earlier broad run hit an existing ordering-sensitive logger assertion in `test_signup_scenario_through_the_gateway_hub`; its focused rerun and the subsequent full run passed. No unrelated logger behavior or test was changed.

Initial GitHub Mobile CI exposed a direct test import before the gateway's source-SDK fallback loaded. The native API and test imports now load the gateway Ports module first. Verified with a Python process whose editable SDK registration was disabled and asserted absent before the gateway import; the full focused suite passed. A first such local run had one transient conformance failure; failure assertions now print only failed checks, and the complete rerun passed. Hosted CI is the additional merge gate; this record does not assert its final status ahead of completion.

Provider tests cover owner approval, decline, expiry, repricing, changed permission, cancelled runs, exact request replay, restart during submission, no second purchase after an uncertain response, authentication/error redaction, signatures and replay refusal, app routing restrictions, correct country-aware number identity, existing account assignment, stale/wrong-sender/ambiguous SMS, one-shot private delivery and no OTP in database dumps. Provider ownership and current rental expiry are checked before inbox access. Fixtures use documented responses; they cannot prove a vendor's production implementation.

Browser acceptance used the actual production Glass bundle and actual gateway/Ports/approval services on an isolated port with fake provider transport. The page was labelled **SIMULATED PROVIDER ACCOUNTS · NO REAL PAYMENT**. Verified both masked connection forms, the visible Numbers navigation item, saving a developer app restriction and retaining it after reload, VMOS country/plan/price review, SMSBot country/template/period/service/price review, requesting review and displaying the exact request in the separate owner Inbox. The fake price examples are not live vendor prices. No browser Approve action or provider purchase was performed.

Credentials in production Windows use the existing DPAPI vault, outside the order/inventory databases. Non-Windows environments are explicitly memory-only. No credentials, inbox text, OTPs, generated bundles, screenshots or local test data are committed. Purchase and price uncertainty never authorize a fresh debit. Unanswered stale approvals are withdrawn automatically; submitted/uncertain orders remain available for read-only reconciliation.

## Open and use in a build containing this change

1. Open Glass → Command Center → **Numbers** → **Number providers**.
2. Enter the owner's VMOS access/secret keys or SMSBot API key. Saving verifies the account catalogue and runs the native plugin's conformance checks without buying. Keys remain blank when reloading.
3. Enable agent use deliberately and optionally limit Android app packages and routine IDs. Ports pause, consent and routing still apply.
4. Load the live catalogue. Choose provider, country, plan/template, period and optional account assignment. Read all included services, exact total and rental terms.
5. Request owner review, then inspect the exact price in the Inbox. A purchase is only permitted after that specific approval, with a current price recheck and automatic renewal off.
6. For an owner-authorized mission, use the approved plugin's advertised skill. Prepare the code window before requesting the SMS; fill the fresh matching code through the existing sealed-to-phone path. Final account creation and any terms remain subject to the phone's normal owner approval.

If a purchase is uncertain, keep the same request ID. VMOS reconciles the original purchase token. For SMSBot, identify the actual rental in the provider dashboard and match its ID in Glass; this action is read-only. Do not buy another number as a retry.

## Required live acceptance before calling this production-verified

Owner account authentication against each real vendor, one exact-price approved rental per vendor, expiry/number assignment, interruption after the debit, a fresh verification SMS delivered to a paired physical phone, and cancel/revoke behavior must be witnessed separately. Production stock, delivery time, website acceptance, sender naming and long-term account recovery remain vendor/site dependent. Temporary numbers are not a replacement for an independent recovery method.

No balance top-up, bulk purchase, automatic renewal, refund, release, VMOS device restart/bind or global SMSBot webhook changes are included. Plan 50 GitHub packaging needs the future Plugin Host and a separate packaging acceptance; no such package is asserted here.
