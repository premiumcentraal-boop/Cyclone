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

- Unit/contract commands:
- Result:
- CI run/status:
- APK/artifact SHA (if built):
- Physical Android test performed: yes/no
- Physical result / blocker:

## Learning/autonomy impact

<!-- Does this improve perception, route reuse, confidence, self-healing, automation or AI fallback behavior? -->

## Known limitations

<!-- Be explicit. -->

## Integration notes

<!-- Order/dependencies/migrations for the coordinator. -->
