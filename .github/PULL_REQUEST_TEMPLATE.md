# Cyclone change / agent handoff

## Mission

<!-- What user/product problem does this solve? -->

## Exact scope

- Base SHA:
- Head SHA:
- Owner lane:
- Owned paths:
- Explicitly untouched paths:

## Behavior changed

<!-- Describe observable behavior, not only files. -->

## Contracts changed

<!-- JSON/protocol/action/schema/version/auth changes. Write “none” if none. -->

## Product invariants

- [ ] One `com.cyclone.mobile` app / one `.MainActivity` launcher preserved (if mobile touched)
- [ ] Home / Teach / AI / Automations / Brain / Settings preserved (if UI touched)
- [ ] Gateway remains inside Cyclone AI rather than a second app surface
- [ ] User-visible version references canonical release source
- [ ] No second phone executor/control engine introduced
- [ ] No unrestricted model shell/root capability introduced
- [ ] Sensitive typed values remain redacted
- [ ] Consequential/authentication policy boundaries preserved

## Verification

- Change class:
- Focused first gate:
- Full gate required for this blast radius:
- Unchanged/skipped lanes and reason:
- Existing exact-SHA CI/artifact reused:
- Unit/contract commands:
- Result:
- CI run/status:
- APK/artifact SHA (if built):
- Physical Android test performed: yes/no
- Physical result / blocker:

## APK impact and evidence

- [ ] No APK required (explain why), or Android CI required
- [ ] `versionCode` incremented if this APK will be distributed
- [ ] `versionName` changed only for a new named product release/channel
- [ ] `python scripts/ci/mobile_metadata.py` passed
- [ ] Checked-in Gradle wrapper used; no version-specific workflow added
- Authoritative `Cyclone Mobile CI` run ID:
- Artifact name:
- Source SHA in artifact:
- APK SHA-256:
- Signing mode (expected beta/debug unless separately proven):
- Publication status (production disabled unless proven; combined beta may publish from approved release workflow):

## Time/token efficiency

- [ ] Read scope was limited to root rules, fast-work playbook, generated context and owner lane
- [ ] Additional agents were used only for independent, non-overlapping lanes
- [ ] Full suites ran once on the final candidate rather than after every edit
- [ ] No unchanged SHA was rebuilt or manually downloaded/re-uploaded
- Baseline/candidate run timing (required for workflow optimization changes):

## Learning/autonomy impact

<!-- Does this improve perception, route reuse, confidence, self-healing, automation or AI fallback behavior? -->

## Known limitations

<!-- Be explicit. -->

## Integration notes

<!-- Order/dependencies/migrations for the coordinator. -->
