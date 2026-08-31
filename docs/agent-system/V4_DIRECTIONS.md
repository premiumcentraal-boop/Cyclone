# Cyclone V4 Directions

**Status:** product direction for V4  
**Date:** 30 August 2026  
**Running product:** Cyclone 3.6.0

## Direction

Ship the consumer face Google previewed (stay in chat, background UI automation, live view, take control) on **any Android**, with **any PC AI**, and with **skills the user keeps**.

Gemini will own OEM-blessed tasks on new Pixels. Mobilerun will own tap benchmarks. Tasker will own dumb macros. Cyclone owns the compiler:

> learn on the messy real UI → freeze a verifier → run on this phone or the next one → model stays quiet.

## Why now

PC AIs still get lost when compact observation drops `pageText` or caps an arbitrary control list, and when `phone_act` does not return after-state. That is an agent-context bug, not a missing emulator.

## What V4 is

- Overlay super-app chrome on the existing Accessibility service.
- Page card as the default observation.
- Self-verifying acts.
- Skill compile into the existing store.
- Four-tool MCP + official `SKILL.md`.
- Teacher / worker fleet using one `device_id`.

## What V4 is not

- A seventh tab or second Companion.
- A second executor, Brain, or routine runtime.
- Hermes / cyclone-core as a dependency for phone learning.
- An AVD-first rewrite.
- 50 MCP tools.
- CodeAct (“model writes Python that drives the phone”).
- Copying AGPL Portal into `com.cyclone.mobile`.
- A `4.0.0` APK before slices 1–4 are green on Pixel.

## Default PC loop

```text
phone_status
phone_locate(goal)          # page card + ranked hits; skill short-circuit
phone_act                   # after-state + delta required
phone_skill_save | run
```

If a verified skill matches goal + pageKey, zero model calls.

## First vertical (must ship before farm chrome)

Pixel 8, one story:

1. Messages (or any chat) names a food order.
2. Analysis lists items.
3. User taps **Order this from**.
4. Working while the shop opens.
5. Live view of the cart.
6. GATE before pay.
7. Draft skill in Automations.

If that path still requires the model to write an essay, fix locate + act + skill before any new UI surface.
