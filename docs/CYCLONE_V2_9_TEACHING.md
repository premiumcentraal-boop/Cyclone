# Cyclone Mobile V2.9 — Unified Routine Teaching

Cyclone V2.9 turns Follow Me and the Guided Routine Recorder into one learning surface.

## Product loop

```text
START FOLLOW ME
      ↓
LEARN overlay remains visible
      ↓
USER navigates normally ───────────────┐
      │                                │
      │ optional exact teaching       │
      ├─ Tap target                    │
      ├─ Hold target + duration        │
      ├─ Swipe arrow                   │
      ├─ Check/assert                  │
      ├─ Wait                          │
      └─ Back/Home                     │
      │                                │
      ▼                                ▼
V2.8 Page Awareness            GuidedRecorderEngine
      │                                │
one stable semantic page       before/after screenshot + UI
per real page                  target selector + evidence
      └──────────────┬─────────────────┘
                     ▼
             RoutineTeachingRuntime
                     │
       ┌─────────────┼──────────────┐
       ▼             ▼              ▼
   App Graph     Automation DSL   Teaching history
       │             │              │
       ▼             ▼              ▼
Adaptive Brain   deterministic   screenshots + notes
                copy + optional   + corrections
                AI proposal
```

## Human demonstration is not the replay format

The user may teach a slow physical action because that is the clearest way to communicate intent. Cyclone stores both the demonstration and the Android semantics around it.

Example:

```text
User holds a control for 2 seconds
                ↓
Accessibility target advertises ACTION_LONG_CLICK
                ↓
Teaching evidence stores:
  demonstratedDurationMs = 2000
  semanticSignal = ACTION_LONG_CLICK
  replayStrategy = SEMANTIC_LONG_CLICK
  optimizedDurationMs = 0
                ↓
Future execution should prefer the semantic Android action/selector,
not reproduce the two-second human gesture unless semantic execution fails.
```

Likewise, page scroll gestures are checked for semantic `ACTION_SCROLL_FORWARD`, `ACTION_SCROLL_BACKWARD` and `ACTION_SCROLL_TO_POSITION` support before being treated as coordinate-only knowledge.

## What is captured locally

A teaching session stores:

- selected model
- app/package transitions
- stable semantic pages
- current Page Awareness key
- meaningful user clicks/long-clicks/scrolls
- semantic selectors
- advertised Android accessibility actions
- replay strategy
- screenshots
- normalized UI snapshot evidence
- before/after fingerprints for explicit guided steps
- deterministic workflow ID, when one is generated
- AI-optimized proposal ID, when requested
- per-step user corrections
- optional single selected-model post-session analysis

Typed field contents are not learned by Follow Me.

## Page discipline

V2.9 inherits V2.8 Page Awareness. Repeated Accessibility content events do not create a new learned page. A page timeline item is created only when the stable semantic PageKey changes.

The learning hierarchy is:

1. package/activity context
2. stable semantic page signature
3. meaningful controls
4. advertised Android actions
5. demonstrated transition
6. resulting page
7. verification evidence

Screenshots are supporting evidence rather than the identity of the page.

## Overlay UX

The Accessibility overlay is deliberately visible for the entire active session.

The collapsed bubble shows `LEARN` and a live evidence count. Tapping it opens:

- current app/page and learning counters
- Pause / Resume
- Stop & review
- Routine teaching history
- model selector
- Tap
- Hold with 0.5 / 1 / 2 / 3 second demonstration options
- Swipe
- Check
- waits
- Back/Home
- optional selected-model optimization

The user remains the controller while teaching.

## Stop and review

`Stop & review` is a terminal control for the teaching session. Cyclone:

1. saves any explicit Guided Recorder workflow,
2. restores normal controller state,
3. persists the App Graph/Adaptive Brain evidence,
4. finalizes the teaching-session JSON,
5. writes the Obsidian-compatible `Report.md`,
6. opens the dedicated report Activity,
7. optionally performs one compact post-session OpenRouter analysis.

The report contains a screenshot timeline. Every step has a correction field so the human can edit Cyclone's interpretation later. Corrections persist when the session is reopened from teaching history.

## AI cost policy

Teaching is deterministic-first.

Cyclone does **not** call the selected model for every Accessibility event, tap, screenshot or page. Local Android semantics and Page Awareness produce the canonical evidence.

If model optimization is enabled, the explicit Guided Recorder can produce an optimized Automation proposal. Separately, after the session ends, V2.9 may make one compact selected-model request to summarize reusable skills, speedups and uncertain areas. Raw screenshots and full Accessibility trees are not sent in that post-session pass.

## Existing architecture boundaries

V2.9 does not replace prior layers:

- Agent 1 owns `phone.*` and Android primitives.
- Agent 2 owns Automation Studio and deterministic workflow execution.
- Agent 3/Hermes owns AI reasoning and recovery.
- V2.8 Page Awareness owns stable semantic page identity.
- App Learner owns per-app graph knowledge.
- Adaptive Brain owns reusable learned phone knowledge.
- V2.9 Routine Teaching orchestrates human demonstration and evidence across those existing interfaces.
