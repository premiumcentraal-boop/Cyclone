# Handoff: test Cyclone's VMOS support on the owner's real account, and fix what breaks

For a Claude Code agent running **locally on the owner's Windows PC**, where the owner's VMOS account and balance are.
Written 2026-10-09 on top of release **5.0.0-alpha.117.dev1**.

## Paste this to start the local agent

> Read `docs/handoff/VMOS_LIVE_TEST.md` in the Cyclone repo and do what it says: test Cyclone's VMOS support against
> my real VMOS account, stage by stage, and debug and fix whatever fails. Ask me before every paid or destructive
> step, with the exact price or what gets replaced. Never read, print or commit my VMOS keys.

## 1. What this is about

The owner wants VMOS cloud phones in the Cyclone fleet:
- easy to add;
- arriving with Cyclone and our skills;
- staying connected over ADB;
- rentable from Cyclone by the period (a week, a month) or pay-for-time;
- backed up when the owner asks.

Alpha.117 built all of that from VMOS's official OpenAPI docs, but **nothing has been called on a real VMOS account
yet**. Your job:
1. Run the staged test.
2. Find exactly what differs from the docs.
3. Fix it with tests.
4. Leave a clean branch the next release can be built on.

**Read first, in this order:**
1. `AGENTS.md`: the repo rules (ownership, validation, versioning).
2. `Cyclone V5 plan/56-vmos-fleet.md`: §0 rules, §2 the VMOS API as documented, §2.5 what is unverified.
3. `docs/RELEASE_5.0.0-alpha.117.dev1.md`: what was built.
4. The code you will test:

| File | What it holds |
| --- | --- |
| `apps/device-gateway/cyclone_device_gateway/cloud_fleet/providers/vmos.py` | The VMOS client: signing, every endpoint, parsing |
| `apps/device-gateway/cyclone_device_gateway/cloud_fleet/service.py` | The keeper loop; rent, power, renew and backup jobs |
| `apps/device-gateway/cyclone_device_gateway/cloud_fleet/api.py` | Glass's `/v1/cloud` routes |
| `apps/device-gateway/cyclone_device_gateway/cloud_fleet/tunnel.py` | The SSH tunnel for VMOS ADB |
| `apps/glass/src/pages/cloudPhonesView.ts`, `apps/glass/src/services/cloud.ts` | The Glass page |
| `scripts/pc/cloud_probe.py` | **The test harness you will run** |
| `apps/device-gateway/tests/test_cloud_fleet.py`, `test_cloud_rent.py` | The tests to extend |
| `scripts/ci/tests/test_cloud_fleet_guard.py` | The CI guard: allowed endpoints and the safety rules |

**VMOS's own docs** are the reference:
- `https://cloud.vmoscloud.com/vmoscloud/doc/en/server/OpenAPI.html`
- `…/server/example.html` (V2 signing)
- `…/server/ErrorCode.html`

## 2. Rules that do not bend

1. **Keys.**
   - The owner puts the VMOS keys in `%USERPROFILE%\.cyclone\vmos-keys.json`
     (`{"accessKey": "...", "secretKey": "..."}`) or in the environment variables `CYCLONE_VMOS_AK` /
     `CYCLONE_VMOS_SK`.
   - **You never open, print, echo, copy or commit that file or those values**, and never pass a key on a command
     line.
   - If a key is ever shown anywhere, stop and tell the owner to rotate it in the VMOS console.
2. **Money.**
   - The paid stages (`timing`, `rental`) first print what they would buy and the exact price, then stop.
   - Show the owner that line and ask: "Pay $X for this?" Run with `--confirm-cents N` only after the owner says yes
     to that exact amount in this chat.
   - One phone, one paid stage at a time. Never auto-renew.
   - **Never retry a paid order on your own.** If an order fails or looks wrong, stop and ask.
3. **Destruction.**
   - `restore` replaces everything on a phone. Only run it on the phone the owner names as the test phone, and only
     after they say yes.
   - Nothing deletes a phone: deleting is done by the owner in the VMOS console.
4. **Cyclone's rules hold** (`AGENTS.md`, plan 56 §0):
   - VMOS is only the way in. No VMOS-native taps or typing, and no shell for the model.
   - No identity spoofing (SIM, device properties, "new device", GPS, proxies), no app hiding, no cloud numbers or
     social accounts.
   - Do not add endpoints outside plan 56 §2.3 without asking. If you add one, add it to the CI guard's allowlist with
     the reason.
