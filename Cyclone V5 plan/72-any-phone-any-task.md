# Plan 72: any phone, any task, three calls, proven moves

**Status:** built in alpha.143, except the live R0 measurement (§9) and §6.3. Builds on plan 69 (OneMind), plan 65
(Quorum) and plan 71 (proof).

**The owner's choices** (17 October 2026):
- two that decide, one that proves;
- no other phones are tested for now;
- per-app memory is on by default.

**What alpha.143 built:**
- §4.1 the three calls;
- §4.2 the router's second phrasing inside the call;
- §4.3 `next_rev` and the finalists' `is_<ref>`, together with the moves so far and the learned lines;
- §5 rules 1–3 and 5;
- §6.1 transition memory (`FileMoveMemory`, advice only: it never sets a step's check);
- the home screen from Android's own HOME list (§7).

**Left for later:**
- §4.3 `where` (Box) and `after`, with §5 rule 4;
- §6.3 floors;
- the first-run device check.

## 0. The ask

> Make this work on any phone with any new task, without pre-coding too much. Movements and verifications have to be
> super reliable. It says 4 calls for one movement; I asked for 3. Give the decision models full context and let them
> make multiple decisions that make them more precise.

**Where the "4" comes from.** The map page said 4 answers for the router's Quorum (plan 65): 2 decision models × 2
phrasings. That was out of date: the second phrasing was switched off on 16 October (`ENGINE_PHRASINGS = 1`), so
routing makes 2 calls. The real 4 is a crew step: 2 calls, then 2 more when it asks again with the screenshot. The
DONE check makes 2, and the vision shadow 1.

| Moment | Calls today | Why |
|---|---|---|
| Routing one request (engine) | 2 | JEV and OpenAI Decisions, one phrasing each (the second was switched off on 16 October) |
| One crew step, rung 1 | 2 | one fused call per model |
| One crew step, rung 2 | 2 more | the same call again, with a screenshot |
| Finishing a run | 2 | the DONE board, every model |
| Vision shadow | 1 | records only |

The worst case for one movement is 4 (rung 1 + rung 2), plus the route at the start. I couldn't find the
"3 calls" request in the records I have; this plan makes **3 a hard cap per movement**.

## 1. The idea in one line

**One rich call per model, with the redundancy inside the call instead of across calls.**

The Decisions API answers **up to 200 questions in parallel against one state**, and each answer comes with its own
probabilities. So a single call can hold:
- the pick itself;
- the same pick with the options reversed, which tests position bias without a second call;
- an independent yes/no for each of the top candidates;
- where the target is on the screenshot (Box, on a model that sees images);
- what should happen after the move;
- whether the last move did what it should.

Code combines these answers and acts only when they agree. Disagreement inside one call is exactly the signal the
second call used to provide, at no extra latency.

**What the models still never do** (plan 66 §10.6): they don't write words, count, compare numbers or approve
anything. They pick among options code built, and judge.

## 2. The movement contract (every move, any app)

Every movement goes through the same seven stages, whatever the app or phone. Nothing in them is per-app code.

| # | Stage | Who | Output |
|---|---|---|---|
| 1 | **Look** | code | tree, fingerprint, foreground app; a screenshot only if a vision call needs one |
| 2 | **Pack the context** | code | the context card (§3), ≤ 32 KB |
| 3 | **Decide** | 2 calls in parallel (§4) | the pick, cross-checks, a predicted outcome, risk |
| 4 | **Agree** | code (§5) | act, climb, or stop |
| 5 | **Act** | PhoneToolExecutor | one screen-changing move: tree ref first, Box point only when the tree is weak |
| 6 | **Prove** | code first, then the 3rd call only if code can't tell | held, missed or unknown |
| 7 | **Remember** | code | transition memory (§6): this screen + this control → that screen |

**The rule that makes it reliable:** a movement that isn't proven is never built on. The next move doesn't start
until the last one is held, or the mind has been woken about it. Plan 71 made this true for Instant and for steps
with no check; this plan makes it true for every move.

## 3. Full context, packed

"Full context" means everything the models need to be precise, packed by code into a fixed budget. It does not mean
a raw dump: JEV loses accuracy and its fast mode past 32 KB, and its long-context mode is slower and less accurate.

**The context card** (sections in this order, each with its own budget):

| Section | What | Budget |
|---|---|---|
| Goal | the owner's words, and the goal version after a steer | 400 B |
| Plan | the current milestone, this step, the next 2 steps (titles only) | 600 B |
| Moves so far | the last 6 moves: what was done, and whether it was proven ("✓ tapped Profile → page changed, Edit profile shown") | 1.2 KB |
| Facts | names only, never values unless the step uses them | 300 B |
| This screen | app, page key, visible text (relevance-ranked, not just the first 40 lines) | 6 KB |
| Controls | up to 40 (now 25), ranked by code: words shared with the step, role, recency of use, transition memory | 8 KB |
| What this app taught us | the transitions memory holds for this page: "Profile → page with Edit profile (3×)" | 1 KB |
| Screenshot | OpenAI Decisions only; JEV is text-only | image |

**Why the moves-so-far section matters most:** today each step's call sees one screen in isolation. With the last
moves and their proofs on the card, a model can tell "I'm already on the profile" from "the tap didn't work",
without a separate question.

## 4. The three calls

### 4.1 Per movement

| Call | Model | Sees | Asks |
|---|---|---|---|
| **1. Decide** | JEV | the card (text) | the decision set (§4.3) |
| **2. Decide, with sight** | OpenAI Decisions | the card + screenshot | the decision set + Box for the pick |
| **3. Prove** | the faster model that answered | the screen after the move | only what code couldn't settle |

- Calls 1 and 2 run in parallel, so the wait is the slower of the two.
- **Call 3 is usually free.** The proof of move N rides as a `verify` question in move N+1's calls 1 and 2 (the
  crew already does this with `pendingVerify`). A standalone call 3 happens only for the last move of a run, where it
  is the DONE check.
