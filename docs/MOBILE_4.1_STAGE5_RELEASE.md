# Cyclone Mobile 4.1 Stage B5 — release lane

**Identity:** mobile `4.1.0` / versionCode `80`; Cyclone One / `pc_companion` stays `1.0.0` on this tree  
**Base:** B4 tip `f1e0239` / PR #71 (Fast Path named VD on Glass + Contract + Sticky + published `v4.0.4`)  
**Physical Pixel 8:** **UNVERIFIED** — this lane packages the operator cut path. Do not treat a green CI build as device evidence.

Stage B5 is the Mobile 4.1.0 packaging handoff. It does **not** rewrite B1–B4, cut Magisk, invent a physical pass, or bump Cyclone One. Product notes live in [`RELEASE_4.1.md`](RELEASE_4.1.md); this file is merge order + operator commands only.

`publication_authorized` remains **false** until a signed artifact exists. Do not claim the GitHub tag `v4.1.0` already exists. This PR does not create the tag.

## Merge order

Do not rewrite stack history. Merge in this order:

1. **#65** Sticky (`grok/mobile-4.1-s1-sticky`) base `release/cyclone-mobile-v4.0.4` / `v4.0.4`
2. **#67** Contract (`grok/mobile-4.1-s2-contract`)
3. **#69** Glass (`grok/mobile-4.1-s3-glass`)
4. **#71** Fast Path named VD (`grok/mobile-4.1-s4-fastpath-bg`)
5. **this B5 PR** (`grok/mobile-4.1-s5-release` base `grok/mobile-4.1-s4-fastpath-bg`)

After the stack lands, cut `v4.1.0` from the merged SHA. This PR does not create the tag.

## Operator cut path

Mobile CI artifact + update-compatible signer continuity vs 4.0.4:

1. After stack merge, create and push `release/cyclone-mobile-v4.1.0` from the merged SHA so Mobile CI **push** run fires (`on.push.branches` includes `release/cyclone-mobile-v*` in [`.github/workflows/mobile-ci.yml`](../.github/workflows/mobile-ci.yml)).
2. Wait for [`.github/workflows/mobile-ci.yml`](../.github/workflows/mobile-ci.yml) success on that **push** run (not a pull_request run). Record the run id and unsigned artifact name (typically `Cyclone-Android-4.1.0` if `versionName` is `4.1.0` — do not invent the name).
3. Dispatch [`.github/workflows/mobile-release.yml`](../.github/workflows/mobile-release.yml) with that run id + artifact name (environment `mobile-release-approval`). Signing reuses the exact green Mobile CI APK. `android_signing` = `LEGACY_UPDATE_COMPATIBLE_DEV_KEY` — same update-compatible dev signer as 4.0.4 so in-place upgrade from versionCode 75→80 works. If the signer does not match a device's 4.0.4 install, documented wipe; do not claim update succeeded.
4. Do **not** dispatch [`.github/workflows/pc-companion-release.yml`](../.github/workflows/pc-companion-release.yml) as part of this mobile 4.1.0 cut. One/PC 1.1.0 is A5, out of scope. Pairing: full Layer 2 MCP needs One ≥ 1.1.0; 4.0.4/4.1.0 phone + One 1.0.0 remains foreground-capable.
5. Dry-run: `python scripts/ci/cut_v41_release.py`
6. Tag only when CI is green and no `v4.1.0` tag already exists:

```bash
gh release create v4.1.0 --title "Cyclone Mobile 4.1.0" --notes-file docs/RELEASE_4.1.md
```

Never force-push. Never replace an existing `v4.1.0`.

The helper refuses leftover `4.1.0-alpha.4` / versionCode `79`. `--execute` is for the operator after CI; this session does not run it.

## Physical Pixel 8

Physical Pixel 8 remains **UNVERIFIED**. Use the checklist in [`RELEASE_4.1.md`](RELEASE_4.1.md). Do not check those boxes from CI.

## What B5 does not do

- Restart or rewrite B1–B4
- Magisk / root / a second mutation engine
- Merge other PRs
- Invent device results or mark Pixel 8 verified
- Claim the GitHub tag `v4.1.0` already exists
- Flip `publication_authorized` without a signed artifact
- Cut One/PC A5 (1.1.0)
