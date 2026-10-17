# Plan 66: The One Mind runtime

> **Design history.** The final plan is [plan 69](69-onemind-final-plan.md). Where this plan differs from it, plan 69 wins.

**Status:** design (2026-10-16). Nothing is built yet. §10 adds what JEV 1.14 and the Decisions API offer: pin the
version now; Box for targets with no node; icon matching through images as options; one fused call per step. §11 keeps replanning and diverting: plan drive
and Mind drive in one run, wake triggers, local replans, detours, and a ≥ 98% recall bar measured on today's runs.

**Scope:** Flash and the Mind (`Mode.FLASH`, `Mode.MIND`, the "Cyclone Mind" missions).

**Builds on:**
- plan 16 (Cyclone Mind);
- plan 41 (the Pilot, lessons L1–L14);
- plan 42 (modes and the baton);
- plan 58 (Decisions wire, the destination index);
- plans 63 and 65 (decision engines, Replay, deciding while you speak).

## 1. What is true today

These facts come from a full read of the code, not from older plans.

**Flash has no engine of its own.**
- A Flash run is a Mind mission with one extra sentence: "This is a quick run…" (`MindPrompt.FLASH`, used once at
  `MindMissions.kt:765`).
- It uses the same frontier model, the same 30-minute budget, the same 400 turns and the same card ("Cyclone Mind").
- Plan 42's `FlashRun` "with its own planner" was never built.

**Every step of every Flash or Mind run is one frontier-model call.**
- The model gets the whole compacted conversation: up to 100k characters, about 15–25k tokens a turn.
- It also gets about 45 tool schemas and the screen as text.
- It is one call per turn, not streamed, with up to 180 s per call.
- Only Anthropic models get cache breakpoints.

**The cheap decision path inside missions is off.**
- The Pilot (plan 41) walks a plan on a fast model or on Decisions/JEV.
- It is the only cheap path inside a run, and `FastMode.enabled` defaults to **false**.
- So in practice Decisions and JEV are used only *before* a run starts (routing). Never during it.

**Nothing checks the finish.**
- A run ends when the model calls `task_finish` with some evidence.
- No independent check confirms that the goal was met.
- The gateway Lab has a `false_success` category because this happens.

**Loops only warn.**
- A repeated identical failure adds a note for the model.
- Nothing else stops a looping run except 30 minutes of working time or 400 turns.

**Cost is half-counted and uncapped on the phone.**
- `MindUsage` counts the main model only. Pilot and advisor calls are thrown away.
- The phone has no USD cap. Only the PC Manager has one.

**Safety is good, and stays.**
- Gates are decided by code: `GateClassifier` (PAY/SEND/DELETE/GRANT), the access profile, `ClickGateIntercept`
  on the real click, exact-text approval for sends, and the vault for secrets.
- Models never approve anything. This plan keeps that exactly.

**The arithmetic of a routine 10-step Flash run today** (estimates, not measured on Cyclone):

| | Today |
|---|---|
| Frontier-model calls | about 12–20 turns |
| Input tokens | about 250–400k (each turn re-sends the conversation) |
| Wall time | about 60–90 s, mostly waiting for the model (plan 41 §2 measured ~70 s for 10 steps, against ~25 s of acting) |
| Who checks the finish | nobody but the model itself |

## 2. First principles

### 2.1 What a step needs

The question is what an agent step needs, not what our loop does.

Most steps of a phone task are **obvious given the plan**:
- tap the search field;
- type the name;
- tap the result;
- tap the message box;
- type;
- press send (gated).

They need *recognition*: which of these 40 controls is "the search field"? That is a typed choice. Decision
models answer typed choices in about 0.2–0.5 s for a fraction of a cent.

A few moments need a **mind**:
- understanding the goal;
- writing words;
- choosing between real alternatives ("which of the two Annas?");
- making sense of a surprise;
- deciding that the job is done.

**Today we pay mind prices for recognition work.** That is the whole cost and latency problem in one sentence.

### 2.2 Difficulty is a property of a moment, not of a request

"Send Anna the photo from yesterday" is:
- Mind-hard at one moment (which Anna, which photo);
- Instant-easy at nine (open WhatsApp, tap search…).

A runtime that picks one difficulty per request is wrong most of the time in both directions:
- too slow and expensive on the easy steps;
- or too shallow on the hard one.

**So Instant, Flash and the Mind stop being three engines.** They become three starting points on one ladder that
every single step climbs only as far as it needs.

That is what makes transitions seamless. There is nothing to transition between:
- an Instant action inside a Mind run is just a step decided on rung 0 or 1;
- a hard moment inside a Flash run is just a step that climbs to rung 3.

### 2.3 The best part is no part

