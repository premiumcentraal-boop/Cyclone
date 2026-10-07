# 49 · Human Hands: moving and typing like the owner

Status: **plan (2026-10-07, at alpha.98 + Ports run 4 WIP)**. Nothing in this plan is built yet. It picks up the
Human Gesture project (V0.3, shipped in 4.7.6–4.7.8) and finishes it.

## 0. Why

Cyclone acts on the owner's own phone, in the owner's own accounts, because the owner asked it to. Today its hands
are rigid:

- every tap lands near the middle of the target;
- every swipe starts at the same spot, at a constant speed;
- every text field is filled in one frame (set-text) or by paste.

Rigid motion has two costs:

- It looks like a script, and apps that score "is this a bot?" pick up scripted motion.
- The Mind can only ask for a few fixed motions: no drags, no pinches, no shapes.

The goal is a smart assistant whose hands move like the owner's: varied, fluid, quick and still exact.

### What this plan does and does not do

It does:

- make motion and typing natural, varied and measurable;
- learn the owner's own style on a Cyclone practice page;
- give the Mind a richer, typed set of motions.

It does not:

- hide that Cyclone uses an Accessibility service;
- spoof the device, its fingerprint or its sensors;
- solve CAPTCHAs;
- run several accounts on one app;
- raise send rates or bulk-messaging limits.

Two honest limits apply:

- **Injected input stays detectable.** Android marks touches and key events injected through Accessibility, and an
  app that checks the raw event can see that. We do not try to hide it.
- **No guarantee against blocks.** Natural motion makes Cyclone look less like a crude bot, but it cannot promise an
  account will never be flagged, and each app's terms still apply.

Approval boundaries are unchanged: pay, send, delete, permissions and authentication still ask the owner.

## 1. What is built (verified in the code)

| Piece | Where | What it does today |
| --- | --- | --- |
| Planner | `gesture/HumanGestureEngine.kt`, `GestureModel.kt`, `GestureRng.kt` | **Taps:** a point inside the target, biased toward its centre, with a 35–220 ms press. **Swipes:** one cubic Bézier with a single bow to one side (LIGHT ≤ 8 px, NORMAL ≤ 42 px) that stays on screen. Seeded with SplitMix64, so a run can be replayed. |
| Profiles | `gesture/RuntimeHumanization.kt` | Callers ask for `humanize = auto / off / light / normal`; anything else fails closed. AUTO uses LIGHT for taps and long presses, NORMAL for swipes and scrolls. The seed comes from the command id, an ordinal and the geometry. |
| Dispatch | `HumanGestureDispatch.kt`, `gesture/AndroidGestureRenderer.kt` | Sends **one** `StrokeDescription(path, 0, duration)`, waits for `onCompleted`, and lets Cyclone's own overlay pass the touch through. Background named displays use `setDisplayId`; if Android never queued the gesture, the helper's straight `input -d` is the fallback. |
| Executor wiring | `PhoneToolExecutor.kt`, `CycloneAccessibilityService.kt` | `phone.tap`, `phone.swipe`, `long_press`, the click fallback and the scroll fallback go through Human Gesture. Results carry a bounded `humanGesture` evidence object. |
| Capability truth | `gesture/HumanGestureRuntimeCapabilities.kt`, gateway `capabilities/human_gesture.py` | The phone reports what each display can do; the gateway normalises it. `phone.drag` is reported unsupported. |
| Diagnostics | `gesture/diagnostics/*`, `docs/HUMAN_GESTURE_DIAGNOSTICS_V1.md` | Trace v1 (`u, v, t`), a canonical trace hash, no text and no secrets. |
| Lab | `tools/human-gesture-lab/` | Stdlib-only analysis: path/chord ratio, deviation, peak-velocity position, fuzz and benchmark. `evidence_compare.py` keeps synthetic, core and device evidence apart. |
| Device harness | `src/debug/.../HumanGestureTestActivity.kt`, `android_v03_device_harness.sh` | A debug-only page of tap, long-press and scroll targets. **Never run on a phone (UNVERIFIED).** |
| Tests | 10 Kotlin test files (about 38 tests), plus 2 lab and 2 gateway/MCP test files | Bounds, determinism, fuzz, performance, safety ordering and contract shape. |
| Design only | `docs/HUMAN_GESTURE_TEMPLATES_V1_5.md` | Recorded templates in a start-to-end frame (`s, n, tau`). **Not built.** |

