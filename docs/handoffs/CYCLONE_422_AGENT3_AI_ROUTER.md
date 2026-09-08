# CYCLONE 4.2.2 — AGENT 3 AI ROUTER HANDOFF

BRANCH: `agent/422-ai-auto-router-polish`

FINAL SHA: `b4e0891c59a491b1076029b9461c0a4c2fad3ec9` (final implementation/test-compatibility commit; this refreshed handoff is committed separately as metadata only)

BASE SHA: `7d263f4a49ea08e6903427653e90723cd818d7e9`

MISSION RESULT: One-composer Ask Cyclone routing and visual repair are implemented on the exact preserved 4.2.1 base. The user no longer chooses Chat versus Phone task. A deterministic local router classifies obvious requests before any Android readiness/observation/mutation path. Chat remains independent from active phone work; routed phone requests start the existing Workspace flow when available or use the existing Up Next queue when work is active.

OLD MODE TOGGLE: Removed the `Chat | Phone task` segmented control, the boolean phone-task mode, and the redundant `New request` label. No replacement phone toggle was added.

NEW ROUTER: Added `RequestIntentRouter` with `RequestIntent { CHAT, PHONE_TASK }`, confidence, reason, attachment relevance, optional app hint, and explicit dispatch. It is local/deterministic and has no OpenRouter/provider classifier, Accessibility observation, screen capture, or Android mutation dependency.

CHAT RULES: Requests answerable from user text, attached text/image content, or model knowledge stay CHAT. Examples covered include image questions, arithmetic, summarization, explanations, writing an email draft, general knowledge, and explanatory questions such as “How do I open Chrome?”. CHAT does not call `WorkspaceTasks.canStartRequest()` and therefore does not require Accessibility/background setup merely to decide or answer.

PHONE TASK RULES: Explicit requests that require current Android state, phone/system mutation, app opening, or app interaction route PHONE_TASK. Covered examples include opening Chrome/Settings, checking notifications or login state, changing Wi-Fi/Bluetooth, Instagram search, and preparing an order in Starbucks. Obvious routing performs no classifier provider request.

AMBIGUITY RULE: Low-confidence consequential requests without a clear Android interaction default to CHAT/clarification rather than speculative phone mutation. No model-assisted classifier fallback is used in 4.2.2.

ATTACHMENT ROUTING: Attachment presence alone never forces PHONE_TASK. Attached media questions remain CHAT context. Explicit app-action prompts keep the attachment on the phone-task route. CHAT takes the pending attachment only after CHAT dispatch; cancellation/provider failure restores it. Fresh phone setup leaves it pending for the existing Workspace start path. Queued phone work uses the existing `WorkspaceTasks.queueRequest()` attachment ownership so the request and its attachment stay paired.

ACTIVE TASK ROUTING: CHAT is not gated by an active phone task and can run while phone work continues. A new PHONE_TASK checks the existing `WorkspaceTasks.canStartRequest()` only after the local router selects PHONE_TASK. If work can start, the existing `WorkspaceActivity` app-selection/start flow opens. If work is active, the request is queued; the active task is not replaced or cancelled.

QUEUE INTERACTION: Uses Agent 2/runtime public surfaces unchanged: `WorkspaceTasks.requests`, `WorkspaceTasks.canStartRequest()`, `WorkspaceTasks.queueRequest()`, `CycloneAskTaskPanel`, and `CyclonePendingRequests`. No queue/runtime signatures or Agent 2-owned card implementations were modified.

LIGHT THEME FIX: AI status, task container, composer, and assistant bubbles use Material surfaces with subtle `outlineVariant` borders plus `tonalElevation = 0.dp` and `shadowElevation = 0.dp`, removing the layered dark/gray halo mechanism instead of whitening surfaces.

DARK THEME FIX: AI page surfaces define theme-appropriate `contentColor`; text/icons use `onSurface`, `onSurfaceVariant`, container content colors, primary, or error. No black-on-dark/white-only content assumptions were introduced.

AI NAV FIX: `CycloneV32BottomBar` explicitly supplies `NavigationBarItemDefaults.colors`. AI uses a transparent Material selected indicator and one Cyclone-primary 48 dp circle when selected, removing the green-pill-behind-blue-circle double indicator while preserving `NavigationBarItem` selection semantics. Other destinations retain a coherent Material selected indicator. The final ownership cleanup reduced `CycloneV32Components.kt` to a 28-line diff versus base, confined to nav imports and `CycloneV32BottomBar`.

FILES CHANGED:
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ai/RequestIntentRouter.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt`
- `apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32Components.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/ai/RequestIntentRouterTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/ui/v32/CycloneV39AiChatPageTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/ui/v32/CycloneV39AiPageContractTest.kt`
- `apps/mobile/app/src/test/java/com/cyclone/mobile/ui/v32/TaskComposerSeparationTest.kt`
- `docs/handoffs/CYCLONE_422_AGENT3_AI_ROUTER.md` (handoff metadata only)

TESTS:
- PASS — standalone JUnit-style `RequestIntentRouterTest` and `CycloneV39AiPageContractTest` compiled/run with local Kotlin/JUnit stubs.
- PASS — routing matrix includes required image/chat/phone cases, attachment-only behavior, no classifier flag, conservative consequential fallback, and dispatch behavior preventing CHAT from entering Up Next.
- PASS — source-contract guards verify no Chat/Phone segmented control, router-before-phone-readiness ordering, bounded task area before composer, chat-only Stop Reply, zero-elevation/outline surface treatment, and transparent AI Material indicator.
- PASS — legacy `CycloneV39AiChatPageTest` and `TaskComposerSeparationTest` were updated from 4.2.1 mode-toggle assertions to the 4.2.2 one-composer contract; their source assertions were rechecked against the final page.
- PASS — changed-file whitespace/privacy guards; Kotlin parser smoke found no syntax errors.
- PASS — GitHub base comparison confirms exactly seven Agent 3 code/test paths plus this handoff; no prohibited runtime/Agent 2 file changed. Navigation-file diff is 24 additions / 4 deletions and limited to the owned nav treatment.
- NOT RUN — `./gradlew :app:testDebugUnitTest`, `./gradlew :app:compileDebugKotlin`, `./gradlew :app:lintDebug`: this execution environment has no checked-out Android workspace/dependency classpath. Direct container access to GitHub is unavailable, `mobile-ci.yml` does not automatically run for this agent branch, and no PR was opened because the mission explicitly forbids one.
- NOT RUN as a real repository command — `git diff --check`; equivalent trailing-whitespace/tab guards passed on every changed source/test file.

PHYSICAL UI STATUS:
- LIGHT THEME UI: UNVERIFIED
- DARK THEME UI: UNVERIFIED
- KEYBOARD UI: UNVERIFIED

KNOWN LIMITATIONS: Full Android Gradle compile/lint/unit-suite evidence and physical-device visual acceptance remain outstanding. The router intentionally favors CHAT for uncertain requests; uncommon phrasing may therefore ask/answer rather than mutate the phone. There is intentionally no extra provider classifier for ambiguous prompts in V4.2.2.

CROSS-LANE NOTES FOR AGENT 2: No Agent 2-owned file or queue/task-card signature changed. Agent 2 can integrate its card presentation independently. This page consumes the existing current-task and queued-request composables in a bounded area above the composer and depends only on current public Workspace APIs.

READY_FOR_422_INTEGRATION: NO

Reason for NO: implementation and owned tests are complete, but the sprint’s required full Android Gradle test/compile/lint commands and physical UI checks could not be executed in this environment. Promote to YES only after those integration checks pass.