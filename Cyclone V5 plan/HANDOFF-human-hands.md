# Handoff — Human Hands (plan 52)

Written 2026-10-07. For the next agent picking up the Human Hands work on Cyclone.

## The goal, in one paragraph

Make Cyclone navigate and type on the owner's phone the way the owner would: varied, fluid movement and
character-by-character typing instead of rigid, scripted taps and one-shot text fills. The point is quality and
naturalness of motion on the **existing Accessibility path** — not hiding that Cyclone is an automation tool. Approval
boundaries (pay / send / delete / permissions / sign-in), GATE, ownership, fresh observation and the Fast Path settle
all stay exactly as they were. Scope guard: this is about moving like a person, not about defeating any app's
anti-automation checks. Keep it on that track.

The full design and the remaining runs live in **`Cyclone V5 plan/52-human-hands.md`**. Read that first; this file is
the "where we are and how to continue" layer on top of it.

## Where things stand

- **Released:** Cyclone **5.0.0-alpha.111.dev1**, Android versionCode **259**, at commit `8e15381a`. Mobile CI green,
  the paired publish ran green, and the published `Cyclone-5.0.0-alpha.111.dev1.apk` SHA-256 matches
  `release-manifest.json` (`b6e089db…40eb1c`). Glass unchanged (1.0.0-alpha.61).
- **Branches:** work is on the feature branch **`claude/cyclone-ui-updates-emnerc`**; the dev/publish branch is
  **`claude/cyclone-v5-handoff-review-9qrs40`** (fast release lane — pushing a `release/version.toml` bump there
  triggers `.github/workflows/v5-publish.yml`). Both are at `8e15381a`.
- **Built so far (runs 1–4 of plan 52):** speed-curved/shaped swipes, finger-roll on taps, swipe-by-intent with a
  hand model, touch-first presses, thumb scrolling, the keyboard-on-screen fix + key-by-key typing, human pauses, the
  Settings › Hands control, and the `hands` capability block. Details below.
- **Still to do (runs 5–7):** owner-taught templates, drag/pinch/double-tap/draw, and physical-device proof +
  flipping the defaults. Details below.
- **Physical phone: UNVERIFIED.** Nothing here has run on a real Pixel. Precise style reproduces the old behaviour, so
  it's the safe fallback if anything misbehaves on a device.

## What was built (runs 1–4) and where it lives

All new Kotlin is pure/testable where it can be; Android calls are isolated in the dispatch/service layer.

- **`gesture/Hands.kt`** — process-wide `Hands` (style, handedness, typos, and two runtime-support flags
  `segmentedStrokesSupported` / `keystrokesSupported`). `HandsStyle` = PRECISE / NATURAL / RELAXED; `.natural` means
  NATURAL or RELAXED. In-process default is **PRECISE** until the stored choice loads; the stored default is NATURAL.
- **`gesture/HandsSettings.kt`** — reads/writes the `cyclone_hands` prefs and applies them to `Hands`. Applied in
  `CycloneAccessibilityService.onServiceConnected()` and on every settings change.
- **`gesture/HumanMotion.kt`** — the motion engine: minimum-jerk / late-peak-flick / hold velocity curves, shape
  families (thumb arc, bow, S-curve, overshoot), and `liftPoint` for finger-roll. Produces a `MotionPlan` of timed
  points. Pure and seeded.
- **`gesture/HandModel.kt`** — `SwipeIntent` (direction / amount / speed / region) → `PlannedSwipe` via a model of
  where the thumb rests for the holding hand, clear of system gesture edges. Also `SwipeIntents.resolve(...)` for the
  tool params, and `FixedGestureRng` so Precise style stays identical every time.
- **`gesture/TouchFirst.kt`** — `TouchFirst.decide(...)` (press a visible/uncovered/clickable target with a finger,
  else keep the semantic click) and `NaturalScroll.plan(...)` (thumb-scroll a big upright list). Both choose one
  channel up front so "Unchanged is not a second click" holds.
- **`gesture/Pacing.kt`** — bounded human pause before a Mind action; already-elapsed time counts toward it.
- **`gesture/typing/KeystrokePlanner.kt`** — the per-key rhythm (letter-pair gaps, bursts, symbol-layer switches,
  hesitations, optional corrected typos), capped in total time. `replay()` reconstructs the text for tests.
- **`HumanGestureDispatch.kt`** — `naturalSwipe` plays a `MotionPlan` as chained `continueStroke` pieces, falling back
  to one stroke (and flipping `Hands.segmentedStrokesSupported`) if a phone drops a piece; `naturalPressPath` adds the
  roll; trace carries `shape/ending/pieces/segmented`.
- **`CycloneAccessibilityService.kt`** — `raiseKeyboard` (touch the field so the app opens the IME; wait for a
  `TYPE_INPUT_METHOD` window) and `typeKeys` (Android 13 `InputMethod.AccessibilityInputConnection.commitText` per
  key); `flagInputMethodEditor` added to `res/xml/accessibility_service_config.xml`.
- **`PhoneTypeEngine.kt`** — typing ladder is now **keys → set_text → paste**, with read-back deciding success and an
  owner takeover aborting. Secrets (`redactObservedText`) keep single set-text; typed characters never reach results,
  traces or diagnostics (only length + method + `keyboard: shown/not_shown`).
- **`PhoneToolExecutor.kt`** — `phone.swipe` resolves an intent through the hand model (foreground and background),
  `phone.scroll` uses `NaturalScroll` first, approval check classifies the resolved start point.