## 2. What is not built (the gaps, in order of impact)

| # | Gap | Evidence in the code | Effect |
| --- | --- | --- | --- |
| G1 | **Constant speed.** Each gesture is one stroke with one duration, and Android plays it back at an even pace along the path. | `HumanGestureDispatch.description()` | Real fingers speed up and slow down: a smooth bell-shaped speed curve. A flick leaves the glass still moving; a drag slows before lifting. Even speed is the clearest scripted signature, and the curve does not fix it. |
| G2 | **The callers are rigid.** The Mind swipe always starts at the exact centre of the area, travels 30 % or 60 % of it, and takes 350 ms. The scroll fallback always runs 75 % → 25 % on the centre line. Instant does the same. | `PhoneMindToolbox.kt:1300–1314`, `PhoneToolExecutor.kt:353, 888`, `AndroidInstantHands.kt:70` | The start, the end and the duration repeat every time. The engine only moves them a few pixels. |
| G3 | **One shape.** A single bow to one side. No thumb arc (a thumb pivots at the base of the hand), no S-curve, no small overshoot and pull-back, no tremor, no settle or drift between finger-down and finger-up. | `HumanGestureEngine.planSwipe` | Every swipe looks the same. |
| G4 | **Most clicks are not touches.** `phone.click` uses `ACTION_CLICK` first, so the app gets a click with no finger on the glass. This is the most common action Cyclone takes. | `CycloneAccessibilityService.clickResolved` | An app that tracks touches sees clicks that no touch produced. |
| G5 | **Typing is instant.** `phone.type` sets the whole value in one frame, then falls back to paste. Text over 4 000 characters goes straight to paste. No key-by-key entry exists. | `PhoneTypeEngine.kt`, `CycloneAccessibilityService` set-text and paste | A username appears in a single frame or by paste, every time. This is the marker the owner named. |
| G6 | **No rhythm between actions.** After the Fast Path settle (300 ms, +500/+1000) the next action follows at once. There is no time to read or find the target. | Fast Path, Mind loop | Sessions run at machine tempo. |
| G7 | **Missing motions.** No drag, no pinch or zoom (two fingers), no double tap with human spacing, no drawing, no custom shapes. | Capability: `phone.drag` unsupported | The Mind cannot rearrange, zoom maps or draw. |
| G8 | **No real human data.** Every tuning constant is a guess. Templates (V1.5) and device capture were never built. | Calibration doc: "synthetic is not human" | No proof that anything looks human. |
| G9 | **Stale truth.** The runtime doc still says named displays are "endpoint + duration only", but since `0c3a34b3` they use cubic `setDisplayId`. Instagram stock skills and the V33 gateway adapter pin `humanize=off` until a Pixel smoke test that never happened. | `docs/HUMAN_GESTURE_RUNTIME_V03.md`, `Instagram*StockSkill.kt`, `GatewayV33ActionAdapter.kt:679` | The docs and the defaults disagree with the code. |

## 3. Strategy: the rules every run follows

1. **Exact first, natural second.**
   - Variation never makes a tap miss: the landing point stays inside the target's safe area.
   - Typing always ends with the exact value, checked by read-back.
   - A swipe that must reach a spot reaches it.
   - If natural motion cannot be safe, Cyclone falls back to precise motion and says so in the evidence.
