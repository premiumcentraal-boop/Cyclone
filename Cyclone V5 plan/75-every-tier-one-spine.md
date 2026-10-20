# Plan 75: every tier, one spine

**Status:** the strategic plan for alpha 145–150. Nothing in it is built yet. It builds on alpha 144 (plan 73) and
takes in plan 74 (fewer "Needs you" cards) as one of its releases. Where plan 74's own build order names alpha numbers,
§9 here replaces them.

The owner, after alpha 144: *"Make the final strategic plan for an upgrade to all tiers that will significantly be more
reliable, accurate, and fast. And fixing all the problems."* Earlier in the same session: *"harden Flash and OneMind
runs, make them use the Decisions API and flash models side by side."*

Order of priority, as the owner set it: **reliable first, then accurate, then fast, then cheap.**

## 1. The idea in one paragraph

Every request, whatever tier takes it, gets the same **spine**: a goal contract written once by code, one way to read
the screen, one proof that a move worked, one memory of what worked before, one way to ask the owner, and one record
of time and cost. The tiers differ only in **who decides the next move**. Code decides when it can. The decision models
(JEV and OpenAI Decisions, side by side) decide most moves in well under a second. A fast chat model, the **first
officer**, settles what they can't in one or two seconds. The owner's chosen model, the **captain**, plans and replans
only what needs real judgement. The owner is asked only what only the owner can answer (plan 74). Every rung has a
deadline, a fallback and a number in the run record, so a slow network or a missing model changes how long a run takes,
never what it does.

## 2. What is wrong today (alpha 144, traced in code and field test 143)

This register is the list of problems plan 75 closes. "Partly" means alpha 144 fixed the symptom but not the cause.

| # | Problem | Evidence | Status | Fixed in |
|---|---|---|---|---|
| P1 | Nothing measures where a run's time goes: no per-stage times, no decision-model latency distribution, no split rate | Release notes 136 and 143: "Model speed and accuracy on Cyclone's requests are unmeasured"; field test S8 | Open | 145 (§8.1) |
| P2 | Cost per request isn't recorded; spend showed $0.00 through the whole field test | Field test S8 | Open | 145 |
| P3 | The golden set is scored against a perfect stand-in model, so it tests logic, not model accuracy | `GoldenSetTest` against `GoldenSetOracle` | Open | 145 |
| P4 | The Conductor's mind calls have no backup model and no wait on a rate limit: two failures end the run | `one/Conductor.kt` `call()`; the classic loop does fail over (`MindLoop.kt`) | Open | 145 |
| P5 | OneMind runs have no working-time budget; only the classic loop honours the owner's working minutes | `MindMissions.kt` passes `workingMinutes` to the classic loop only | Open | 145 |
| P6 | Dead and misleading code: the retired Pilot and its Fast-mode settings still show in Settings; the unused vision shadow would send screenshots to JEV and skip zero-retention routing; the compaction model id isn't in the model registry | `MindMissions.kt:717` (`fast = null`); `AndroidCrew.visionShadow`; `AndroidMinds.LUNA` | Open | 145 |
| P7 | Lab checks match exact screen words, so a pass doesn't survive another language or app version | Field test S9 | Open | 145 |
| P8 | The step race waits for the slower decision model every move (up to 1.8 s), with no early stop | `AndroidCrew.board`: `DecisionRace` without `stopEarly`/`quorum` | Open | 146 |
| P9 | Any split between the two decision models, or a pick that flips when options are reversed, wakes the mind (seconds) | `FusedBoard.combine`, `FusedBoard.doubt` → `NEEDS_MIND` → `Conductor` CONSULT | Open | 146 |
| P10 | Each move that reaches the decision models reads the screen twice (once without, once with a screenshot) | `StepEngine.run` (`look(false)` then `look(true)`) | Open | 146 |
| P11 | Observations are whole screens; Flash spends turns re-reading a screen that didn't change ("changed during capture") | Field test B7, S4; alpha 144 widened the capture retry | Partly | 146 |
| P12 | The "Flash" planner isn't fast: plan, replan and wheel use the owner's chosen model at the owner's effort | `AndroidMinds.brain` | Open | 147 |
| P13 | Flash crosses a move that isn't easy to undo with no review at all (reviews are off in the Flash profile); the code gates for pay/send/delete still hold | `Conductor.barrier` with `profile.reviews == false` | Open | 147 |
| P14 | Nothing in code stops a re-plan from quietly changing the done check | A re-plan's plan lines may carry a new done line (`PlanLines` `DoneLine`) | Open | 147 |
| P15 | Each tier proves "done" its own way (Instant's looks, Flash/Mind's done board) | Field test S3, B1 | Partly | 148 |
| P16 | The done check asks one model only, and a strong code check that holds with no model answering counts as done | `AndroidCrew.doneBoard`; `Conductor.doneConfirmed` | Open | 148 |
| P17 | Recipes are found by the owner's exact words, so "open my profile" and "open my Instagram profile" don't share one; the owner never sees when one was used | `Recipes.key` = `Replay.keyOf(goal)`; field test S6 (6 s, 154 s, 11 s for the same request) | Partly | 148 |
| P18 | Controls are matched by label words, so "mijn profiel" misses an English "Profile" tab | Field test S2 | Partly (apps fixed in 144, controls not) | 148 |
| P19 | Too many "Needs you" cards: stuck, place, typing and free-form questions all raise one | Plan 74 §2 | Open | 149 |
| P20 | One mission slot: a second task is refused (now with a reason and retryable) instead of waiting | Field test B6, S7; `ASK_BUSY` | Partly | 149 |
| P21 | Stuck is a fixed count (same trigger 3×, 8 wakes) rather than "no progress for this task's length" | `Breaker`; field test S5 | Partly (ladder in 144) | 149 |
| P22 | Routing still sends a request up to Flash when no model answered and the phone has no guess, so slow networks still change the route for some sentences | `ModeRouter.decide` after alpha 144 | Partly | 146 |
| P23 | Flash plans are never checked by a stronger model; Mind's background checks exist but are off in Flash | `Conductor` glances under `profile.reviews` | Open | 150 |
| P24 | Instant taps took 5–6 s and an app open 11 s on the Pixel; alpha 144's settle fix is not yet measured on a phone | Field test B8 | Partly, unmeasured | 145 measures, 146 fixes what remains |

