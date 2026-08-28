# Teamwork Sniper Accessibility Contract

## Evidence status

Package: `tech.picnic.workapp`  
Launcher: `tech.picnic.workapp/.MainActivity`  
Target Pixel: `3B171FDJH0061G`  
Current live status: **UNVERIFIED — physical ADB is not exposed in the authoring runtime.**

The parser/tooling is intentionally structured so selectors and observations carry one of three evidence levels:

- `LIVE_CONFIRMED` — personally observed from a physical Teamwork hierarchy.
- `PROVISIONAL` — parser rule designed for live validation, not asserted as Teamwork behavior.
- `SYNTHETIC_ONLY` — contract/test corpus only; never evidence of Teamwork UI.

No Teamwork resource ID is frozen into the contract until repeated live captures establish it.

## Machine-readable OpenShift contract

Schema: `docs/teamwork-sniper/accessibility-contract.schema.json`

Fields:

- `date`: ISO date or null when ambiguous.
- `day`: normalized weekday or null.
- `code`: normalized `M<n>`/`S<n>` or null when ambiguous.
- `start`, `end`: normalized HH:MM or null when ambiguous.
- `state`: currently `OPEN_TO_TAKE` for open candidates.
- `semanticRowIdentity`: deterministic diagnostic identity for one semantic row shape.
- `claimCandidatePath`: semantic ancestor path or null.
- `confidence`: `UNAMBIGUOUS` / `AMBIGUOUS`.
- `ambiguity`: explicit fail-closed reason codes.
- `evidenceLevel`: `LIVE_CONFIRMED`, `PROVISIONAL`, or `SYNTHETIC_ONLY`.

## Provisional open-row grammar

A candidate begins at a semantic node whose `text` or `content-desc` contains `Open to take`. The parser climbs ancestors and selects the **smallest** subtree that contains a recognized shift code and an unambiguous start/end pair. Within that scope it resolves:

- `text=` and `content-desc=` values;
- empty/nested wrapper nodes;
- clickable parent or grandparent ancestry;
- nested Compose-style semantics;
- duplicate semantic exposure;
- ranges `08:00–10:35`, `08:00 - 10:35`, or two distinct time tokens `08:00 10:35`;
- ISO, numeric Dutch/European, and Dutch/English month-name dates;
- sticky preceding day/date headings when the row itself omits the date and a unique year anchor is available.

Any multiple interpretation remains `AMBIGUOUS`; the parser does not pick a most-likely answer.

## Binding rule

Date + day + code + time + state must resolve within the smallest qualifying row scope, except that a missing date/day may inherit from the nearest preceding semantic day/date heading only when that heading resolves uniquely. Cross-row and cross-day token binding is forbidden.

## Claim candidate rule

The diagnostic candidate is the nearest enabled ancestor of the open marker with `clickable="true"`. This is **PROVISIONAL**, not a live Teamwork fact. The probe reports the path but performs no claim.

## Success and confirmation

No live success or confirmation grammar exists yet. Synthetic confirmation/success/failure fixtures model contract states only. Production integration must require a live-confirmed post-action semantic state and must not treat click completion as success.

## Full-week aggregation

`probe.py` accepts one or multiple XML dumps. Multi-dump mode:

1. parses each page;
2. normalizes shifts;
3. deduplicates by `(date, code, start, end, state)`;
4. records pages where each shift appeared;
5. reports `newPerPage`;
6. computes semantic fingerprints;
7. reports `stable=true` when the newest page adds no shifts and repeats the immediately previous page fingerprint.

This allows a live scanner to stop only after the semantic traversal itself has also reached a no-progress condition.

## Date grammar

Supported provisional forms include:

- `2026-08-29`
- `29/08/2026`
- `29-08-2026`
- `29 Aug 2026`
- `29 August 2026`
- `29 augustus 2026`
- `August 29, 2026`

Yearless sticky headings such as `Saturday 29 Aug` are accepted only when the current hierarchy contains exactly one unambiguous `20xx` year anchor.

## Notifications

`notification_parser.py` isolates `dumpsys notification` records containing `tech.picnic.workapp` and reports package, channel, title, text, post time and whether a content intent appears present. It does **not** claim where that intent routes.

## Static safety rule

`safety_guard.py` scans executable Teamwork probe paths and fails on forbidden screen-capture/OCR/image mechanisms or obvious hardcoded tap coordinates. Documentation is allowed to describe prohibited mechanisms.

## Current live questions

Only physical-device evidence can establish the actual Teamwork row boundary, actual claim node, actual scroll selector/action, actual confirmation/success states, stable IDs/content descriptions, notification routing destination, and Android/app timing.