2. **Measured, not random.**
   - Human motion is not white noise. It follows known laws:
     - smooth speed curves (minimum jerk);
     - harder targets take longer to hit (Fitts's law);
     - the thumb sweeps in arcs;
     - key-to-key timing depends on the letter pair.
   - Cyclone generates from those models, then tunes them to the owner's own data (Run 5).
3. **Variety from structure, not jitter.** The big variation comes from:
   - where a swipe starts;
   - how far and how fast it goes;
   - which hand and grip are in use;
   - pauses.

   Small pixel noise on top adds little.
4. **The phone owns the motion.** The Mind and the PC ask for typed *intents*, such as "swipe up a page in the feed"
   or "type into this field". Android picks the path, timing and keys. Raw point arrays from the PC stay forbidden
   (contract V1). The one exception is drawing inside a grounded canvas (Run 6).
5. **Physics unchanged.**
   - GATE, ownership, fresh observation, duplicate suppression, one screen-changing action per turn and the Fast Path
     settle all stay.
   - Unchanged is never a second click.
   - Every new motion still runs inside `PhoneToolExecutor`.
6. **Privacy.**
   - Templates and typing rhythms store only geometry and timing: no app, no text, no keys pressed.
   - Cyclone learns from the owner's touches only on Cyclone's own practice pages. It never records touches or keys
     in other apps.
   - Typed values never enter diagnostics, Brain or traces. Secret fields keep their own path.
7. **The owner chooses the speed.** The **Hands** setting has three styles:

   | Style | Motion | Typing | Pace |
   | --- | --- | --- | --- |
   | **Precise** | Today's behaviour | Today's behaviour | Today's behaviour |
   | **Natural** (default after Run 7 verifies) | Natural | Key by key | Short human pauses |
   | **Relaxed** | Natural | Key by key | Longer reading pauses |

   Background runs follow the same setting.

## 4. The build: 7 runs, each a releasable alpha

### Run 1: motion physics (engine v2)

The core fix: the speed curve and real shapes.

**Speed curves.**
- The renderer cuts a planned stroke into 8–24 chained segments using `StrokeDescription.continueStroke(…, willContinue = true)`.
- Each segment's duration follows the speed curve:
  - minimum-jerk for drags and precise swipes (slow start, fast middle, slow end);
  - an asymmetric curve for flicks that still peaks near the end, so the finger lifts while moving and the list
    keeps scrolling;
  - a drag-and-hold ending (slows to nearly zero and lingers 40–120 ms before lifting) for "move it exactly here".
- Fallback: if a phone rejects chained strokes, Cyclone uses one stroke and records `segmented=false` in the evidence.
- To confirm on a device: how often Android samples each stroke, and whether 16 segments are enough.

**Shape families**, chosen per gesture:

| Shape | What it is |
| --- | --- |
| Thumb arc | A circular arc around a pivot below and to the side of the screen; the radius depends on hand size |
| Single bow | Today's shape |
| Gentle S | Two bows with opposite curvature, for long diagonal swipes |
| Overshoot and correct | Overshoots by 2–6 % and returns; drags only, never flicks |

**Small details.**
- Taps get 0–3 px of drift between finger-down and finger-up, plus a few milliseconds of settle.
- Long presses get a slow drift under 2 px.
- A stroke's start can sit a few pixels off its declared start (a finger lands imprecisely), but always inside a
  safe zone.

**Other changes.**
- Engine v2 identity: `synthesisVersion = 2`. The trace adapter emits segment timing, and the lab analyser reads
  speed curves (peak-velocity position, jerk, lift-off velocity).
- Tests: progress only moves forward; segment durations add up to the total; every point stays on screen; taps hit
  their targets 100 % over 50 000 fuzz cases; flicks peak late and drags peak mid-stroke; same seed, same output.
- Device capture: the debug harness records the `MotionEvent`s it receives into trace v1 with
  `source=device_capture`, so we can prove the speed curve really arrives.

**Files:** `gesture/*`, `HumanGestureDispatch.kt`, `gesture/diagnostics/*`, `tools/human-gesture-lab/*` and the debug
harness.

### Run 2: intent-level gestures and touch-first taps

Remove the rigid geometry from the callers.

**New request shape.** `phone.swipe` also accepts `{direction, amount: peek | half | page | far, region?: elementId,
speed?: gentle | normal | flick}`. The phone chooses the start, the end and the duration with a **hand model**:

- handedness and grip (one-handed thumb or two-handed), chosen per owner and drifting a little per session;
- start points from where that thumb naturally rests in the region;
- distance and speed varied around the chosen amount.

The old `x1..y2` form stays for compatibility.

**Callers switched to intents:**
- the Mind's `swipe` and scroll-to-find;
- Instant swipe and scroll;
- the scroll fallback (no more fixed 75 % → 25 %);
- the Background Check probe.

**Scrolling that reads like reading.**
- Successive scrolls vary in distance and speed.
- A long hunt mixes flicks and controlled drags.
- Scrolls stop where content lines up. Semantic scroll stays first; it is invisible to apps and always safe.

**Touch-first taps (G4).** In Natural style, `phone.click` on a visible, uncovered, large-enough element taps its
bounds with Human Gesture. Otherwise it uses `ACTION_CLICK` as today.
- Cyclone picks one channel *before* acting, so the "Unchanged is not a second click" rule holds.
- An element counts as covered when the observation shows another window or node over its bounds.
- Each case is decided before the action, never retried with the other channel.

**Tests:**
- 1 000 swipes in the same area have spread-out starts, ends and durations, and none leaves the area.
- The touch-first choice is one channel per action.
- A source-order guard keeps GATE ahead of dispatch.

### Run 3: key-by-key typing

Fix G5. Text goes in as keystrokes, the way a keyboard sends it.

**The route.**
- Android 13+ lets an Accessibility service act as an input method on the focused field: set
  `flagInputMethodEditor` and use `AccessibilityService.getInputMethod().getCurrentInputConnection()`.
- Cyclone sends `commitText` per character or per short burst, `sendKeyEvent` for Enter and Backspace, and
  `setSelection` to place the cursor.
- The owner's keyboard stays on screen and stays their keyboard; nothing has to be switched. The app's own
  `minSdk` is 33, so every supported phone has this.

**Timing model.**
- Inter-key gaps come from the letter pair: easy pairs fast, same-finger and shift pairs slower.
- Bursts of 3–7 characters, short pauses at word boundaries, and a longer pause before the `@` and `.` in emails.
- "Fast typing" targets about 7–10 characters per second, like a quick thumb typist; Relaxed is slower.
- A cap bounds how long one field can take.

**Typos (opt-in, off by default).**
- Typos are rare and adjacent-key only.
- Each one is corrected at once with Backspace.
- Never in usernames, emails, codes, URLs or numbers.
- The final value must match exactly.

**Delivery ladder.** Keystrokes, then read-back. If the field does not hold the exact value: set-text, read back,
then paste (today's ladder). The result reports `method: keys | set_text | paste`, and a value that could not be
verified is reported as unverified.

**Long text.**
- Up to 300 characters is typed.
- Longer drafts are typed sentence by sentence in Relaxed style. Otherwise Cyclone types the first sentence and
  pastes the rest, as people do.
- 4 000+ characters stays paste.

**Secrets stay as they are.**
- Passwords and Vault fills keep the single-step set-text: that is how password managers and Android Autofill fill
  fields, so it is the natural behaviour for a secret.
- Port codes (OTP) may use keys, since people type codes by hand. The Vault and port-code path keeps its separate,
  non-wire boundary (`fillVaultSecretOnce`).
- Typed characters never reach evidence, traces or diagnostics: only the length and the method.

**Fallbacks.** WebView or Compose fields that ignore an Accessibility input connection, or a phone below API 33, fall
back down the ladder. The capability reports `keystrokeTyping: available | unavailable`.

**Tests:**
- The timing model's distributions and caps.
- A fake input connection receives the exact value.
- Typo correction always converges.
- The redaction guard: no character of the value appears in any payload.
- The secret path is unchanged.

**Files:** `PhoneTypeEngine.kt`, a new `gesture/typing/*`, `CycloneAccessibilityService` and the Accessibility
config XML (`flagInputMethodEditor`).

### Run 4: rhythm between actions

Fix G6 without making Cyclone slow.

- **A pacing layer** in the Mind and Instant hands, not in `PhoneToolExecutor`; the executor stays fast and
  authoritative. Before the next action it waits:
  - reaction time;
  - plus reading time scaled by how much new text the page shows;
  - plus a target-finding term from Fitts's law (far or small targets take longer).

  Precise adds 0 ms. Natural adds about 150–700 ms. Relaxed adds about 400–1 500 ms.
- **What does not get paused.** The time already spent waiting for the model and the Fast Path settle counts toward
  the pause, so a slow model adds no extra wait. Approvals and the owner's own steps never get artificial delay.
- **Session variety.** Small shifts in tempo over a long run, and a pause before irreversible steps (the confirm the
  owner already approved).
- **Tests:** pacing is bounded and never runs during an owner hand-over; a stopped task ends its pause at once.

### Run 5: "Teach Cyclone my hands" (owner templates)

Fix G8 and build Templates V1.5 for real.

**A practice page in Settings › Hands:**
- about a minute of swiping (feed-style, page-style and flicks);
- tapping targets of different sizes;
- typing a few given sentences, which are not saved.

The page records only the touches it receives itself, and stores templates in the start-to-end frame (`s, n, tau`)
plus timing distributions:
- tap press length;
- swipe speed curve;
- the owner's thumb zone;
- inter-key gaps per letter-pair class.

**Storage.** Local and encrypted, never sent to the PC, never in Brain. Removing it is one tap, and it is included in
profile backups only if the owner says so.

**The engine uses the owner's templates.**
- Picking a template is deterministic.
- It warps to the request.
- If a template would leave the safe area, Cyclone picks another or falls back to procedural motion. It never
  clamps the points flat.
- Typing and pacing models take the owner's distributions.

**Glass** shows a small "Hands" card: style, whether templates are learned, and device-capture comparisons from the
lab (shape statistics only).

**Tests:** template quality gates (from the V1.5 doc), warping stays on the chord, no text or app in the store, and a
replay with the same seed matches.

### Run 6: drag, pinch, double tap and shapes

Fix G7.

**New typed tools.** The engine plans them all; they all pass GATE; none takes raw points.

| Tool | What it does |
| --- | --- |
| `phone.drag {fromElementId, toElementId \| direction+amount, hold?}` | Long-press pickup, a drag with a slowing end, a hold, then release |
| `phone.pinch {elementId, scale, center?}` | Two strokes in one `GestureDescription`, with slightly different finger speeds and a small rotation |
| `phone.double_tap {elementId}` | Two taps 90–180 ms apart, the second a few pixels from the first |
| `phone.draw {canvasElementId, shape \| strokes}` | Only inside a grounded drawing or signature canvas. Either a named shape (`circle`, `check`, `underline`, `zigzag`, `scribble`, `signature-style`) or at most 4 normalised polylines of 64 points each inside that canvas. This is the one place a caller describes a path, and it is limited to the canvas. |
| Swipe `style` | Lets the Mind ask for variety in plain terms: `arc`, `straight-ish` or `s-curve` |

**Capabilities** report each tool truthfully per display. On background displays, pinch is reported unavailable until
a device proves it works.

**Mind tools:** `drag`, `zoom`, `double_tap` and `draw`. Instant grammar adds "zoom in/out" and "drag X to Y".

### Run 7: device proof, defaults and docs

Fix G9 and turn Natural on.

**Pixel device matrix**, using the harness, real apps (feed, maps, a form) and device capture:
- speed curves arrive as planned;
- taps hit every target;
- keystrokes land in Chrome, a native form, a WebView form and a Compose field;
- background displays behave the same;
- typing and swipe timing against the owner's templates.

**Then:**
- Make Natural the default style.
- Remove the `humanize=off` pins (Instagram stock skills, the V33 adapter) where the smoke test passes; record any
  that stay pinned and why.
- Bring the docs up to date: the runtime doc, the contract (named displays are cubic, new tools, keystroke typing)
  and a release note.

Until this run happens, every device claim stays **UNVERIFIED**.

## 5. Order, size and versions

| Run | Main paths | Size | Depends on |
| --- | --- | --- | --- |
| 1 Motion physics | `gesture/*`, dispatch, lab | M | — |
| 2 Intents + touch-first | the Mind and Instant hands, executor, registry | M | 1 |
| 3 Key-by-key typing | `PhoneTypeEngine`, new `gesture/typing/*`, Accessibility config | M–L | — (parallel with 1–2) |
| 4 Rhythm | Mind loop, Instant hands, settings | S | 2 |
| 5 My hands | settings page, template store, engine | L | 1, 3 |
| 6 Drag, pinch, draw | registry, executor, engine, gateway capability, Mind tools | M | 1 |
| 7 Device proof | harness, docs, defaults | S (needs a phone) | all |

- Each run ships as one alpha through the fast release lane: Android only, plus gateway and MCP when the capability
  or tool contract changes.
- The next free number is after the pending Ports alpha.99, so Run 1 is alpha.100 (versionCode 245) unless Ports
  run 4 is shelved.
- Runs 1 and 3 touch different files and can be built by two agents in parallel.

## 6. Risks and open questions

- **Segment sampling.** Android re-samples each stroke itself. Run 1 must measure on a device whether 8–24 chained
  segments give a smooth speed curve or visible steps.
- **Accessibility input-method coverage.** Some fields (custom keyboards in games, certain WebViews) will not accept
  it. The ladder covers them, and the result says which method was used.
- **Speed vs naturalness.** Natural typing and pacing add seconds per task. The Precise style keeps today's speed,
  and the Fast mode (plan 41) can choose Precise automatically.
- **Touch-first taps can miss** where semantic clicks would not (overlaps, animations). The pre-action coverage check
  and device data decide when touch-first is allowed.
- **Owner decisions needed:**
  1. Natural as the default once verified, or opt-in?
  2. Typos on or off by default (proposed: off)?
  3. Pace for background runs: the same as foreground, or Precise?
