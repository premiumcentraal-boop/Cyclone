# Cyclone 4.4.0 execution foundation sprint

Baseline: published v4.3.8, efec0488540665491a01837417fd1d82bb975cce (versionCode 99).
Target: Mobile 4.4.0 / 100. Branch: codex/cyclone-4.4-execution.
Reference dossier: https://github.com/premiumcentraal-boop/Cyclone/tree/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades

## Checkpoints

| Checkpoint | Original proposals | Implementation / tests | Commit |
|---|---|---|---|
| 1: regression and timing foundation | [09][p09], [13][p13] (deadline basis [01][p01]/[08][p08]) | ExecutionTiming and controlled task fixtures | 6b7be439de5f0f5ba30b6c5c0d513394f5a87c02 |
| 2: coherent observation | [15][p15] | Shared semantic projection; bracketed visual capture | fd29e10821b32d51fb131bb1efe51b2b9d3b6e9b |
| 3: persistent incidents / target revalidation | [02][p02], [03][p03] | Journal incidents and fresh exact target resolution | 97f4197cfaac67d569bf32f21e9935cdcbd90cc9 |
| 4: health / shared provider lifecycle | [07][p07], [08][p08] | Typed bounded observation recovery; shared HTTP deadlines/cancellation/pacing | cbfb0436da18ba91aeac220699a325c8e6bb43da |
| 5: interruption phases and budgets | [01][p01] | Explicit local outcomes; phase budgets and provider progress | 73fbd20b6147674b6d12e782bde161f3c45f358c |
| 6: release identity and workspace health | [07][p07] | 4.4.0 / 100; four fixed workspace error codes and redaction regression | bfef18b6cca0eee9cf6778e3e5ce8c4c4238e143 |

[p01]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/01_LATENCY_AND_INTERRUPTION_BUDGETS.md
[p02]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/02_PERSISTENT_RECOVERY_INCIDENTS.md
[p03]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/03_TARGET_REVALIDATION.md
[p07]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/07_PERCEPTION_BACKEND_HEALTH.md
[p08]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/08_PROVIDER_REQUEST_LIFECYCLE.md
[p09]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/09_TRACE_EVIDENCE_AND_REPLAY.md
[p13]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/13_REPRODUCIBLE_DEVICE_BENCHMARKS.md
[p15]: https://github.com/premiumcentraal-boop/Cyclone/blob/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades/15_COHERENT_OBSERVATION_SNAPSHOTS.md

## Fixture manifest

Suite v1; seed 0; JVM scripted Android/provider ports; no physical device, account, network model, tokens or human intervention. Locale en initially; display default-foreground/0. Runtime convergence is exercised through CycloneLocalAgent. Independent fixture state verifies consent removal; model DONE alone is insufficient. The original real login/authentication task is NOT claimed complete by this narrower consent-to-login-surface fixture.

Controlled reproduction: two independent semantic captures with a banner transition return different generations. This captures the source-level race class, not proof about the private original export. Existing CookieInterruptionPolicyTest proves one reject dispatch across observation churn. Existing overlay exclusion tests remain mandatory.

Measurements and checks will be recorded per checkpoint. Synthetic injected-clock durations are accounting tests, not phone performance claims. No production pixels, full trees, provider reasoning or raw secrets are added to telemetry.

## Remaining limitations

Final full tests/lint/build and release guards are recorded below when complete. Physical-device browser/login acceptance UNVERIFIED. No AndroidWorld score, provider performance or real-device latency claim. Cyclone One unchanged.

Checkpoint 1 validation: full :app:testDebugUnitTest passed (5m27s clean build); 7 new task/timing fixtures passed. Injected provider span: 75ms exactly; cancellation/deadline/403/stale fixtures dispatched zero actions; consent fixture dispatched once with zero prior provider calls. Overlay collection exclusion asserted. All attempted fixture cases retained.

Checkpoint 1 SHA: 6b7be439de5f0f5ba30b6c5c0d513394f5a87c02; 1,007 tests, zero failures/errors.

Checkpoint 2 (proposal 15): GatewayObservation's existing PageContext now travels with its Page Card as an in-process projection. Standalone observe/after-action/learning paths consume that projection and no longer call phone.observe followed by bridge.locate. Failed capture clears the current page rather than retaining a stale actionable view. Semantic capture records monotonic interval and geometry. Explicit visual escalation brackets pixels with two same-scope semantic observations and rejects changed package/activity/content/fingerprint/geometry or >1,500ms capture interval. This is a conservative skew check, not hardware atomicity. Normal planning no longer attaches unbracketed live task pixels; reference-only user attachments remain separate. No image failure fabricates a semantic tree.

Tests: production environment/bridge capture-queue race (one capture for both projections), rotation/display/content/skew rejection. Physical-device p95 and visual false-rejection rates remain unmeasured; 1,500ms is a conservative initial bound requiring device calibration.

Checkpoint 2 full suite: 1,009 tests passed. Checkpoint 1's late-added overlay assertion was not included in the first compiling test snapshot and failed on rerun; corrected to TaskSurfaceWindows.includeSibling, the actual service boundary. This correction is included in checkpoint 2. No runtime overlay policy was weakened.

Checkpoint 2 SHA: fd29e10821b32d51fb131bb1efe51b2b9d3b6e9b.