Delete before optimising. Each item below goes, or folds into one place:

| What | Where it goes |
|---|---|
| The separate Flash concept | Gone. Flash is "the plan came from the fast planner". |
| Re-sending the whole conversation every turn | Gone. The Mind gets a situation report (§3.6). |
| A frontier call to tap an obvious button | Gone. |
| The Mind certifying its own success | Gone. Independent done check (§3.5). |
| Unbounded loops | Gone. A hard breaker (§3.7). |
| Uncounted calls | Gone. One ledger (§3.7). |

### 2.4 Reliability comes from checks, not from a smarter driver

A frontier model driving every step is still one point of failure per step.

The reliable shape is the one rockets use:
- a plan;
- a cheap, independent check after every action;
- an expensive mind only where the checks say something is off.

**Every action gets a postcondition, and every postcondition gets checked before the next step.** Errors are then
caught one step after they happen, not ten steps later, when the model notices it is in the wrong chat.

### 2.5 Decision models can only make things safer, never riskier

The rule keeps plan 41's lessons. A decision model may:
- pick among options the code made valid;
- say "stop, this needs the Mind";
- say "stop, this needs the owner".

A decision model may **never**:
- approve a gated action;
- declare a run done;
- write words that get sent.

## 3. The system

### 3.1 One loop

```
request ──► Route (plans 63/65: grammar, rules, Replay, engines)
              │  gives: a starting plan source + a starting rung
              ▼
        ┌──────────── THE PLAN (a contract, typed) ────────────┐
        │ goal · steps[do, target hint, expect, risk, slots]   │
        │ done predicate · owner-visible milestones            │
        └───────────────────────────────────────────────────────┘
              │
              ▼   for each step:
   OBSERVE (tree + screen keys; image only when weak) ─► interruption? ─► POPUP board
              │
   DECIDE on the ladder:  rung 0  code / map / recipe ............ 0 calls
                          rung 1  Decisions race (JEV ∥ OpenAI) .. ~0.3 s
                          rung 2  Decisions + screenshot ......... ~0.5 s
                          rung 3  the Mind, with a situation report
              │
   TRIPWIRE (parallel, cheap; only for anything not easy to undo) ─► may stop, never approve
   GATE (code, unchanged) ─► owner approves sends, payments, deletes, grants
              │
   ACT  (PhoneToolExecutor, unchanged: the only mutation path)
              │
   VERIFY (code first, then the VERIFY board) ─► held: next step
              │                                 └ missed: climb a rung for this step
              ▼
   DONE CHECK (code predicate + DONE board, independent of whoever claims done) ─► finish, or back to the Mind
              │
   RECIPE (a verified run compiles to a recipe: the next time it runs on rung 0)
```

**One task card, one trace, one cost ledger, from the first word to the finish.**
- The card's title shows the rung the run is on, not "Cyclone Mind" for everything.
- The owner sees one smooth run that sometimes "thinks" for a moment.

### 3.2 The plan: where the intelligence goes, once

A plan is typed data, not prose. Each step has:
- `do`: a verb and a target hint ("tap · the search field");
- `expect`: a postcondition as a screen predicate, for example:
  - "the app is WhatsApp";
  - "a field with focus";
  - "a row containing 'Anna'";
  - "the composer contains the draft";
- `risk`: undoable / gated / irreversible, taken from the action class (code decides; the plan can only raise it);
- `slots`: values the plan carries, for example the composed message, written once by the planner.

The plan also carries a **done predicate**: what the screen must show when the goal is met.

**Plan sources, cheapest first:**
1. **Recipe** (§3.8): a verified earlier run of the same kind of request, slots filled by code or by one
   Decisions call. **No model writes anything.**
2. **Map / destination index** (plan 58 §5C): known screens and moves.
3. **Fast planner**: GPT-6 Luna (the chat model, about 1–2 s), as plan 62 intended. **This is Flash.**
4. **The Mind**: the frontier model at the owner's effort. **This is the Mind.**

The router chooses the starting source. Any step that the plan can't carry escalates to rung 3. The Mind can then
rewrite the rest of the plan, and the run continues cheaply after that.

### 3.3 The ladder: deciding each step

**Rung 0 (code):**
- the step's target hint resolves to exactly one control by code: an exact label, a map move, or a recipe's
  recorded control identity;
- or the step is a direct action: open an app, a deep link, settings, back, home, wait.

There is no call. Most recipe steps and many plan steps land here.

**Rung 1 (the STEP board):** Decisions, JEV and OpenAI Decisions racing, as in plan 63's Consensus.
- `next`: a choice over the shortlisted controls plus typed verbs (scroll, back, wait, done-with-step,
  needs_mind);
