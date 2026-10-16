# 62 · The Instant Mind: decisions that are ready before you finish asking

Status: **Design (2026-10-16), nothing built. Revised the same day: JEV, OpenAI Decisions and GPT-6 Luna named apart (§1).** Written at alpha.134 dev1. It is the umbrella over plan 58 (the "Luna Decision Box": its "Luna" meant OpenAI Decisions) and plan 61 (no trigger words), and uses what the latest models on OpenRouter actually offer. Physical
device: **UNVERIFIED**.

**Owner's ask (2026-10-16):**
> Design the frontier: how this system should be built with the latest JEV and OpenAI's Decisions API on OpenRouter.
> It shouldn't feel like a first decision, but like the most advanced, AGI-like instant decisions, and it must actually
> work.

---

## 0. The idea in one paragraph

Today Cyclone waits for the request, then looks at the screen, then asks a model, then acts. Each step waits for the
one before. **The Instant Mind turns that around.**
- **It always knows where you are.** A live model of the phone is kept up to date as the screen changes.
- **It has already worked out what you could ask here.** Each screen's possible actions are pre-computed in idle time
  and cached.
- **It starts deciding while you are still speaking or typing.**
- **It asks both decision models (JEV and OpenAI Decisions) at once** and takes the first sure answer.
- **It acts the moment it is sure, then checks**, and undoes a reversible move that turned out wrong.

So by the time you finish the sentence, the decision has usually already been made. That, not a faster model, is what
makes it feel like it knew.

---

## 1. Three different things, kept apart

Earlier plans wrote "Luna" for two different products. From this plan on they are named apart:

| | **JEV** | **OpenAI Decisions** | **GPT-6 Luna** |
|---|---|---|---|
| **What it is** | TypeSafe's decision model | OpenAI's Decisions API. On OpenRouter its id is `openai/gpt-6-luna-decisions`: GPT-6 Luna served through the Decisions endpoint | OpenAI's general model, used through chat completions / Responses |
| **Id on OpenRouter** | `typesafe/jev-1.14`, `~typesafe/jev-latest` (1.13 still listed) | `openai/gpt-6-luna-decisions` | `openai/gpt-6-luna` |
| **Endpoint** | `POST /api/alpha/decisions` | `POST /api/alpha/decisions` (natively `POST /v1/decisions`) | `POST /api/v1/chat/completions` |
| **What it returns** | Typed answers only: choice, noul, score, and **box** (1.14) | Typed answers only: choice, noul (predicate), score, or a refusal | **Free text, JSON, tool calls.** It can write |
| **Images** | PNG/JPEG, 256–1,568 px a side; **any image puts JEV in its "slower and less accurate" long-context mode** | Up to 128 per request; built multimodal | Yes (image parts in messages) |
| **Context / limits** | 262 K tokens | ~1.05 M tokens; up to 200 questions | ~1 M tokens |
| **Price** | $0.042 / M input, output free | $0.10 / M input, output free | $0.10 / M input, $0.50 / M output (plan 54) |
| **Measured speed** | OpenRouter's own test, text: **194 ms** median, 0.63–0.69 s p95 | Community via OpenRouter: 0.16–0.35 s median, 0.74–0.86 s worst; OpenAI claims ~150 ms | Chat: 0.8–1.1 s median in OpenRouter's test (GPT 5.6 Luna); 1.47–2 s in ML6 and AIML tests; OpenAI's own figure 1.6 s |
| **Cyclone uses it for** | Fast typed decisions on text; box for unlabelled targets | Typed decisions on text (second opinion) and **all decisions that need sight** | **Only what needs writing:** free-value slots, Flash's short plans, the Mind (if chosen), and a slow fallback for typed questions when both decision models are down |

**The evidence, sorted by what it really compared:**
- **JEV vs OpenAI Decisions, both through the decisions endpoint:** only one test, the GitHub puzzle benchmark. JEV
  ~470 ms median vs ~550 ms, and more puzzles solved (14.8 vs 9.7 of 20). The solver was tuned on JEV.