## 3. The spine (shared by every tier)

### 3.1 The goal contract

Written by code when a request is routed, before any tier starts. It travels with the request through every hand-over.

```
GoalContract(
  requestId, text, language,
  intent,                       // open_app, setting, navigate, message, find, … or "open"
  target,                       // the app, page, setting or contact it names (resolved by code, §3.4)
  proof: Expect,                // what must be true at the end (AppIs, PageIs, Setting, Shows, HasFact…)
  strong: Boolean,              // true when the proof names what the goal shows, not just where the phone is
  limits(timeMs, moves, costUsd),
  consent: Set<RiskKind>        // the kinds of move this goal may ask the owner to approve
)
```

Rules, enforced in code:

- **Only the owner changes the goal.** A steer adds a goal version (as today). No model reply can change `text`,
  `proof` or `consent`. A re-plan may change milestones and steps, never the contract (closes P14).
- **One proof for all tiers.** Instant, Flash and Mind all finish through the same `ContractCheck`: code's check of
  `proof` on a settled screen first, then the done board (§3.3) (closes P15).
- **The limits are real.** Time, moves and cost are checked every move, in every tier, including OneMind (closes P5).
  Running out of time is an honest "couldn't finish" with Continue, never a silent stop.

### 3.2 One way to read the screen

- **One read per move.** The first read takes the screenshot only when code can already see the move will need a
  decision model (no exact target, a typing step, a pending verify). Otherwise it reads without one (closes P10).
- **Identity plus changes.** Every read carries alpha 144's screen identity. A read after a move is compared with the
  one before. The decision call gets the controls that appeared, disappeared or changed state first, then the unchanged
  context, inside the same 30,000-character state limit (closes P11).
- **Changed during capture is not a failed turn.** It is retried once, against the identity. If the identity is the
  same, the screen is "settled with moving content" and is used (field test B7).

### 3.3 One proof per move and per run

