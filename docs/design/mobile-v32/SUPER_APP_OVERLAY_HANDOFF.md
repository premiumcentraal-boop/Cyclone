# Cyclone Super App Overlay — Agent Handoff

**Status:** steering document for overlay + skill OS  
**Date:** 30 August 2026  
**Read after:** root `AGENTS.md`, `docs/agent-system/FAST_WORK_AND_TOKEN_PLAYBOOK.md`, `docs/agent-system/V4_BUILD_BIBLE.md`  
**Does not outrank:** one `PhoneToolExecutor`, one policy authority, one memory write seam

## Thesis

Stay in the conversation. Let Cyclone finish the job.

Google’s Gemini UI automation shows the language users will expect: an Analysis card over the current app, one confirm, background work, live view, **Stop task** and **Take control**, a second gate before purchase.

Cyclone copies that interaction. It does not copy the lock-in. Cyclone is the super app if any model can drive any Android, and a verified skill can run on the next phone without the model debating the screen again.

If a change does not make `Analysis → Confirm → Working → Live view → Skill` cheaper, do not ship it.

## Overlay states (one Compose overlay, not five screens)

| State | UI | Engine |
|---|---|---|
| IDLE | Optional Ask Cyclone chip | Accessibility listening. No mutation. |
| ANALYSIS | White card, structured bullets, one CTA | Read pageText / thread. Intent slots. No taps. |
| WORKING | Status row, View progress, stop square | Skill or planner. After-state each step. |
| LIVE | Target app full screen | Same executor. Overlay is chrome only. |
| GATE | Pay / send / delete / grant | PolicyGovernor. PC cannot override. |
| DONE | One-line outcome + draft skill | Memory write seam. Disabled until review. |

### Copy deck

- Analysis title: `Analysis`
- Working title: `Task automation`
- Working body: `I'm on it. I'll let you know when this is ready to complete. You can leave this screen.`
- Status: `Working on this task`
- Primary: `View progress`
- Confirm: `Do this` / commerce: `Order this from`
- Live: `Stop task` · `Take control`
- Composer: `Ask Cyclone`
- Gate: `Cyclone needs you to confirm before finishing this.`
- Done: `Saved as a draft skill. Review it in Automations before it can run alone.`
- Legal: `Supervise closely. Interrupt when needed. Select apps only. Compatibility varies.`

### Never say

- “Let me think about the best approach…”
- “I noticed several possible buttons.”
- “Here is my plan in eight steps.”
- “I have placed the order.” unless the user confirmed pay.

## System loop

`phone_status` → `phone_locate(goal)` → confirm → `phone_act` (after-state required) → `phone_skill_save|run`

If a verified skill matches goal + pageKey, skip the model.

Overlay chrome buttons only change Cyclone state (stop, take control, confirm). They do not tap the host app except through `PhoneToolExecutor`.

## First vertical

Pixel 8, one story:

Messages names a food order → Analysis lists items → `Order this from` → Working while the shop opens → Live view of the cart → GATE before pay → draft skill in existing `AutomationStore`.

## Lanes

| Lane | Own | Do not |
|---|---|---|
| Overlay UX | New overlay Compose + Accessibility overlay controller. Tokens from mobile-v32. | Seventh tab. Restyle Home. |
| Page card | MCP compact, `pageText` / `pageSummary`, goal ranking | Raise a 12-control cap without ranking |
| Act envelope | `phone_act` after-state + delta | Default coordinate taps from MCP |
| Skills | AutomationStore + capsules + Brain memory seam | Parallel JSON memory for Codex |
| Policy | GATE before pay/send/delete/grant | PC auto-approve |

## Invariants

One package `com.cyclone.mobile`. One launcher. One executor. Semantic first. Verify after mutation. Observation-scoped element IDs. No generic shell to the model. No secrets in Brain. CI ≠ physical VERIFIED.
