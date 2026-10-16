# Plan 65: Zero-wait Cyclone

**Status:** design + first build (2026-10-16). Built in alpha.136: **Quorum** (four answers in parallel) and
**Replay** (verified repeats with no call). Everything else here is the road.

**Builds on:** plan 62 (The Instant Mind), plan 63 (Decisions from first principles) and plan 64 (No trigger words).

## 1. The question

You asked for something revolutionary that works for billions of people, rock solid, super fast and efficient, with
decision calls cheap enough to be close to free.

The way to answer that is from physics, not from what assistants do today:
- question every requirement;
- delete every step that can be deleted;
- only then make what is left fast;
- and make it reliable by redundancy, not by hoping one part never fails.

## 2. First principles

**The limit is the owner, not the model.**
- A person speaks a short command in about 1–2 s.
- The phone does an easy action in about 50–300 ms.
- A decision model answers in about 0.2–0.6 s (public numbers, plan 62 §2; none measured on Cyclone yet).
- So the ideal is a decision that **is ready when they stop speaking**, or one that needs **no call at all**.

**The best decision call is no call.**
- People repeat themselves. The same few words for the same easy action, many times a day, in their own language.
- A request this phone has already done the same way, and verified, does not need a model again.
- That deletes the slowest step for the most common requests.

**Cheap calls change the design.**
- When a call costs a fraction of a cent, asking once and hoping is the wrong trade.
- Ask **several independent opinions at the same moment** and act only on what they agree on.
- Rockets and aircraft get reliability the same way: redundant computers that vote. A single computer, however good,
  is not trusted with an irreversible action.

**Decision models are pickers, and pickers have known failure modes:**
- **Position bias:** a tendency to choose options listed first.
- **Self-inconsistency:** a different answer to the same question asked again.
- **Correlated errors:** two models trained on similar data can be wrong the same way.
- Each one is fixed by a specific kind of redundancy (§3). Each makes the error **visible as disagreement**, and
  disagreement goes up a rung, never into an action.

**Safety by what an action does, not by words.**
- Easy-to-undo actions (volume, flashlight, media, Back/Home, scrolling, opening an app or the camera) may go fast.
- Everything else needs agreement, or the Mind, which asks.
- No list of trigger words decides this (plan 64).

## 3. Built now (alpha.136)

### 3.1 Quorum: four answers at once

Every request the grammar, the rules, Replay and the phone model leave open is asked **four times in parallel**:

| Lane | Model | Phrasing |
|---|---|---|
| `jev#0` | JEV 1.14 | the plain board |
| `jev#1` | JEV 1.14 | options in reverse order ("none" stays last), screen and app lists reversed, the request after the phone |
| `openai#0` | OpenAI Decisions | the plain board |
| `openai#1` | OpenAI Decisions | the reversed board |

**What each kind of redundancy catches:**
- **Two models** (N-version voting): one model's blind spot.
- **Two phrasings of the same question:**
  - **position bias:** an answer that only won because it was listed first now loses in the reversed list;
  - **self-inconsistency:** a model that isn't sure answers two ways.

**The rules (pure, in `Ensemble`):**
1. **One model, two answers → one opinion.**
   - If both agree, the opinion keeps the lower confidence of the two.
   - If they differ and one of them was an Instant action, the opinion is "not sure", so Flash.
   - If they differ otherwise, the more careful rung wins.
2. **Two models' opinions → one route.**
   - An action needs every model to say the same action.
   - "Not meant for Cyclone" needs every model to say so.
   - Otherwise the most careful rung wins.
3. **One model only** (the other slow or down): only an easy-to-undo action goes ahead.

**Speed.**
- The race ends as soon as every answer is in.
- Once every model has answered **once**, the remaining answers get **300 ms**, not the whole 1.5 s deadline.
- **Consensus** takes about the slower model's first answer, plus at most 300 ms when one of its two answers lags.
- **Sprint** acts as soon as **two answers** are sure of the same easy-to-undo action and **none** says otherwise.
  Usually those are JEV's own two answers, so Sprint stays about as fast as JEV, now with a self-check.

**Graceful degradation.**
- Each model keeps its own circuit breaker. After a 429, or three failures in a minute, that model pauses for two
  minutes, both of its lanes answer nothing at once, and the other model decides under the one-model rule.
- With both models down, the request goes to Flash in at most the deadline.

### 3.2 Replay: no call for verified repeats

Pure code in `mind/decide/Replay.kt`, built from the lessons already kept on this phone.

**When a request replays:**
- The key is the owner's own words, normalised (case, accents, punctuation and spacing don't matter). No word list.
- The last **two** runs of those words must have been the **same easy-to-undo action**, and the phone must have
  **verified** both.
