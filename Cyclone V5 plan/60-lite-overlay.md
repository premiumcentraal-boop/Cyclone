# 60 · The Lite overlay: one still window, motion drawn on the GPU, nothing running when nothing changes

**Owner ask (2026-10-15):** "Develop and research deeply into how to make a lightning fast and every action and page
consistent smoothly feeling overlay for the lite version."

**Scope.** "Lite" is the owner's existing **Visual quality** setting (Settings → Auto / Full / Lite; prefs
`cyclone_ui` / `visual_quality`, `VisualQuality.resolved`). The app's own Ask screens follow it today. The overlay does
not: it has one look for every phone. This plan gives the overlay a Lite mode, and a new engine underneath that Full
can reuse later.

**Status.**
- **Built in alpha.134:** L1 (the constant work, both modes) and L2 (the Lite skin). See §9.
- **Deferred:** L3, the one-window stage, after the constraints review in §9. The window structure stays as it is.
- **Not yet built:** L0 (measuring) and L4 (R8 and the Baseline Profile).

All numbers are estimates from reading the alpha.133 code and from Android's own documentation and source (cited in
§8).

---

## 1. Why the overlay doesn't feel fast today (alpha.133)

The flicker fix in alpha.133 removed the worst problem: the overlay disappearing on every action. What is left is
**constant work**: the overlay keeps redrawing, re-laying out and talking to the window system even when nothing on it
changes. On a slow phone that is felt as stutter during transitions and sluggish actions. On any phone it costs
battery and makes the agent itself slower.

**Every frame while a task runs (60–120 times a second):**

| # | What | Where | Why it costs |
|---|---|---|---|
| 1 | The Trace Field draws a full-screen shader every vsync for the whole task | `tracefield/TraceFieldView.kt:223`, `TraceFieldShader.kt` | Up to 8 texture reads per pixel. At 1080×2400 that is about 20 million samples per frame. There is no frame-rate cap. |
| 2 | Up to 4 blur windows re-blur the app behind them | `ai/OverlayBlurWindows.kt` (4 slots, 24 dp) | A blurred layer forces SurfaceFlinger to compose everything beneath it on the GPU (`Output.cpp`). The animating Trace Field underneath makes the blur redo itself every frame. |
| 3 | `PlaneSymbol` recomposes every frame | `PlanePill.kt:181-183` | An infinite 700 ms sweep is read during composition, not at draw time. |
| 4 | The Stop-gesture loop asks for every frame | `OverlayAppleLiquidComposer.kt:296-301` | `withFrameNanos` runs for the whole task. It keeps the Choreographer awake and votes for high refresh. |
| 5 | The tilt-glass dot fields redraw 20× a second, or every frame while the phone moves | `TiltGlass.kt:133-148, 273-375` | About 3,500 dot evaluations plus 520 rim strokes per tick (est.). They are driven by the gravity sensor. |
| 6 | Spinners and rings run whether or not they mean anything | `OverlayGlassStack.kt:195, 226, 662`; Drive orb, 3–5 loops even when idle | Each one is a redraw every frame. |

**Every transition (opening the card, island ↔ Ask bar, gate, check-in card, a step that wraps to two lines):**
- The card window is `WRAP_CONTENT`. Every animation that changes the card's height (`SizeTransform`, the drawer
  reveal, `expandVertically`, `animateContentSize`) resizes the **window** on every frame.
- Because the window is bottom-anchored, its top moves too. So each frame both moves and resizes it, which AOSP turns
  into a **synchronous** relayout with a BLAST sync (`ViewRootImpl.relayoutWindow`).
- Each of those frames also re-places the blur, refreshes the touch regions and kicks a Trace Field redraw.
- A 300 ms morph at 60–120 Hz is therefore 18–36 window relayouts.
- The keyboard glide calls `updateViewLayout` on every frame for 280 ms.

**Every agent action:**
- The alpha.133 step back flips the window's focus flag twice. That is 2 window IPCs plus a 2-frame wait on entry,
  and the same again on exit (`OverlayChromeController.syncHostGesturePassthrough`).