- `fits_plan`: noul;
- `surprise`: noul (the screen is not what this step expected).

The rule for acting:
- an easy-to-undo move acts on Sprint;
- anything else needs Consensus.

This is plan 41's Pilot, promoted from an optional side tool to the default step engine, with two models instead of
one.

**Rung 2 (the same board with the screenshot):** OpenAI Decisions with the marked screenshot. Used when:
- the tree is weak (no useful controls);
- or rung 1 disagrees.

**Rung 3 (the Mind):** the frontier model, called with a **situation report** (§3.6), not the conversation. It
returns one of:
- an action for this step;
- a rewritten rest of the plan;
- a question for the owner;
- give up.

A step climbs **only** when:
- the rung below is unsure or disagrees;
- its result missed its postcondition;
- or the code says the step needs words or a real judgement.

A step never climbs because the request was labelled "Mind". After the Mind has answered, the **next** step starts
at rung 0 again. That is the seamless transition.

### 3.4 Cheap checks everywhere (almost free)

Each check is one Decisions call: about 0.2–0.5 s, a fraction of a cent, often in parallel with something else.

| Board | When | Questions | It can |
|---|---|---|---|
| **VERIFY** | after every action, only when code can't settle the `expect` predicate | `expectation_held` (noul); `blocked_by` (dialog, login, permission, error, none) | move on, or climb a rung |
| **POPUP** | a new screen that isn't in the plan | interruption kind (cookie, rating, permission, update, ad, none); `dismiss` target | dismiss an easy-to-undo popup on rung 1, or escalate. Popups waste many Mind turns today. |
| **TRIPWIRE** | in parallel with any move that isn't easy to undo, and before typing into any field | `sends_or_posts`, `money`, `destroys`, `shares_personal`, `account`; `matches_request` (is this what the owner asked for?) | stop and climb to the Mind or the owner. **Never approves.** The code gate still decides. |
| **DRAFT** | before a gated send, on the exact text | `faithful` (does the text say what the owner asked?); `recipient_matches` | send the owner a sharper approval card ("This may not match what you asked: …"), or bounce it to the Mind to rewrite. Never sends. |
| **DONE** | when anyone claims the goal is done | `goal_achieved` with the screenshot and the done predicate; `partial` | veto a false finish (back to the Mind with the reason). It can't finish a run on its own. |
| **ASK** | before the Mind interrupts the owner with a question | `answerable_from_context` (the goal, memory, the screen) | let the Mind try once more with the hint "the answer is likely in …". The owner is asked only when this says no, or when the question is about consent. |

Each board is typed, has a deadline, and degrades safely:
- **no answer** means today's behaviour (the Mind decides, or the owner is asked);
- **a disagreement** means the careful side.

### 3.5 The independent finish

Today's biggest silent failure is a run that says "done" when it isn't.

The new finish has two sides:
1. **Code first:** the plan's done predicate on the real screen.
   - Examples: the message bubble with the draft text exists and is newest; the timer is listed; the setting reads
     the new value.
   - `PhoneSettingReader` already does this for settings.
2. **The DONE board** with the screenshot when code can't settle it.

The decision:
- **both say done:** the run finishes;
- **the code predicate fails:** back to the Mind with exactly what's missing;
- **the board alone doubts:** the run finishes, but the card says "Done — please check", and the case is logged
  for the numbers.

### 3.6 The Mind's context diet

When the Mind is called (rung 3), the code builds a **situation report**:
- the goal and its version (steers included);
- the plan, with each step's state (done ✓ / here ▶ / to do);
- the last few events, one line each: "tapped Search (rung 0) · typed 'Anna' (rung 0) · verify missed: two Annas";
- the current screen (text, plus the image only when weak);
- the question for the Mind: "two contacts match 'Anna'; which, or ask the owner?";
- memory facts that apply.

The report is built so its first part stays byte-identical between calls (rules, then goal, then plan). That lets
**every** provider's automatic prompt cache hit, not only Anthropic's.

**Expected effect:**
- Mind calls go from about 20k tokens to about 5–8k;
- they happen only at real judgement points: about 1–4 per routine run instead of 12–20.

The full conversation is kept as the **record**, for the trace and for `recall`, not as the prompt.

### 3.7 One ledger, one breaker

**Ledger.** Every call is counted on the run: the plan, Mind calls, every board, the fast planner. Each entry has
its model, tokens, cost and ms. The owner gets:
- a per-run **cost cap**: default $0.50, switchable;
- a per-day **cap**: default $5.

Near a cap, the run asks before continuing. It never just stops in the middle of a send.

**Breaker** (hard limits, owned by code):
- the same action on the same screen key twice without progress: the step climbs a rung;
- the Mind proposing the same failing action twice: the run asks the owner;
- three missed postconditions in a row: the Mind rewrites the plan. A fourth: the run asks the owner;
- no plan step completed for 10 actions: the run stops and explains.