- **JEV vs GPT-6 Luna, the chat model** (not the Decisions API):
  - ML6: JEV 0.3 s vs Luna ~2 s, accuracy about tied;
  - AIML API: Luna chat 80.7% vs JEV 78.3% on intent routing, at 1.47 s;
  - OpenRouter's own test: JEV 194 ms vs chat Luna ~1.1 s.

  These show JEV beats a **generative** model on speed. They say nothing about OpenAI Decisions.
- **OpenAI Decisions alone:** community timings of 0.16–0.35 s median, plus OpenAI's ~150 ms claim.

**What the numbers say:**
- **The two decision models are close.** Both are typically 150–550 ms; JEV was a little faster in the one matched
  test.
- **The generative model is a different class:** about 1–2 s. That is too slow for the instant tier, and fine for
  writing.
- **For sight, OpenAI Decisions:** JEV's own docs say images make it slower and less accurate.
- **JEV's box** is unique for pointing at an unlabelled spot.
- **Input is cheap and output is free for both decision models,** so asking both costs a fraction of a cent (§5).

None of this is measured on Cyclone's own requests yet. R0 measures it (§10).

## 2. Five principles

1. **Never start from zero.** The phone's state, the screen's possible actions and your likely next request are already
   computed when you speak.
2. **Every decision is typed and chosen from what's really there.** Models pick from candidates the phone supplies.
   They never invent an app, a contact or a button (plans 58, 61).
3. **Spend latency only on the critical path.** Everything else is speculative, parallel or done in idle time.
4. **Confidence is earned and measured.** Each capability acts only above a calibrated bar, with a guaranteed error
   rate (§5.4). Unsure goes up, never down.
5. **Reversible moves are optimistic; consequential ones never are.** Act, verify, undo if wrong for a scroll or
   opening an app. Ask first for anything that sends, pays, deletes or touches an account.

---

## 3. The architecture: five layers

```
            ┌──────────────────────────────────────────────────────────────────────────┐
 always on  │ 1 PERCEIVE   Live Phone Model: screen graph, effects, app map position,   │
            │              recent context. Updated on every accessibility event          │
            ├──────────────────────────────────────────────────────────────────────────┤
 idle time  │ 2 ANTICIPATE Affordance cache per screen ("what can be done here"),        │
            │              likely next requests, pre-resolved candidates, warm sockets   │
            ├──────────────────────────────────────────────────────────────────────────┤
 as you     │ 3 DECIDE     Streaming intent → Request Graph. Cascade: on-device cache →  │
 speak      │              JEV ∥ OpenAI Decisions race → GPT-6 Luna writes → the Mind │
            ├──────────────────────────────────────────────────────────────────────────┤
 < 400 ms   │ 4 ACT        Optimistic for reversible moves; approval for consequential    │
            ├──────────────────────────────────────────────────────────────────────────┤
 after      │ 5 VERIFY & LEARN  Verify board, auto-undo, outcome → on-phone training      │
            └──────────────────────────────────────────────────────────────────────────┘
```

### Layer 1 · Perceive: the Live Phone Model (always on, on the phone)

**A small, always-current picture of the phone,** maintained from accessibility events, never fetched at request
time:
- **the foreground app and screen**: its fingerprint, and where it sits in the App Map (plan 58 §5C);
- **the numbered controls**: label, role, bounds, and **effect** from Screen Semantics (plan 61 B);
- **sensitivity flags** from Android's own structure: password fields, autofill hints, `FLAG_SECURE`;
- **recent context**: the last few requests, the open conversation, what Cyclone just did.

**When the screen changes, only the parts that changed are recomputed, in under 20 ms.** A request then starts with
the state already in hand: no "read the screen" step on the critical path.

A screenshot is **not** taken continuously. It is captured only when Layer 3 asks for sight (§4.3), so privacy and
battery stay intact.

### Layer 2 · Anticipate: the affordance cache (idle time)

**For each screen fingerprint, Cyclone keeps "what can be done here":**
- the screen's numbered controls with their effects;
- the App Map's reachable destinations, with routes;
- the capabilities that make sense here (on a camera screen: shutter, switch camera, video);
- for icon-only or unlabelled screens, a **marked-screenshot description computed once**:
  - OpenAI Decisions answers, in one call, "what does each numbered control do?" over the fixed effect list;
  - where no accessibility node exists at all (games, canvases, video), JEV 1.14's **box** locates the target.