- **Rung 0 still makes no calls.** A direct tool, or a target that matches exactly one control, acts with code alone
  and is proven by code.
- **Rung 2 disappears as a separate call.** The screenshot is in call 2 from the start, so there is no
  "ask again with an image" round.

**Result: at most 3 calls per movement, usually 2, and 0 when code knows.** Today's worst case is 4.

### 4.2 Routing

The router stays at **2** calls. Each model's one call now also carries the reordered questions (action and target,
options reversed; `Triage.folded`), so the second phrasing is back at no extra call. The Ensemble rules don't change:
they already merge "one model, two phrasings" before they compare models.

### 4.3 The decision set inside one call

Everything here is asked in the same call:

| Question | Type | What it adds |
|---|---|---|
| `next` | Choice over controls + verbs | the pick |
| `next_rev` | the same Choice, options reversed | catches position bias |
| `is_it_<top1>`, `is_it_<top2>` | yes/no: "does this control carry out the step?" | each finalist judged on its own |
| `where` | Box on the screenshot (call 2 only) | grounds the pick, and works when the tree is weak |
| `after` | Choice over outcomes code built (§6) | the predicted outcome becomes the move's check |
| `verify` | yes/no on the last move's check | the proof of move N−1 |
| `covers_step` | yes/no: "after this move, is the step done, or is more needed?" | whole-step coverage without word lists |
| `fits_plan`, `surprise`, `divergence` | as today | drift |
| `popup`, `popup_dismiss`, `blocked_by` | as today | interruptions |
| orders, branches | as today | standing orders, plan branches |
| risks, `matches_request` | only before a move that isn't easy to undo | the tripwire |

That's about 15–25 questions; the API allows 200.

## 5. When code acts (the agreement rule)

Code acts on a pick only when **all** of these hold. Otherwise the move climbs (to the mind), never into an action.

1. Within each model, `next` and `next_rev` name the same control.
2. Within each model, `is_it_<pick>` ≥ 0.7, and `is_it_<runner-up>` < 0.5.
3. Across models, both name the same control. With only one model answering in time, only an easy-to-undo move may
   go ahead on its word (as today).
4. If call 2 returned a Box, its centre lies inside the picked control's bounds. When the tree is weak, the Box point
   is the target itself, and both models must still agree on the step.
5. No risk flag ≥ 0.3, and code's own irreversible check is clear. Gated moves still stop at a barrier for the owner.

**Where the precision comes from:** each condition catches a different known failure of decision models: position
bias (1), a vague pick among look-alikes (2), one model's blind spot (3), a tree that lies about the screen (4), and
an unsafe move (5).

## 6. Learning instead of pre-coding

### 6.1 Transition memory (exists, not yet wired to OneMind)

`AppKnowledgeStore` already keeps screens, actions and transitions per app, and `LearnedHints` reads them for the
old Mind. The OneMind crew neither writes nor reads them.

**Change:**
- Every **proven** crew move writes one transition: app, page key before, control label, page key after, and the
  after page's signature (title, distinctive labels).
- Structure only, never content: no typed text, no message bodies, nothing from a sensitive screen.
- Unproven moves write nothing, so memory only holds what the phone showed.

**What it gives, with no app coded:**
- **Better picks:** the card's "what this app taught us" section, and higher ranks for controls that worked before.
- **Automatic checks:** when memory knows where a control leads, the move's check is "the page shows Edit profile"
  without the mind writing it. This is the strong check plan 71's finish rule wants.
