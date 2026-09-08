# CYCLONE 4.2.2 — AGENT 2 TASK GLASS HANDOFF

BRANCH: `agent/422-task-glass-overlay`

FINAL SHA: `46e08b975591e55c84fc6a695d7fb2b7e4b023e7` (implementation commit; this handoff document is committed immediately after it)

BASE SHA: `7d263f4a49ea08e6903427653e90723cd818d7e9`

MISSION RESULT:
Completed the Agent 2 task-glass repair. The overlay now derives current-task presentation only from a real `WorkspaceTaskUi`, uses a Gemini-inspired semantic Material task card, gives queued work separate Steer/Stop cards, keeps destructive current-task controls inside the existing exact-task progress/details flow, and adds conservative FIFO queue promotion.

PHANTOM TASK ROOT CAUSE:
`OverlayChrome` previously rendered a fallback current-task row when overlay visual state was ANALYSIS / WORKING / LIVE / GATE even if `WorkspaceTasks.state` was null. That made animation/UI state look like an executable task and exposed a dead Stop control.

PHANTOM TASK FIX:
Removed the fallback. A real `WorkspaceTaskUi` is now the only source of truth for the current card. Null or STOPPED task state produces no current-task card, regardless of overlay animation state.

CURRENT CARD DESIGN:
Rounded 28dp semantic Material surface with subtle outline, resolved Android app icon with Cyclone fallback, one small status line, deterministic humanized task sentence, and one >=48dp primary action. No destructive action appears on the collapsed card.

QUEUE CARD DESIGN:
Each queued phone task is its own secondary card with app icon, humanized label, concise queue state, and proper >=48dp `Steer` and `Stop` buttons. Raw `Start` / `Remove` links are gone. Keyboard-open mode keeps a bounded number of queue cards visible and reports remaining count.

TASK HUMANIZER:
Added deterministic bounded task naming without another LLM call. Examples covered by tests: `open chrome` -> `Opening Chrome`; Instagram login request -> `Checking Instagram login status`; battery settings request -> `Checking battery settings`. Unknown goals use a concise <=65-character fallback.

APP ICON RESOLUTION:
Current and queued cards use the existing `CycloneAppIcon`, which resolves `PackageManager.getApplicationIcon(packageName)` and falls back to the Cyclone task icon. No bitmap icon is persisted in task state.

VIEW PROGRESS:
The current card opens the exact original task through `UiTask(task).open(context)` / existing `ViewProgressRouter`. REVIEW / confirmation tasks label the CTA `Review` but still route through exact-task identity.

CANCEL LOCATION:
Stop / cancel / pause / continue controls remain in the existing progress/details experience and service notification where appropriate. The collapsed current card and compact background task glass do not expose Stop/Close.

STEER CONTRACT:
Queued work can retain a `WorkspaceDestinationHint`. The inline overlay-safe steering sheet reads existing Layer 2 workspace/profile inventory. It offers Profile A and only exposes Profile B when an actual visible secondary profile exists. Steering does not mutate Android profile state.

QUEUE PROMOTION CONTRACT:
One-hot execution is preserved. Promotion considers only the FIFO head and only when there is no active task or the previous task is truly STOPPED/FAILED. REVIEW, HUMAN, PAUSED and DONE do not promote. Before promotion it re-runs `canStartRequest`, Layer 2 GATE checks, background setup and the existing `WorkspaceTasks.start` safety path. A secondary-profile hint is never guessed into the named-VD execution plane; it stays queued. Attachments remain attached to their queued request until the existing start path consumes them, and a full queue checks capacity before taking a draft attachment.

DARK MODE:
Task-glass surfaces use `MaterialTheme.colorScheme` semantic content/background colors. Owned task-card/queue/compact-glass presentation contains no hard-coded black or white text.

LIGHT MODE:
The same semantic colors, subtle outline and restrained translucency are used in light mode. Primary CTA uses `primary` / `onPrimary`.

KEYBOARD / LARGE FONT:
Expanded task area is vertically scrollable and display-bounded (`TASK_AREA_MAX_HEIGHT_DP = 430`, keyboard max 210). Queue removes the legacy 150dp cramped box. With IME open it shows a compact bounded queue subset while preserving Steer/Stop controls and a remaining-count summary.

FILES CHANGED:
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceRequestQueue.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceTaskService.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/WorkspaceTaskState.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/BackgroundTaskGlass.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChrome.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChromeContract.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneAskTaskPanel.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CyclonePendingRequests.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/TaskGlassPresentation.kt` (new)
- `apps/mobile/app/src/test/java/com/cyclone/mobile/runtime/background/WorkspaceRequestQueue422Test.kt` (new)
- `apps/mobile/app/src/test/java/com/cyclone/mobile/ui/v32/Cyclone422TaskGlassTest.kt` (new)
- `apps/mobile/app/src/test/java/com/cyclone/mobile/ui/v32/CycloneVisual42ContractTest.kt`

TESTS:
PASS — pure Kotlin behavior checks for queue FIFO/removal/steering/attachment retention/promotion policy and task presentation/humanization.

PASS — source-contract checks for phantom-task removal, collapsed destructive-control removal, Steer/Stop queue actions, safety rechecks, Profile B fail-closed behavior, semantic colors, bounded task area and keyboard compaction.

PASS — whitespace / source validation and `git diff --check` in the available staged source workspace.

PASS — parser-level Kotlin gate reached only unresolved Android/Compose/JUnit symbols in the partial source workspace; no syntax/parser failures were found.

NOT RUN — required full-repository Gradle commands `:app:testDebugUnitTest`, `:app:compileDebugKotlin`, `:app:lintDebug`. This execution environment does not contain the complete private repository checkout/Gradle project and has no network path to clone it. The pushed commit currently has no GitHub status checks or workflow runs attached.

PHYSICAL UI STATUS:
OVERLAY DEVICE ACCEPTANCE: UNVERIFIED. No physical phone UI run was available in this environment.

KNOWN LIMITATIONS:
- A queued request without an explicit/bound target starts automatically only when exactly one safe launcher-label match can be resolved; otherwise it remains queued with `Choose destination` rather than guessing.
- A valid secondary-profile steering hint is presentation/routing metadata only in this lane. Automatic named-VD promotion deliberately refuses to guess a secondary Android profile route.
- DONE retains the exact prepared app page and therefore does not automatically release the hot-task slot; promotion waits for true close/stop/failure.
- Full Android Gradle and physical-device acceptance remain for the integration/build environment.

CROSS-LANE NOTES FOR AGENT 1:
No Agent 1 implementation dependency was introduced. No `WorkspaceRuntime` VD execution, `PhoneToolExecutor`, Session Kernel, profile setup, Fast Path or Skill Compiler files were modified. If Agent 1 adds profile routing, preserve the fail-closed `WorkspaceDestinationHint` semantics and do not bypass the existing start/GATE checks.

CROSS-LANE NOTES FOR AGENT 3:
Public Compose entry signatures remain `CycloneAskTaskPanel(task)` and `CyclonePendingRequests(onOpen)`. Agent 3 request-router work can continue to submit/queue through the existing task/request interfaces without manufacturing visual current-task state. The new queue target parameters are optional and backward-compatible.

READY_FOR_422_INTEGRATION: YES
