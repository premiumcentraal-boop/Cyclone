# Teamwork Sniper 3.5.2-beta

Standalone companion APK for `tech.picnic.workapp`.

- package: `com.cyclone.teamworksniper`
- versionName: `3.5.2-beta`
- versionCode: `1`
- input: Teamwork notifications + Android accessibility hierarchy only
- actions: semantic `AccessibilityNodeInfo.ACTION_CLICK` only

## Runtime contract

The notification listener filters strictly to Teamwork, timestamps the trigger, prefers the notification `PendingIntent`, and falls back to Teamwork's package launch intent. The accessibility service performs a bounded fast settle, runs an immediate semantic comparison, scrolls using accessibility scroll actions, fingerprints semantic state to stop loops, and evaluates persisted rules.

`OpenShift` never retains a live node. Parser output keeps an observation-scoped semantic path separately. Every action gets a fresh tree, confirms the target is still `Open to take`, confirms the rule is unchanged and enabled, confirms global enabled + armed state, resolves one target, climbs to a clickable semantic ancestor and sends `ACTION_CLICK`. Any ambiguity fails closed. Sequence rules preflight all members on the same date before the first action and stop on partial change.

No Teamwork resource ID is hard-coded. Text/content-description/class/resource-id/action metadata may all be observed, but only supported code/date/open-state semantics are assumed by the generic parser.

## Rule JSON schema

```json
{"schemaVersion":1,"rules":[{"id":"uuid","name":"S1 → S2 → S3","type":"EXACT | SEQUENCE | COMBINATION","enabled":true,"codes":["S1","S2","S3"],"weekOffsets":[0,1],"dates":["2026-08-31"],"days":["MONDAY"]}]}
```

`EXACT` requires one code. `COMBINATION` means any selected code is independently desired. `SEQUENCE` requires every consecutive selected code on the same date and in observed start-time/code order. Empty `dates`/`days` means unrestricted. Week offsets are relative to the current Monday-based week.

## Gates

From this directory with Android SDK + Gradle available:

```bash
gradle testDebugUnitTest verifySemanticOnly assembleDebug
python scripts/verify_semantic_only.py
```

The Gradle static guard is attached to `preBuild` and `check`.