5. **No release.**
   - Do not change `release/version.toml`: a push that changes it publishes a release.
   - Work on your own branch (§3). The owner decides when alpha.118 is cut.
6. **Commits.** Never put a model name or identifier in a commit message or file.

## 3. Setup (once)

```powershell
git clone https://github.com/premiumcentraal-boop/cyclone.git
cd cyclone
git fetch origin claude/cyclone-v5-handoff-review-9qrs40
git switch -c claude/vmos-live-test origin/claude/cyclone-v5-handoff-review-9qrs40
python -m venv .venv
.venv\Scripts\activate
python -m pip install -e "apps/device-gateway[test]" -e tools/codex-phone-mcp
python -m pytest apps/device-gateway/tests/test_cloud_fleet.py apps/device-gateway/tests/test_cloud_rent.py -q
```

The tests must pass before you touch the real account. Also check:
- `ssh -V` works. Windows' OpenSSH Client is needed for VMOS's ADB tunnel.
- `adb version` works, if you test the full Glass link.

Ask the owner:
- Is the keys file ready?
- Which phone is the **test phone** (its padCode, like `AC32010230001`)?
- May it be backed up and restored?

Write every output under `cloud-probe/` (git-ignored by you: **do not commit it raw**, see §6).

## 4. The stages

Run them in order. After each one:
1. Read `cloud-probe/summary-<stage>.md`, and `cloud-probe/vmos-<stage>.json` for the exact answers.
2. Tell the owner in two or three plain sentences what passed and what didn't.
3. Fix failures (§5) before going on.

| # | Command | Costs | Passes when |
| --- | --- | --- | --- |
| 1 | `python scripts/pc/cloud_probe.py --stage read --pad <test padCode>` | Free | Signed in; phones listed with sensible states; names and paid-until dates from `userPadList`; offers for Android 13/14/15; Cyclone's install state on the test phone |
| 2 | `python scripts/pc/cloud_probe.py --stage adb --pad <test padCode>` | Free | An SSH link with a 7-day expiry. Then do the **Glass check** below. |
| 3 | `python scripts/pc/cloud_probe.py --stage backup --pad <test padCode>` | Cloud storage | A `backupId` comes back |
| 4 | `python scripts/pc/cloud_probe.py --stage restore --pad <test padCode> --backup-id <id> --replace-everything-on-the-phone` | **Replaces the phone's data**. Owner's yes first. | The phone runs again |
| 5 | `python scripts/pc/cloud_probe.py --stage timing` (prints the price), then with `--confirm-cents N` after the owner's yes | **Paid** | Ordered; whether it starts powered on is recorded; power off, then on, works |
| 6 | `python scripts/pc/cloud_probe.py --stage rental` (cheapest period; `--sku` to pick), then with `--confirm-cents N` after the owner's yes | **Paid** | The order is accepted and the new phone appears. The owner checks the console's order list for the same plan and period. |

**Stage 1: what to compare with the owner.** Have the owner compare the printed prices with the VMOS console.
Cyclone reads them as US cents (500 = $5.00). If they are not cents, fix this before any paid stage:
- `money()` in `service.py` and `cloud.ts`;
- the probe's `money()`;
- the Glass texts.

**Glass check (after stage 2).** This proves the real product path, not just the client:
1. Install the alpha.117 PC package (`Cyclone-PC-5.0.0-alpha.117.dev1.zip` from the GitHub release) and open Glass.
2. Glass → Devices → Cloud phones → add the VMOS account → **Keep connected** on the test phone.
3. Watch it go Opening ADB → Starting the secure tunnel → Connected. Cyclone should install on the phone if it was
   missing.
4. Open **Rent phones** and look, but press **Cancel**.

If it stalls, the status line says why. Debug it with `tunnel.py` (`explain()` maps SSH failures) and the keeper in
`service.py` (`_keep`).

## 5. Debugging: where each failure lives