- **Per move:** as alpha 144: a move counts only with `screenProof` (another app, another screen identity, a dialog, a
  switch, a field). Unchanged is never a second click.
- **Per run (the done board):** both decision models are asked, the careful (lowest) yes counts, and the screenshot goes
  to the model that reads images. If neither answers, code reads the screen again after a settle and checks `proof`
  once more; only a strong proof that holds twice may finish without a model's yes (closes P16).

### 3.4 Names and meanings resolved by code

- **Apps:** alpha 144's `AppNames` (labels, packages, maker words), extended with the owner's languages.
- **Controls by role, not only by words.** A small table of phone-wide roles (`profile_tab`, `search`, `settings`,
  `back_up`, `close`, `send`, `compose`, `home_tab`…) is matched by resource id, content description, selected state
  and position as well as label words, in every supported language. "mijn profiel" and "Profile" meet at
  `profile_tab` (closes P18). The role table is data, tested per language, and only ever adds candidates; the decision
  models still pick.

### 3.5 Memory of what worked

- **Recipes by meaning, not by words.** A recipe is found by `(intent, target, starting screen identity)` from the goal
  contract, with the owner's words only as a tie-break. "open my profile" and "open my Instagram profile" share one
  recipe (closes P17).
- **Verify before reuse.** A recipe replays move by move with the normal per-move proof. The first move that doesn't
  prove drops back into the normal ladder at that step; it doesn't start over.
- **Visible.** Every run says "used what worked last time" or nothing. Glass and the Ask status show it.
- **Never secrets.** Typed values are scrubbed as today (`MindRedaction.scrubText`). Approvals are never carried.

### 3.6 One record of time and cost

Every request writes one `RunRecord`: each stage's time (route, read, decide, act, settle, wake, ask), each model call
(tier, provider, model, ms, input tokens, cost, ok), which rung decided each move, splits between the decision models,
recipe use, asks by kind, and the end. No request text and no typed values; the same redaction as diagnostics today.
The Brain, Glass and the Lab read it (closes P1, P2).

## 4. The decision ladder (who decides a move)

Every move climbs only as far as it needs. Each rung has a deadline and a way down.