Checkpoint 3 (02/03): task journal schema 2 retains one incident, originating task/session/display/package/generation, typed intended effect, bounded internal strategy codes, and resolution. Old schema 1 still reads. The compact incident is outside history truncation. Unrelated accepted actions do not close it. Consent closure requires a fresh same-package/scope useful tree, a login control and no remaining consent text/control evidence. Unsupported goal effects remain open until independently verified task completion or a separately marked terminal boundary. Terminal is not verified resolution. No action arguments/typed values or coordinate identities are added to incidents.

Android production environment revalidates element intent against one fresh capture immediately before PhoneToolExecutor. It preserves scope/workspace generation, requires exact semantic identity, unique enabled visible nonoverlapping bounds, and replaces the observation/element ID. Old-rectangle replacement, missing/ambiguous/occluded controls and scope changes reject with typed reasons. Graph/legacy resolution no longer chooses the first fuzzy match. Existing executor approval/typing/readiness checks still run after revalidation.

Tests: accepted-but-unverified recovery retains one incident and original goal; incident codec redacts non-code strategies; wrong effect/display cannot close it; moved control dispatch uses new ID; replacement/ambiguity/scope mismatch produce zero executor calls. Limitations: exact identity matching is conservative; unknown effects are not auto-closed; no process/device restart experiment or real keyboard/browser timing measurement yet.

Checkpoint 3 exact working-source rerun: 1,014 tests passed, zero failures/errors; version/product/security guards passed. Consent proof uses the fresh capture boundary rather than comparing process-local generation counters after restart.

Checkpoint 3 SHA: 97f4197cfaac67d569bf32f21e9935cdcbd90cc9. Matching push CI 34752640792 passed.

Checkpoint 4 (07/08): typed semantic observation health distinguishes healthy/empty valid, timeout, permission/service unavailable, disconnected, scope mismatch and unavailable. Same-scope recovery has a 500ms cooldown and two-attempt bound; permanent boundaries stop without provider calls. Resuming a handoff resets health; no alternate display/backend is introduced. Health is projected into task progress, trace and provider context. Settings-wide diagnostics UI and alternate backend switching remain outside this foundational slice.

Phone tasks, text chat and model qualification share ProviderRequests: exact request passthrough, individual cancellation, 30-second maximum remaining deadline, at most one classified transient retry, 250–5,000ms bounded pacing, and one active/half-open request per account/model/endpoint/purpose. 401/402/403/400 and embedded 403 do not retry. Key replacement cancels/invalidate old-account requests and pacing. Stop does not cancel a global HTTP dispatcher. Coroutine and late-response fixtures verify isolation and discarded results. Offline interceptor fixtures do not establish real provider latency or physical phone response time.

Tests include permanent one-attempt errors, 503→success, repeated 429 bounded to two attempts, cancellation during backoff, response past deadline, independent concurrent request survival, coroutine cancellation, one half-open probe, key replacement, and no raw key in request context. Existing chat source assertion now checks the shared cancellation adapter; behavioral cancellation is covered by transport tests.

Checkpoint 4 final suite: 1,027 tests passed, zero failures/errors. Transient observation fixture recovered after cooldown with exactly two captures in the same task; dead-service fixture made one capture and zero provider calls.

Checkpoint 4 push CI: https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34753146814 (passed).

Checkpoint 5 (01): extends the existing allowlisted cookie policy with explicit handled/not-applicable/ambiguous/user-choice/unresolved outcomes and English/Dutch reject labels. A unique scoped reject is still selected before route recall or any provider request. Ambiguous, disabled or already-attempted consent controls suspend with a specific unresolved incident; authentication remains human-owned and the original goal stays active. The task fixture now explicitly suspends at login instead of treating consent removal as login completion.

Monotonic phase checks reject an over-budget returned plan before dispatch; provider calls share the remaining decision/task deadline and actively cancel HTTP. Local policy, recall, prompt, observation, plan, dispatch and verification have budgets. Grounding/settling remain included in dispatch timing; their existing executor freshness/settle bounds are retained. Synchronous Android callbacks cannot be forcibly interrupted by the caller: their budget is checked on return, whereas provider deadlines are actively enforced. These bounds are initial engineering limits, not measured device latency targets. Provider progress reports elapsed waiting/backoff/deadline/Stop state, with payload-free causal request IDs. Historical page projections no longer retain full legacy pages.

Added fixtures cover Dutch consent, auth/permission exclusion, exact ambiguous/missing/repeated-effect reasons, non-dialog cookie text, a 35,001ms injected plan rejected with zero actions, and Stop precedence over phase expiry. Physical latency percentiles and a live browser/device run remain unverified.

Checkpoint 5 suite: 1,032 tests passed; 72 release-guard tests passed; metadata, product and repository security guards passed. Final release compilation/lint/assembly follows the version checkpoint.

Checkpoint 5 exact push CI passed: https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34787264959.

Release checkpoint (07 follow-up): normalizes the workspace runtime's fixed legacy exception codes for disconnected backend, expired session, missing Accessibility service and target not visible. Raw message suffixes are discarded. The added regression checks precise terminal health and zero actions for all four boundaries. Version identity is 4.4.0 / 100; publication remains gated by exact release-branch CI and the existing provenance/signing workflow.

Local gateway contracts: 199 tests passed; phone MCP: 158 tests passed. ADB reported no attached device; physical acceptance remains UNVERIFIED.

Final release working-source Mobile suite: 1,033 tests passed, zero failures/errors (37s). Metadata, product and security guards passed. The initial 4.4 local tests/lint/release assembly passed (11m51s); the final workspace-code follow-up was then verified by the full Mobile suite. Exact-final-source lint/assembly and signing provenance are additionally required in the release branch CI; links and immutable source SHA are recorded by the release assets and PR #106.
