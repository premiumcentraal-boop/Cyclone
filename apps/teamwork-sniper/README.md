# Teamwork Sniper 3.5.3.2

Standalone phone-side companion for `tech.picnic.workapp`.

- package: `com.cyclone.teamworksniper`
- versionName: `3.5.3.2`
- versionCode: `5`
- PC connection: not required
- AI model: optional
- primary input: Teamwork notifications + Android accessibility hierarchy
- primary actions: semantic `AccessibilityNodeInfo.ACTION_CLICK` and semantic scroll actions only

## Teamwork calendar overlay

On recognized Teamwork calendar pages, days marked `No shift` receive a transparent stack of full-width sniper rows. There is no card, header, note, calendar icon, or plus button. Each row uses a sniper sight icon and the same date/code model as the deterministic claim rules. Orange hollow rows are available choices, orange filled rows are selected, and green rows are verified claims. Assigned Teamwork rows are never covered or offered as sniper choices.

## Deterministic-first runtime

Teamwork Sniper is designed to work with no PC and no model API.

A Teamwork notification timestamps the trigger and opens Teamwork using the notification PendingIntent when available, otherwise the package launch intent. The accessibility service then:

1. observes the current semantic hierarchy;
2. recognizes the shift surface or navigates through a locally learned semantic UI-map hint;
3. scans `Open to take` rows;
4. scrolls using accessibility actions and deduplicates semantic state;
5. normalizes shifts to date/code/time;
6. compares only against persisted user rules;
7. requires both Enabled and Armed before any action;
8. re-observes and resolves one fresh semantic target;
9. sends `ACTION_CLICK`;
10. verifies the target is no longer open before continuing.

The local UI map stores only a successful semantic resource ID/label hint. It never stores screen coordinates and never turns an old observation path into permanent truth.

## Optional OpenRouter advisor

OpenRouter is an optional prioritization layer, not an action authority.

- disabled by default;
- configurable model, default `openrouter/auto`;
- API key encrypted with Android Keystore;
- skipped when one safe candidate is already enough;
- receives only candidates that already passed deterministic rules and semantic open-state checks;
- may reorder those existing candidates or return no preference;
- invented/unknown candidate IDs are rejected;
- timeout/API/model failures fall back immediately to deterministic ordering;
- AI cannot arm the sniper, create a shift, expand the user's rules, resolve an ambiguous UI node, or click anything directly.

## Rule JSON schema

```json
{
  "schemaVersion": 1,
  "rules": [
    {
      "id": "uuid",
      "name": "S1 → S2 → S3",
      "type": "EXACT | SEQUENCE | COMBINATION",
      "enabled": true,
      "codes": ["S1", "S2", "S3"],
      "weekOffsets": [0, 1],
      "dates": ["2026-08-31"],
      "days": ["MONDAY"]
    }
  ]
}
```

`EXACT` requires one code. `COMBINATION` means any selected code is independently desired. `SEQUENCE` requires every consecutive selected code on the same date.

## Build gates

From the repository root:

```bash
python scripts/ci/teamwork_sniper_metadata.py --require-app
python scripts/ci/teamwork_sniper_guard.py --require-app
./apps/mobile/gradlew -p apps/teamwork-sniper :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

Physical Teamwork acceptance is a separate gate. Synthetic parser tests and CI compilation must not be represented as proof of a live claim.
