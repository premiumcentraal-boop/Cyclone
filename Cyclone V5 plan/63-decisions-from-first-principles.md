# 63 · Decisions from first principles: what a good Cyclone truly is, and how to build it

Status: **Design + first build (2026-10-16).** §6's two engines are built (alpha.136, unreleased at the time of
writing); the rest is the plan. This plan consolidates plans 58 (the decision box), 61 (no trigger words) and 62 (the
Instant Mind) into one design, worked out from the product backwards. Physical device: **UNVERIFIED**.

**Owner's ask (2026-10-16):**
> Go through the whole plan again and think it through to the frontier. Cost is not an issue: parallel decisions,
> faster, more robust. Secure, strong, fast, powerful, smart. Not the first idea, but first principles on what a good
> Cyclone truly is. A big, thought-through build. I can't live-test, so build the two most confident options and let
> me switch between them in Settings.

---

## 0. The answer in short

**A good Cyclone is not a fast model. It is a phone that does what you meant, at once, and never does what you
didn't mean.** Everything below follows from that sentence.

Four conclusions come out of the first principles (§1–§5):
1. **"Instant" is mostly not about model speed.**
   - A person feels about 100 ms as instant and about 1 s as "it kept up".
   - Even the fastest decision model takes 150–550 ms, and Android needs 300 ms to settle after a tap.
   - So the frontier is reached by taking the model **off the critical path**: knowing the phone already, deciding
     while you speak, caching what each screen allows, and asking several models at once.
2. **Decision models are pickers, not thinkers.**
   - They choose well among options Cyclone supplies, and they can be wrong with confidence.
   - **Two independent models agreeing** is much stronger evidence than one model being "90% sure". Cost no longer
     matters (output is free, input is fractions of a cent), so Cyclone should ask both, every time.
3. **Safety is a property of actions, not of words.**
   - Easy-to-undo actions may act on less evidence, because the downside is a moment of your time.
   - Consequential ones (send, pay, delete, post, accounts) never act on a model's word alone, whatever the
     confidence.
   - This is how a safe system can also be the fastest one.
4. **Without live testing, build what can be proven offline.**
   - Safety properties can be proven with tests over the golden set and random model answers.
   - Speed and accuracy can't be proven without real calls.
   - So the two engines built now are the two whose **safety is provable** and whose **worst case is today's
     behaviour, or one rung up** (§6).

**Built in this release:** Settings → Speed → **Decision engine**:
- **Consensus** (the default): JEV and OpenAI Decisions answer at the same moment. Cyclone acts only when both agree.
- **Sprint**: both answer at the same moment, and the first sure answer acts at once, but only for an easy-to-undo
  action. Everything else waits for both.
- **Classic**: alpha.123's single model, kept as the way back.

---

## 1. First principles: what Cyclone is for

### 1.1 The job

**You say what you want, in your words and your language. Your phone does it, or tells you plainly why it won't.**

Cyclone is judged on outcomes, not on how clever its parts are. A request has gone well when:
1. the **right** thing happened;
2. it happened **soon enough** that you didn't wait or wonder;
3. **nothing you didn't want** happened;
4. **you could tell** what happened (a sound, a buzz, one line);
5. it worked in **your language and your words**;
6. it worked **every time**: on a bad network, when one provider is slow, when the screen is unusual.

These six are the whole specification. Every design choice below is a trade between them, and safety (3) always wins
a tie.

### 1.2 The physics of "instant"

| Threshold | What people feel | Consequence for Cyclone |
|---|---|---|
| ≤ 100 ms | Instant, cause and effect | Only reachable when no network call is on the critical path: the phone already knows (T0) |
| ≤ 400 ms | Fast, still one motion | One decision call, if it starts **before** you finish speaking, or runs in parallel with everything else |
| ≤ 1 s | It kept up | Two dependent calls at most; anything more needs visible progress |
| > 1 s | Waiting | Only for real work (writing, judgement, long tasks), shown honestly |

**Where the time goes today** for a request the command matching doesn't settle:
- reading the screen: 50–200 ms;
- one decision call: 150–550 ms median, tails to about 0.9 s;
- the move;
- the settle: 300 ms plus;
- app start: 0.5–2 s, outside Cyclone's control.

Done in a row, a "simple" request is 1–3 s. **The fix is not a faster model. The fix is to not wait for each step in
turn:**
- **perceive continuously:** the screen is already known;
- **decide early:** on partial speech, with candidates resolved in advance;
- **ask in parallel:** both models at once;
- **act on reversible moves as soon as they're sure,** then verify.

### 1.3 The truth about decision models

