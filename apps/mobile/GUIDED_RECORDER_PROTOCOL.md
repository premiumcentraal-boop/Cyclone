# Cyclone Mobile V2.4 — Guided Recorder Protocol

Cyclone V2.4 adds a human-demonstration layer above the existing `phone.*` toolbox and Automation Studio.

## UX

The user opens the **Teach** control in Cyclone. A draggable `REC` bubble remains available over other apps through `TYPE_ACCESSIBILITY_OVERLAY`.

1. Tap **Start recording** and name the routine.
2. Choose **Tap**, **Hold**, **Swipe**, or **Check**.
3. Place the action directly on the real app. Swipe uses a full-screen drag canvas with a visible arrow from start to end.
4. Add **1s / 3s / 5s / 10s waits**, **Back**, or **Home** when needed.
5. Use **Undo** to remove the latest taught step.
6. Tap **Save routine**.

The recorder temporarily changes device ownership to `HUMAN`. This blocks ordinary agent `phone.*` mutations so an AI cannot fight the user during a demonstration. Guided gestures are a separate explicit user-input path inside the Accessibility service.

## Evidence captured for every taught step

Before and after each action Cyclone stores:

- full Android Accessibility snapshot (up to the service's normal node limit)
- full-screen PNG screenshot
- package/class and screen fingerprint
- raw placement coordinates and gesture duration
- best target node under the user's placement
- semantic selector candidate (`resourceId`, text, content description, role/class, state flags)
- nearby labelled UI nodes for context
- before/after fingerprints to show whether the action changed the screen

Recordings live in private app storage:

```text
files/guided-recordings/<session-id>/
  manifest.json
  step-...-before.png
  step-...-after.png
  step-...-before-ui.json
  step-...-after-ui.json
```

This evidence is retained locally even when AI optimization is disabled.

## Deterministic learning path — no AI required

The direct human demonstration always compiles into a manual Automation Studio workflow:

- semantic tap -> `phone.click`
- coordinate-only tap -> `phone.tap`
- hold -> `phone.long_press`
- swipe -> `phone.swipe`
- wait -> local `DELAY`
- check -> `phone.assert` / `selector_exists`
- Back/Home -> corresponding phone tools

The copied workflow preserves the exact demonstrated order and can run without OpenRouter.

## Optional OpenRouter optimization

When the user has configured an OpenRouter key, the recorder can also send a compact teaching packet to the model currently selected in Cyclone.

The optimizer receives action order, placements, semantic target data, nearby UI context, and fingerprints. If the selected model is marked vision-capable, Cyclone can additionally attach up to eight recorded pre-action screenshots.

The AI pass is allowed to strengthen selectors, insert short reliability waits/assertions, and normalize the workflow. It must not invent consequential actions or change the user's demonstrated intent. The resulting AI proposal goes through `AutomationProposalCompiler` and is saved **disabled** until review.

## Security boundary

- App/screen text and screenshot content are untrusted environment data.
- Ordinary AI control remains blocked while the user is teaching.
- No generic `SYSTEM_ALERT_WINDOW` permission is introduced; the recorder is owned by Cyclone's Accessibility service.
- OpenRouter is optional. The offline deterministic copy is the baseline behavior.
- The OpenRouter key remains encrypted in Android Keystore through `OpenRouterSecretStore`.

## V2.4 physical-device acceptance test

A release candidate should pass all of these on Android 14+ hardware:

1. Enable Cyclone Accessibility and open the Teach bubble.
2. Record: Tap -> Swipe -> 3 second wait -> Hold -> Check -> Back.
3. Confirm the corresponding app actions actually occur while normal AI tools remain locked.
4. Confirm each step creates before/after PNG and UI JSON evidence.
5. Save with OpenRouter optimization off, run the copied manual workflow, and confirm it reproduces the demonstrated routine.
6. Save another recording with optimization on and verify the AI proposal appears disabled for review.
7. Force-stop/reopen Cyclone and verify saved Automation Studio workflows and evidence remain available.