**This is computed when a new screen is first seen and the phone is idle,** then reused every time that screen comes
back. A tap on "the blue one" then usually needs **no image call at request time**: the answer is in the cache.

**Also kept ready:**
- **Likely next requests**, ranked by the on-phone model from your history on this screen and at this time of day
  ("after opening Instagram you usually go to DMs").
- **Pre-resolved candidates**: installed apps, frequent contacts, recent destinations, so the decision's choice lists
  are ready.
- **Warm connections**: one HTTP/2 connection to OpenRouter, kept alive while the Ask bar or Live voice is open.

### Layer 3 · Decide: streaming, racing, calibrated

**3a. Decide while the words arrive.**
- **Live voice:** the speech-to-text partials stream into the on-device tier (plan 61 A's multilingual embedding cache)
  on every partial.
- **When the partial is already clear,** the decision is ready before the sentence ends. One example: "open the cam…"
  is already nearest to `camera.open` with a large margin.
- **When it isn't clear,** the remote boards are sent **speculatively**, as soon as the partial looks stable. A yes/no
  question, "is this request complete?", replaces "and then"-word endpointing.
- **When the final transcript differs**, the speculative answer is thrown away. The cost is a fraction of a cent.

**3b. The cascade, cheapest first:**

| Tier | Where | Typical time | Decides |
|---|---|---|---|
| **T0 · Live Phone Model + affordance cache + on-device embedding** | Phone | ≤ 30 ms | Requests that clearly match a capability, cached destination or affordance |
| **T1 · Text race: JEV ∥ OpenAI Decisions** | OpenRouter | ~200–500 ms | The Request Graph's typed questions (Triage, Bind) on text and the numbered control list |
| **T2 · Sight: OpenAI Decisions (+ JEV box when nothing is labelled)** | OpenRouter | ~300–800 ms | Only when T0/T1 say they need to see (§4.3) |
| **T3 · Writing: GPT-6 Luna** | OpenRouter (chat) | ~1–2 s | Only free values that aren't choices (a duration's number of minutes when it isn't plain digits, the words of a note), in a strict JSON schema that code validates; Flash's short plans |
| **T4 · The Mind** | The owner's Mind model | seconds | Messages, judgement, long plans; the decision models still step the routine parts |

**3c. The race (§5)** is how T1 gets both the speed of the faster model and the safety of two opinions.

**3d. Calibrated, conformal acting (§5.4)** decides whether an answer may act, or must go up a tier.

### Layer 4 · Act: optimistic for reversible moves

- **Reversible capabilities act at once when sure:**
  - opening an app or the camera, a gesture, the volume, the flashlight, media keys;
  - navigating to a mapped destination.

  The earcon and haptic fire **with** the move, not after verification. Target: first move ≤ 400 ms p50 from the end of
  the request, and ≤ 100 ms when T0 decided.
- **Owner-visible moves keep their window:** a call or an alarm keeps the 2-second "tap to stop".
- **Consequential moves never act optimistically:** send, pay, delete, post, account, permission. They are decided by
  **effect** (plan 61 B), not words, and wait for your approval.

### Layer 5 · Verify and learn

- **The Verify board runs after every move** (plan 58 §4): "did the screen reach the goal?", "what's blocking?".
  - It reads the Live Phone Model first.
  - It asks with an image only for visual apps.
- **A reversible move that verifies false is undone automatically** (Back, or the previous screen, or volume reset),
  and the request goes one tier up, with the baton.
- **Every verified outcome becomes an on-phone lesson** (plan 61 C), labelled by the outcome:
  - new exemplars for T0;
  - recalibrated bars;
  - a better ranking of likely next requests.

  The share of requests decided on the phone, with no network call, grows with use.

---

## 4. Routing by modality, from the evidence

### 4.1 Text decisions → the race, JEV favoured

- **Text-only boards go to both models at once** (§5).
- **JEV is expected to answer first.** It was faster (~470 vs ~550 ms) in the one test that ran both through the decisions
  endpoint, and it is cheaper. That is one test, tuned on JEV, so R0 confirms it on Cyclone's own requests.
- **OpenAI Decisions' answer is the second opinion:** if it arrives within the hedge window, it confirms or contradicts JEV.

### 4.2 Sight → OpenAI Decisions

- **Any decision that needs the screen as an image goes to OpenAI Decisions:**
  - it is built multimodal and takes up to 128 images;
  - JEV's own docs say an image puts JEV into its slower, less accurate long-context mode.
- **Screenshots are PNG or JPEG**, sized for both models, so either can be used. WebP is out (JEV rejects it).

### 4.3 When does a decision "need a visual"? The model says so; code adds hard signals

**The model says so:** every target question carries a `need_to_see` answer (plan 58 §5A), and Triage has
`needs_screen`.

**The phone adds hard signals of its own:**
- the likely targets have no text or description (icon-only);
- the screen has almost no accessibility nodes (a game, a video, a canvas, a WebView);
- the request points at what is shown, which is understood by meaning, not trigger words (plan 61);
- Verify is checking a visual app.

**Sight is never used:**
- on a sensitive screen (structure, not words);
- when "Let decisions see the screen" is off.

### 4.4 Pointing at something unlabelled → JEV box

- **The case:** the target has no accessibility node at all, such as a button drawn inside a game or a video player
  control.
- **One JEV 1.14 request on the screenshot asks:**
  - a **noul**: "is the target visible?" (a box has no "not found");
  - a **box**: where the target is.
- **The tap goes to the box centre** through `PhoneToolExecutor`, and Verify checks the result.
- **The box result is cached in the affordance cache**, so the next time is free.
- Box is an "early feature" in TypeSafe's docs, so it ships behind its own switch and is measured.

---

## 5. The race: hedged, dual-model decisions

**Why it works now:** input is cheap and output is free. A Triage-sized request is a few hundred tokens: about
$0.00001–0.00005 per model. Asking both costs almost nothing, and it buys two things:
- **the tail:** whichever model answers first wins, so one model's slow moment doesn't stall Cyclone;
- **a second opinion:** when both answer, agreement is strong evidence.

### 5.1 The protocol

1. **Send to JEV at once.** Send to OpenAI Decisions at once as well, or as a **hedged request** once JEV hasn't answered by its
   own p50 (the classic "tail at scale" technique). The choice is set by measurement in R0.
2. **The first answer that passes its bar** (§5.4) acts, if the capability is reversible.
3. **When the second answer arrives:**
   - **Agrees:** logged; it raises calibrated confidence for this kind of request.
   - **Disagrees on a reversible move that already acted:** Verify decides, and undoes when wrong.
   - **Disagrees before acting** (neither had passed its bar alone): the request goes one tier up.
4. **Consequential capabilities:**
   - **both models must agree, or the request goes up;**
   - and they still need your approval. Agreement only decides whether Cyclone *proposes* the action.

### 5.1b When both decision models are unreachable

- **GPT-6 Luna (chat) can answer the same typed questions** with a strict JSON schema. The current code already has
  this wire: `BoxWire.chatBody`.
- **It is slower (~1–2 s),** so it only steps in when both decision models' breakers are open. The routing deadline
  still applies: a late answer is no answer, and the request goes up.

### 5.2 One request, many questions

Plan 58's boards (Triage, Bind, Step, Verify) become **one compiled question set per decision point**:
- independent questions are batched into one call;
- dependent ones form the smallest number of rounds (a dependency graph, critical path first);
- the question compiler builds them from the capability registry and the Live Phone Model, so they are data, not code.

### 5.3 Speculation

Two kinds of call go out before they are certain to be needed:
- the next step's Bind is pre-asked while the current move settles (plan 58 §5B);
- a cached affordance is refreshed in idle time.

Unused answers are dropped. A daily cost cap and the breaker (alpha.123) bound all speculation.

### 5.4 Confidence you can trust: calibration and conformal prediction

- **Each tier's raw probabilities are calibrated** on the golden set and live outcomes, per capability:
  temperature/isotonic fits, checked with reliability curves.
- **Acting uses conformal prediction:** for each capability and a chosen error rate (say 1% for opening an app, 0.1%
  for a call), Cyclone computes the set of answers the model can't rule out. **It acts only when that set has exactly
  one answer.** If two candidates remain (Mam and Mama), it asks you or goes up. This turns "unsure goes up" into a
  stated, measured guarantee instead of a hand-picked 0.9.
- **Agreement between JEV and OpenAI Decisions** is a feature in the calibration, not a vote count.

---

## 6. What it feels like: worked timelines (targets, to be measured)

| Request | What already exists when you speak | What happens | Feels like |
|---|---|---|---|
| "open camera" (any language) | T0 exemplars | T0 decides on the partial; the camera intent fires at the end of the sentence | **≤ 100 ms**: instant |
| "make it louder" | T0, earned | Volume up with the haptic; Verify reads the volume level | Instant |
| "tap the blue one" | The screen's affordance cache (computed when the screen first appeared) | T0 matches "blue one" against the cached control descriptions; if not sure, T1 text race over the numbered list; sight only if both say they need it | ~0.1–0.5 s |
| "open insta, go to my DMs, open the first one" | App Map destination "Direct", the routes, the inbox list layout from the cache | The graph: open_app → go_to → open_item. The app opens; the map walk follows screen events, with no model; the first real conversation is known from the cached list structure; Verify at the end | App start + ~0.5 s |
| A game's "Play" button (no accessibility node) | Box location cached from first sight | Tap at the cached box; Verify; on first sight, one JEV box call | ~0.1 s (cached) / ~1 s (first time) |
| "open my Gmail, check which account, then message my grandma from it" | Gmail map, the contacts candidate list | The graph marks `sends` and `writes` → Mind; the routine steps run Instant/Flash at once; the Mind writes; **asks before sending** | The routine part instant; one question at the end |

---

## 7. Safety: the speed never touches the boundaries

- **Consequential is decided by effect, not words** (plan 61 B).
- **Every consequential action:**
  - needs both models to agree, or it goes up;
  - always needs your approval.
- **Optimism only for reversible capabilities,** each with an undo and a Verify check.
- **Sensitive screens never leave the phone as images.** This rests on structure: password fields, autofill hints,
  `FLAG_SECURE`.
- **Speculative calls only ask questions; they never act.** They are bounded by the daily cap and the breaker.
- **One move per decision,** re-observe after each (AGENTS.md), and only through `PhoneToolExecutor`.

## 8. Privacy

- **The Live Phone Model, the affordance cache, the lessons and the likely-next model stay on the phone.**
- **Text decisions carry app words only**, with secret-looking lines dropped.
- **Screenshots are taken on demand only,** never on sensitive screens. The "Let decisions see the screen" and
  "Strict privacy" (zero data retention) switches stay.
- **The health report carries counts, times and costs only.**

## 9. Measures and gates

| Measure | Target |
|---|---|
| Perceived latency, end of request → first move | p50 ≤ 400 ms; T0-decided p50 ≤ 100 ms |
| Share decided on the phone (T0) | Rising week on week; ≥ 50% of everyday requests after a month of use |
| Speculation waste | ≤ 30% of speculative calls unused |
| Wrong reversible moves (undone by Verify) | ≤ 2% |
| **Consequential actions without approval** | **0, the hard gate** |
| Conformal guarantee holds | Observed error ≤ the chosen rate per capability, on held-out data |
| Cost | ≤ $0.002 per request on average, including the race and speculation |
| Languages | Every golden-set language within a fixed margin of English (plan 61 C) |

---

## 10. Build order

Plans 58 and 61 fold into this one; release numbers are assigned when building starts.

| Release | Builds | Gate |
|---|---|---|
| **R0 · Measure the models** | The probe extended: JEV 1.14 and OpenAI Decisions on Cyclone's own text and image boards, plus GPT-6 Luna (chat) on the slot-extraction schema, five runs each, p50/p95, accuracy on the golden set, box accuracy on a set of game/canvas screenshots. JEV marked able to see; screenshots as PNG/JPEG in both models' limits. Naming in code and Settings follows §1: the `LUNA` provider becomes `OPENAI_DECISIONS`, "Test LUNA" becomes "Test OpenAI Decisions", and GPT-6 Luna (chat) is named only where it writes. The race in watch mode (both asked, JEV decides as today) | Real numbers per model and modality on the owner's network; the routing table in §4 confirmed or changed |
| **R1 · Perceive + Decide** | The Live Phone Model; the Request Graph and on-device embedding tier (plan 61 A); the compiled question sets; the race with hedging; calibration and conformal bars; sight routed to OpenAI Decisions; box behind its switch; GPT-6 Luna (chat) only for validated free-value slots and the fallback | Golden set v2 in 12+ languages; 0 consequential under-routes; p50 ≤ 400 ms in the Lab |
| **R2 · Anticipate + Act** | The affordance cache (built in idle time); likely-next ranking; streaming partial decisions for Live voice; speculative Bind; optimistic reversible moves with Verify and auto-undo; Screen Semantics effects (plan 61 B) | Perceived latency targets met on the device matrix; wrong reversible moves ≤ 2%; 0 consequential without approval |
| **R3 · Learn** | On-phone learning loop (plan 61 C); chains (plan 58 §5B) on the graph; Glass draws the numbers; the old word lists and routers deleted with the vocabulary guard on | T0 share rising; every §9 row met; owner sign-off |

## 11. Owner decisions

1. **Race mode:** both models at once on every text decision (fastest, about double the decision cost, still a
   fraction of a cent), or hedged (the second only when the first is slower than its median)? *Recommended: decide
   from R0's numbers; hedged by default.*
2. **Optimistic reversible moves:** act before Verify for reversible moves (recommended), or always verify first
   (slower)?
3. **Idle-time affordance cache:** allow OpenAI Decisions to describe new screens in idle time, with a screenshot of
   non-sensitive screens only (recommended, with the "see the screen" switch respected), or only on demand?
4. **JEV box** for unlabelled targets (games, canvases): on behind its own switch after R0's box test (recommended), or
   wait until TypeSafe calls it stable?

## 12. Sources

- [OpenRouter: JEV docs (1.14, box, long-context mode)](https://openrouter.ai/docs/guides/community/jev)
- [OpenRouter: Multimodal Decisions (image limits per model)](https://openrouter.ai/docs/guides/community/multimodal-decisions)
- [OpenRouter: Decisions API reference](https://openrouter.ai/docs/api/api-reference/alphadecisions/submit-a-decisions-questions-and-answers-request)
- [OpenRouter: Jev vs LLM benchmark (2026-09-19)](https://openrouter.ai/blog/tutorials/jev-vs-llm-when-to-use-each/) (JEV vs chat GPT 5.6 Luna)
- [GPT-6 Luna Decisions on OpenRouter](https://openrouter.ai/openai/gpt-6-luna-decisions)
- [Benchmark Jev vs GPT-6 Luna Decisions via OpenRouter (GitHub PR)](https://github.com/matsonj/eval-connections/pull/33) (the only matched decisions-endpoint test)
- [ML6: Jev vs GPT-6 Luna vs BERT](https://www.ml6.eu/en/blog/jev-vs-gpt-6-luna-vs-bert-text-classification) (GPT-6 Luna through OpenAI's endpoint, not the Decisions API)
- [AIML API: What is Jev (Banking77, TweetEval)](https://aimlapi.com/blog/what-is-jev) (GPT-6 Luna and Sol as generative models, not the Decisions API)
- [OrcaRouter: OpenAI's Decisions API](https://www.orcarouter.ai/blog/openai-decisions-api-gpt-6-luna)
- Live endpoint data: `GET https://openrouter.ai/api/v1/models/{id}/endpoints` (2026-10-16: both 100% uptime over 30
  min; latency not reported for decisions models)
- Dean & Barroso, "The Tail at Scale" (hedged requests); conformal prediction (Vovk et al.; Angelopoulos & Bates)