| Symptom | Likely cause | Where to fix |
| --- | --- | --- |
| 2019 "signature" on every call | V2 bytes differ from what VMOS signs. Check: the exact body string (`json.dumps(..., separators=(",", ":"))`), the timestamp in **seconds**, and the path with the `/vcpcloud/api/padApi/` prefix. Try `--signing hmac` to see if the account still uses the old scheme. | `sign_v2`, `v2_headers`, `_signed_call` in `vmos.py` |
| 2019 only on GET (`getCloudGoodList`) | The signed query differs from the one sent | `_signed_call` GET branch |
| 2033 | The PC clock is off. Owner: Windows → set time automatically. | — |
| 1116 | The PC's IP is not on VMOS's API allow list. Owner fixes it in the console. | — |
| 2031 | Wrong access key | Owner |
| Phones missing or with wrong states | The `infos` shape differs, or a `padStatus` code isn't mapped | `_rows`, `_phone`, `RUNNING` in `vmos.py` |
| ADB answer without `command` or `key` even after `openOnlineAdb` | ADB is not enabled for the account. The owner asks VMOS support (`start@vmoscloud.com`). | — |
| Tunnel dies, "Permission denied" | The SSH password (`key`) or the user/host parse | `parse_ssh_command`, `tunnel.py` |
| Offers empty, or `priceCents` none | The `getCloudGoodList` shape differs | `offers`, `_sku` |
| Rental refused (e.g. 1001, 401) | `goodId` is the **one documented guess**: Cyclone sends the period's sku id (`goodTimes[].id`). It might want the product group (`goodId` at the top) plus something else. **Do not retry the paid order.** Read the answer, check the docs, propose the fix to the owner. | `rent`, `renew` |
| Pay-for-time phone listed with an unknown status while off | Its `padStatus` while powered off isn't mapped | `RUNNING`, and the keeper's pay-for-time branch in `_keep` |
| Backup refused | 40016 is storage; 1018 means one is already running; 220029 means the phone isn't running | `_advance_backup` messages |

**The fix loop:**
1. **Write a failing test first.** Use the real answer from `cloud-probe/vmos-<stage>.json` as the fixture, in
   `test_cloud_fleet.py` or `test_cloud_rent.py` (their `FakeHttp` routes take that JSON as is).
2. **Fix the code.**
3. **Run:**
   ```powershell
   python -m pytest apps/device-gateway/tests -q
   python -m pytest scripts/ci/tests -q
   cd apps/glass; npm ci; npm test; cd ../..   # only if you changed Glass
   ```
4. **Re-run the stage** that failed. Paid stages count: ask again before paying again.

## 6. Keeping the evidence (privacy first)

- **Raw output stays out of git.** The probe already removes the keys and VMOS's SSH password (`key`), and never
  records headers.
- **Before keeping any answer as a fixture,** search it for the key values (by script, without printing them) and for
  anything personal. Then copy the trimmed answers into `apps/device-gateway/tests/fixtures/vmos_live/<stage>.json`.
- **Write `docs/vmos-live-test/RESULTS.md`:**
  - one row per stage (date, PASS/FAIL, what VMOS answered, the fix and its commit);
  - the answers to the open questions in plan 56 §2.5 and the release notes' Limits (prices in cents?
    `goodId`? does a pay-for-time phone start on? its status while off? does `setKeepAliveApp` accept the
    accessibility service?).
- **Update plan 56 §2.3:** mark each connector "Confirmed on the owner's account (date)" or what changed.

## 7. Finish

1. All suites pass (§5 step 3). Commit on `claude/vmos-live-test` with clear messages, and push:
   `git push -u origin claude/vmos-live-test`.
2. Tell the owner, in plain words:
   - what works on their account now;
   - what you fixed;
   - what still needs them (for example, VMOS support enabling ADB).
3. **The next release (alpha.118)** is built from this branch when the owner asks. Bump these together:
   - `release/version.toml` (product 5.0.0-alpha.118.dev1, `android_version_code` 271, gateway and MCP);
   - `apps/mobile/app/build.gradle.kts`;
   - the three `pyproject.toml` files;
   - `apps/glass/package.json` and `package-lock.json` if Glass changed;
   - a new `docs/RELEASE_5.0.0-alpha.118.dev1.md`.

   Then run `python scripts/ci/release_versions.py --check`. Pushing the bump to
   `claude/cyclone-v5-handoff-review-9qrs40` publishes, after Mobile CI passes on the same commit.

   Plan 56's next run (V1, "Ready in one click": provisioning without a tap) builds on the facts you recorded.
