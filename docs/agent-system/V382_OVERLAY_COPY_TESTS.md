# Cyclone 3.8.2 Agent E — overlay copy and task-entry tests

Lane: overlay chrome copy + state machine. Agent C owns `phone.type`; these tests do not touch `PhoneToolExecutor` typing internals. They assume the Phone task field can receive text after Agent C lands.

## What is covered

| Test class | Contract |
|---|---|
| `OverlayCopyTest` | Frozen bible strings and never-say list (pre-existing). |
| `OverlayTaskEntryFlowTest` | Once a task string is accepted, overlay walks **IDLE → ANALYSIS → WORKING → LIVE → DONE** and asserts exact copy at each state. Commerce CTA uses **Order this from**. |
| `OverlayGateInterruptTest` | **GATE** for pay / send / delete / grant. **Stop task** / **Take control** pause Cyclone only (`clicksHost=false`, no Accessibility dispatch). |
| `OverlayPhysicalFixturesTest` | Safe Messages, Phone keypad, Chrome SERP, and cart/pay fixture notes. No private phone data. |
| `OverlayChromeMachineTest` | Pre-existing six-state machine and event JSON. |

## Exact strings under test

- Analysis
- Task automation
- I'm on it. I'll let you know when this is ready to complete. You can leave this screen.
- Working on this task
- View progress
- Do this
- Order this from
- Stop task
- Take control
- Ask Cyclone
- Cyclone needs you to confirm before finishing this.
- Saved as a draft skill. Review it in Automations before it can run alone.

Never say: “Let me think about the best approach”, “I noticed several possible buttons”, “Here is my plan in eight steps”, “I have placed the order”.

## How to run

From `apps/mobile`:

```text
./gradlew testDebugUnitTest --tests com.cyclone.mobile.ui.overlay.*
```

Focused Agent E classes:

```text
./gradlew testDebugUnitTest --tests com.cyclone.mobile.ui.overlay.OverlayTaskEntryFlowTest
./gradlew testDebugUnitTest --tests com.cyclone.mobile.ui.overlay.OverlayGateInterruptTest
./gradlew testDebugUnitTest --tests com.cyclone.mobile.ui.overlay.OverlayGateClassWireTest
./gradlew testDebugUnitTest --tests com.cyclone.mobile.ui.overlay.OverlayPhysicalFixturesTest
./gradlew testDebugUnitTest --tests com.cyclone.mobile.ui.overlay.OverlayCopyTest
```

CI green is not Pixel VERIFIED. Physical rows live in `V382_PIXEL_OVERLAY_ACCEPTANCE.md`.

## Interrupt rule

Stop task and Take control call `OverlayCycloneStateEffects.pauseAgentForUser()` and emit overlay events with `clicksHost=false` and `dispatchAccessibilityAction=false`. Host taps stay on `PhoneToolExecutor`. Overlay chrome is `TYPE_ACCESSIBILITY_OVERLAY` (`FLAG_NOT_TOUCH_MODAL`); chrome buttons are Cyclone-only.

## Isolated run (Agent E box, 2 Sep 2026 20:52 CEST)

Compiled overlay JVM sources + Agent E tests with kotlinc 2.0.21 + JUnit 4.13.2 (no repo clone, no Android runtime).

```text
OK (16 tests)
```

| Class | Tests |
|---|---|
| OverlayTaskEntryFlowTest | 4 |
| OverlayGateInterruptTest | 5 |
| OverlayGateClassWireTest | 4 (pay/send/delete/grant) |
| OverlayPhysicalFixturesTest | 3 |

Physical Pixel T0–T6 remain UNVERIFIED. T6 is UNVERIFIED by design (no charge-free cart).