- So **4 window IPCs and 4 frame waits per action**: about **66 ms at 60 Hz**, and up to 1.2 s when the main thread is
  busy (a 0.5 s stall has been measured).
- It also flips accessibility importance and makes the keyboard insets dispatch again.

**Every status update:**
- **Two notifications are rebuilt**, with no throttle and no de-duplication (`AgentTaskNotificationRuntime.kt:49-58`).
- **The whole overlay recomposes from the root.** The root reads 10+ flows, `OverlayChromeSnapshot` is not
  `@Stable`, and `ComposerPanel` collects 9 more.
- **A "Thinking" tick about once a second** still runs a full layout pass, with 4 lock-state calls and one blur sync,
  even though nothing changed.

**All the time:**
- **Pollers:** lock state every 500 ms forever, and the keyboard tracker every 80 ms while the card is open.
- **Windows:** 8 attached during a task (main, halo, share pill, 4 blur dialogs, full-screen Trace Field), plus the
  tools sheet and 1–3 Drive windows. Android phones usually have about 4 hardware overlay planes, and the status bar,
  navigation bar and the app already use them. Every extra layer pushes composition onto the GPU (AOSP HWC docs).
- **One main thread for everything:** `onAccessibilityEvent` runs on the main thread for every event, the same
  thread that draws the overlay. Window lists, observation bookkeeping and the learner hooks all run there.
- **Build:** no R8 for release and no Baseline Profile, so overlay code runs in the interpreter or JIT the first time
  each feature is used.

---

## 2. The design: four rules

1. **One still window.** The window never changes size or position during motion. Everything moves inside it.
2. **Motion is drawn, not laid out.** Animations change only draw-phase properties (`graphicsLayer`, `offset {}`,
   `drawBehind`). That costs one GPU frame and nothing else: no layout, no IPC, no recomposition.
3. **When nothing changes, nothing runs.** No infinite animations, no sensors, no frame loops, no pollers, no
   full-screen shader. A static card costs zero frames per second.
4. **One motion, everywhere.** Three springs, the same for every page and action, interruptible and overlapping.
   There is never an empty frame and never a size jump.

### 2.1 The window: a fixed "stage" with a touchable region

- **Two window shapes, chosen per mode, never animated:**
  - **Dock:** the idle 48 dp ball. This is unchanged.
  - **Stage:** full width × a fixed height, the tallest a Lite surface needs (about 55% of the screen; it holds the
    card, the Ask bar with keyboard, the gate, check-in cards, and the tools sheet as a tall panel).
- **Switching between dock and stage resizes the window once.** That happens behind the ball's own scale-up, so it
  is not seen.
- **The stage window lets touches through its empty part** with `rootSurfaceControl.setTouchableRegion(card bounds)`.
  This is public since API 33, and Cyclone's minSdk is 33. It is the pattern Android's own Bubbles use (one window and
  a touchable region; `BubbleStackView`).
  - The region is set when a surface settles, not every frame.
  - The alpha.133 stroke rule ("hide only for a stroke that lands on the glass") keeps working: the stage reports the
    card's bounds, not the window's.
- **The rest of the stage draws nothing**, so those pixels are transparent and cheap to compose.
- **Everything is one composition in that window:** the halo, share pill, tools sheet, Ask bar, island, card, gate
  and check-in cards. They are separate composables, not separate windows. The share pill stays its own window only
  while sharing, because it needs `FLAG_SECURE`.
  - Lite windows during a task: **1** (today: 8).
- **The keyboard:** the stage is anchored at the bottom. When the keyboard opens, the content translates up by the
  keyboard height (`offset { }`, drawn) instead of the window gliding by `updateViewLayout` every frame.

### 2.2 Lite glass: smoked, static, drawn once