### 3.8 Recipes: the run that teaches itself

A run that passed its done check compiles to a **recipe**:
- the plan;
- each step's resolved control identity (the semantic name, role and screen key, as Replay and the map already
  store them);
- each postcondition;
- the slot boundaries: what was owner-specific, such as the contact name or the message text.

It is keyed by the request's shape:
- "message ⟨contact⟩ on WhatsApp ⟨text⟩";
- "set a timer for ⟨duration⟩";
- "turn on ⟨setting⟩".

The shape comes from the Triage board's typed slots (plan 64's Request Graph when it lands), not from words.

**Next time:**
- the router finds the recipe;
- slots are filled by code, or by one Decisions call;
- only a **written** slot (the message text) needs the fast planner;
- every step runs on rung 0 with its postcondition;
- any miss climbs as usual, and the run then updates the recipe.

**Recipes are forgotten** the same way Replay entries are: after a failed or cancelled run, or when the app changes
(its version or screen keys). They live on the phone only.

This is plan 65's Replay generalised to multi-step runs. It is the biggest saving over time, because
**people mostly repeat the same twenty kinds of task**.

## 4. What it should do to the numbers

Estimates from public latencies and today's token sizes. They are **targets to measure, not results.**

| A routine 10-step task | Today | One Mind runtime | With a recipe |
|---|---|---|---|
| Frontier-model calls | 12–20 | 1 plan (Luna, not frontier) + 0–2 Mind | 0 (1 Luna call if a slot must be written) |
| Decisions calls | 0 | about 10–25 (step, verify and check boards) | about 3–8 (checks only) |
| Input tokens to big models | 250–400k | about 10–20k | about 0–2k |
| Cost (frontier at ~$3/M input) | about $0.80–1.50 | about $0.03–0.08 | under $0.01 |
| Waiting on models | about 45–70 s | about 6–12 s (0.3 s boards; acting dominates) | about 1–3 s |
| Checked finish | no | yes | yes |

**A hard Mind task** (40 steps with real judgement):
- the Mind is called about 8–15 times instead of 50+, with a 5–8k context instead of 20k;
- **about 8–15× cheaper**, and it keeps the frontier model exactly where it earns its price.

## 5. Why this won't repeat alpha.23

Plan 41 deferred "routes and forks" because alpha.23's screen shortcuts **hijacked runs**: they acted before
anything understood the goal. This design differs in four ways:
1. **A goal-understanding model writes the plan first** (or a verified recipe of the same request shape). Rungs
   only *execute* it.
2. **Every step is checked against its postcondition** and `fits_plan`. A step that drifts climbs at once, not
   ten steps later.
3. **Climbing is cheap and fast:** about 0.5 s to the board, or one Mind call. "Unsure goes up" costs little,
   so the thresholds can be strict.
4. **The code still owns safety:** gates, the vault, keep-off apps, exact-text approval.

## 6. Points of failure, before and after

| | Today | One Mind runtime |
|---|---|---|
| Engines | Instant + the Mind (Flash is a sentence), plus the classic agent for background tasks | one runtime; Instant is a one-step plan |
| A wrong step | noticed when the model notices | caught by the next postcondition |
| A false finish | not caught on the phone | code predicate + DONE board |
| A loop | 30 minutes or 400 turns | the breaker, within 2–4 actions |
| A provider outage | backup model once, then stop | rungs degrade: Decisions down → the Mind decides; the Mind down → the run asks the owner and pauses (resumable) |
| Cost | uncounted parts, no cap | one ledger, run and day caps |
| Safety | code gates | the same code gates + TRIPWIRE and DRAFT checks that can only stop |

## 7. Releases

> **Superseded by the owner (plan 67 §19):** there is no staged rollout and no switch. The whole runtime is built
> in the code runs of plan 67 §17 and released at once as the only engine, and the owner tests on it directly. The
> list below is kept as the order of work.

Each release ships behind a switch in Settings → Speed → **Run engine: Classic / One Mind**, so either can be
chosen without live tests, as with the decision engines. Classic is today's Mind loop, unchanged.

**R0 · Measure and guard** (low risk, no change to how runs act):
- the ledger: count every call (including the Pilot and the advisor), with run and day caps;
- the hard breaker;
- DONE, TRIPWIRE and VERIFY boards in **shadow** inside today's Mind runs: they record and never act. This gives
  their agreement with the Mind and the false finishes they would catch.
- Proven offline with golden runs from the Lab traces, the same way plan 63 proved the engines.

