# Cyclone Mobile V2.7 — Adaptive Brain

## Core product rule

Cyclone should not solve the same phone problem from scratch repeatedly.

```text
unknown phone state
    ↓
AI / user solves it once
    ↓
record small evidence-backed actions
    ↓
store micro-skills + app/screen/path knowledge
    ↓
retrieve before the next model decision
    ↓
replay only when confidence + fresh state permit it
    ↓
verify result
    ↓
raise or lower only the relevant confidence
```

V2.7 adds this loop without replacing the existing Phone Toolbox, Automation Studio, Hermes/OpenRouter runtime, Guided Recorder, or App Learner.

## Memory layers

### App Learner graph
Semantic per-app knowledge remains the canonical screen/navigation map:

- app
- screen types
- safe actions
- selectors
- transitions
- confidence/staleness

### Adaptive micro-skills
`cyclone_adaptive_brain_v27.db` stores tiny reusable pieces of action evidence:

- `phone.home`
- `phone.open_app(package)`
- semantic clicks
- scrolls
- waits
- assertions
- other safe reusable phone actions

Each micro-skill stores safe parameters, from/to package and fingerprints when useful, success/failure counts, confidence, source and last use. Typed values are not stored.

### Learned paths
A completed task can point at an ordered list of micro-skill signatures. Repeated success raises path confidence. Automatic replay requires strong evidence; one AI-written reflection is never enough.

### App inventory
Cyclone maps launcher label → package name locally. This allows requests such as `Open Spotify` to resolve deterministically instead of asking a model which package to launch.

### Notes
User-added and background-refiner notes are non-executable knowledge. They help retrieval/explanation but do not raise the permission or execution confidence of a selector.

## Brain-first execution

`OpenRouterAdaptiveAgent` performs:

1. fresh structured Android observation
2. local Brain recall
3. deterministic direct plan if the request is already known strongly enough
4. otherwise one model decision with the relevant Brain subset included
5. execute one typed phone tool
6. fresh observation + verification
7. update the exact micro-skill success/failure evidence
8. repeat only for unknown remainder
9. finish task
10. write task/path memory and enqueue background refinement

Simple system/app-launch tasks can complete with **0 model decisions**. Repeated learned paths can also migrate toward deterministic execution after enough successful evidence.

## Background refinement

`BrainRefinementWorker` is deliberately downstream from the foreground task.

It may create small non-executable lessons or optimization notes from the task result and retrieved evidence. It cannot change executable confidence and cannot invent a successful selector. This keeps the model useful for semantic consolidation without allowing self-reported success to become a permission to act.

## Brain Chat

The AI tab contains `Chat with Brain`.

The user can:

- ask what Cyclone knows
- inspect relevant micro-skills/apps/task reports
- save knowledge directly
- say `Remember that ...`

When OpenRouter is available, a model can synthesize an answer from retrieved local Brain context. Without OpenRouter, local retrieval still returns matching apps, skills and notes.

## Follow Me

Follow Me is a user-driven whole-phone learning mode.

- controller is `HUMAN`
- Cyclone never generates autonomous taps/clicks in this mode
- Accessibility events are observed in the background
- clicked element semantics and screen transitions are learned
- each visited app extends the existing App Learner graph
- cross-app/app-launch transitions become Adaptive Brain evidence
- `TYPE_VIEW_TEXT_CHANGED` contents are ignored
- sensitive/password-like fields are not learned as ordinary click knowledge

This complements focused single-app Guided / Task / Passive learning.

## Overlay

V2.7 no longer restores an always-on trace overlay.

If the user enabled the overlay preference:

```text
AI task starts
↓
task-scoped overlay appears
↓
matching user-facing decision events only
↓
Done / Stopped state
↓
brief final result
↓
fade + slide out
↓
view removed
```

The preference stays enabled for the next task, but the actual overlay window exists only during a task.

## History

History is outcome-first rather than a flat trace dump.

- All / Success / Failed filtering
- run result card
- verified-action and failure counts
- task learning report
- saved mistakes/reusable sequence
- clean timeline by default
- optional technical mode reveals model/observe details

The timeline is explicit product telemetry and user-facing progress summaries, not hidden chain-of-thought.

## Privacy and safety invariants

- typed values are omitted from micro-skill params
- passwords, tokens, OTPs and payment credentials are not written into Brain Markdown
- screenshot base64 is not persisted to trace/Brain
- foreground app text remains untrusted input
- Safe Mode still gates consequential actions
- AI refiner notes do not change executable confidence
- Follow Me does not control the device
- real-device behavior is not VERIFIED until tested on Android hardware