- **No cross-window blur.** The blur is what forces SurfaceFlinger onto the GPU for the whole screen.
- **No dot fields, no tilt light and no gravity sensor.**
- **Each surface** is a tinted body at about 92% opacity, a 1 px inner highlight and a faint vertical gradient.
  - AOSP's own blur-off guidance uses a background alpha of 255 instead of 170.
  - Cyclone already fades to this smoke when blur is off, so Lite is the existing fallback look made the only one.
  - It is drawn with `drawWithCache` and rebuilt only when a surface's size class changes, not every frame.
- **No full-screen Trace Field in Lite.** Where Cyclone is working is shown by a thin lit edge on the card itself
  (one gradient stroke) and a 6 dp dot that marks the current target on the step row.

### 2.3 One motion

- **The springs** are Material 3's standard motion scheme (androidx `StandardMotionTokens`), shared by every surface:

  | Spring | Damping / stiffness | About | Used for |
  |---|---|---|---|
  | **Move** (spatial default) | 0.9 / 700 | 300 ms | Morphs and slides |
  | **Quick** (spatial fast) | 0.9 / 1400 | 200 ms | Small shifts, the keyboard lift |
  | **Fade** (effects default) | 1.0 / 1600 | — | Alpha and colour |

- **The morph.** Island ↔ Ask bar ↔ card ↔ gate ↔ check-in card ↔ sheet are one container whose bounds are an
  `Animatable` (left, top, width, height, corner).
  - The glass is drawn at those bounds in `drawBehind`, with a matching clip.
  - Content rides inside with `graphicsLayer { translationY; alpha }`.
  - **No `SizeTransform`, `animateContentSize` or `expandVertically` anywhere in Lite.**
- **Changing content:** the new content fades in on top while the old one stays solid, then the old one fades out.
  This is the alpha.133 overlap, done with `CompositingStrategy.ModulateAlpha`, so no offscreen buffer is used.
- **Interruptible.** Every animation is an `Animatable`. A new state starts from the current value and velocity, so
  a quick double change never snaps back. This follows Apple's "fully redirectable interface" and Android's own
  guidance on `animate*AsState`.
- **Height doesn't jump on every step:**
  - the status line is one line with an ellipsis;
  - the step list shows a fixed number of rows (2), and each new step slides in at the bottom;
  - the card is sized for its content class (working / needs-you / done), not for its text.
- **Working is shown by one small arc** turning with `graphicsLayer { rotationZ }`. The rotation is drawn on the
  RenderThread from a single `Animatable`, and it runs only while the card is on screen and the task is working.
  Nothing else loops.

### 2.4 The update path: one model, conflated, off the main thread

- **A `LiteOverlayPresenter`** (background coroutine) combines:
  - the machine snapshot;
  - `WorkspaceTasks`;
  - `MindMissions.live`;
  - `MissionPlanes.ui`;
  - `WorkingApp`;
  - the owner moment.
- It emits **one** `@Immutable LiteOverlayModel`:
  - it uses `distinctUntilChanged`;
  - it is sampled at most 10 times a second, except for state changes (working → needs you → done), which pass at
    once;
  - "Thinking" ticks and repeated messages never reach the UI.
- **The UI reads one `StateFlow`**, and each composable reads only its own slice (`derivedStateOf` for mode flags).
  A new status line recomposes only the status text.
- **Notifications** are built from the same model: at most once a second, and only when their text changes. This
  applies to Full too.

### 2.5 Actions: no window work per action

- **While the agent works, the Lite stage stays `NOT_FOCUSABLE` and out of the accessibility tree the whole time,** not
  flipped per action.
  - It becomes focusable only when the owner taps the Ask field to type, and goes back when they send or close.
  - The agent filters Cyclone's own windows when it reads the screen, so permanent exclusion is safe.
  - This removes the 4 IPCs and 4 frame waits from every action.
- **Strokes that land on the glass** still hide the stage for that stroke (alpha.133). With one window that is one
  visibility flip, not four.

### 2.6 The main thread is for drawing

- **`onAccessibilityEvent` only enqueues.** It copies the few fields it needs and posts them to one `HandlerThread`
  that does the window reads, observation bookkeeping and learner hooks. Tree reads are already off the main thread.