**R1 · Flash becomes real:**
- the plan contract (typed steps, postconditions, done predicate);
- the fast planner (GPT-6 Luna);
- the step engine (rungs 0–2; the Pilot's code becomes its core);
- POPUP and VERIFY acting;
- the DONE check acting.

Rung 3 is today's Mind loop, started with the situation report and handing back after one answer.

**R2 · The Mind on the ladder:**
- Mind runs start with a Mind-written plan, and routine stretches run on rungs 0–2;
- the context diet;
- TRIPWIRE, DRAFT and ASK acting.

**R3 · Recipes:**
- verified runs compile;
- the router starts known request shapes from a recipe.

**R4 · One runtime:**
- one task card that shows the rung;
- Instant becomes a one-step plan on the same runtime;
- the classic agent's remaining uses (background workspace) move over;
- the Flash/Mind split leaves the code.

## 8. Open questions for the owner

1. **Caps:** are $0.50 a run and $5 a day the right defaults, and should a run ask or stop at the cap?
2. **The fast planner:** GPT-6 Luna, or the owner's chosen frontier model at low effort?
3. **"Done — please check":** show it when only the DONE board doubts, or treat every doubt as not done?

## 9. Limits, stated plainly

- **Nothing here is measured on Cyclone yet.** R0 exists to measure before anything acts.
- **Decision models have not been tested on Cyclone's step boards at scale.** The Pilot's route to them exists but
  has been off by default.
- **Postconditions must be written well.** A weak `expect` either climbs too often (costly but safe) or misses a
  wrong step (the done check is the backstop).
- **Recipes depend on stable screen identities across app updates.** Plan 58's destination index and the map
  already face this; recipes are forgotten when an app changes.
- **The plan must be honest about writing.** Any step that composes words for someone else always goes through the
  planner or the Mind, and then the DRAFT check and the owner's approval.

## 10. What JEV 1.14 and the Decisions API add (researched 2026-10-16)

Sources:
- OpenRouter's Jev guide, Multimodal Decisions guide and Decisions API reference;
- TypeSafe's docs: images, box, confidence, the Jev 1.13 jaggedness page, cookbooks;
- release coverage of Jev 1.14.0, 14 October 2026.

Every feature below is mapped to where it fits in the runtime, or why it doesn't.

### 10.1 The JEV version (owner's decision: follow latest, see plan 67 §16)

- Cyclone calls `~typesafe/jev-latest` (`Decisions.kt:42`, `FastMode.kt:50`).
- That alias moves from 1.13 to **1.14 around 21 October 2026**.
- TypeSafe has published no 1.14 benchmarks and no calibration notes, and says to recheck thresholds tuned on 1.13.

**Do now:**
- pin production boards to `typesafe/jev-1.13`;
- run 1.14 in shadow on the golden set and the engine log;
- move over only when the numbers say so.

1.13 stays available with 60 days' notice before it is deprecated.

### 10.2 Box: the "Where?" question (1.14 only, experimental)

**What it is:**
- A `box` question names one target in an image. It answers `top`/`bottom`/`left`/`right` as fractions from the
  top-left corner, plus `confidence`.
- **It has no "not found":** it always answers a box. TypeSafe's rule is to pair it with a `noul` ("is X in it?") in
  the same call and ignore the box when that is low. That is exactly the screenshot you sent: *Is he in it? 0.91 ·
  Where? Boxed 0.80*.
- One target per question. "Several" needs a Choice of none / one / several first. Small targets need a two-stage
  ask (a Choice over image tiles, then Box on the tile).
- Needs the header `TypeSafe-Danger-Zone-Images: true` (to be confirmed through OpenRouter with the probe).
- Any image puts JEV in its **slower, less accurate long-context mode**.
- TypeSafe calls it "dreamer-ready", **not production-ready**.

**Where it fits:** a new **rung 2b, sight with no node**. It is for when a step's target has no accessibility node
at all:
- canvas or game views, camera viewfinders, map pins;
- images in a feed ("open the first photo");
- web views with a dead tree.

Today those cost a frontier call with a screenshot.

**One call:**
- Box("the ⟨target⟩ in `screen`") + Noul("is ⟨target⟩ visible in `screen`") + Choice(none/one/several).
- Tap the box centre with `tap_point` only when **all** of these hold:
  - the noul ≥ 0.9;
  - the choice is "one";
  - the box is plausibly sized;
  - the move is easy to undo.

Point taps already go through `ClickGateIntercept`'s labels-at-point check. After the tap, the VERIFY question
confirms the expected change. Anything else climbs to rung 3.

**Ships in shadow first** (R0/R1). It records where JEV *would* tap against where the Mind did. It acts only after
the numbers show it is sure, and never for gated moves.

### 10.3 Images inside criteria and instructions: icon matching (1.13 and 1.14)

**What it is:**
- Choice options, Score levels and Noul definitions can carry **images**.
- Several named images can sit in one state (`` `before` ``, `` `after` ``, `` `frames[2]` ``) through an `assets`
  map of reference strings.

**Where it fits** (better than Box for most cases, because the answer is a **real node**):

- **Icon-only controls.** Code crops each unlabelled clickable control from the screenshot, using the accessibility
  bounds it already has. The Choice options *are* those crops: "which of these is the shutter / send / like
  button?". The answer is an element id, so the tap goes through the normal tree path and every gate. This
  replaces the screenshot-to-the-Mind step for icon buttons, and the Instant follow-ups' sight box.
- **VERIFY with two pictures.** Before and after screenshots as `` `before` `` / `` `after` ``: "did the screen
  change the way step k expected?". This catches the silent no-ops that the text tree misses: a "liked" heart, a
  toggled switch drawn as an image.
- **DONE with the evidence picture:** the final screen and the done predicate, in one Noul.

**The caveat:** images trail text in accuracy, and every image means long-context mode. Use images only where the
tree has nothing, and keep OpenAI Decisions (up to 128 images, built for vision) as the racing partner for any
image question.

**Open question:** OpenRouter's route takes images as `image_url` parts in `state`. TypeSafe's own `assets` /
reference-string form may not pass through OpenRouter. The probe settles this before any build.

### 10.4 Fan-out: one call per step, not one per board

**What it is:**
- Up to **200 questions per call**, each answered in isolation against the same state.
- "Adding questions barely changes the response time."

**This changes §3.4:** the boards are not separate calls. Each step sends **one fused call**:
- STEP (`next`, `fits_plan`, `surprise`);
- VERIFY of the step that just ran;
- POPUP;
- TRIPWIRE when the candidate move isn't easy to undo.

DONE and DRAFT stay separate, since they run once a run.

**Expected effect:** about 3× fewer calls than §4 estimated, with the same latency. A routine 10-step run then
needs about 10–12 Decisions calls in total, about $0.001 at JEV's **$0.042 per million input tokens (output free)**.

### 10.5 Confidence, used the way it is defined

- **Choice and Score:** `confidence` is computed from the probability spread (1 = all on one answer).
- **Noul:** has no confidence. Use |2p − 1|.
- **TypeSafe's routing pattern:** high → act; medium → confirm; low → don't act. The bars are scaled to risk, with a
  0.5 floor.

That is the ladder of §3.3 exactly:
- **high** acts on the rung;
- **medium** climbs a rung (it never asks the owner for a routine step);
- **low** climbs straight to the Mind.

**Bars are per model and per action class,** never shared: probabilities differ between models. R0's shadow log
calibrates them.

### 10.6 The jaggedness rules become design rules

TypeSafe's list of Jev 1.13's rough edges, each turned into a rule:

| Rough edge | Rule in the runtime |
|---|---|
| Reads literally | One literal question per factor, combined in code (the boards already do this). |
| Not a calculator; can't count | Counting, comparing prices, times and dates stays in code. JEV only *picks*. "The 3rd row" is resolved by code from the list, not asked. |
| Weak on dates | Dates extracted as a Choice of parts, compared in code. |
| Large state lowers accuracy | State stays lean: code shortlists controls (≤ 25), drops irrelevant text, and keeps every board under **32,000 bytes**, so JEV stays in its fast mode. Enforced by code. |
| Leans to the first option | The second phrasing (plan 65, built, off) switches on **only** for moves that aren't easy to undo. |
| Adversarial content can steer it | The screen is untrusted: a page can say "tap Approve". Decision models can only pick among code-valid options or stop. Gated moves need the code gate and the owner, and TRIPWIRE can only stop. So the worst an injected page can cause is a climb, never an approval. |
| Can't generate | Words always come from the planner or the Mind (§9). |

### 10.7 The rest of the API

- **`session_id` and `trace`:** set the run id as `session_id` on every board of a run, so OpenRouter's
  observability groups a run's calls. The wire already supports it (`DecisionsWire.kt:119`). Never put request text
  in `trace`.
- **`provider.zdr`**, already wired: on when Strict privacy is on.
- **`provider.max_price`:** per-call price caps, a second fence under the ledger's caps.
- **`provider.preferred_max_latency`:** a p90 latency cutoff for step boards, so a slow provider route is skipped
  instead of waited for.
- **Long context (262k, header `TypeSafe-Danger-Zone-Long-Context`):** **not used.** It is slower and less
  accurate, and lean state is the design.

### 10.8 Other decision models: candidates, not choices

OpenRouter now lists about 19–21 decision models on the same Decisions API:

| Model | Note |
|---|---|
| Perplexity `pplx-decider-v1.1-27b` | images |
| Cloudflare `clef`, `clef-flash`, `clef-omni` | images; omni also does audio and video; at most 4 images |
| Inception `mercury-decide` | has a **free** route |
| Upstage `solar-decide` | |
| Microsoft Decision-1 | |
| Liquid, Together and others | |

**Use:**
- a **third voter in shadow**: free or near-free;
- for easy-to-undo steps only;
- adopted only if the engine log shows it lowers disagreement without lowering accuracy.

Plan 63's race takes any number of lanes, so adding one is a list entry. Nothing changes until measured.

### 10.9 Jev Router: maybe, for the Mind's effort

`typesafe/jev-router` is a **chat** model id that picks a model and a reasoning effort for each request.

**For:** it could pick the effort of each rung-3 call (low for "which Anna", high for a new plan).

**Against:**
- the response doesn't say which model served the call;
- the owner chose their Mind model.

So it is **Lab-only**: an experiment variant measured against the owner's model at fixed effort. It is never the
default without a visible "served by".

### 10.10 Cookbook patterns that map onto the runtime

| Cookbook | Becomes |
|---|---|
| SDE cascade (cheap extraction → verification → reasoning) | the ladder itself |
| Function calling (a request mapped to typed functions) | recipe lookup and slot filling (§3.8): the request's shape is a Choice over the recipe catalog, its slots are Choices over candidates extracted in code |
| Skill suggestion | picking a recipe or a manual ability from the catalog without the Mind |
| LLM guardrails | TRIPWIRE (inputs) and DRAFT (outputs) |
| Citation check | DRAFT's "does the text say what the owner asked" |
| Line search | finding "the email from Anna" or "the 9:30 alarm" among long lists by text, without a screenshot |
| Self-consistency | the second phrasing, for risky steps only |
| Basic memory | a cheap "is this worth remembering" before the Mind writes memory |

### 10.11 What changes in the releases

**R0 adds:**
- pin JEV 1.13;
- 1.14 in shadow;
- the probe checks the image header and the `assets` form through OpenRouter;
- Box and icon-matching in shadow;
- per-model bars calibrated from the shadow log.

**R1 uses:**
- one fused call per step (§10.4);
- icon matching acting for easy-to-undo moves;
- before/after VERIFY.

**R2 and later:** Box acts for easy-to-undo moves once its shadow numbers clear the bar. The third voter goes in if
measured.

## 11. Replanning and diverting: kept, and placed exactly where they are needed

**The risk this section answers.** Today the Mind sees every screen, so it can change course on any turn:
- it notices a shorter path;
- it notices the goal is already met;
- it notices it is in the wrong chat;
- it takes a detour (fetching a code from email, logging in);
- it follows an owner's steer.

A ladder that only *follows* a plan would lose that. A run that can't change course fails exactly where today's
Mind succeeds.

**The rule: never worse than Classic.** Every path that the cheap rungs can't settle ends in **Mind drive**: today's
continuous loop, turn by turn, with the full record. The new runtime adds speed and checks on top of today's
ability. It never replaces that ability.

### 11.1 Two drivers in one run: plan drive and Mind drive

- **Plan drive:** the rungs walk the plan, and the Mind is consulted at triggers (§11.2). This is the cheap, fast
  default for stretches the plan understands.
- **Mind drive:** the Mind holds the wheel turn by turn, exactly as today: every screen, every judgement, free
  replanning. It hands back to plan drive by writing a new plan for the rest, or for the current milestone.

Both drivers share one record, one card and one ledger. Switching costs nothing but the next call.

**The Mind takes the wheel by itself when:**
- the app or screen is unknown: no map, no recipe, and it is not in the plan's knowledge;
- the goal is open-ended (search, browse, compare, "find out…"), where the next step depends on what is read;
- two surprises happen inside one milestone;
- the owner steers;
- it decides to: rung 3 can always answer "I'll drive".

So exploration and messy stretches keep today's success. Only the understood stretches get cheap.

### 11.2 Wake triggers: when plan drive calls the Mind

Every trigger is detected by code, or by questions riding in the step's fused call (§10.4), so watching for them
costs nothing extra.

| Trigger | Detected by | The Mind gets |
|---|---|---|
| **Surprise:** the step's postcondition missed, or the screen isn't the expected one | code + VERIFY | "Expected X, see Y": the screen, the last events, the plan |
| **Blocked:** login, permission, error, paywall, or a popup that won't close | VERIFY `blocked_by` + POPUP | the blocker; it can take a detour (§11.4) |
| **Drift:** `fits_plan` low, or a step needs more than 4 moves | the STEP board + the breaker | the step's history |
| **New information** the plan depends on: a value read, a list of results, a reply | plan steps of type *read*, marked at plan time | what was read; the Mind decides the next part |
| **Fork:** a real choice between alternatives (two Annas, which flight) | the STEP board's `several` / disagreement | the options |
| **Opportunity:** the screen offers a more direct way to a later milestone | a `shortcut` question in the fused call: a choice over visible controls plus "none" | the shortcut; the Mind accepts or ignores it (one short call) |
| **Already done:** the goal's done predicate holds early | the DONE question in the fused call | early finish, checked as in §3.5 |
| **Owner steer** (plan 38) | Task Kit | the new goal version: an immediate replan, never handled by the rungs |
| **Checkpoint:** a milestone the Mind marked "review me" when it planned | the plan | a review call at that point |

### 11.3 Plans are layered, so replanning is local

A plan has **milestones**, owned by the Mind ("open Anna's chat", "attach yesterday's photo", "send"), and
**steps** under each, owned by the rungs.

**A replan rewrites only what it needs:**
- **Step level:** the rest of the current milestone's steps, a short call with a small report;
- **Milestone level:** the remaining milestones, when the route changes;
- **Goal level:** everything, after a steer or when the goal turns out to be different.

Completed milestones stay completed and verified. A replan never redoes them unless their postcondition no longer
holds.

**Mind runs keep strategic awareness.** In runs routed as Mind, the Mind gets a short **milestone review** at each
milestone boundary: one call with a 3–5k report. It can change course there, even when no trigger fired. That is
about 3–6 extra calls per run, and it is what keeps the Mind ahead of the work instead of only reacting to it.
Flash runs skip the reviews and rely on triggers.

### 11.4 Diversions and detours

Some runs must leave the plan and come back: a 2FA code from email or messages, a login, a missing permission, a
contact looked up in another app, or the owner's "and also…".

**A detour is a plan pushed onto a stack:**
1. The current milestone is paused with its state (where it was, what it expected).
2. The detour runs on the ladder like any plan.
3. When the detour's done predicate holds, the runtime returns to the paused milestone:
   - first it checks the paused milestone's screen predicate again;
   - if the screen is gone, it navigates back with the map or the Back stack;
   - if it can't, it climbs to the Mind.
4. Values the detour found (the code, the number) become slots for the paused milestone.

The rules for detours:
- **at most 3 detours deep;**
- a detour can never approve anything;
- a detour into a gated action asks the owner like any other.

**Owner diversions** keep plan 38 exactly:
- **Steer** is a new goal version and an immediate replan.
- **Queue** and **Parallel** are separate runs, as today.
- **Pause** holds at the next step boundary.

### 11.5 Escalation never loses context

When the Mind is woken, the situation report (§3.6) leads with **why it was woken**: the trigger and its evidence.
It includes:
- the plan with each milestone's state;
- the last events, one line each;
- the current screen;
- for a surprise, the before and after screens.

The full record stays available through `recall`, so the Mind can open any earlier screen or result when the report
isn't enough. Nothing the rungs did is hidden from it. It is only summarised.

### 11.6 Highest success: the checks that raise it above today

Today's Mind succeeds by noticing problems eventually. This runtime notices them on the step they happen:

| Failure today | Caught by |
|---|---|
| Wrong chat, wrong field, wrong item | the next postcondition, one step later (today: many turns later, or never) |
| A silent no-op (a tap that did nothing) | before/after VERIFY |
| Popups eating turns | POPUP |
| False "done" | DONE + the code predicate |
| Loops | the breaker, within 2–4 actions |
| Missing the faster route | the opportunity trigger |
| Doing work that was already done | the early done check |

Where the runtime is unsure, it does what today's Mind does, with the Mind in charge. A cheap mistake costs one
climb. It never costs a wrong action that isn't easy to undo: those still need agreement, the code gate and the
owner.

### 11.7 Measuring that replanning is kept

R0 has Lab traces of today's Mind runs, so the triggers can be checked before anything acts. Every point where
today's Mind actually changed course is a labelled example of "the Mind was needed here":
- a `plan_update`;
- a diversion;
- a recovery after a failed tool;
- a steer.

**R0 replays those runs with the triggers in shadow and measures:**
- **recall:** the share of real course changes where a trigger would have woken the Mind. **The bar is ≥ 98%.**
  Every miss is a run that could fail, so the triggers are tuned to over-wake.
- **precision:** how often a trigger woke the Mind for nothing. This is only a cost. It is tuned down after recall
  holds.
- **success parity:** the One Mind engine must match or beat Classic's success rate on the same Lab suite before it
  becomes the default for any difficulty. Until then Classic stays the default, and One Mind is a switch.
