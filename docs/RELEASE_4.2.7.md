# 4.2.7 structural lane — NOT release ready

## Preserved base and checkpoints

Base: `b3f499416df7e569940827eb2250e95e1c0d9eb9`.
Branch: `agent/427-structural-reliability`.
The base release has not been modified. No PR, tag, signing or publication.

Chronological pushed checkpoints:

1. `b4714efcec955e90f09ce6aaa57eb42021489945` — foreground intent and shared Ask dispatch.
2. `d5d8e2786ee9ed7065dc700fbe55be65c18a9877` — service-recreation cancellation and launcher retention.
3. `35bff2755394935267c3fac297d8ecda5e185575` — profile collection and typed secondary-user provisioning.
4. `f525ed968c2f3f5eb9dd74d8b67575f477aeba2f` — verified simple launch and updated dispatch assertions.
5. `16cee7e79f4620e0bbef8d2fed13215925f553be` — active-store closure tests and detached-window recovery.
6. `ac9411aa1784345185e56046340977b806adcfe9` — exact Layer 2 lease cleanup preserving other jobs.

## Confirmed root causes and source changes

A: Overlay submit used live whole-screen capture as the foreground selector. Naming an app
otherwise started a VD task. In-app Ask and queue promotion repeated that assumption.
The shared resolver now defaults ordinary requests to foreground and requires explicit
background wording. App-name discovery no longer chooses isolation. Exact simple launch
uses PhoneToolExecutor followed by foreground-package verification, without a model request.
Longer tasks reuse the existing adaptive agent. Explicit-profile text is still fail-closed;
it is NOT implemented as exact-user execution yet.

B: A cancel command started a fresh WorkspaceTaskService with no instance taskId. It could
not match or clear the failed current task. Cancellation now binds supplied exact store
identity, joins execution/creation, releases the session, clears current by compare-and-set,
and removes notification 902. Stale updates cannot recreate the removed task. Closed results
are separate bounded in-memory history; this is not new durable run-history storage.
Layer 2 close revokes the matching lease through PhoneToolExecutor without disarming other jobs.

C: clearBackgroundChrome reset the idle chip to false and dismissed the controller.
Task cleanup now retains the launcher; controller-level background failure no longer detaches
it either. Reattach/render repairs a missing window through the existing controller.
Instrumentation: overlayLauncherCount should be 1 while enabled, 0 after detach;
overlayWindowCount includes decoration and screen-share windows and is not a launcher count.

D (PARTIAL): saved profile records form a persistent collection. The old singleton journal
is preserved into that collection before planning another profile. A failed setup continues
the same journal. New rooted profiles use typed full-secondary-user creation, retaining exact
journal/name/user checks and refusing unrelated managed-profile adoption. Cyclone itself is
automatically installed and verified alongside selected apps. No root-manager APK is required
by this code; su comes from the device daemon. Minimal setup controls expose saved records,
repair, Add profile, and Open for ready full users. No visual redesign.

## Release-blocking remaining work

- Resolve explicit profile requests to exact Android user and execution identity. Current
  resolver intentionally refuses these instead of silently running them on Profile A.
- Recover renamed legacy Rooted Clone using additional hard ownership evidence. The current
  changes recover generated names from the journal, not arbitrary renamed/missing journals.
- Complete registry availability and return-to-A navigation after switching to a new Android
  user. The new app installation has fresh private storage; do not call cross-user navigation
  complete. Existing managed profiles remain app-based spaces.
- Add custom names and complete profile-management UX; no delete capability added.
- Add an injectable root runner and full D1–D10 orchestration coverage. Existing/new pure
  policies test command shapes, journal recovery, no unrelated adoption, required packages
  and limit classification, not actual Android provisioning.
- Finish activity/process/service instrumentation for lifecycle and launcher invariants.
  Added JVM tests do not constitute full B/C device acceptance.
- Review explicit-target wording coverage, alternate profile queue steering and foreground
  identity after profile switches. No claim that every target/user case is solved.

Because this lane is incomplete, version remains **4.2.6 / 87**. Only after completing and
validating the structural lane should the release metadata become **4.2.7 / 88** (recheck
reserved codes first). Do not publish this checkpoint as a completed 4.2.7.

## Validation

- `python -m unittest discover -s scripts/ci/tests -q`: 71 passed.
- `python scripts/ci/mobile_product_guard.py`: passed.
- `python scripts/ci/release_versions.py --check`: passed for unchanged base version.
- `python scripts/ci/repository_security_guard.py`: passed.
- `git diff --check`: passed.
- Local `cd apps/mobile && ./gradlew :app:testDebugUnitTest`: blocked downloading Gradle 8.9
  because services.gradle.org is unreachable in this environment.
- GitHub CI runs `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease`.
  Run 34398176719 compiled and ran 885 tests; two old source-contract assertions failed.
  They were updated to require the shared foreground dispatcher, not WorkspaceActivity.
- Final source validation: https://github.com/premiumcentraal-boop/Cyclone/actions/runs/34399391872
  Consult its final result before relying on build status.

Added tests: ExecutionTargetResolverTest, WorkspaceTaskCloseTest,
ProfileStructural427Test, WorkspaceTaskRelease427Test, OverlayTerminalLifecycle427Test.
Existing two AI dispatch contract suites updated for the intentional routing change.

## Device status

UNVERIFIED. No phone, Pixel, USB or ADB testing performed.
Recommended release decision: **HOLD** until the release-blocking work above is complete.
No physical/UI success claims, no signed artifact, no publication.

## Changed source/test files

- `.github/workflows/mobile-ci.yml`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OverlayChromeController.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/ExecutionTargetResolver.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceTaskService.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceTaskState.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/ProfileProvisioningContract.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/ProfileRegistryStore.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/ProfileSetupPlan.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/ProfileSetupRuntime.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/WorkspaceEngine.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces/WorkspaceRuntime.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/RootFeaturesCard.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeRuntime.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/background/ExecutionTargetResolverTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/background/WorkspaceTaskCloseTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/workspaces/ProfileStructural427Test.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/workspaces/WorkspaceTaskRelease427Test.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/ui/overlay/OverlayTerminalLifecycle427Test.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/ui/v32/CycloneV39AiChatPageTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/ui/v32/CycloneV39AiPageContractTest.kt`
- `docs/STRUCTURAL_4.2.7_CHECKPOINTS.md`