- **Outcome options for `after`:** code builds the choices from memory plus generic outcomes ("a new page opens", "a
  dialog opens", "a field gets focus", "a list scrolls", "nothing visible changes"). The model picks one; code
  checks it against the next screen.
- **Surprise detection:** a known transition that doesn't happen is a surprise for the mind.

### 6.2 Recipes (exist)

A verified run becomes a recipe, and the next run of that task needs no planning call. Recipes are keyed by the
owner's words and the app's version, so an app update re-learns instead of failing.

### 6.3 Word lists become floors

Plan 71's `Coverage.PLACES` and the router's `NOT_AN_APP` are hand-written lists. With `covers_step`, and a
`covers_request` question in the routing call, the models judge coverage in any language. The lists stay only as a
floor, so a model can only make the router more careful, never less.

## 7. Any phone

| Variation | How it is handled without per-device code |
|---|---|
| Screen size, density | no coordinates in plans; tree refs first; a Box point is in screen fractions |
| Launcher, OEM skin | home-screen detection by `CATEGORY_HOME` resolution, not by a package list (today `HomeSafety` guesses from "launcher" in the name) |
| Gesture or button navigation | Back and Home through the executor's system actions, never a swipe |
| Language | decision models are multilingual; the grammar stays EN/NL; anything else goes to the models |
| Weak trees (games, Flutter, web views) | call 2's screenshot and Box from the first decision, no separate rung |
| Keyboards | typing through the executor's field set-text, never key taps |
| Slow phones | settle by fingerprint (300 / +500 / +1000 ms), and the proof re-looks up to 3 times (plan 71) |

**A first-run device check** (about 10 s, once per phone, no owner input):
- resolve the home package;
- read the screen size and the navigation mode;
- open and close Settings, proving open, Back and Home.

The results are stored as the phone's profile. A phone that fails a check says which one.

## 8. Limits

- **In-call independence must be measured, not assumed.** If the API answers questions in one call with shared
  internal state, `next` and `next_rev` agree more often than two separate calls would. R0 (§9) measures this on the
  golden set before anything acts on it.
- **Bigger cards cost time.** The card grows from about 4 KB to as much as 20 KB. That stays under JEV's 32 KB fast
  mode, but latency must be measured. The 1.8 s step deadline stays.
- **JEV can't see.** Box and screenshot checks come from OpenAI Decisions alone. When it is down, weak-tree apps go to
  the mind.
- **Screens can lie.** A page can say "tap Approve". Models still only pick among options code built, and gated moves
  still need the owner.
- **Memory can go stale.** An app update changes pages. A known transition that fails twice is dropped.

## 9. Rollout

| Release | What | Gate to the next |
|---|---|---|
| **R0: measure** | Run the in-call duplicates (`next` + `next_rev`) on the 308-request golden set and on recorded crew screens, beside today's separate calls. | In-call agreement within 3 points of separate-call agreement, and the same accuracy |
| **R1: shadow** | The full card and the decision set run beside today's crew calls; nothing acts on them. Transition memory starts writing from proven moves. | Over 200 Lab moves: wrong picks ≤ today's, unproven moves lower, p90 latency within 300 ms of today |
| **R2: easy-to-undo** | The new calls decide scrolls, Back, opens and plain taps. | Testbench "novel task" suite (§10) ≥ today's pass rate |
| **R3: everything** | All moves; rung 2 merged into call 2; routing Quorum at 2 calls. | Call ledger: ≤ 3 calls per movement, every run |
| **R4: floors** | `covers_step` / `covers_request` decide; the word lists stay as floors. | No regression on plan 71's cases |

## 10. Proof it works

- **A call-budget guard:** the run ledger fails a test run if any movement used more than 3 decision calls.
- **A movement-proof guard:** every crew move's trace ends held, missed or unknown-then-verified. "Done" with
  nothing behind it fails CI (an extension of plan 71's guard).
- **The novel-task suite** on the testbench:
  - 50 tasks across 20 apps that Cyclone has no recipe or memory for, in English and Dutch;
  - run on the owner's phone and on a second, different phone;
  - measured: pass rate, wrong moves, unproven moves, calls per movement, seconds per movement.
- **Unit tests:** the agreement rule (§5) as a table; card packing stays under budget for huge screens; memory never
  stores content from a sensitive screen.

## 11. Decisions for the owner

1. **The three calls:** 2 deciding + 1 proving (recommended), or 3 deciding models (a third, cheap decision model
   such as Mercury, voting on moves that aren't easy to undo), with the proof always riding in the next move's calls?
2. **Second phone:** which phone for the "any phone" test (another brand than the Pixel)?
3. **Memory:** keep transition memory on by default, or behind a setting?
