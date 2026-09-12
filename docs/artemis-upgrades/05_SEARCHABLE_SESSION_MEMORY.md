# Searchable session memory with asynchronous compaction

**Priority: P1. Scope: long-running task context. Implementation size: large. Status: proposal, not implemented.**

## Finding

Artemis Flash and Pro share a transcript ledger that separates static instructions, older compressed history, recent active turns, and current observation. It uses measured prompt tokens when available, replaces older images with descriptions, and makes compressed step detail recallable through history tools. Per-role context policies choose different detail levels.[^1][^2][^3]

The useful distinction is between losing history and compressing it while keeping a retrieval path. Asynchronous compression can reduce work on the action path, but generated summaries remain fallible. The immutable action/evidence records must remain authoritative.

## Cyclone comparison

Cyclone already has local Brain memory and a queryable context ledger. Its current page-decision prompt includes only the last 12 successful actions and eight failed actions. That bounded prompt is sensible for short tasks; it is not a model-facing searchable account of a long session.[^4][^5] Adding another general-purpose memory database would duplicate existing infrastructure.

## Proposed change

Build a session transcript projection over the existing trace/context storage. Keep task instructions, user corrections, active scope, unresolved incidents, and current goal requirements pinned. Keep recent verified actions in detail. Compress completed spans into summaries containing covered step IDs, evidence references, subgoal identity, and unresolved facts.

Add constrained read-only recall operations for the active task: search action summaries by terms/step range and replay sanitized structured evidence. Do not expose another task, user profile, or secret-bearing typed payload. Ordinary recall should retrieve text/effect records; production traces must continue excluding raw screenshots and hidden provider reasoning.

Run summary generation outside the foreground mutation loop, using a bounded queue and a snapshot/version stamp. Apply a summary only if its source range still matches. Cancellation or summarizer failure must not block the next action. A deterministic compact action ledger is the fallback when a model summary is unavailable. Do not silently introduce another paid model; use an explicitly configured budget or local summarization.

Use actual request token counts to tune compaction thresholds when the provider reports them. Otherwise use a conservative estimator. A summary cannot mark a goal satisfied, remove a user constraint, or convert an attempted click into a verified effect.

## Acceptance and rollout

| Scenario | Required result |
|---|---|
| A 100-step synthetic session | Bounded prompt size and recall of an early verified result |
| User corrects a goal before compaction | Correction remains pinned verbatim after secret redaction |
| Summarizer stalls or fails | Actions continue within the normal budget |
| Late summary arrives after cancellation | No state change or resumed execution |
| Summary contradicts an effect ledger | Structured evidence wins |

Compare total prompt tokens, p95 decision latency, early-fact recall, and erroneous completion rates against the current bounded history. Start with deterministic text compaction and read-only recall before optional model summaries. Roll back by disabling the projection; preserve the existing ledger. Depends on proposal 09 for stable evidence IDs and proposal 02 for non-evictable incidents.

## Sources

[^1]: Artemis, [`artemis/memory/transcript.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/memory/transcript.py#L254), `class TranscriptLedger`.

[^2]: Artemis, [`artemis/memory/context_policy.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/memory/context_policy.py#L53), `class ContextPolicy`.

[^3]: Artemis, [`artemis/tools/history/search.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/tools/history/search.py).

[^4]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/PageAgentProtocol.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/PageAgentProtocol.kt#L124), `successfulActions.takeLast(12)`.

[^5]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/observability/context/ContextLedger.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/observability/context/ContextLedger.kt).
