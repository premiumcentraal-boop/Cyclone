# Cyclone V4 Directions

**Status:** product direction for V4  
**Date:** 30 August 2026

## Direction

Ship the consumer face Google just previewed (stay in chat, background UI automation, live view, take control) on **any Android**, with **any PC AI**, and with **skills the user keeps**.

Gemini will own OEM-blessed tasks on new Pixels. Mobilerun will own tap benchmarks. Tasker will own dumb macros. Cyclone owns the compiler:

> learn on the messy real UI → freeze a verifier → run on this phone or the next one → model stays quiet.

## Why now

Codex still gets lost because compact observation drops `pageText` and caps ~12 controls, and `phone_act` does not return after-state. That is an agent-context bug, not a missing emulator.

Meanwhile the expected consumer language is already public on developer.android.com: Analysis card, Working card, Live view, Stop task, Take control, alert before purchase.

V4 is the join of those two facts.

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

## Experience principles

1. **Stay.** The user does not have to open Cyclone to start a job. The overlay comes to them.
2. **One tap to start.** Analysis then Confirm. No eight-step plan in prose.
3. **Leave if you want.** Working state is honest. Live view is optional.
4. **Interrupt is first-class.** Stop task and Take control are always visible while driving.
5. **Sensitive work stops.** Pay, send, delete, grant need GATE on the phone.
6. **Remember quietly.** Two verified steps become a draft skill. Review in Automations.
7. **Same store.** Follow Me, overlay, and PC AI all write AutomationStore / Brain memory.

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

## Competitive posture

| Player | They win at | We do not try to beat them by |
|---|---|---|
| Gemini on Pixel 10 / S26 | OEM convenience | Waiting for AppFunctions |
| Mobilerun / Droidrun | First-run taps, AndroidWorld | Replacing our executor |
| phone-harness | 5-minute Codex USB | Making ADB the product |
| android-remote-control-mcp | Token-tight trees | A second MCP server on the phone |
| CellClaw | On-phone chat agent | Moving the brain onto the device |

We take their **structures** (skill file, page cursor, app cards, manager/executor, placeholder redaction, driving banner) and keep our **OS** (policy, memory seam, farm, overlay).

## Success looks like

A user starts a task from Messages. Cyclone works in the background. The user takes control before pay. Tomorrow the same job runs as a skill on a second phone without Codex rediscovering the shop.
