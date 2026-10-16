# Plan 69: Cyclone OneMind, the final plan

**Status:** FINAL. Agreed with the owner on 2026-10-16, and the source of truth for the build. Nothing is built yet.

**For agents:** read this plan first and build from it. Plans 65–68 are the design history behind it. Where they
differ from this plan, **this plan wins**. Each section points to the history for the reasoning.

## 0. In one paragraph

Cyclone replaces today's Mind engine with **OneMind**: one runtime for every difficulty.
- **Every step climbs a ladder only as high as it needs:** code first, then JEV and OpenAI Decisions racing, then
  Decisions with the screenshot, then a frontier model.
- **Hard runs (Mind)** are overseen by **one frontier model in one conversation** that is woken only when something
  happens.
- **Short runs (Flash)** have their own small mind on the owner's selected model.
- **Single actions (Instant)** have no controller at all.
- **Every action is checked against what it should have done.** Every finish is checked independently.
- **Code owns safety, exactly as today.**

It ships in one release with **no transition**: OneMind becomes the only engine, and the owner tests on it directly.

## 1. Vocabulary (use these names in code and copy)

| Name | Meaning |
|---|---|
| **OneMind** | The whole runtime. Also the name of the frontier overseer in Mind runs. |
| **Instant / Flash / Mind** | The three difficulties (`Mode.INSTANT`, `Mode.FLASH`, `Mode.MIND`). Each is a *starting point and a control level*, not a separate engine. |
| **The Score** | The typed state of one run, the one shared truth (§3). |
| **The crew** | Everything that isn't a mind: code, the decision models, tools, checks. |
| **Rung 0–3** | The ladder a step climbs: code → Decisions race → Decisions with sight → a mind. |
| **The Flash mind** | Flash's conductor: the owner's selected model, with one small conversation. |
| **Wake** | A moment a mind is called: Glance, Consult, Replan, or the wheel. |
| **The wheel / Mind drive** | The mind acting turn by turn: today's Mind loop, kept inside OneMind. |
| **Standing orders** | What a mind tells the crew to watch for and when to wake it. |
| **Barrier** | A point no work may cross while a review is open (sends, payments, deletes…). |
| **Recipe** | A verified run saved so the same kind of request replays with almost no model calls. |

## 2. The three difficulties

| | Instant | Flash | Mind |
|---|---|---|---|
| Character | reflex: one move | fluency: a short task in one motion | deliberation: work that needs judgement |
| Controller | **none** | **the Flash mind** (owner's selected model) | **OneMind** (owner's selected frontier model) |
| Plan | one step, from the router | a streamed, branching micro-plan | milestones + steps + standing orders |
| Replans | — | up to 3, then hand over | free |
| Wakes | — | on triggers | on triggers, plus reviews OneMind chose, plus forced reviews before consequential milestones |
| Goes up to | Flash, same run | Mind, same run, automatic handover | the wheel (it takes over itself) |

**Escalation never restarts a run.** Instant → Flash keeps the Score. Flash → Mind hands the Flash mind's run to
OneMind as its first briefing (§8). A Mind run whose remaining work is routine lets the crew run it with OneMind
asleep until the next barrier.

The router (plans 63–65: grammar, rules, Replay, Consensus/Sprint engines, deciding while you speak) is
**unchanged**. It chooses the starting difficulty.

## 3. The Score: one shared truth per run

The Score is typed data in code (Kotlin, pure, persisted with the mission journal). It holds:

| Section | Written by | Notes |
|---|---|---|
| goal and its versions | owner, mind | a steer is a new version |
| plan: milestones → steps, each with `do`, target hint, `expect` (postcondition), `risk`, slots | the mind (or a recipe) | branches allowed in Flash plans |
| facts: values found, with their source (tool, step, time) | crew, tools, mind | numbers, names and amounts live **only** here, copied exactly |
| events: one typed line per thing that happened | crew, tools, checks, code | the raw material for briefings |
| standing orders | the mind | at most 8 |
| barriers | code + the mind | |
| ledger: every model call with model, tokens, cost, ms | code | shown live; **no cost caps** |

**The separation rule:**
- the crew can mark steps done, failed or blocked, and propose a skip-ahead;
- **only a mind replans;**
- **only the owner changes the goal.**

## 4. The crew: how a step is decided, done and checked

### 4.1 The ladder

| Rung | Decided by | When |
|---|---|---|
| 0 | code | an exact label match, a map move, a recipe step, a direct action (open app, deep link, back, home), a tool call |
| 1 | JEV ∥ OpenAI Decisions (the plan 63 race) | a typed choice among shortlisted controls. Both must agree unless the move is easy to undo. |
| 2 | OpenAI Decisions with the screenshot | the tree is weak, or rung 1 disagrees |
| 3 | the run's mind | a fork, a surprise, words to write, or the rung below is unsure |

