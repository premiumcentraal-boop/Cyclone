# Cyclone V5 Alpha 138: OneMind

Developer alpha for owner testing. It builds on alpha.136 dev1 and carries alpha.137's unpublished work.

- **Mobile:** `5.0.0-alpha.138.dev1` (version code 292).
- **PC runtime:** gateway and MCP `5.0.0-alpha.138.dev1`. The gateway accepts the phone's new OneMind summary in
  ask.status.
- **Glass:** `1.0.0-alpha.68`. It shows a run's wakes and cost under its milestones.

**Update the phone and the PC together.** An older PC gateway rejects ask.status while a OneMind run is live, because
of the new summary.

## OneMind is the engine (plan 69)

There is one runtime for Flash and Mind now, released at once as the only engine. No switch, no transition.

**How a step is done.** Each step climbs only as high as it needs:
1. **Code first:** an exact label, opening an app, a link or a setting, Back, timers, calendar, contacts, memory,
   Cyclone Ports.
2. **The decision race:** JEV and OpenAI Decisions answer **one fused call** per step. It asks which control to use,
   whether the last step worked, popups, blockers, drift, your mind's standing orders, branch conditions and, before
   anything not easy to undo, the risk questions.
3. **The same call with the screenshot,** when the screen's tree is weak or the models disagree.
4. **A mind,** only when one is needed.

Every action is checked against what it should have done.

**Who oversees what:**
- **Instant:** unchanged. A promotion hands its work into the run's Score, so Flash or the Mind carries on the same
  run.
- **Flash:** the *Flash mind*, on your selected model.
  - Its plan streams, and step 1 starts while the rest is still being written.
  - Likely forks are written into the plan ("already on → skip"), so the crew settles them without asking.
  - If the goal already holds, it finishes at once.
  - It hands over to OneMind by itself, once, when the task turns out bigger: 3 replans, 2 surprises in a milestone,
    12 steps, or the Flash mind asking for it.
- **Mind:** *OneMind*, your selected model, in **one conversation for the whole run**.
  - It is woken only when something happens: a glance, a consult, a replan, or taking the wheel (today's Mind loop,
    kept).
  - It writes milestones, steps and up to 8 standing orders ("wake me if the amount is over €400"; code does the
    comparison).
  - Code makes it review before any milestone that sends, pays or deletes.
  - The crew keeps going on easy-to-undo moves during a glance, and takes them back if the glance disagrees.
  - Side trips (a login, a code) return to the paused milestone.

**Finishing honestly:**
- A claimed finish is checked by code first, then by the DONE question.
- **Doubt means not done:** the mind gets at most 2 re-checks. Then the task ends as **"Couldn't confirm: please
  check"**, never as "Done".

**When it goes round in circles:**
- 4 wakes in a milestone make the mind change approach.
- 8 wakes, or the same trigger three times, open a **check-in card**: "This seems stuck on … Keep going, change course,
  or stop?". Your answer counts as a steer.
- 10 actions without finishing a step stop the run, with an explanation.

**Recipes.** A request that ran this way before, and passed its done check, starts on that plan with no planning call.
- Any surprise falls back to the mind.
- The recipe is rewritten after the next verified run, or dropped after a miss.
- Approvals are never carried forward.

**What you see:**
- The task card shows milestones (✓ when done), the current step, each wake and why, and the cost so far.
- Glass shows the same counts and lines, never screen text.

**What stays exactly the same:**
- Safety: the code gates, exact-text approval for sends, the vault, redaction and private screens.
- No model can approve anything.
- Account setup and sign-up mapping keep their own Mind runs.
- A task interrupted before this update resumes on the old loop. OneMind runs resume from their saved Score.

## Settings

- **Speed → Compact history at:** 100k tokens (default) · 50k · 200k · Off.
  - A long Mind run's history is shortened only past this size, at a milestone boundary (or at once if it is 20%
    over).
  - Old screens fold to one line each, and the facts stay exact.
  - One GPT-6 Luna call writes a short story so far, with a code version if Luna fails.
  - The Flash mind's conversation is never compacted.
- **Fast mode is gone.** The Pilot's work lives in OneMind's crew now.

## Also in this release (from alpha.137, never published on its own)

- **Live voice from the Ask bar:** with nothing typed, the send arrow is a voice wave that opens Live voice (Driver
  mode's new name).
- **Deciding while you speak:** the transcription, the understanding call and the decision race start at your pauses.
  They are used only if what you finally say is exactly the same, and nothing is carried out before you finish.
- **Two answers again:** each decision model is asked once (Consensus needs both to agree).
- **The Live voice overlay** in the Ask overlay's design.
- **The finished work island:** if a task's work island is open when the task finishes, it stays open in its finished
  state.

## Tests

- **Local JVM harness:** 180 tests, including:
  - **`OneMindCrewTest`:** Score, Expect, Breaker, Ledger, ToolSteps, the fused call, the step ladder, the vision
    shadow.
  - **`OneMindConductorTest`:**
    - each wake kind and its effort;
    - the wheel's hand-back;
    - a forced review beside the crew, with the undo;
    - barriers;
    - replans and detours;
    - done doubt;
    - the check-in and the 4-wake note;
    - the ask hint and consent;
    - steers;
    - the Flash handover;
    - streamed plans;
    - compaction at the limit and never below it;
    - recipes, resume, the Instant baton, owner messages and pause;
    - the card.
- **Gateway:** `test_plan69_ask_status_carries_the_onemind_summary_and_nothing_else`.
- **Glass:** "a OneMind run shows its wakes and cost under the milestones".
- **Repository guards:** 381 pass.

## Limits, stated plainly

- **Physical device: UNVERIFIED.** OneMind has not run on a phone yet. Run the alpha.138 rows (7.1–7.12) in
  `docs/LUNA_DEVICE_MATRIX.md`.
- **Not built yet:**
  - pipelined boards (sending the next step's call while the current one settles);
  - Instant as a one-step OneMind plan (Instant keeps its own fast engine);
  - recipe lookup by Triage's typed slots (it uses your normalised words);
  - a phone setting reader for setting postconditions (they are checked by the decision models instead);
  - `recall` of folded history after a resume.
- **Box ("Where?") and icon answers stay shadow only.** They record where they would tap and never tap.
- **GPT-6 Luna** is called as `openai/gpt-6-luna`. If it is unavailable, compaction and the handover use the code
  summary.
