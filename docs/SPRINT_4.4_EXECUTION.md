# Cyclone 4.4.0 execution foundation sprint

Baseline: published v4.3.8, efec0488540665491a01837417fd1d82bb975cce (versionCode 99).
Target: Mobile 4.4.0 / 100. Branch: codex/cyclone-4.4-execution.
Reference dossier: https://github.com/premiumcentraal-boop/Cyclone/tree/15ba8cfe6449609b8864dd3fe58339b473d7012b/docs/artemis-upgrades

## Checkpoints

| Checkpoint | Original proposals | Implementation / tests | Commit |
|---|---|---|---|
| 1: regression and timing foundation | 09, 13 (deadline basis 01/08) | ExecutionTiming and ExecutionFoundationFixtureTest; existing cookie, overlay, scope, provider tests retained | This checkpoint; exact SHA recorded in checkpoint 2 |
| 2: coherent observation | 15 | Pending | Pending |
| 3: persistent incidents / target revalidation | 02, 03 | Pending | Pending |
| 4: health / shared provider lifecycle | 07, 08 | Pending | Pending |
| 5: interruption phases and budgets | 01 | Pending | Pending |

## Fixture manifest

Suite v1; seed 0; JVM scripted Android/provider ports; no physical device, account, network model, tokens or human intervention. Locale en initially; display default-foreground/0. Runtime convergence is exercised through CycloneLocalAgent. Independent fixture state verifies consent removal; model DONE alone is insufficient. The original real login/authentication task is NOT claimed complete by this narrower consent-to-login-surface fixture.

Controlled reproduction: two independent semantic captures with a banner transition return different generations. This captures the source-level race class, not proof about the private original export. Existing CookieInterruptionPolicyTest proves one reject dispatch across observation churn. Existing overlay exclusion tests remain mandatory.

Measurements and checks will be recorded per checkpoint. Synthetic injected-clock durations are accounting tests, not phone performance claims. No production pixels, full trees, provider reasoning or raw secrets are added to telemetry.

## Remaining limitations

Implementation checkpoints 2–5, final full tests/lint/build and release guards pending. Physical-device browser/login acceptance UNVERIFIED. No AndroidWorld score, provider performance or real-device latency claim. Cyclone One unchanged.

Checkpoint 1 validation: full :app:testDebugUnitTest passed (5m27s clean build); 7 new task/timing fixtures passed. Injected provider span: 75ms exactly; cancellation/deadline/403/stale fixtures dispatched zero actions; consent fixture dispatched once with zero prior provider calls. Overlay collection exclusion asserted. All attempted fixture cases retained.

Checkpoint 1 SHA: 6b7be439de5f0f5ba30b6c5c0d513394f5a87c02; 1,007 tests, zero failures/errors.

Checkpoint 2 (proposal 15): GatewayObservation's existing PageContext now travels with its Page Card as an in-process projection. Standalone observe/after-action/learning paths consume that projection and no longer call phone.observe followed by bridge.locate. Failed capture clears the current page rather than retaining a stale actionable view. Semantic capture records monotonic interval and geometry. Explicit visual escalation brackets pixels with two same-scope semantic observations and rejects changed package/activity/content/fingerprint/geometry or >1,500ms capture interval. This is a conservative skew check, not hardware atomicity. Normal planning no longer attaches unbracketed live task pixels; reference-only user attachments remain separate. No image failure fabricates a semantic tree.

Tests: production environment/bridge capture-queue race (one capture for both projections), rotation/display/content/skew rejection. Physical-device p95 and visual false-rejection rates remain unmeasured; 1,500ms is a conservative initial bound requiring device calibration.

Checkpoint 2 full suite: 1,009 tests passed. Checkpoint 1's late-added overlay assertion was not included in the first compiling test snapshot and failed on rerun; corrected to TaskSurfaceWindows.includeSibling, the actual service boundary. This correction is included in checkpoint 2. No runtime overlay policy was weakened.