After a mind answers, the **next step starts at rung 0 again**.

### 4.2 One fused Decisions call per step

The Decisions API answers up to 200 questions in one call, in parallel, with almost no extra latency. Each step
sends **one** call carrying:
- **STEP:** `next` (choice), `fits_plan`, `surprise`;
- **VERIFY** of the previous step: `expectation_held`, `blocked_by`;
- **POPUP:** the interruption kind, and a dismiss target;
- **DIVERGENCE:** a score over on course / minor doubt / likely off / off;
- **STANDING-ORDER questions** (yes/no);
- **TRIPWIRE**, when the candidate move isn't easy to undo: sends, money, deletes, shares personal data, account,
  `matches_request`.

DONE (at a finish) and DRAFT (before a send) are separate calls.

**Models:**
- JEV is `~typesafe/jev-latest`;
- OpenAI Decisions is `openai/gpt-6-luna-decisions`;
- always the latest versions, with no pinning.

### 4.3 Rules from the decision models' known weak spots (plan 66 §10.6)

- Counting, maths, dates and comparisons are done in **code**. Decision models only **pick**.
- Keep the state under 32 KB, so JEV stays in its fast mode. Code shortlists controls (≤ 25).
- Decision models **never write words**, never approve, and never declare done on their own.
- Screen text is untrusted. A decision model can only pick among code-valid options or stop.

### 4.4 Checks (each can only stop or escalate, never approve)

| Check | When | Result |
|---|---|---|
| Postcondition | after every action. Code first, then VERIFY. | held → next step; missed → climb |
| POPUP | a screen that isn't in the plan | dismiss an easy-to-undo popup, or climb |
| TRIPWIRE | beside any move that isn't easy to undo | stop and climb to the mind or the owner |
| DRAFT | before a gated send, on the exact text | a sharper approval card, or back to the mind |
| DONE | whenever a finish is claimed: code predicate + DONE question | **doubt means not done:** the mind is woken to fix it or explain |
| ASK | before a mind interrupts the owner | the mind gets **one** more try with a hint; consent questions always reach the owner |

### 4.5 Vision features

**Box ("Where?") and icons as answer options run in shadow only.** They record where they would tap and **never
tap**, until TypeSafe calls image input and Box production-ready.

### 4.6 Tools before taps

Calendar, notifications, contacts, timers, settings, memory, deep links and **Cyclone Ports** are typed plan steps
run by code (rung 0), with typed results stored as facts. Arguments come from three places:
- the mind;
- code, from earlier facts;
- a decision model **picking** among candidates code extracted.

Rules for ports and writing tools:
- `port_send` (to the PC or Glass) is a **barrier**, checked by DRAFT and TRIPWIRE. Never passwords, codes or private
  screens.
- `port_wait` costs nothing while it waits, and its arrival is an event.
- Writing tools that reach people or devices are barriers.

## 5. OneMind: the overseer of Mind runs

### 5.1 One conversation, grown by wakes

OneMind is the owner's selected frontier model, with **one conversation for the whole run**. Each wake appends:
1. a **briefing**, built by code: the events since its last look, the trigger, focused evidence (screen text; an
   image only when the trigger is visual; before/after for a surprise);
2. OneMind's reply.

Routine steps never enter the conversation one by one. The conversation only ever appends, so the provider's prompt
cache keeps hitting.

### 5.2 Standing orders

When OneMind writes the plan, it writes up to 8 standing orders, for example:
- "Wake me when the invoice is open."
- "Wake me if the amount is over €400."
- "Dismiss popups in this app."

Code compiles each order into a yes/no question in the fused call, a code check on a fact (comparisons are always
code), or a briefing filter. Orders never grant anything.

### 5.3 Wakes

| Kind | When | Effort | Blocks the work? |
|---|---|---|---|
| **Glance** | a milestone OneMind chose to review; a "tell me" order | low | **no**: the crew keeps going on easy-to-undo moves |
| **Consult** | a fork, a shortcut, an ASK | low | only that step |
| **Replan** | a surprise, a block, drift, DIVERGENCE "likely off" (or "minor doubt" twice) | owner's setting | until answered |
| **The wheel** | an unknown app, an open-ended stretch, repeated surprises, a steer, or OneMind's own choice | owner's setting | the crew stands by |

**OneMind chooses which milestones to review.** Code **forces** a review before any milestone that ends in a send,
payment or delete.

### 5.4 Barriers

