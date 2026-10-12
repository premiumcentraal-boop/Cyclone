# Cyclone V5 Alpha 117 dev2: a VMOS account without phones

A fix on alpha.117 dev1, found on the owner's own VMOS account.

- **Mobile:** `5.0.0-alpha.117.dev2` (version code 271). No app changes; the version moves with the release.
- **PC runtime:** gateway and MCP `5.0.0-alpha.117.dev2`.
- **Glass:** `1.0.0-alpha.64` (unchanged).

## The fix

**What was seen.** Adding a VMOS account that had no phones yet showed "VMOS Cloud: Instance not found" on the
account card. Sign-in had worked: VMOS answers its phone list (`infos`) with an "instance not found" error, instead of
an empty list, when the account has no phones.

**Now:**
- An account without phones reads as empty, with no error.
- Cyclone also reads the account's cloud-phone list (`userPadList`) when `infos` says there is no instance, so phones
  `infos` doesn't list still appear. Their status comes from VMOS's `cvmStatus` (100 normal → running; restarting,
  resetting, upgrading → starting; failed → abnormal).
- Any other VMOS refusal still shows on the card, in VMOS's words.

**First real-account facts (2026-10-09):**
- V2 signing is accepted by VMOS for the owner's keys.
- `infos` on an empty account answers "Instance not found".

## Tests

- `test_an_account_without_phones_is_empty_not_an_error`: no phones gives an empty list; the fallback to `userPadList`
  with its status; any other refusal still raised.
- `test_adding_an_account_without_phones_shows_no_error`.
- The gateway, CI-script and Glass suites pass.

## Limits

- **Still UNVERIFIED** on a real account: renting, power, renew, backup and restore. See
  `docs/handoff/VMOS_LIVE_TEST.md`.