- **`mind/PhoneMindToolbox.kt`, `mind/modes/AndroidInstantHands.kt`** — Mind + Instant swipes now send intents;
  `humanPause()` runs before each Mind action.
- **`PhoneToolRegistry.kt`** — `phone.swipe` publishes the intent params.
- **`gesture/HumanGestureRuntimeCapabilities.kt`** + gateway **`capabilities/human_gesture.py`** / **`models.py`** —
  phone reports a `hands` block; gateway projects it in a fixed vocabulary (`HumanGestureDiscovery.hands`).

Tests (all green locally and on CI): `HumanMotionTest`, `HandModelTest`, `TouchFirstTest`,
`gesture/typing/KeystrokePlannerTest`, `PhoneTypeEngineKeysTest`, updated `HumanGestureSemanticSafetyContractTest`
and `mind/PhoneMindToolboxTest`, plus two new gateway projection tests in `test_human_gesture_transport_v1.py`.

## What's left (runs 5–7 of plan 52)

5. **"Teach Cyclone my hands"** — a short practice page in Settings › Hands that records only the owner's own touches
   and typing on Cyclone's own screens, stores templates (start-to-end `s,n,tau` frame + timing distributions) locally
   and encrypted, and feeds the engine. Build Templates V1.5 for real (`docs/HUMAN_GESTURE_TEMPLATES_V1_5.md`).
6. **Drag, pinch, double tap, draw** — new typed tools `phone.drag`, `phone.pinch`, `phone.double_tap`, `phone.draw`
   (draw only inside a grounded canvas); a swipe `style` option; capabilities report each per display. The engine
   plans them; none takes raw PC-authored point arrays (contract unchanged).
7. **Device proof + defaults** — run the matrix on a real Pixel (do chained strokes play smoothly? keyboard across
   Chrome / native / WebView / Compose? finger presses in real apps?), then flip the remaining `humanize=off` pins on
   the Instagram stock skills / V33 adapter where the smoke test passes, and finish the doc updates. Until this run,
   every on-device claim stays UNVERIFIED.

## Owner decisions still open

1. Should Natural stay the default once it's verified on a phone, or revert to opt-in? (Shipped as default now.)
2. Typos default — currently off. Keep off?
3. Background-task pace — same as foreground, or force Precise in the background?

## How we work (so the next agent matches the house style)

- **Read first:** `AGENTS.md`, then `Cyclone V5 plan/52-human-hands.md`, then the owning file's nearest tests.
- **No Android SDK in this environment.** Validate Kotlin by compiling the touched pure files + their tests with the
  local Kotlin harness at `/tmp/claude-0/.../scratchpad/kt/kt.sh` (SplitMix/JUnit/json on the classpath), and
  type-check Android-API code against the Robolectric `android-all` jar in `scratchpad/kt/aa/`. The real build +
  instrumented-free unit tests run only in **Mobile CI**; treat CI as the compile authority and keep changes
  CI-provable, not "looks right."
- **Gateway/Python:** use the project venv at `scratchpad/venv/bin/python`; run `apps/device-gateway/tests`,
  `scripts/ci/tests`, and the release guards (`scripts/ci/release_versions.py --check`,
  `scripts/ci/mobile_product_guard.py`).
- **Privacy invariants (never break):** no passwords / OTPs / API keys / payment data / raw typed values in Brain,
  learning stores, diagnostics or traces. Typed text stays length-and-method only. Learn gestures only from the
  owner's touches on Cyclone's own surfaces, never from other apps.
- **Keep one phone-mutation authority:** everything still goes through `PhoneToolExecutor`; the PC/MCP never sends raw
  gesture paths; `phone.swipe` has no MCP route.
- **State physical verification honestly** as UNVERIFIED until a real device run happens.

### Release flow (fast lane)

1. Land code on the feature branch; also push it to the dev branch so Mobile CI builds it on real Android.
2. When CI is green on that exact commit, bump `release/version.toml` (`product_version`, `components.mobile`,
   `android_version_code`, and `python_version` + `device_gateway` + `mcp` + the three `pyproject.toml` only if PC
   code changed; `components.glass` only if `apps/glass` changed), bump `apps/mobile/app/build.gradle.kts`
   (`versionName` + `versionCode`), add `docs/RELEASE_<mobile>.md`, run the two release guards, and push the bump to
   the dev branch — that triggers `v5-publish.yml` (Windows smoke + waits for Mobile CI + signs + publishes with a
   SHA-256 manifest).
3. Do not push other commits to the dev branch while a publish is in flight (Mobile CI cancels in-progress runs per
   branch).
4. Verify after publish: the release's APK SHA-256 == `release-manifest.json`.
5. Next free number: alpha.111 is taken by this work; note PR #208 ("Cyber") also claimed alpha.111 and will need a
   new number when merged — reconcile before the next bump.
6. No model IDs in any commit, PR, code comment or pushed artifact. Attribution footer only in chat/GitHub posts.

## Not in scope (closed, do not reopen)

A tangent earlier explored rooted / `uinput` kernel-level touch injection to make input look like a hardware
digitizer. **It is out of scope and must stay out.** It violates `AGENTS.md` ("do not expose generic shell/root
control to the model"), breaks Play Integrity, and its only real effect is concealment of automation rather than
better motion — which is not what Human Hands is for. If the topic comes back, the only acceptable sliver is an
honest, consent/capability-gated fidelity backend for the background/private-display fallback (where the non-root path
degrades to a straight `input -d` swipe), replaying the same `MotionPlan` and still reporting its true backend — and
even that is optional, not part of plan 52's runs.