- **Pollers become events:**
  - the 500 ms lock check becomes the `SCREEN_OFF` / `USER_PRESENT` broadcasts;
  - the 80 ms keyboard tracker runs only while the Ask field has focus.
- **Faster from the first use:**
  - R8 on for release;
  - a Baseline Profile covering show overlay → open card → step updates → gate → island;
  - Compose compiler reports, to keep the Lite tree skippable.
  - Google reports Baseline Profiles make code run about 30% faster from first launch; R8 case studies report 24–40%
    faster starts.

---

## 3. Estimated gains (Lite vs alpha.133)

These are estimates. Each row says how it was reached. L0 measures the real values on the owner's phone first.

| What | Today (alpha.133) | Lite | How the estimate is reached |
|---|---|---|---|
| Overlay windows during a task | 8 (+ sheet, + Drive 1–3) | **1** (+ share pill only while sharing) | Counted from `OverlayChromeController.show()`, `OverlayBlurWindows.SLOTS = 4`, the Trace Field attach. |
| Full-screen translucent layers | 1–3 | **0** | Trace Field, Drive dim and sheet become in-window composables or are dropped. |
| Frames the overlay asks for while the card just sits there working | every vsync: **60–120 /s** | **about 1–5 /s**, real updates only (plus the small working arc, drawn by the RenderThread) | Today 4 always-on frame sources (Trace Field, Stop loop, PlaneSymbol, tilt clock) each ask for every frame. Lite has none. That is **about 95% fewer overlay frames**. |
| GPU work behind the card | full-screen shader (~20 M texture samples / frame) + up to 4 blur passes, with forced GPU composition of the whole screen | the card's own pixels when they change; the rest can stay on hardware planes | SurfaceFlinger `Output.cpp` forces client composition under any blur layer. HWC docs: about 4 planes, extra layers go to the GPU. Expected to remove **most of the overlay's GPU cost during a task, est. 80–95%**. |
| Window IPCs during one transition (about 300 ms) | **18–36** relayouts (move + resize = synchronous BLAST sync), plus blur hide/show | **0** (1 when the dock opens into the stage) | One relayout per animation frame at 60–120 Hz today. Lite animates only draw properties. |
| Window work per agent action | **4 IPCs + 4 frame waits ≈ 66 ms** at 60 Hz (up to 1.2 s when busy) | **0** | `syncHostGesturePassthrough` on enter and exit. Lite never flips focus per action. |
| Agent speed over a 30-action task | — | **about 2 s faster** (1.3–2.6 s for 20–40 actions), more on slow phones | 66 ms saved per action. It's the agent going faster, not just the overlay looking smoother. |
| Recomposition per status update | whole root + `ComposerPanel` + `WorkCard` | the changed text only | One conflated model with per-slice reads. "Thinking" ticks never reach the UI. |
| Notifications rebuilt | about 2 per status, about 2 per tool call | **≤ 1 per second**, only on change | Throttle plus de-duplication. |
| GPU memory | full-screen Trace Field buffers + 4 blur dialogs + extra window buffers | about **30–40 MB less** | A 1080×2400 RGBA window is about 10 MB per buffer, times up to 3 buffers. |
| Pollers | 500 ms forever + 80 ms while open | **none** (broadcasts; keyboard only while typing) | Inventory §5. |
| Visual consistency | blur steps aside on moves, height changes on wraps, separate windows can't share a transition | **one morphing surface**: no empty frames, no height jumps, no pulses | Everything is one composition (shared bounds within one window, per the Compose shared-element limits). |
| First open of the card after install or update | interpreted / JIT | **about 30% faster code** in the first opens | Google's Baseline Profile figure. |

