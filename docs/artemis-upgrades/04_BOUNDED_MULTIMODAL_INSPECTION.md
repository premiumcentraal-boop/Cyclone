# Bounded multimodal inspection for missing controls

**Priority: P1. Scope: read-only perception. Implementation size: medium to large. Status: proposal, not implemented.**

## Finding

Artemis's Explorer is a dedicated locator rather than the executor. Its configured tiers expose different tools and budgets: one-shot detection, a short perception loop, or deeper inspection with OCR, object detection, and image-region processing. The model does not choose an arbitrarily larger tier for itself.[^1][^2] `ScreenIndex` preserves whether a candidate came from the UI tree or OCR, along with bounds and interactivity evidence.[^3]

The useful pattern is to ask a small, specific perception question before replanning the whole task. An OCR string or VLM point is still a candidate, not permission to act.

## Cyclone comparison

Cyclone already has goal-ranked search, element inspection, bounded exploration, and one-shot visual escalation. Its visual click binding requires a uniquely represented current semantic control.[^4][^5] The remaining opportunity is to make visual recovery more targeted when compact controls omit a button, rather than adding raw coordinate control or assuming any screenshot request solves the problem.

## Proposed change

Add a proposed `PerceptionRequest` with a precise target question, active session/display/generation, allowed operations, source frame, and time/token budget. Return `PerceptionCandidate` objects containing provenance, label, bounds, geometry transform, supporting current node IDs, and ambiguity. The perception component has no mutation tools.

Try existing semantic search first. If evidence is sparse, inspect a crop of the relevant region from the same approved frame. Add OCR only when supported and enabled. Preserve the transform from cropped/scaled coordinates back to the original frame; use explicit coordinate-space tags rather than guessing from numeric magnitude. Merge candidates conservatively without upgrading OCR text to a clickable node.

Feed the candidates back through proposal 03's canonical grounding. If a candidate cannot bind to one currently allowed control, return a typed unresolved result. Canvas-only execution is a separate future capability requiring its own contract and review. Do not weaken the present gate to make a demo succeed.

Configuration should select bounded inspection effort independently of the chosen model. Do not silently send screenshots to another model or provider. A text-only selected model produces a specific capability limitation; it must not receive image data.

## Acceptance and rollout

| Case | Required result |
|---|---|
| Reject control omitted from compact list but present in full tree | Search/inspect recovers the current semantic control |
| OCR text lies inside a large noninteractive parent | Candidate stays non-executable without separate grounding |
| Cropped image is scaled or rotated | Explicit transform maps to the correct original frame |
| Candidate overlaps two controls | Ambiguous; zero mutation |
| Wrong-profile image or exhausted budget | Inspection stops with typed reason |

Measure successful control recovery, grounding rejection accuracy, image/token cost, and p95 inspection latency. Start with crop inspection using existing image-capable models; make OCR a separate opt-in adapter. Roll back by disabling the new inspector while preserving semantic recovery. Depends on proposals 03 and 15.

## Sources

[^1]: Artemis, [`artemis/agents/explorer/explorer.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/agents/explorer/explorer.py#L68), `class Explorer`.

[^2]: Artemis, [`artemis/agents/explorer/tiers.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/agents/explorer/tiers.py#L68), `EXPLORER_TIERS`.

[^3]: Artemis, [`artemis/agents/explorer/screen_index.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/agents/explorer/screen_index.py#L74), `class ScreenElement`.

[^4]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/agent/recovery/AgenticRecoveryPolicy.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/agent/recovery/AgenticRecoveryPolicy.kt#L113), `class AgenticVisionPolicy`.

[^5]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/VisualControlGrounding.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/VisualControlGrounding.kt#L9), `fun bind`.
