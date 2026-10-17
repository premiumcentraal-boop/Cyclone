# Plan 70: as big as the work

Status: **built in alpha.140** (2026-10-17). Builds on Cy (alpha.139) and OneMind (alpha.138, plan 69). Physical
device: **UNVERIFIED**.

## 1. The owner's ask

"How should Instant, Flash and full OneMind runs, and a plan diversion, look in the new design?" It should feel
interactive, not messy, with little text, and without changing the runtime. A small request must not bring up "the
whole circus": an Instant run should not show "Opening the camera". Simplicity and beauty lead.

## 2. The philosophy, in one line

> **The overlay is as big as the work.** Small work whispers, short work folds, real work opens its card. A run only
> grows on its own; only you fold it back. Words appear when there is something for you to read.

Five rules follow from it:

1. **Three sizes, one material.** The *whisper* (a small capsule where the Ask bar was), the *island* (the folded bar)
   and the *card*. All three are the same Tilt Glass. Nothing new appears on screen; the bar changes size in place.
2. **Cy carries the state, words carry the news.** Cy says *working*, *thinking*, *done* and *puzzled* without text.
   Words are kept for what only words can say: an answer, what didn't happen, a step in a longer task, a choice for you.
3. **Grow, never shrink.** Instant → Flash → Mind is a run growing: whisper → island → card. A run never shrinks on its
   own, so nothing jumps back and forth. The owner can always fold it.
4. **Changes are calm.** A plan that changes course on its own is how Cyclone works, not an alarm: one quiet line on
   the rail. Warm colour is reserved for what the owner did (a steer) or must read (a reason).
5. **The runtime decides, the glass shows.** No mode, step or plan is changed for the look. The overlay reads what
   already exists (the chrome session, the mission's Flash flag, the Score's difficulty, the "Thinking" status).

## 3. Each kind of run

| Run | Size while working | What you see | What you don't see |
|---|---|---|---|
| **Instant** ("open camera", "swipe up", "volume up") | Whisper | The Ask bar folds to a capsule: Cy working in its ring, the app on its shoulder, pause / hold-to-stop. Then Cy hops and the bar comes back. | No step words, no card, no "Working" chip, no progress line. |
| **Instant with something to read** | Whisper with words | An answer ("It's 14:32"), or what didn't happen ("Couldn't find Pokémon GO.") beside Cy, for a moment sized to the words. | — |
| **Instant call** (2 s cancel window) | Island | "Calling Mam in 2 s. Tap Stop to cancel." and Stop. The one Instant moment that must be read and can be undone. | — |
| **Flash** (a few routine steps) | Island | One line: what it does now ("Opening WhatsApp"), then "2 of 3". Cy works; while the mind thinks, Cy thinks. | The card, unless you open it. |
| **Flash that becomes the Mind** | Island → card | The island opens into the card once, when the run hands over (unless you moved it yourself). | — |
| **OneMind (fully planned)** | Card | Status chip, apps, progress line, the last three steps with Cy beside them (working, or thinking while the mind is awake). | Marks doubled ("✓" drawn twice), "Task in Cyclone Mind". |
| **Needs you** (any size) | Card | The owner card, as before: the message is the hero, Cy waves. | — |
| **Voice / Drive** | (its own panel) | Unchanged: quiet success with the done sound. | — |

## 4. A plan diversion

- **Cyclone changed course on its own, with nothing to unfold** (every OneMind replan today): one quiet line on the
  rail ("Plan diverted", in the muted colour, with the small fork mark), and the new steps under it. No chevron, no
  panel, no empty "Earlier plan".
- **With a reason, an earlier plan or dropped steps:** the same row in warm, with the chevron; a tap shows why and the
  plan it left, as before.
- **The owner steered it:** warm, as before.
- At most three new steps show (like the step list); the rest is "+N more".

## 5. What was built (alpha.140)

- `ui/overlay/RunPresentation.kt`: the sizes, kinds, "grow never shrink", and the whisper's ending (a hop, or a few
  words, held for a time sized to them).
- The chrome: `OverlayChromeSnapshot.whisper`, `OverlayChromeMachine.setWhisper`, and
  `OverlayChromeRuntime.missionWorking(fold, loud)` / `missionGrow` / `missionFinished(ending)`:
  - an Instant session folds into the whisper from its first frame;
  - a Flash mission starts as the island;
  - a whisper that is handed up to the Mind grows into the card;
  - a finished whisper leaves no "finished island" behind (its ending is its own).
- `CycloneModes`: answers and "what didn't happen" become the whisper's words; the call window is loud.
- `MindMissions`: Flash starts folded (`fold = run.flash`) and grows when the Score's difficulty becomes the Mind.
- `WhisperCapsule` (the capsule), Cy THINK in the island and the card while the status is "Thinking", the quiet
  diversion, and copy: step marks stripped (`OverlayGlassCopy.label`), and a generic "Cyclone Mind" title replaced by
  the progress alone on the island.
- Tests: `RunPresentationTest`, plus the existing chrome, island, copy and diversion tests.

## 6. Not built yet

- The ScoreCard's wake count and cost, which plan 69 §10 wanted on the card. The card stays as simple as this plan
  asks; they can come behind a tap later.
- OneMind giving a reason and the earlier plan for a replan (`planHistory`), which would turn the quiet line into the
  full one when there is a reason worth reading.
- An earcon or haptic for the typed whisper's done (voice already has one).
