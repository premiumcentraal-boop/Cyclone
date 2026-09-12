# Latency budgets and local interruption handling

**Priority: P0. Scope: foreground decision scheduling. Implementation size: medium. Status: proposal, not implemented.**

## Finding

Artemis has a direct Flash observe/model/action loop as well as a Pro graph with planning and checking. Flash commits the previous action result, renders bounded history, and immediately asks for the next tool call. Its visual-summary machinery is separate from the core loop.[^1][^2] This makes optional reasoning and history work identifiable costs instead of one undifferentiated waiting state.

Artemis's advertised step timings are not measurements of Cyclone or a reproducible latency target. Its unbounded-turn option and unvetted action bursts are unsuitable defaults for Cyclone. The transferable idea is a small critical path with explicit budgets.

## Cyclone comparison

Cyclone already performs cookie rejection before an API call, checks compiled skills and known routes locally, and enforces cancellation/deadline checks after blocking work. The 4.3.7 baseline therefore does not need the same cookie fix reimplemented.[^3] The cookie handler currently recognizes a finite English label set and marks one attempt per session/display/package/label. Unsupported or ambiguous banners still fall through to reasoning.[^4]

## Proposed change

Instrument a decision as separate monotonic spans: observation, local interruption policy, route recall, prompt construction, provider wait, grounding, dispatch, settling, and effect verification. Add a visible typed phase and elapsed time to existing progress state. Track time since the last verified user-goal progress separately from time since the last model response.

Generalize the local cookie policy into an allowlisted interruption registry. Its result should be `handled`, `not_applicable`, `ambiguous`, or `blocked_by_user_intent`, with a reason and intended effect. Add localized reject labels only with fixtures. Preference dialogs can be inspected in a bounded sequence, but each page-changing action still requires a fresh observation. Never treat permission grants, login confirmation, payments, or arbitrary close buttons as disposable nuisance UI.

Budget optional inspection and summary work independently from the main task deadline. If no local safe action exists, issue one bounded model/inspection request with the exact missing evidence. On expiry, cancel that request and report its phase; do not spend another full request silently repeating the same context. Keep all dispatches behind the existing executor and authority checks.

The desired behavior is concrete: a recognized reject-cookie control should consume zero provider calls before its first action. A new dialog should result in a bounded inspection or an actionable explanation, not two minutes of indistinguishable activity.

## Acceptance and rollout

Use replay fixtures for English and Dutch consent dialogs, ambiguous accept/reject layouts, a delayed provider, and a user-authentication gate. Assert zero provider calls for deterministic rejection, no repeated click without effect, no action after timeout/cancel, and retention of the original login goal. Measure p50/p95 time to first useful action and time spent in each phase; proposed performance gates must be calibrated on a physical device, not copied from Artemis's README.

Ship timing and phase reporting first, then one interruption handler at a time behind a registry flag. Roll back a handler independently when its false-dismissal count is nonzero. Dependencies: proposal 13 for real task fixtures; proposals 02 and 08 for incident persistence and provider request deadlines.

## Sources

[^1]: Artemis, [`artemis/agents/flash/runner.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/agents/flash/runner.py#L1036), `async def run(self, state: State)`.

[^2]: Artemis, [`artemis/agents/flash/summarizer.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/artemis/agents/flash/summarizer.py#L139), `class VisualStepSummarizer`.

[^3]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt#L286), `session.cookieInterruptions.next`.

[^4]: Cyclone, [`apps/mobile/app/src/main/java/com/cyclone/mobile/ai/CookieInterruptionPolicy.kt`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/mobile/app/src/main/java/com/cyclone/mobile/ai/CookieInterruptionPolicy.kt#L7), `class CookieInterruptionPolicy`.