| Rung | Who | What it does | Target | When it gives up |
|---|---|---|---|---|
| R0 | Code | Direct tools, an exact or role match (§3.4), a recipe step, the per-move proof | ~0 ms | No single match |
| R1 | Decision models, both in parallel | The fused call (plan 72): pick, verify the last move, popups, blockers, drift, risk questions | p50 ≤ 0.6 s | A split, a doubt, or no answer by the deadline |
| R1.5 | Decision models, one follow-up | Only the two finalists, with the screenshot: "which of these carries out the step?" | ≤ 0.6 s | Still unsure (< 0.8) |
| R2 | **First officer** (a fast chat model) | Repairs the current step or writes the next 1–3 steps of the current milestone | p50 ≤ 1.5 s | It can't, or it would change the milestone |
| R3 | **Captain** (the owner's model) | Re-plans; hand-over from Flash to Mind | 5–20 s | Hand-over budget spent |
| R4 | The owner | Only the hard needs of plan 74: consent, a secret, their hands, a fact only they know | — | — |

### 4.1 R1: the decision models side by side

- **Early stop (closes P8).** The race ends as soon as both have answered. For a move that is easy to undo, one clean
  answer (no doubt, sure ≥ 0.8) acts after a short grace (`QUORUM_GRACE_MS`, about 300 ms) for the other; the late
  answer is still read and, if it disagrees, the move's own proof is checked with extra care and the split is
  recorded.
- **A slow lane is set aside.** A provider that misses the deadline three moves in a row stops being waited for in this
  run (it is still asked, in the background, so the numbers stay honest). Its breaker works as today.
- **Moves that are not easy to undo keep the strict rule:** both models must pick the same control, the risk questions
  ride along, and the code gates stay exactly as they are.

### 4.2 R1.5: settle a split without waking a mind (closes P9)

When the models disagree, or the reversed pick flips, on a move that is easy to undo, one short follow-up goes to both
models: the two finalists only, the step, the screenshot to the model that reads images. A pick sure at ≥ 0.8 acts.
R1.5 and R2 **start at the same moment**; the first sufficient answer wins and the other is cancelled.

### 4.3 R2: the first officer

A fast chat model (the **Flash model**, a setting: see §10) with a strict JSON schema. It gets the goal contract, the
current milestone, the last moves with their proof, the decision models' reading with probabilities, and the screen.
It may answer only:

- `step` — one replacement step for the current one (same schema as a plan step, with an `expect`);
- `steps` — up to three next steps inside the current milestone;
- `climb` — it can't, with a reason.

Code rejects any answer that changes the goal, the milestone list, the done check or a step's risk downward, and any
answer whose steps the decision models then judge as not moving towards the goal ("does doing this move towards the
goal?" rides along in the next fused call). A rejected answer is a `climb`.

### 4.4 R3: the captain

The owner's chosen model, at the owner's effort, as today: the Mind plan, re-plans after the first officer climbs,
the single Flash-to-Mind hand-over, and the wheel. **Its calls fail over** (closes P4): the first officer's model and the
owner's backup model are tried in turn, with a wait of `Retry-After` (capped at 5 s) on a 429, before the run ends.

## 5. The tiers

### 5.1 Router (tier 0): the route is a function of the request, not of the clock

Today's order stays: grammar and answers, rules, the phone model, then the decision engine. Changes:

- **Act and judge at once for easy-to-undo actions (closes P22).** When the grammar or the phone model has a reversible
  guess, Instant starts it and its proof immediately while the decision engine answers in parallel. If the engine
  disagrees before the move, the move is cancelled and the request goes where the engine says; if after, the proof
  decides and the disagreement is recorded. No deadline decides the route for these.
- **Everything else** that no model answered goes to Flash as today, but the `RunRecord` says so, and the Lab's route
  stability test (§8.3) runs it on a slow network on purpose.
- **The goal contract** (§3.1) is written here for every route, so the tier that runs it starts with the proof.

### 5.2 Instant

- Proof, settle and app opening as alpha 144, measured on the phone in 145 (P24). Targets: a direct action ≤ 0.3 s, an
  app open ≤ 2.5 s after the launcher hands over (cold start excluded and recorded apart), a tap ≤ 1.5 s.
- Finishes through the one contract check (§3.3).
- Anything it can't prove goes to Flash with its baton (`RunBaton`: what it did, why it stopped, candidates) as today,
  plus the goal contract and the last screen identity, so Flash's first move can be checked against where Instant
  left off instead of starting with a fresh plan.

### 5.3 Flash

- **The first officer plans** (closes P12): the plan streams from the Flash model as today's Flash plan does. The
  captain is called only through R3.
- **The ladder is R0 → R1 → R1.5/R2 → R3**, with Flash's budgets (12 steps, 3 re-plans, 2 surprises, one hand-over) and
  the contract's time limit.
- **Barriers get a quick review (closes P13).** Before a move that isn't easy to undo, the first officer answers one
  question: "is this the move the goal asked for?" (about 1 s). A no climbs to the captain. The code gates are
  unchanged and still raise consent cards.
- **The captain checks the plan in the background (closes P23, in 150).** Mind's glances, on for Flash with the captain
  as reviewer. It never blocks a move that is easy to undo; it can pause the run before the next barrier.

### 5.4 Mind (OneMind)

- The captain plans; the crew runs the same ladder (R0 → R1 → R1.5/R2 before any wake of the captain). Most of today's
  CONSULT wakes become R1.5 or R2.
- Reviews, compaction and the wheel stay. The barrier glance stays synchronous (it guards moves that can't be undone).
- The contract's time limit applies (P5); the wheel keeps its own budget inside it.

## 6. Asking the owner (plan 74, unchanged in substance)

Plan 74 is built as written: code decides the kind of need; only consent, secret, hands and unknowable facts raise a
card; stuck, clarify, place and input are settled by the ladder; every assumption shows as a quiet NOTICE line with
Change through Task Kit. Two links to this plan:

- Plan 74's R2 "quick Flash judgement" is this plan's first officer.
- Stuck stops being a fixed count (closes P21): `Breaker` counts moves without **contract progress** (a new screen
  identity on the plan's path, a step proven, a fact found) against a budget set by the plan's length, not "the same
  trigger three times".

## 7. Many tasks (closes P20)

A request that arrives while a mission runs is queued (`MissionQueue`, extended to Ask), not refused. Ask status says
"waiting, behind <task>". A mission waiting on the owner longer than its limit ends honestly and lets the queue move.
Steers to the running mission still go to it, as today. Easy-to-undo Instant requests that don't touch the mission's app
may run at once; anything else waits its turn.

## 8. How we know it works

### 8.1 Numbers recorded on every run (145, before anything else changes)

From `RunRecord`: time per stage, model calls by rung and provider, decision-model latency (p50/p90, with timeouts
counted at their real time once the answer arrives), split rate, R1.5/R2/R3 use, recipe hits, asks by kind, cost per
request, end state.

### 8.2 The golden set against real models

The 308 golden requests run against the live providers in the Lab, not only the oracle (closes P3). Each release reports
pick accuracy per provider, the split rate, and accuracy when they agree versus when one decides alone.

### 8.3 The Lab gates

Every release of this plan ships only if, on the same mission set and the Pixel, **no number gets worse** and its own
target number gets better:

| Measure | Field test 143 / today | Target by 150 |
|---|---|---|
| False done | B1 seen | 0 |
| Same sentence, same route over 5 runs (including on a throttled network) | 4 of 19 changed | 100 % |
| Flash, simple task (≤ 5 steps), p50 / p90 | 76 s / 197 s | ≤ 25 s / ≤ 60 s |
| Instant tap / app open p50 | 5.1–6.2 s / 11.1 s | ≤ 1.5 s / ≤ 2.5 s |
| Moves decided at R0–R1.5 | not measured | ≥ 85 % |
| Captain wakes per Flash run | not measured | ≤ 1 |
| Needless owner cards per 100 requests | 2 in ~10 min | 0 |
| Runs ended by a model outage | not measured | 0 (fail-over) |
| Repeat of a verified request (rep 2 and 3) | 154 s / 11 s | ≤ 30 % of rep 1, no captain call |
| Refused requests while a mission runs | 5 of 16 | 0 |
| Cost per Flash request | not recorded | recorded; ≤ $0.01 |

The flash-task target is a target, not a promise: it depends on where the time really goes, which 145 is built to
measure. If 145's numbers show the time is somewhere this plan doesn't touch, §9 is reordered before 146 starts.

### 8.4 Lab checks by meaning (closes P7)

Lab missions check the contract's proof (package, screen identity, selected tab, setting state) and fall back to words
only when nothing else can tell. Each mission sets its own start state.

## 9. Build order

One release per checkpoint on the fast lane: tests, guards, Mobile CI and the owner's "merge and publish". Each one
starts with the Lab numbers of the one before.

| Release | Name | What | Closes | Guard |
|---|---|---|---|---|
| alpha 145 | **Measure and harden** | `RunRecord` with cost; golden set on live models; Conductor fail-over with 429 wait; OneMind time limit; remove the Pilot, Fast-mode settings and the vision shadow; fix the compaction model id; Lab checks by meaning; phone measurement of Instant | P1–P7, P24 (measure) | `test_run_record_guard.py` (no request text or typed values in a record); `ConductorFailoverTest`; a guard that no screenshot goes to a provider without vision |
| alpha 146 | **Speed without risk** | Early stop and slow-lane set-aside; R1.5; one read per move; changes-first observation; act-and-judge routing for easy-to-undo actions | P8–P11, P22 | `FusedBoardTest` (strict rule unchanged for moves that can't be undone); route stability test on a throttled network |
| alpha 147 | **The first officer** | The Flash model setting; R2 with its schema and code checks; the contract can't be changed by a model; Flash plans from the first officer; barrier review in Flash | P12–P14 | `GoalContractTest` (no reply changes goal, proof or consent); `FirstOfficerTest` (out-of-milestone answers rejected) |
| alpha 148 | **One proof, one memory** | `ContractCheck` for every tier; the two-model done board; recipes by meaning, verified per move, visible; control roles in every supported language | P15–P18 | `ContractCheckTest` across Instant, Flash and Mind; `RecipeKeyTest`; `ControlRolesTest` per language |
| alpha 149 | **Only what only you can do** | Plan 74 C1–C3; progress-based stuck; the Ask queue | P19–P21 | Plan 74's guards; no `ASK_BUSY` refusal for a request that can wait |
| alpha 150 | **The captain watches** | Background plan checks in Flash; the full Lab gate table (§8.3) on the Pixel; field test 150 | P23 | Lab report against §8.3 |

## 10. Choices to make, and the defaults

1. **The Flash model: the latest DeepSeek Flash (the owner's choice).** The first officer uses
   `~deepseek/deepseek-flash-latest`, OpenRouter's alias for DeepSeek's newest Flash. Today that is the
   `deepseek-v4.1-flash` line: text and images, strict JSON (`response_format` and structured outputs), from $0.056
   per million input tokens. The alias moves with each new Flash, so the run record names the model that actually
   answered, and a Lab result always belongs to one snapshot (`deepseek/deepseek-v4.1-flash-1017` at the time of
   writing). The Lab in 145–146 measures it from the phone (time to first token, output speed, JSON validity, pick
   accuracy). Gemini 3.8 Flash and GLM 5.3 Flash are measured beside it, as fallbacks only. The owner can change the
   model in Settings → Model & intelligence (the old Fast-mode row, repurposed).
2. **Cost ceiling per request.** Default $0.02, shown in the run record; a run that would pass it asks the captain to
   finish within it or ends honestly.
3. **Strict privacy.** When it is on, every rung (decision models, first officer, captain) is sent to zero-retention
   providers only; a model with no such provider is skipped, never used anyway.

## 11. Risks and answers

- **More wrong taps from R1 early stop and R1.5.** Only on moves that are easy to undo; every move still needs its
  proof; a miss is undone with Back and recorded. The split rate and wrong-tap rate are gated in §8.3.
- **A weaker plan from a fast model.** The contract can't be changed by it, the decision models check each step against
  the goal, the captain checks in the background (150), and the hand-over to Mind is still there.
- **Two models cost more.** Decision-model output is free and input is small (one fused call ≈ 3–8k tokens); R1.5 runs
  only on splits; R2 replaces captain wakes that cost more. Cost is recorded from 145, so this is checked, not assumed.
- **Hidden failures.** Every rung writes to the run record, every assumption is a visible NOTICE, and "couldn't finish"
  is always honest.
- **Privacy.** No new data leaves the phone: the same screen state goes to the same kinds of providers, under the same
  zero-retention setting. No secrets, OTPs, payment data or typed secret values are stored in records, recipes or the
  role table.
- **Safety boundaries.** Unchanged: the code gates for pay, send, delete, permission and account changes; the Lab's
  test-only approvals; Task Kit for every task button. No rung can approve anything.

## 12. How sure this plan is, part by part (out of 10)

| Part | Score | Why not 10 |
|---|---|---|
| Measure first (145) | 10 | — every other part depends on it |
| Fail-over and time limits | 9 | Certain and cheap; it makes runs finish, not faster |
| First officer for Flash planning and R2 | 9 | A fast model plans worse; the Lab must pick the model |
| R1.5 for splits | 8 | The split rate isn't measured; it may be rare |
| Early stop | 8 | The gain depends on the slower model's real speed |
| The goal contract | 8 | Stops drift by rule; a plan can still head the wrong way inside the rules |
| One proof for all tiers | 8 | Mostly unifies what 143–144 built; the gain is fewer edge cases |
| Recipes by meaning | 8 | The largest saving for repeated tasks; screen identities change with app updates |
| Control roles by language | 7 | Large reach, but the table needs care per language and app |
| Act-and-judge routing | 7 | Removes the clock from routing; a cancelled move must be truly harmless |
| The Ask queue | 7 | Fixes refusals; ordering rules need care |
| Changes-first observation | 6 | Helps tokens and re-reads; the field test cause was mostly fixed in 144 |
| One read per move | 6 | About 0.4 s per decided move |
| Captain background check in Flash | 6 | Doubles plan cost; its worth depends on how often fast plans go wrong |