- Then the next run is done at once: **0 ms, no model asked.** The trace says "you asked this before and it worked".

**When it is forgotten:**
- Any later failure, hand-up or cancel of the same words forgets it.
- Being routed elsewhere forgets it too.
- A different verified answer starts a new streak.

**Where it sits in the router:**
- **After** the grammar, the phone's own answers, the safety rules and the multi-step check.
- **Before** the phone model and the engines.
- So "pay the bill" can never replay.
- A replayed app must still be installed.
- Audits skip Replay, so the models keep checking replays now and then.
- "Learn only" never replays.

### 3.3 Measured from the first request

**The engine log keeps every answer:**
- its model;
- its phrasing;
- its time;
- whether it was late.

**New number: self-agreement per model,** how often a model's two phrasings gave the same answer.
- This is the honest measure of how sure a model really is on this owner's requests.
- It passes to the health report as a share only.

## 4. The road

**R1. Decide while they speak.**
- Start the four-answer race on the partial transcript.
- Cancel and restart it when the words change in a way that matters (the typed answers change, not the characters).
- When speech ends, the decision is usually already in.
- Target: Instant actions that start **under 100 ms after the last word**.

**R2. Prepare the screen.**
- While the phone is idle, ask "what can be done here?" once per new screen (plan 62's affordance cache).
- So "tap the second one" needs no sight call.

**R3. Calibrate, don't guess.**
- Replace the fixed bars (0.85, 0.9) with per-action bars learned from verified outcomes.
- Use conformal prediction, so "sure" means a measured error rate, for example ≤ 1 in 500 for easy-to-undo
  actions.
- Self-agreement from §3.3 is the first input.

**R4. Optimistic and undoable.**
- For the easiest actions, act on the first sure answer.
- Verify within ~300 ms.
- Undo automatically if the quorum later disagrees.
- This is only for actions with a perfect undo (volume, flashlight, scrolling).

**R5. Fleet learning, privately.**
- With the owner's opt-in, share **only** the shape of what worked: language, action class, verified or not, never
  the words.
- Use it to set better starting bars for new phones.
- Each phone's own words and Replay stay on that phone.

**R6. Every language, same quality.**
- Plan 64's 20-language eval gate.
- No release lowers any language's score.

## 5. Why it works for billions

**No word lists:**
- Replay keys on each owner's own words, in any language.
- The models read meaning.
- Safety reads what an action does.

**Works on bad networks:**
- Replay and the phone model work offline.
- With any one model alive, the race still decides.
- With none, the request goes up a rung within the deadline.

**Cheap enough to be free:**
- Four decision calls cost a fraction of a cent.
- Replay removes the calls for the most repeated requests, so cost falls as an owner uses Cyclone more.

**The worst case stays known:**
- Every failure mode ends in today's behaviour, or one rung up.
- It never ends in an action the models didn't all agree on, unless that action is easy to undo.

## 6. Proof without a phone (alpha.136)

**`EnsembleTest` (17):**
- the earlier 12;
- Sprint needs two sure answers and no dissent;
- the 300 ms grace once every model has answered;
- one model's two phrasings merge;
- the second phrasing is the same question reordered;
- **a seeded property test over the 308-request golden set:** four answers per request, 75% agreeing, 10 rounds.
  Over all of them:
  - Instant only when all four said the same action;
  - never lower than the most careful answer;
  - "ignore" only when all four say so;
  - Sprint's early act is always easy to undo, risk-free and unopposed.

**`ReplayTest` (6):**
- two verified runs replay, one doesn't;
- a failure, hand-up or cancel forgets;
- a new answer starts a new streak;
- taps and Flash never replay;
- the router asks no model for a replay;
- audits and "learn only" do ask;
- a replayed app must be installed;
- the rules come first.

**Guards:**
- `test_luna_box_guard.py` pins the merge rule, Sprint's two-answer rule, Replay's easy-to-undo-only rule, and
  Replay's place after the rules.

## 7. Limits, stated plainly

- **No live call was made.** Speed, agreement and self-agreement on real requests are unmeasured until your first
  requests. The latency figures above are public numbers, not Cyclone's.
- **Twice the calls per undecided request** (four instead of two). Still a fraction of a cent. It also doubles the
  request rate at OpenRouter, so a 429 is more likely; the breaker then pauses that model and the other decides.
- **Consensus can be up to 300 ms slower** than alpha.136's first two-lane build, when one of a model's two answers
  lags. Sprint is not slower.
- **Replay trusts the phone's verification.** If an action "worked" but wasn't what the owner meant, and they don't
  cancel it, Replay can repeat it. It is limited to easy-to-undo actions for exactly this reason.
- **Physical device: UNVERIFIED.** See the alpha.136 rows in `docs/LUNA_DEVICE_MATRIX.md`.