**Targets L0 and L4 will check** (on the owner's phone, and on a low-end reference phone in the Lab):
- P99 frame overrun at or below 0 ms for the overlay during a task and during every transition;
- P90 CPU frame time under 8 ms;
- the first visual response to a tap within 1 frame;
- a state change on screen within 100 ms of happening (the "feels instant" limit, NN/g / RAIL);
- **0 window IPCs per agent action** and **0 during transitions**, counted by Cyclone itself.

**What could make the estimates wrong:**
- **Real phone numbers aren't measured yet.** On a fast Pixel at 120 Hz the visible difference is mostly the
  transitions and the action speed. On budget phones it should be night and day.
- **Touchable regions are new to Cyclone.** If a phone's skin mishandles `setTouchableRegion`, the fallback is today's
  WRAP_CONTENT window, resized only once at the start and end of a transition, never during it. That loses some
  smoothness and none of the other gains.
- **The look changes.** Lite has no real blur and no living light. It is the smoke look Cyclone already falls back to,
  but the owner should see it before it becomes Auto's choice on any phone.

---

## 4. How Lite is picked

- **The owner's setting:**
  - **Visual quality → Lite:** the overlay uses Lite.
  - **Full:** the overlay looks as it does today. From L3, Full also uses the new engine, keeping blur, dots and the
    Trace Field.
- **Auto:**
  - Lite on the phones Auto already treats as slower: Android's low-RAM flag, under 4.5 GB, fewer than 6 cores, or
    performance class 0 with under 6 GB (`AskQualityPolicy.autoTier`).
  - Lite whenever cross-window blur is off: Battery Saver, the owner turned blurs off, or the phone is warm.
  - Plus an **overlay frame watch:** the same gap test the app uses (`AskFrameWatch`: more than 12% of frames over
    1.5× the budget in 3 s), run on the overlay during transitions. If it trips, Auto steps the overlay down to Lite
    and stays there until the owner picks again.

---

## 5. Runs

| Run | What | Notes |
|---|---|---|
| **L0. Measure** | Overlay frame watch: Choreographer gaps during a task and during each transition, as p50 / p95 / p99. Window-IPC counters for `updateViewLayout`, `addView` and relayouts caused by size, per action and per transition. A Lab suite `overlay` that runs a fixed 20-action task and the full set of transitions, then reports these. | No change to behaviour. Gives the real "today" column. |
| **L1. Stop the constant work** (both modes) | Notification throttle and de-duplication. Skip renders with an unchanged snapshot, and remove duplicate `syncBlur` posts. Read `PlaneSymbol` in draw, and only while switching. Stop-gesture loop only while armed. Spinners and rings only while visible and meaningful. Lock poller → broadcasts. Keyboard tracker only while the Ask field has focus. `onAccessibilityEvent` → worker thread. | Small, safe, helps Full too. Est. most of the "per update" and "pollers" rows. |
| **L2. Lite skin** | The overlay reads `VisualQuality.resolved`. Lite: smoked static glass, no blur windows, no dots, no tilt sensor, no Trace Field window, fixed-line text, a lit edge and one working arc. | The look change. The owner sees it first. |
| **L3. The stage engine** | One fixed stage window with `setTouchableRegion`. The morphing container: bounds `Animatable`, glass drawn in `drawBehind`, content in `graphicsLayer`. One motion spec. Keyboard by translation. Permanently not focusable while the agent works. `LiteOverlayPresenter` and `@Immutable` model. | The big one. The transitions row and the per-action row. Full can move onto it afterwards. |
| **L4. First-use speed and proof** | R8 for release. A Baseline Profile for the overlay journeys, generated on a device or emulator in CI. Compose compiler reports. L0's suite re-run against the targets. Auto's overlay frame watch. | Needs a device or emulator for the profile. |

**Guards (CI):**
- **Static checks on the Lite tree:**
  - no `SizeTransform`, `animateContentSize`, `expandVertically` or `rememberInfiniteTransition`;
  - no `updateViewLayout` in animation paths;
  - no blur or sensor code reachable from Lite;
  - `onAccessibilityEvent` does no window reads.
- **The L0 counters checked in a JVM test** against a fake WindowManager: 0 IPCs per action, 0 during a morph.

---

## 6. What stays the same

- Every approval boundary, card and Task Kit button: Stop, take over, I'm done, approve. Same rules, same routes.
- The alpha.133 rule that a stroke landing on Cyclone's glass hides it for that stroke.
- The Secrets card still gets its own focusable, `FLAG_SECURE` handling.

---

## 7. Open questions for the owner

1. Lite's look: the smoked glass without blur, with a lit edge and no full-screen Trace Field. Should Lite drop the
   field entirely, or keep a slow edge-only version at 30 fps?
2. Should Auto choose the Lite overlay on the slower phones it already picks Lite for in the app, or should the
   overlay follow only the owner's explicit choice at first?

---

## 8. Sources

**Code (alpha.133):**
- `ai/OverlayChromeController.kt` (show, applyLayout, syncHostGesturePassthrough, glide)
- `ai/OverlayBlurWindows.kt`
- `ui/overlay/glass/GlassBlur.kt`
- `ui/overlay/tracefield/*`
- `ui/overlay/OverlayChrome.kt`, `OverlayGlassStack.kt`, `PlanePill.kt`, `OverlayAppleLiquidComposer.kt`,
  `OverlayMotion.kt`, `SignatureOverlayDrawer.kt`, `TiltGlass.kt`
- `mind/mission/MindMissions.kt`
- `AgentTaskNotificationRuntime.kt`
- `CycloneAccessibilityService.kt`
- `ui/v32/ask/AskQualityPolicy.kt`, `AskQuality.kt`, `AskGlassWorld.kt`
- `app/build.gradle.kts`

**Android platform source:**
- **Window updates:** `WindowManagerGlobal.updateViewLayout` → `ViewRootImpl.relayoutWindow` (async only for
  position-only or size-only changes; a synchronous BLAST sync when both change); `IWindowSession.aidl`.
- **Touchable regions:** `AttachedSurfaceControl.setTouchableRegion` (API 33).
- **Blur:** SurfaceFlinger `CompositionEngine/Output.cpp` (forced client composition under blur).
- **Untrusted touches:** `InputMonitor.isTrustedOverlay` (accessibility overlays are trusted).
- **Bubbles:** `BubbleController` / `BubbleStackView` (one window, a touchable region, springs).
- **Accessibility threading:** `AccessibilityService.onBind` (callbacks on the main executor);
  `AccessibilityInteractionClient` (blocking tree reads).
- **Motion tokens:** androidx `AnimatedContent.kt` (default sequential fade, `SizeTransform` is layout per frame),
  `GraphicsLayerModifier.kt` (`ModulateAlpha`), Material 3 `StandardMotionTokens.kt`.

**Android docs:**
- Hardware Composer, about 4 overlay planes: <https://source.android.com/docs/core/graphics/hwc>
- Window blurs: <https://source.android.com/docs/core/display/window-blurs>
- Compose phases, best practices and the animation quick guide (defer reads to layout and draw):
  <https://developer.android.com/develop/ui/compose/performance/bestpractices>
- Strong skipping: <https://developer.android.com/develop/ui/compose/performance/stability/strongskipping>
- Baseline Profiles (about 30%): <https://developer.android.com/topic/performance/baselineprofiles/overview>
- Slow rendering and frame budgets: <https://developer.android.com/topic/performance/vitals/render>
- JankStats: <https://developer.android.com/topic/performance/jankstats>
- Macrobenchmark `FrameTimingMetric`: <https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics>
- Adaptive refresh rate: <https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate>
- Android 12 untrusted touches: <https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events>

**Other:**
- WWDC18 "Designing Fluid Interfaces": <https://developer.apple.com/videos/play/wwdc2018/803/>
- NN/g response-time limits: <https://www.nngroup.com/articles/response-times-3-important-limits/>
- RAIL: <https://web.dev/articles/rail>
- R8 case studies on the Android Developers Blog (Reddit, Monzo).


---

## 9. Constraints review and what alpha.134 builds (2026-10-15)

The owner asked to make sure the new overlay doesn't undo two earlier decisions:
- the agent must never have part of its screen hidden by the overlay;
- the Glass live view must keep its black-bar fix.

The full review traced every rule the overlay's windows encode.

### 9.1 The rules the overlay must keep

| Rule | Why (history) | Where enforced |
|---|---|---|
| **Every Cyclone window is `TYPE_ACCESSIBILITY_OVERLAY` (type 4).** | The agent's reads drop Cyclone's windows by type, never by size or flags: the window list, sibling collection, foreground root, observation events, the capture-boundary signature, background-display reads, TouchFirst's "covered" check and target revalidation. So nothing in the agent's view counts the overlay as covering the app, at any size. AOSP also keeps type 4 out of interactive-region math, and treats type 4 as a trusted overlay that sets no obscured flags on host touches. | `CycloneAccessibilityService.kt:308, 509-521, 554`; `TaskSurfaceWindows.kt`; `agent/SemanticCaptureBoundary.kt:27`; `gesture/TouchFirst.kt:80-86`; `agent/tools/CurrentTargetRevalidation.kt:57`; `docs/OBSERVATION_CONTRACT.md:36-38`; tests `SemanticCaptureBoundaryTest`, `TaskSurfaceWindowsTest`, `TouchFirstTest` |
| **Only the Secrets card is `FLAG_SECURE`** (plus the small share pill). | Android draws a secure window **black over its whole bounds** in capture. Before alpha.30 the full-width Ask window was secure, and the Glass USB mirror blacked out most of the phone. Since alpha.30 the ordinary Ask and task surface mirrors normally. A full-screen secure layer would also black out every display screenshot the agent takes. | `ai/OverlayChromeController.kt:138-148`; `docs/RELEASE_5.0.0-alpha.30.dev1.md:12-13`; `docs/design/CYCLONE_TRACE_FIELD.md:37-42`; tests `OverlayChromeWindowPolicyTest`, `TraceFieldPolicyTest` |
| **The agent's screenshots exclude the overlay where Android allows it.** | Window capture of the host app (`takeScreenshotOfWindow`) leaves Cyclone out. The Trace Field is hidden before any full-display capture. | `ai/vision/live/LiveVisionRuntime.kt:134, 186-189`; `CycloneAccessibilityService.kt:1083-1093` |
| **Strokes that land on the glass hide it** (alpha.131/133). | Android delivers injected gestures to touchable type-4 windows even with `FLAG_NOT_TOUCHABLE` (4.8.0). So hiding is decided from the window's real bounds. | `OverlayGesturePassthrough.kt`, `refreshRegions`, `HumanGestureDispatch` |
| **Human touches pass through outside the panel.** | The owner keeps using their app while a task runs (4.0.1). | `OverlayAskBarPolicyTest` |
| **A host action leaves the overlay without focus** (alpha.133 step back). | A focused Ask window can make a permission dialog unreadable, and steals Back. | `PhoneToolExecutor.kt:1210-1214`; `CycloneAccessibilityService.kt:287-288` |

### 9.2 Why the one-window stage (§2.1, L3) is deferred

- **It would hide on almost every swipe.** Stroke-hiding is decided from the window's bounds. A stage covering about
  55% of the screen would hide for nearly every agent swipe, and the alpha.133 blinking comes back.
  - Reporting only the drawn card instead needs `setTouchableRegion` to also stop **injected** strokes on the
    transparent part. That has never been tested on a device, and if it doesn't hold, the agent's taps land on
    Cyclone.
- **It would black out the Glass view again.** The Secrets card inside a large stage would make the whole stage black
  in the Glass mirror and in every display screenshot: the alpha.30 problem.
- **Taken together:** the stage needs a device test before it can ship.
  - Most of the gains in §3 (frames, GPU, notifications, per-action waits) don't depend on it.
  - The per-frame window resizing it was meant to remove is removed in Lite another way: sizes snap.

### 9.3 What alpha.134 builds

**Both modes (Full keeps its full look):**
- **Notifications:** the task notification is rebuilt only when what it shows changes, and at most about once a second
  while only the words move (`NotificationPacer`). A new state, which changes the buttons, posts at once. The channel
  is created once.
- **Renders:** a change that changes nothing (the model's "Thinking" tick about once a second, a repeated status) costs
  no layout pass (`OverlayChromeRuntime.mutate`). Only one blur sync waits at a time.
- **Always-on frame sources are gone:**
  - the screen-switch symbol sweeps only while switching, and is read while drawing;
  - the Stop ring asks for frames only while a press is held or the first tap is armed;
  - the island ring turns only while working;
  - the progress light slides only when the fraction is unknown.
- **Per action:** a window call only when a window's params really change. The step back on a window that is already
  not focusable costs nothing. The 2-frame commit wait runs only when entering a yield that changed a window; coming
  back never waits. The voice windows keep their own rules.
- **Keyboard tracker:** 80 ms only while Cyclone has focus (the owner typing), 500 ms otherwise. It checks at once
  when focus arrives.

**Lite only** (Visual quality → Lite, or Auto on the slower phones it already picks Lite for):
- no cross-window blur, including the tools sheet's full-screen blur;
- no halftone dots, tilt light, gravity sensor or glass clock;
- no full-screen Trace Field;
- still spinners and rings;
- sizes that snap (swaps, folds, the upper drawer, background glass);
- the keyboard lift in one step.

The windows, their types, flags, sizes and capture rules are **unchanged**, and the guard
`scripts/ci/tests/test_overlay_speed_guard.py` pins that.

### 9.4 Expected gain (estimates)

- **Full:**
  - About 2 fewer sources asking for every frame during a Mind task (the Stop loop and the plane sweep).
  - No more recomposition every frame from the plane symbol.
  - No layout pass for "Thinking" ticks.
  - Notification rebuilds cut from about 2 per update to at most about 1 per second.
  - The look is identical.
- **Per action:** a window that is already not focusable (idle, background glass) pays no window call and no commit
  wait. The expanded card pays one wait instead of two (about 33 ms saved per action at 60 Hz).
- **Lite**, on top of the Full gains:
  - no blur passes, so the GPU composition forced by blur is gone;
  - no full-screen shader;
  - no per-frame window resizes during transitions;
  - nothing redraws while the card just sits there working, apart from real updates.
  - That is the bulk of the §3 table, without changing a window.

## 10. No blink: the drawn card never takes a touch (2026-10-15, alpha.134)

**Why strokes landed on Cyclone (4.8.0, alpha 91).** The idle ball and the Ask card were *touchable* windows that
were switched to untouchable just before each stroke. A busy main thread (0.5 s stalls were measured) could miss
that switch, so the stroke hit a window that was still touchable. Hiding (GONE) was the switch that looked reliable,
and hiding is the blink. Windows that are untouchable from birth and never switch, the full-screen Trace Field, the
halo and the blur windows, have always let every stroke through.

**The design.**
- The card window (expanded, minimized composer, background glass, Secrets and owner cards) is drawn with
  `FLAG_NOT_TOUCHABLE` permanently (`OverlayChromeWindowPolicy.drawn`). Focus is unchanged, so typing works.
- `OverlayTouchLayer`: an empty window over the card, of exactly its laid-out size, `NOT_FOCUSABLE`, that forwards the
  owner's touch to the card (offset by where both are on screen, so a card mid-glide still gets the right spot).
- For a stroke on the glass only the layer (and the empty idle hotspot) go GONE; the card and the halo stay.
  The 2-frame commit wait stays, now invisible.
- Built-in check: a new touch reaching the layer while it should be aside is dropped, counted
  (`OverlayGesturePassthrough.stray`) and reported in the gesture evidence as `touchLayerStray`.
- Screen readers: the focused card is read as before; the never-focused background glass speaks through the layer.

**Cost.** One empty window, and a window update per frame for it during the keyboard glide (Full only). The owner's
touch on the card during an agent stroke over it goes to the app (well under half a second).

**UNVERIFIED on a phone:** forwarded taps, long-presses, scrolls and typing; strokes under the card landing on the app.

