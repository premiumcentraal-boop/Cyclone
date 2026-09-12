# Snapshot-bound target revalidation

**Priority: P0. Scope: perception-to-execution boundary. Implementation size: medium. Status: proposal, not implemented.**

## Finding

Artemis Pro checks a proposed target against a live hierarchy immediately before an individual action. Matching combines resource identity, text, bounds, and coordinate evidence. It classifies disappearance, occupation, and movement; moderate movement can cause coordinates to be corrected. XML failure can trigger a pixel check.[^1][^2]

This is valuable evidence engineering, not proof that any pixel-approved action is safe. Multi-action bursts bypass this gate in Artemis, and its thresholds are heuristic. Cyclone should not copy that bypass or assume a VLM verdict is equivalent to a current executable control.[^2]

## Cyclone comparison

Cyclone already binds actions to observations and execution scope. `VisualControlGrounding` checks frame age, session/display identity, dimensions, visibility, and an unambiguous current control before converting image coordinates into a semantic click. It intentionally rejects overlapping or unlabeled targets.[^3] The improvement is better diagnosis and bounded re-resolution of target drift, not introducing unrestricted coordinate taps.

## Proposed change

Extend the existing grounding result into a typed report: `MATCHED`, `MOVED_SAME_IDENTITY`, `OCCLUDED`, `DISAPPEARED`, `AMBIGUOUS`, `STALE_FRAME`, or `SCOPE_MISMATCH`. Record which independent signals supported the decision and which failed. Keep the report readable to the recovery policy without including raw sensitive UI values.

At the canonical executor boundary, re-resolve stale target intent against one fresh, same-scope observation. Require a unique enabled and visible semantic control. If identity is sufficiently stable, create a new observation-scoped action rather than editing old coordinates in place. Never preserve the old observation ID after re-resolution. Stop on scope mismatch, conflicting identity, or overlapping controls; route those cases to bounded inspection.

Pixel evidence may suggest which current semantic control to inspect. It must not override a human gate, expand authority to a different display, or authorize an unrepresented canvas target. Any future canvas execution needs its own reviewed contract; it is outside this proposal.

For the cookie case, this gives a useful answer when a banner shifts during a slow model call: re-find the unique reject control, or explain that it disappeared or is covered. Neither an unexplained refusal nor a blind tap on the old rectangle is acceptable.

## Acceptance and rollout

| Fixture | Required result |
|---|---|
| Reject button moves under the keyboard | Re-resolve the same semantic identity with a new observation ID |
| Accept button occupies the old rectangle | Reject the stale action |
| Two same-label controls overlap | Return ambiguity; no click |
| Screenshot belongs to another display or an old generation | Reject before mutation |
| Authentication confirmation appears | Existing gate remains required |

Add paired before/after fixtures to `VisualControlGroundingTest` and executor scope tests. Measure wrong-target rejection, successful safe re-resolution, added pre-dispatch latency, and stale-plan escapes. Enable re-resolution separately from diagnostic classification so the diagnosis can ship first. Proposal 04 supplies better read-only candidates; proposal 02 preserves unresolved failures.

## Sources

[^1]: Artemis, [`artemis/agents/validator/precondition_xml.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/agents/validator/precondition_xml.py#L587), `async def validate_action_precondition_single`.

[^2]: Artemis, [`artemis/agents/validator/execution_loop.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/agents/validator/execution_loop.py#L76), `async def _run_precondition_gate`.

[^3]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/VisualControlGrounding.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/VisualControlGrounding.kt#L9), `fun bind`.
