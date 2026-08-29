# Cyclone phone skill

Use this when a PC agent must drive a real Android phone through Cyclone.

Do not invent ADB shell, `input tap`, or a second automation runtime. Talk only to Cyclone MCP.

## Before any act

1. If doctor / `phone_status` is not READY, stop and tell the user what is off (USB, Accessibility, gateway, token).
2. Prefer a verified skill when the goal matches. Run it. Do not re-plan taps.
3. Otherwise `phone_locate` with the user goal (or `phone_observe` with `goal`).
4. Read the page card: `pageKey`, `pageText`, `pageSummary`, ranked controls. That is where you are.

## Default loop

```text
phone_status
phone_locate(goal)
phone_act            # elementId from THIS observation only
read after-state
phone_skill_save     # only after 2+ verified steps
```

## Hard rules

- Never reuse an `elementId` after the page changes. The after-state card is the new world.
- Never tap raw coordinates unless locate and search both failed and the diagnosis is `ACCESSIBILITY_PERCEPTION`.
- Never dump the full tree into context. Use locate / search / cursor.
- Never claim success from HTTP 200. After `pageKey` / delta is the proof.
- Never persist passwords, OTPs, cards, or typed secrets. Use slots / placeholders.
- Never complete pay, send, delete, or grant without the phone GATE / user confirm.
- Do not narrate an eight-step plan. Pick the next control from the card and act.
- Screenshot is last, not first.

## If you feel lost

1. Read `pageKey` and `pageText`. Say where you are in one line.
2. `phone_locate` the obvious label (Apps, Save, Continue).
3. If the control exists in semantic counts but not on the card, that is truncation — search, do not guess pixels.
4. `phone_debug_bundle` when transport, execution, and verification disagree.

## After a good run

Save a draft skill. Do not enable it yourself. The user reviews it in Automations.