**What they are good at:**
- choosing among options Cyclone supplies, returning probabilities, in a few hundred milliseconds, in any language;
- reading images (OpenAI Decisions);
- pointing at a spot in one (JEV 1.14's box).

**What they are not:**
- They can't act, and they can't write.
- Their confidence is not a guarantee. A model can be 95% sure and wrong, especially on unusual inputs.
- Their errors are partly independent: different companies, different training, different architectures.

**Therefore:**
- **Candidates come from the phone, never from the model.** Models choose; they never invent an app, a contact or a
  button.
- **Agreement between independent models is the strongest cheap evidence there is.** If each model alone picks a
  wrong Instant action 5% of the time, both picking **the same** wrong action is far rarer. They would have to be wrong
  in the same way.
  - Errors are not fully independent: a misleading screen fools both. That is why Verify (§3, L5) stays, and why
    consequential actions also need you.
- **A refusal, a timeout or an unreadable answer is no answer,** never a "no".

### 1.4 Safety as architecture

**Every action Cyclone can take falls in one of three classes. The class decides how much evidence is needed:**

| Class | Examples | Evidence needed |
|---|---|---|
| **Easy to undo** | Volume, flashlight, media keys, Back/Home/Recents, scroll/swipe, open an app or the camera | One sure model is enough; both agreeing is better |
| **Owner-visible** | A call, an alarm, a timer | The command is clear and the target is certain; a 2-second "tap to stop" window |
| **Consequential** | Send, post, pay, buy, delete, sign in/out, permissions | **Never on a model's word.** Both models agree to *propose* it; you approve it; it is recorded |

**Two rules make this hold everywhere:**
- **The class is decided by code,** from the action's identity, and later from the control's **effect** (plan 61 B).
  It is never a model's opinion.
- **A model can only make a request more careful, never less** (the rules and structural safety floors come first).

### 1.5 Robustness: it must work every time

- **No single point of failure:** two decision models from two companies, each with its own breaker.
  - One slow → the other decides under the one-answer rule.
  - Both down → the request goes up a rung (Flash, then the Mind), never stalls.
- **Deadlines everywhere:** a late answer is no answer.
- **Degradation is graceful and visible:** the run trace says "only JEV answered in time", and the numbers count it.

---

## 2. The shape of the system: eight layers

```
L7  Measure & govern   golden set (20+ languages), calibration, gates, kill switches, numbers in Glass
L6  Learn              verified outcomes → on-phone exemplars, calibration, likely-next
L5  Verify & recover   did it work? undo reversible moves that didn't; hand up with the baton
L4  Act                PhoneToolExecutor only; one move per decision; approvals for consequential effects
L3  Decide             T0 on the phone → engines (JEV ∥ OpenAI Decisions) → GPT-6 Luna writes → the Mind
L2  Anticipate         affordance cache per screen; likely next requests; pre-resolved candidates; warm sockets
L1  Perceive           Live Phone Model: screen graph, effects, map position, sensitivity — always current
L0  Contract           typed everything: the Request Graph, capabilities, effects, answers, records
```

Each layer has one job and a typed boundary to the next. That is what makes each piece testable alone, and the
whole system explainable when something goes wrong.

| Layer | Where it is planned | State |
|---|---|---|
| L0 Contract | 58 (wire), 61 A (Request Graph), 61 B (effects) | **Wire built (alpha.123)**; graph and effects planned |
| L1 Perceive | 62 §3 L1 | Planned |
| L2 Anticipate | 62 §3 L2, 58 §5C (destination index) | Planned |
| L3 Decide | 58 (Triage), 61 A (T0), 62 §5 (race), **63 §6 (engines)** | **Triage + engines built**; T0 embedding planned |
| L4 Act | Existing (`PhoneToolExecutor`, Instant) | Built; effects-based gates planned (61 B) |
| L5 Verify | 58 §4, 62 §3 L5 | Planned |
| L6 Learn | 61 C, 62 §3 L5 | Phone model exists (word-based); embedding learning planned |
| L7 Measure | 58 §10–11, 63 §8 | **Golden set, Lab, call/watch/engine numbers built** |

---

## 3. The three models, each where it is strongest

| | **JEV 1.14** | **OpenAI Decisions** | **GPT-6 Luna** (chat) |
|---|---|---|---|
| Is | A decision model (TypeSafe) | OpenAI's Decisions API (`openai/gpt-6-luna-decisions`) | A generative model (`openai/gpt-6-luna`) |
| Strongest at | Fast typed choices on text; box on unlabelled pixels | Typed choices on text **and images** (up to 128) | Writing: free values, short plans, messages |
| Speed | ~0.2–0.5 s | ~0.16–0.55 s | ~1–2 s |
| Role | Lane 1 of every engine decision; box for games/canvases (planned) | Lane 2 of every engine decision; **all sight** | Only where something must be written; slow fallback when both decision models are down |

Plan 62 §1 has the evidence and its limits. Only one matched test compared the two decision models. Everything is
re-measured on Cyclone's own requests as soon as real calls are allowed (§9, R0).

---

## 4. Request Graph, effects and capabilities: the contract (L0)

These are planned in plan 61 and unchanged here. In short:
- **The Request Graph:** every request, in any language, becomes one typed graph: steps, a capability per step,
  validated slots, risk flags, time, a calibrated confidence. Every component reads the graph, never the words.
- **Effects:** every control on screen gets a typed effect (navigate, toggle, send, pay, delete, …) from structure,
  observed history and vision. Safety reads effects, never labels.
- **Capabilities:** the list of everything Cyclone can do in one move is data, with multilingual examples, an effect
  class and a verify check.

---

## 5. Speed by design, not by model (L1, L2, L3)

All planned in plan 62; the order of work changes (§9). The essentials:
- **Perceive continuously,** so reading the screen is never on the critical path.
- **Cache affordances per screen in idle time:**
  - OpenAI Decisions describes icon-only screens once;
  - JEV's box locates unlabelled targets once.
- **Decide on partial speech**, and send the decision boards speculatively once the sentence looks stable.
- **Race the decision models** (§6). The engine settles in the time of the faster model (Sprint), or of both
  (Consensus), never the sum.
- **Act on easy-to-undo moves when sure, then verify,** with auto-undo (planned, L5).

---

## 6. What is built now: the two engines

### 6.1 Why these two, and why now

Without live testing, an option is "confident" when three things hold:
1. **Its safety can be proven offline.** Tests over the golden set and random model answers check every rule, and
   CI guards keep them true.
2. **Its worst case is no worse than today.** If a model's wire, speed or judgement turns out bad, the engine falls
   back to the other model or goes one rung up. It never acts where today's Cyclone wouldn't.
3. **It uses only parts already built and tested:**
   - the documented wire (alpha.123);
   - the Triage board and its combining rules (golden-set tested);
   - the breakers (alpha.123).

Consensus and Sprint meet all three. The alternatives don't yet:
- **Optimistic act-then-undo** needs Verify and undo built and measured on a phone.
- **Streaming partial decisions** need Live voice changes tested on a phone.
- **On-phone embeddings** need a model in the APK and measuring.

They are R1–R3 (§9).

### 6.2 The race (`mind/modes/Ensemble.kt`, `DecisionRace`)

- **The same Triage board** (plan 58 §5) goes to JEV and OpenAI Decisions **at the same moment**, each through its
  own breaker and the documented wire.
- **Answers are collected as they arrive, until either:**
  - the engine's rule says it can decide (Sprint), or
  - both have answered, or
  - **1.5 s** has passed.

  A late answer is no answer for the decision.
- **The race's numbers include late answers.** Up to a grace period, every answer is logged: who answered, how fast,
  what each chose, whether they agreed. So the speed and agreement of both models are measured on every request,
  which replaces the separate watch call in these engines.

### 6.3 The combining rules (`Ensemble.combine`)

| Situation | Route |
|---|---|
| Both say Instant, **the same action and target** | **Instant** |
| Both say Instant, different actions | Flash ("the models disagree on the action") |
| Instant against Flash or Mind | The more careful one |
| Flash against Mind | The Mind |
| Both say "not meant for Cyclone" | Ignore |
| One says "not meant for Cyclone", the other an action | Flash (a disagreement) |
| One says "not meant for Cyclone", the other Flash or Mind | The other one |
| Only one answered in time: an easy-to-undo Instant action | Instant |
| Only one answered: any other Instant action (a tap, a photo, a call…) | Flash |
| Only one answered: "not meant for Cyclone" | Flash |
| Only one answered: Flash or Mind | That |
| Neither answered | Flash |

**Sprint** adds one rule before these. The **first** answer may act at once when all of these hold:
- it is an easy-to-undo Instant action (`ModeRouter.REVERSIBLE`: no tap, photo, selfie or call);
- no risk flag is raised;
- the action is ≥ 0.9 sure, and the difficulty is ≥ 0.6 sure.

Otherwise Sprint waits and decides like Consensus.

**Unchanged in front of both engines:**
- the command grammar and the local answers;
- the rules: writing, money, deleting and accounts go to the Mind before any model is asked;
- the phone model's earned actions.

### 6.4 Sight

In both engines, every decision question that may need the screen as an image goes to **OpenAI Decisions** with the
screenshot. Today those are Instant's follow-ups: the camera's shutter, the call button.
- JEV is never sent an image.
- Sensitive screens never give one (code decides, from structure and the keep-off list).

### 6.5 What the proofs show (offline, in CI)

**Race timing, with fake models:**
- Consensus waits for both.
- Sprint returns on the first sure easy-to-undo answer, and the late answer still reaches the numbers.
- A model that takes 3 s doesn't stall a decision past the deadline.
- A model that fails or answers nothing means the other decides under the one-answer rule, or the request goes up.

**Every combining rule:** one test per row of §6.3.

**Properties over the golden set** (308 requests × 20 rounds of random, well-formed model answers, half of them
agreeing):
- the engine is Instant **only** when both models said the same Instant action;
- never lower than the more careful model;
- Ignore only when both say so;
- one model alone is never Instant on something that isn't easy to undo;
- Sprint's early act is always easy to undo and never risky.

**Perfect models reproduce every golden label.** One perfect model alone is never lower than a label.

**Guards (CI):**
- the agreement rule;
- the reversible list (no tap, photo, selfie or call);
- the engine asked only after the rules and the phone model;
- Consensus as the default;
- no request text in the engine log;
- sight only to OpenAI Decisions;
- no screenshot of a sensitive screen.

### 6.6 What it costs and what it changes

- **Cost:** two decision calls per undecided Auto request instead of one plus a watch call: about the same as
  alpha.123 with "Compare" on. A fraction of a cent.
- **Speed:**
  - Consensus: about the slower of the two models (~0.5 s median by the public numbers).
  - Sprint: about the faster one (~0.2–0.3 s), for easy-to-undo actions.
- **Behaviour change versus alpha.123 Classic:**
  - The decision board is Triage (risk flags, difficulty, action) answered by both models, instead of Board 0
    answered by one.
  - Requests the models disagree on go one rung up. Expect somewhat more Flash, and fewer wrong Instants, until the
    numbers say otherwise.

---

## 7. What we deliberately don't do

- **No action from free text.** Models choose from what the phone supplies; nothing is executed from words a model
  wrote.
- **No safety from a model alone.** Structure, effects, the rules and your approval decide what is consequential.
- **No screenshots by default** of sensitive screens, or when "Let decisions see the screen" is off.
- **No hidden speculation that acts.** Speculative calls only ask questions.
- **No word lists for meaning** once their replacement passes (plan 61).

---

## 8. Measures (L7)

| Measure | Target |
|---|---|
| End of request → first move, engine-decided easy-to-undo actions | Sprint p50 ≤ 400 ms; Consensus p50 ≤ 700 ms |
| Both models answered in time | ≥ 95% |
| Agreement on Instant actions | Watched; a drop means one model regressed |
| Wrong Instant actions (owner undo, Verify when built) | ≤ 1% |
| **Consequential actions without approval** | **0, the hard gate** |
| Engine-decided requests going up because of disagreement | Watched; tuned only with data |
| Languages | Every golden-set language within a fixed margin of English (plan 61 C) |

All the engine numbers are in Settings → Speed (one line) and in the health report (`decisions.engine`), as counts
only.

---

## 9. The road from here

| Release | Adds | Why in this order |
|---|---|---|
| **alpha.136 (this)** | The two engines; sight to OpenAI Decisions; provider renamed; engine numbers | Provably safe now; worst case is today or one rung up; it measures both models on every request |
| **R0 · Real numbers** (when real calls are allowed) | The probe on both models with text and images; the Lab on both; JEV box test; the routing table confirmed | Every later threshold comes from these numbers |
| **R1 · Understand anything** (61 A + 62 L1) | The Request Graph; the on-device multilingual embedding tier (T0); the Live Phone Model; golden set v2 in 12+ languages; the vocabulary guard | Removes word lists from understanding; makes T0 instant |
| **R2 · Ahead of you** (62 L2, L5) | The affordance cache; streaming partial decisions in Live voice; Verify with auto-undo; Screen Semantics effects (61 B) | Takes the model off the critical path; safety by effect |
| **R3 · Learns** (61 C) | On-phone learning; calibration and conformal bars per capability; chains (58 §5B); Glass draws the numbers; old routers and word lists deleted | Gets faster and more personal with use |

## 10. Owner decisions

1. **Default engine:** Consensus (built as the default; the most careful) or Sprint (faster for easy-to-undo actions)?
2. **Classic:** keep it as the way back (recommended until the engines have a week of numbers), or remove it later?
3. **Next build:** R1 (understanding any language, on-phone instant) or R2 (ahead of you, verify and undo) first?
   *Recommended: R1, because R2's affordance cache and partial decisions build on the Request Graph.*
