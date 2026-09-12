# A shared provider request lifecycle and circuit breaker

**Priority: P0. Scope: model transport reliability. Implementation size: medium. Status: proposal, not implemented.**

## Finding

Artemis separates permanent failures from transient failures, assigns retry policies by category, and uses a circuit breaker keyed by endpoint identity. A half-open breaker permits one probe request rather than allowing every caller to retry at once.[^1] Its shared LLM service applies recovery and exposes provider waits.[^2]

Some Artemis behavior should not transfer: its fallback-model handover and long pause/resume waits can change cost or prolong a stalled foreground task. Cyclone needs shorter task-aware budgets and must preserve the selected model and account policy.

## Cyclone comparison

Cyclone 4.3.7 already fixes the static catalog/picker path, removes public-endpoint pinning, and classifies access, guardrail, authentication, credit, and embedded HTTP 200 errors. Those changes are the baseline, not an unfinished proposal.[^3][^6][^7]

However, chat, the adaptive agent, and qualification still construct separate HTTP clients and request lifecycles with different timeouts. The typed failure result is shared; cancellation, pacing, and aggregate endpoint health are not one shared transport service.[^4][^5] This proposal completes that separation without reopening the model-selection feature.

## Proposed change

Introduce a proposed `ProviderRequestContext`: request ID, task ID, account fingerprint, exact model ID, request purpose, absolute deadline, cancellation handle, and retry budget. Do not store the API key in this record. Centralize execution behind the existing portable body and sanitized failure contracts.

Retry only classified transient failures, within the remaining task deadline, respecting bounded Retry-After guidance when present. Treat 401, 403, insufficient credit, unsupported parameters, and cancellation as nonretryable unless a separate explicit user/configuration change makes a new request meaningful. A timeout after possible completion must not cause a device action to be replayed.

Key the circuit breaker by account fingerprint, model, and endpoint/purpose as appropriate. Invalidate account-specific state on key replacement. Allow one half-open probe, expose cooldown progress, and never silently switch models. Cancel the individual request when its task ends; avoid canceling unrelated chat or other workspace requests through a shared global dispatcher.

Add typed phase events so a stalled request is reported as connecting, awaiting provider, retrying with a deadline, or stopped. Optional background refinement must not consume the foreground task's reserved request capacity.

## Acceptance and rollout

Test 403 and 401 with exactly one attempt, bounded 429/503 recovery, cancellation during backoff, concurrent failures causing one half-open probe, key replacement clearing account state, and a late response after Stop producing zero actions. Confirm the chosen model ID and privacy fields remain unchanged across all attempts.

Ship a shared request-context adapter first, then retry/breaker policy behind a flag. Measure provider calls per verified task, p95 blocked time, duplicate attempts, and canceled-request survival. Roll back the pacing layer while retaining 4.3.7's catalog and error fixes. Define the common deadline/phase contract with proposal 01 first; transport consolidation can then ship independently of new interruption handlers.

## Sources

[^1]: Artemis, [`artemis/llm/reliability.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/llm/reliability.py#L197), `class CircuitBreaker`.

[^2]: Artemis, [`artemis/services/llm.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/services/llm.py#L386), `async def _run_with_recovery`.

[^3]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/ProviderFailure.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/ProviderFailure.kt#L39), `internal object ProviderFailure`.

[^4]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt#L81), `private val http`.

[^5]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/CycloneTextChat.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/CycloneTextChat.kt#L23), `private val http`.

[^6]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterCatalog.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterCatalog.kt).

[^7]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneOpenRouterCatalog.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneOpenRouterCatalog.kt).
