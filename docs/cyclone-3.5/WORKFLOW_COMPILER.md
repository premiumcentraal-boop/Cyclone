# Cyclone 3.5 workflow compiler

Teach authoring is observable; replay becomes deterministic only after evidence passes a quality
gate. The implementation extends the existing `RoutineTeachingSession` and
`TeachingRoutineCompilerV292` formats so stored V292/V293 identifiers remain compatible.

## Evidence captured

Action steps may include app/page identity, semantic selector candidates, before and after
fingerprints, expected result, verifier name, action and verification status, fallback strategy,
timing, and confidence. Screenshots/UI snapshots are evidence and are not treated as an
authoritative success witness by themselves.

## Quality gate

`TeachingWorkflowQuality.evaluate` scores selector stability, page anchoring, verifier strength,
and evidence completeness. A semantic selector plus a changed, verified after fingerprint can be
approved for review. Coordinate-only or incomplete evidence is marked `NEEDS_REPAIR`; no action
evidence is rejected. Selector repair orders resource IDs, content descriptions, visible labels,
roles/classes, and relative semantic selectors ahead of coordinates.

Credentials, OTPs, tokens, PINs, payment numbers, and similar assignment-shaped values reject
compilation. User notes and selected-model analysis are redacted before they are persisted.

## Replay contract

Replay observes fresh state, resolves selectors against that observation, calls the canonical phone
executor, verifies the resulting state, and records confidence. If evidence no longer matches,
the route falls back to the existing Brain/App Graph and AI recovery path; it never silently
promotes a weak compile to learned truth.