No work crosses a barrier while a review is open. Barriers are:
- every gated or irreversible action (send, pay, delete, grant, post, `port_send`);
- leaving an app with unsaved work;
- the end of each milestone in Mind runs;
- any point OneMind marks.

If a Glance disagrees with moves the crew made meanwhile, those can only have been easy-to-undo moves, and the crew
undoes them.

### 5.5 Replans, the wheel and detours

- **Replans are local.** They rewrite the rest of a milestone, the remaining milestones, or the whole plan (after a
  steer). Finished, verified milestones stay finished.
- **The wheel is today's Mind loop, unchanged.** OneMind hands back by writing a new plan.
- **Detours** (a 2FA code, a login, a lookup) push a sub-plan:
  - the paused milestone keeps its state;
  - on return, the run re-checks the paused screen (and navigates back with the map or Back if it is gone);
  - values the detour found become slots;
  - at most 3 deep.
- **Steer, Queue, Parallel and Pause** stay as in plan 38.

## 6. The Flash mind

**The model:** the owner's selected model (it may be revisited later), with one small conversation (a few thousand
tokens).

**It does what OneMind does, in miniature:**
- plans;
- answers consults;
- replans (up to 3);
- writes short texts (DRAFT-checked; gated sends still go to the owner).

**What makes Flash fast:**
1. **The plan streams into action:** step 1 starts as soon as it is parsed.
2. **Branching micro-plans:** likely forks are written into the plan as conditions ("if already on → skip", "if two
   contacts match → the one with a recent chat"). The crew settles them in the fused call, with no wake.
3. **Already-done first:** if the goal already holds, Flash finishes at once ("Already on"), checked.
4. **Pipelined steps:** the next step's fused call is sent while the current action settles.
5. **One approval at the end:** for the single consequential move, with the exact result.

**Handover to OneMind is automatic and happens once at most.** It happens when:
- the Flash budget runs out (3 replans, 2 surprises in a milestone, 12 steps);
- the Flash mind says `needs_frontier`;
- a consequential target is ambiguous;
- the task turns out open-ended.

OneMind's first briefing is the Flash run (§8), and the card changes from Flash to Mind.

## 7. Limits on wakes, and the spiral check-in

**There are no cost caps.** Cost is counted and shown. These limits stop runaway runs instead:

- **Done doubt:** at most 2 re-checks. Then the run ends as **"Couldn't confirm: please check"**, never as "Done".
- **4 wakes in one milestone:** OneMind must rewrite its standing orders or take the wheel.
- **A spiral** (8 wakes in one milestone, or the same trigger three times with no milestone progress): a **quick
  check-in** with the owner: "This seems stuck on ⟨reason⟩. Keep going, change course, or stop?" The answer is a
  steer.
- **Handovers:** at most one Flash → Mind per run.
- **The hard loop breaker:**
  - the same action on the same screen twice without progress climbs a rung;
  - a mind proposing the same failing action twice triggers the check-in;
  - 10 actions with no plan step completed stops the run, with an explanation.

## 8. Compaction: one setting, only when really needed

This replaces plan 68's Chronicler cadence and its cache-price maths. Like Claude and ChatGPT, OneMind compacts
history **only past a limit the owner sets**.

**The setting:** Settings → Speed → **Compact history at**: **100k tokens** (default) · 50k · 200k · Off (until the
model's own limit).

**What happens** when a mind's conversation passes the limit:
1. Compaction runs at the **next milestone boundary**, or at once if the conversation passes the limit by 20%.
2. Code folds old evidence (screens, images, old briefings) into one-line briefs, and finished milestones into one
   verified line each.
3. OneMind's own words (plans, decisions, reasons) are kept whole. **Facts are kept exactly.**
4. **One GPT-6 Luna call** writes a short "story so far" for the folded part: what was learned, what is still open.
   If Luna fails, the code version is used.
5. `recall` reopens anything folded.

**It never compacts:**
- the Flash mind's conversation (it is small);
- below the limit;
- in the middle of a milestone, unless the conversation is past the limit by 20%.

**Luna is also used once for a Flash → Mind handover,** to summarise the Flash run into OneMind's first briefing,
with the same code fallback.

The existing `MindConversation.compact` (today: a fixed 100k-*character* ceiling) becomes this token-based setting.

## 9. Safety: unchanged, and never weaker

- **Code gates decide** what needs approval: `GateClassifier`, the access profile, `ClickGateIntercept`,
  exact-text approval for sends, and the vault for secrets.
- **No model, mind or crew member can approve anything.** Recipes never carry an approval forward.
- `MindRedaction` scrubs everything written to disk, traces, Luna or ports. Screens with secrets never leave the
  phone.
- Strict privacy sets zero-data-retention routing on every call that supports it.

## 10. What the owner sees

- **Phone card and Glass:**
  - milestones with their states;
  - the current step line (built by code from the plan);
  - each wake with its reason;
  - cost so far.
- **Every step on tap.**
- One approval card per consequential move.
- **The check-in card** when a run seems stuck (§7).
- **The end report,** built by code from facts and milestones: for example, "Done: paid €482.10 to Mark, logged in
  October, replied thanks".
- **Recipes start automatically** after a verified run. Any surprise falls back to planning and updates the recipe.

## 11. Owner decisions (all final)

| # | Decision |
|---|---|
| 1 | OneMind chooses its milestone reviews; code forces one before consequential milestones |
| 2 | The crew keeps working on easy-to-undo moves during a glance |
| 3 | The Flash mind uses the owner's selected model |
| 4 | Flash hands over to OneMind automatically |
| 5 | No cost caps; cost is shown |
| 6 | Done doubt means not done (with at most 2 re-checks, §7) |
| 7 | Glass and the card show milestones, wakes and cost; detail on tap |
| 8 | Recipes start automatically |
| 9 | JEV and OpenAI Decisions: always the latest versions |
| 10 | Box and icon taps: shadow only until TypeSafe calls them ready |
| 11 | A question is tried once before it reaches the owner; consent always reaches the owner |
| 12 | One full rebuild, released at once as the only engine; no switch, no transition, no proving period; the owner tests on it |
| 13 | Limits on wakes, with a spiral check-in (§7) |
| 14 | Compaction: one owner setting, default 100k tokens (§8) |

## 12. The build programme

Seven code runs. Each ends compiling, with existing tests and CI green. Nothing is released before run 7.

**New code lives in `mind/one/`.** Today's `MindLoop` becomes the wheel.

**Run 1, the Score, the ledger, the breaker:**
- `mind/one/Score.kt`: goal versions, plan, facts with provenance, events, standing orders, barriers. Pure,
  serialisable with the mission journal.
- `mind/one/Ledger.kt`: every call counted, including today's Pilot, advisor and Decisions calls.
- `mind/one/Breaker.kt`: the hard breaker and the wake limits of §7.
- Today's `MindLoop` and `PhoneMindToolbox` write their events into the Score.

**Run 2, the crew:**
- `mind/one/StepEngine.kt`: the ladder, rungs 0–2.
- `mind/one/FusedBoard.kt`: one Decisions call per step (STEP, VERIFY, POPUP, DIVERGENCE, standing orders,
  TRIPWIRE), built on `decisions/DecisionsWire.kt` and the race in `mind/modes/Ensemble.kt`.
- Postconditions, DONE and DRAFT.
- Tools as plan steps, reusing `PhoneMindToolbox`'s tool implementations.
- Box and icon matching in shadow.
- The Pilot (`mind/pilot/Pilot.kt`) is folded into the StepEngine.

**Run 3, compaction and briefings:**
- `mind/one/Briefing.kt`: event lines, the trigger, evidence.
- `mind/one/Compactor.kt`: the setting of §8, folding, the one Luna call with code fallback.
- The handover summary.
- The "Compact history at" setting in Settings → Speed.
- `MindConversation.compact` moves to tokens.

**Run 4, OneMind:**
- `mind/one/Conductor.kt`: one conversation, wakes and their effort, standing orders compiled into the fused call,
  barriers with parallel glances, local replans, the wheel (today's `MindLoop`), detours, the check-in card.
- `mind/one/StandingOrders.kt`.

**Run 5, Flash:**
- The Conductor's Flash profile on the owner's model: streamed branching plans, already-done first, pipelined
  steps, one approval at the end, automatic handover.
- `CycloneModes.mission` sends Flash here. `MindPrompt.FLASH` is retired.

**Run 6, recipes, Instant, Glass:**
- `mind/one/Recipes.kt`: compiled from verified runs and started automatically. It extends `mind/decide/Replay.kt`.
- Instant as a one-step plan on the same runtime.
- The card and Glass show milestones, the step line, wakes and cost (`ui/overlay`, the gateway's health and task
  feeds).

**Run 7, build and release:**
- remove the old Flash/Mind split;
- Mobile CI;
- release notes;
- device matrix rows for the owner to test on the new system;
- release.

## 13. Not part of this plan

- No cost caps.
- No version pinning.
- No silent comparison runs.
- No staged rollout.
- No Run engine switch.

The classic page agent (`OpenRouterAdaptiveAgent`) is used today only when the Mind is turned off and for background
workspace tasks. It stays as it is for now. Moving those uses onto OneMind is a later plan.
