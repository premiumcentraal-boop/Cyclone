# 58 · The Luna Decision Box: every request triaged in one fast call, Instant in under a second

Status: **Final plan (2026-10-09), nothing built. Builds as alpha.123–130 (§11).** Written at alpha.122 dev1. Builds on plan 41 (Fast mode and decisions)
and plan 42 (Instant, Flash, Mind). Physical device: **UNVERIFIED** throughout.

**Owner's ask (2026-10-09).** OpenAI's Decisions API is now on OpenRouter as `openai/gpt-6-luna-decisions`, and it
reads **images as well as text**. Build Cyclone's "decision box" on it:

1. The box sees the request (and, when it helps, the screen) and decides **which mode answers it**.
   - "Open camera" → it knows this is a standard action → the camera is open in under a second.
   - "Open my Gmail, check which account is logged in, then message my grandma from it" → clearly the hardest
     kind: it goes to the Mind.
2. When Instant can't settle it at once, the box decides what kind of work it is (open an app, a few routine
   steps, real thinking) and hands it on without losing what was done.
3. Designed the way a large team would build it: measured, calibrated, safe, and using **only Luna Decisions** for
   the deciding.

This plan is:
- what exists today, read in code (§1);
- what is wrong with it, including one likely bug that makes the box answer almost nothing (§2);
- what Luna Decisions is, from the official docs (§3);
- the design (§4–§10);
- the runs (§11), the owner decisions (§12) and the sources (§13).

---

## 0. The answer in short

**Most of the shape already exists.** Plan 42 built the decision box (`mind/modes/DecisionBox.kt`), the router
(`ModeRouter`), Instant (`InstantRun`), the baton, and a phone model that learns from every decision. Plan 41 §15
left a frozen `OPENAI_DECISIONS` slot that waits for exactly this release. What is missing:

| Gap | Today | After this plan |
|---|---|---|
| **The wire** | Questions are sent with `choices`; OpenRouter's schema requires `criteria`. Likely every JEV call is a 400, so routing falls to Flash | The documented shape, tested against a real answer |
| **Sight** | JEV is text only, so no screenshot is ever taken | Luna sees a small marked screenshot when labels aren't enough |
| **The router's questions** | One board: route, intent, target, all `choice` | **Triage**: one call, about 12 typed questions (`score` for difficulty, `noul` for risks, `choice` for capability), then a separate **Bind** call for the target |
| **What Instant can do** | 18 hard-coded intents | A **capability registry**: Android intents, learned routes, compiled skills, each with its risk, undo and verify check |
| **Knowing it worked** | Transport success, mostly | A **Verify** board after the move: "did the screen reach the goal?", with the screenshot |
| **Context** | A fixed context for each board | **Context on demand**: words → labels → screenshot → zoom, climbing only when code, memory or Luna's own "need to see" answer calls for it (§5A) |
| **Short tasks** | Any "and then" goes to Flash | **Instant chains**: 2–4 reversible steps, one Luna call per step, App Map walks for known screens (§5B) |
| **Known places** | App Maps are only consulted inside a mission | A **destination index** checked at decision one: a mapped place is reached with zero calls, or offered to Triage as a choice (§5C) |
| **Trust** | One bar (0.8–0.9) for everything | A bar per capability, set by what a mistake costs, calibrated in the Lab |

**The flow (one request):**

```
request ─┬─► Stage 0 (on the phone, ~5 ms): grammar · local answers · safety rules · phone model
         │      clear and earned ──────────────────────────────► INSTANT (0 network calls)
         │
         └─► Triage board (Luna, 1 call, ~150–400 ms, in parallel with the screen read)
                difficulty 0–3 · capability · risk flags · needs the screen? · several apps?
                │
                ├─ instant ─► Bind board (target: app / label / contact; image if icon-only)
                │               └─► INSTANT: ≤3 moves ─► Verify board ─► done (sound + buzz)
                ├─ flash ───► FLASH: a short plan; each step decided by a Luna board (the Pilot)
                ├─ mind ────► MIND: full model, approvals, Pilot for routine stretches
                └─ answer / ignore
         Unsure goes up, never down. Every hand-over carries the baton.
```

**Honest limit, stated up front:** Luna Decisions **picks from lists and gives probabilities**; it never writes text.
So "message my grandma" can be *routed*, *stepped* and *checked* by Luna, but the words of the message, and the
plan for a long task, still come from a generative model (the Mind's model today, `openai/gpt-6-luna` on OpenRouter).
"Only Luna" here means: **every decision is a Luna Decisions call**; generation stays where only generation works.

---

## 1. What exists today (read in code on alpha.122)

| Piece | File | What it does |
|---|---|---|
| Entry | `mind/modes/CycloneModes.kt` | `handle()` is the one entry for Ask, Live voice, Drive, Glass. A running mission takes the request; an attachment or Speed → Always Mind goes to the Mind; everything else is routed on its own thread and timed (`ModesTrace`) |
| Router | `mind/modes/ModeRouter.kt` | 1. Speed. 2. **Stage 0**: `InstantGrammar` + local answers (time, date, battery) + chatter + "that app isn't on this phone". 3. **Rules** (regex): writing, money, deleting, accounts, several apps, a question → Mind; a second clause → Flash. 4. **Phone model** when it has earned the action, or the action is reversible and it is ≥ 0.9 sure. 5. **Board 0** on the decision box. No answer → Flash |
| Decision box | `mind/modes/DecisionBox.kt` | `BoxQuestion` (id, instructions, ≤ 64 choices) → `BoxReply` (choice + confidence). Two wires: a strict-schema chat call and the decisions endpoint. Secret-looking lines are dropped from the context |
| Provider | `mind/decide/Decisions.kt` | `JEV` live (`~typesafe/jev-latest`, `https://openrouter.ai/api/alpha/decisions`, text only); `OPENAI_DECISIONS` frozen (`live = false`, no model, `vision = true`). Routing deadline 2.5 s |
| Instant | `mind/modes/InstantRun.kt`, `AndroidInstantHands.kt` | ≤ 3 moves; never types, sends, pays, deletes or acts on a sensitive screen; follow-up controls (the shutter, the call button) found by name or by one box. Anything it can't finish promotes with what it did |
| Intents | `mind/modes/InstantGrammar.kt` | SWIPE, SCROLL, BACK, HOME, RECENTS, TAP, OPEN_APP, CAMERA, PHOTO, SELFIE, CALL, TIMER, ALARM, FLASHLIGHT, VOLUME, MEDIA, ZOOM, DRAG |
| Learning | `mind/decide/PhoneBrain.kt`, `PhoneModel.kt`, `Lessons.kt` | Every routed request becomes a lesson with its outcome; a small on-phone model trains from them, earns actions (sure ≥ 0.9), and 1 in 5 requests is audited against JEV in the background |
| Flash | `mind/pilot/Pilot.kt`, `FastMode.kt` | A short plan walked by the Pilot; each step is a decision board (`fits_plan`, `needs_smart`, `next`, `reason`) |
| Baton | `RunBaton` in `ModeRouter.kt` | Goal, modes so far, moves done, why it stopped, candidates. The next mode continues, never redoes |

**So "open camera" today:** the grammar matches `CAMERA` in Stage 0, no network, `hands.camera()` fires the camera
intent. That already is the sub-second path, when the grammar recognises the words. The design below is about
everything the grammar *doesn't* recognise, and about seeing.

---

## 2. What is wrong today

1. **The decisions wire doesn't match the documented schema (likely bug, verify first).**
   OpenRouter's `POST /api/alpha/decisions` requires, for a `choice` question, `type`, `instructions` and
   **`criteria`** (an object mapping each option to its guidance). `BoxWire.decisionsBody` and
   `PilotWire.decisionsBody` send a **`choices` array** and no `criteria`; `criteria` appears nowhere in the app.
   If the endpoint enforces its schema, every Board 0 and Pilot decision is a 400 → `null` → "the router's decision
   box gave no answer" → Flash. That fits the alpha 91 note that "make it louder" took 15–70 s through Flash.
   **Run L0 checks this with one real call before anything else.**
2. **`state` is sent as `{task, situation}`.** Images must be top-level items of a `state` **array**
   (`{"type":"image_url","image_url":{"url":"data:image/webp;base64,…"}}`), text items plain strings. The current shape
   can't carry a screenshot.
3. **The answer reader guesses.** `parseDecisions` tries `answers|decisions|results|output`. The documented answer is
   `answers.<name>` with `choice`, `confidence`, `probabilities` (and `noul` / `score`). It should read that exactly,
   and keep the full `probabilities` (the second-best option matters: "Mam" vs "Mama").
4. **Dependent questions in one call.** Board 0 asks route, intent and target together, but the target depends on
   the intent. The Decisions guidance is: independent questions together, dependent ones in a second call.
5. **The rules are regexes over words.** `MIND_WORDS` sends "open my mail" to the Mind (it contains "mail"), and
   "check which account" to the Mind ("account"). Safety words are right to force *approval*; they are wrong as the
   only signal of *difficulty*.
6. **One bar for every action.** Opening an app and calling someone use the same confidence bar; the cost of being
   wrong is very different.
7. **No verify.** Instant trusts that a move worked when the executor says so; "transport success ≠ task success"
   (AGENTS.md) is only partly applied.

---

## 3. Luna Decisions, from the docs

| | |
|---|---|
| **Model** | `openai/gpt-6-luna-decisions` (OpenRouter); natively `POST /v1/decisions`, model `gpt-6-luna`. Public beta 2026-10-06 |
| **Endpoint (OpenRouter)** | `POST https://openrouter.ai/api/alpha/decisions`, bearer key: the same endpoint and key Cyclone already uses for JEV |
| **Request** | `model`, `state` (string, object, or array), `questions` (map of name → question); optional `provider` (`sort`, `zdr`, …), `session_id`, `trace` |
| **Question types** | `noul`: yes/no, `criteria` with keys `"true"` and `"false"`. `choice`: `criteria` maps each option → guidance. `score`: `criteria` is an ordered array, lowest first |
| **Answer** | `answers.<name>`: `noul` → probability of yes; `choice` → `choice`, `confidence`, `probabilities`; `score` → `score`, `confidence`, `probabilities`, `legend`. A `refusal` answer is possible and is **not** a "no" |
| **Images** | Top-level `state` array items `{"type":"image_url","image_url":{"url":"data:image/png|jpeg|webp;base64,…"}}`; remote URLs are not fetched. Up to **128 images** per request |
| **Limits** | Up to **200 questions** per request; ~1.05–1.1 M context |
| **Price** | $0.10 per million input tokens, output free |
| **Speed** | OpenAI claims ~10× faster than a Responses call; plan 41 cites ~150 ms per decision vs ~1.6 s for a Luna chat call (a published figure, not our measurement). **No p95 is published: we measure our own** |
| **Data** | Up to 30 days abuse-monitoring retention by default; ZDR available with caveats. Treat a screenshot as leaving the phone |

What this means for Cyclone:
- **Many questions cost one call.** Output is free and up to 200 questions fit, so the triage can ask a dozen
  questions for the price of reading the request once.
- **Probabilities, not just labels.** Thresholds can be set per question by the cost of a mistake.
- **It sees.** Icon-only controls (a shutter, a send arrow, a profile avatar) can be picked from a marked screenshot.

---

## 4. The design: four boards, one ladder

The decision box becomes four boards. Each is one Luna call, pure question builders and readers in
`mind/modes`, the call itself in `mind/decide/Decisions`.

| Board | When | Questions (types) | Image? | Deadline |
|---|---|---|---|---|
| **Triage** | Every request Stage 0 doesn't settle | ~12, all independent (§5) | Only if the request points at the screen ("tap that", "what's this") | 1.2 s |
| **Bind** | Triage said Instant and the capability needs a target | 1–3: which app / label / contact; "is the target on screen?" | When the best label candidates are weak or icon-only | 1.0 s |
| **Step** | Each Flash / Pilot step (today's Pilot board, rewired) | `fits_plan` (noul), `needs_smart` (noul), `next` (choice), `reason` (choice) | Yes when allowed (§9) | 1.5 s |
| **Verify** | After every screen-changing Instant move and at the end of Flash | `goal_reached` (noul), `blocked_by` (choice: none, permission dialog, sign-in, error, popup, wrong app) | Yes when allowed | 1.0 s |

**The ladder:** Stage 0 → Instant → Flash → Mind. A board that doesn't answer in time, answers below the bar, or
refuses moves the request **one rung up**, never down, with the baton.

---

## 5. The Triage board, question by question

One call. Code combines the answers; **the model never decides alone what is safe** (the rules in §7 do).

| Name | Type | Criteria (short) | Used for |
|---|---|---|---|
| `difficulty` | score | 0 one obvious action · 1 a few routine steps in one app · 2 several apps, or reading and choosing · 3 writing, judgement, or personal context | The rung |
| `capability` | choice | The registry's entries that fit the request (§6), plus `none` | What Instant would do |
| `is_for_cyclone` | noul | true: an instruction or question for the assistant; false: chatter, someone else being spoken to | Ignore |
| `answer_only` | noul | true: wants information, nothing done on the phone | Answer path |
| `sends_or_posts` | noul | true: sends, posts, shares, replies to anyone | Approval (§7) |
| `money` | noul | true: buys, pays, orders, books, transfers | Approval |
| `destroys` | noul | true: deletes, removes, uninstalls, resets | Approval |
| `account` | noul | true: signs in, out, changes an account or a password | Approval |
| `writes_text` | noul | true: needs words Cyclone must compose | Mind (generation) |
| `multi_app` | noul | true: needs more than one app | ≥ Flash |
| `needs_screen` | noul | true: refers to what is on screen now ("this", "that button") | Take the screenshot |
| `later` | noul | true: at a time or on a condition ("in 10 minutes", "when I get home") | Routine / scheduler, not Instant |

**Combining (code, `ModeRouter.fromTriage`):**

```
if !is_for_cyclone ≥ 0.8                         → IGNORE
if answer_only ≥ 0.8 and capability = none        → ANSWER (local facts) or MIND (a real question)
risky = sends_or_posts | money | destroys | account   (each ≥ 0.3: a low bar, because missing one is expensive)
if writes_text ≥ 0.5 or difficulty ≥ 2.5          → MIND
if later ≥ 0.5                                    → MIND (it sets a routine; plan 26)
if difficulty < 0.6 and capability.p ≥ bar(capability) and !risky and !multi_app
                                                  → INSTANT (then Bind if the capability has a target)
if difficulty < 1.6 and !writes_text              → FLASH (risky steps still stop for approval)
else                                              → MIND
```

Notes:
- `difficulty` comes back as an **expected score with a confidence**; a wide spread (say 0.4 on 0 and 0.4 on 2)
  means unsure, and unsure goes up.
- The risk flags have a **low** bar on purpose: they don't make the request harder, they put an approval in front of
  the one dangerous move. That replaces the regex `MIND_WORDS` as the difficulty signal; the regex stays as a
  belt-and-braces safety net (§7).
- The candidate list for `capability` is cut on the phone to the ~40 most likely entries (word overlap, the phone
  model's ranking), so the choice stays sharp.

**Worked requests (targets, to be measured in the Lab):**

| Request | Stage 0 | Triage | Then | Target time to first move |
|---|---|---|---|---|
| "Open camera" | grammar `CAMERA` | — | `hands.camera()`, Verify: camera preview | **≈ 0.3 s, 0 calls** |
| "Pull up the thing I take pictures with" | none | d 0.1, `camera` 0.93 | camera; Verify | ≈ 0.6 s, 1–2 calls |
| "Make it louder" | phone model (earned) | — | volume up | ≈ 0.1 s |
| "Turn on dark mode" | none | d 0.3, `settings.dark_mode` 0.9 | Android settings panel / toggle; Verify | ≈ 0.8 s |
| "Tap the blue one" | none | d 0.2, `tap`, needs_screen | Bind with a marked screenshot → label #7 | ≈ 1 s |
| "Call mam" | grammar `CALL` | — | Bind contact (Mam vs Mama → owner asks if the gap < 0.2); 2 s cancel window | ≈ 1 s + window |
| "Open Gmail and see which account I'm on" | none | d 1.2, multi_app false | **Flash**: open Gmail, tap avatar, read the account (Step + Verify boards) | ≈ 1.5 s |
| "Open my Gmail, check which account is logged in, then message my grandma from it" | none | d 2.9, sends 0.97, writes_text 0.95 | **Mind**: plans, Flash-steps the routine parts, writes the message, **asks before sending** | several s; the send waits for the owner |

---

## 5A. Context on demand: the box decides what it needs to see

**The rule:** every question starts with the **cheapest context that could answer it**, and climbs only when the
answer itself says the context wasn't enough. No screenshot "just in case"; no guessing when one is needed.

### 5A.1 The context ladder

| Tier | What the board gets | Added cost | Typical use |
|---|---|---|---|
| **C0 · Words** | The request, the foreground app, a shortlist of installed apps, local facts | — | Triage for "open camera", "louder", "set a timer" |
| **C1 · Labels** | + the screen as a **numbered** list of controls from the accessibility tree: `7: "Send" (button)`, `12: "Anna · 2h" (list item 1)` | tree read, ~20–80 ms | Bind and Step on normal app screens |
| **C2 · Sight** | + a marked screenshot (~512 px, WebP), the **same numbers** drawn on the controls | capture + encode + upload, est. 150–400 ms (to measure) | Icon-only controls, games, canvases, "tap the blue one", Verify on visual apps |
| **C3 · Zoom** | + a sharper crop of one region (the list, the toolbar) | another image | Small text, dense lists, two similar icons |
| **K · Map** | + the App Map card for this app: its known screens and the route to them (`mind/map/MindMap`) | on the phone, ~5 ms | "Go to my DMs", "open settings in Instagram": a known destination |

The numbers are the **choices**: Luna answers `12`, code taps control 12. It can't point at something that isn't
on the screen, and the text list and the picture can't disagree, because they come from the same numbering.

### 5A.2 Four ways the tier is chosen (cheapest first)

1. **Code knows (0 calls).** Signals in the accessibility tree force a tier before asking anything:
   - the likely targets have no text or description (icon-only buttons) → C2;
   - the screen has almost no nodes (a game, a video, a WebView canvas, a Flutter app with an empty tree) → C2;
   - the request points at the screen ("this", "that", "the blue one", "what's on my screen") → C2;
   - the screen is sensitive → **never** C2 or C3; the question goes up a rung instead.
2. **The phone remembers (0 calls).** Every answered question leaves a lesson: *(app, screen key, question kind) →
   the tier that finally answered it.* Next time the question starts at that tier. Instagram's bottom bar is
   icon-only: after the first time, Bind on that screen starts at C2 without trying C1 first. This is the phone
   learning **what context it needs**, per screen, from experience.
3. **The answer asks for more (1 extra call, only when needed).** Every target question carries two **abstain
   choices** with clear criteria:
   - `not_listed`: "what the request means isn't among these controls";
   - `need_to_see`: "the words alone don't tell which control is meant; seeing the screen would".

   An answer acts only when **all three** hold: the top choice ≥ the capability's bar, the gap to the second choice
   ≥ 0.2, and abstain < 0.15. Otherwise the **same single question** (not the whole board) is asked again one tier
   up: `need_to_see` → C2; a close tie between two similar icons → C3; `not_listed` → scroll once (in a chain) or
   go up a rung. At most **two climbs**, then the run goes up a rung with the baton.
4. **Racing, when the phone has learned it's a coin flip.** For a screen where C1 failed more than ~30 % of the
   time, ask C1 and C2 **at the same time** and take the first sure answer. It costs a fraction of a cent and saves a
   whole round trip. Off for sensitive screens and when "Let decisions see the screen" is off.

Triage also asks `needs_screen` (noul) from the words alone. It is a **hint**, used to start the screenshot early
in parallel, never a permission to skip one that code or the abstain answer calls for.

### 5A.3 Why this is solid

- **No silent guesses.** A model forced to pick from a list will pick something; the abstain choices give it an
  honest way out, and code treats "unsure" as "go up", never "go ahead".
- **Context matches the question, not the request.** In one chain, step 1 (open Instagram) needs C0, step 2 (the DM
  icon) needs C2, step 3 (the first conversation) needs C1. Each step climbs on its own.
- **It gets faster with use**, because the remembered tier skips the failed attempts.

---

## 5B. Chains: Instant for two to four steps

"Open Instagram, go to my DMs and open the first one" is three steps, all reversible, with no typing. It should
run in seconds without the Mind. That is an **Instant chain**: still Instant mode (the ⚡ chip shows "3 steps").

### 5B.1 When a request is a chain

Triage gets a **slot plan**: up to four extra choice questions, `step_1` … `step_4`, each picking a **step kind**
from a short fixed list. The step kinds:
- `open_app`, `go_to` (a screen in the app), `find` (scroll until it's there), `tap`, `open_item` (the nth thing
  in a list), `back`, `toggle` (a setting);
- `read` ("tell me who messaged"), which ends the chain with an answer;
- `none`, which ends the plan.

These are **independent** questions about the words, so one call answers all of them (Decisions' own guidance). The
step *targets* are **not** chosen yet: they depend on the screens that don't exist yet.

The router takes a chain when:
- Triage's `difficulty` < 1.3;
- 2 to 4 slots are filled;
- every step kind is reversible;
- `writes_text`, `sends_or_posts`, `money`, `destroys` and `account` are all below their bars.

Otherwise it goes to Flash, as today. The existing clause splitter (`agent/nav/TaskClauses`) is used as a check:
when it and the slot plan disagree on the number of steps, the run goes up to Flash.

### 5B.2 One call per step: the fused board

After each move the screen settles (the Fast Path settle: 300 ms, then up to 500 ms / 1 s while the screen is
still changing). Then **one** Luna call answers, about the screen *as it is now*:

| Question | Type | Meaning |
|---|---|---|
| `prev_done` | noul | Did step *k* reach what it was for? |
| `blocker` | choice | `none` · `popup` · `permission` · `sign_in` · `error` · `wrong_screen` · `loading` |
| `next_target` | choice | Step *k+1*'s control: the numbered controls + `not_listed` + `need_to_see` |
| `next_move` | choice | `tap` · `scroll_down` · `scroll_up` · `back` · `wait` |

Verifying the last step and binding the next one in **one call** halves the calls. These questions are independent
given the screen, so they belong together. A three-step chain is about three calls, not six.

### 5B.3 The shortcuts that make it fast

- **Map walk, zero calls.** If the App Map knows the destination ("Direct inbox" in Instagram), `go_to` walks its
  route with `MapWalker`: each move is checked by the screen key, no model. "Go to my DMs" becomes two map taps. Only
  when the map diverges does the fused board take over.
- **Ordinals in code.** "The first DM", "the latest email", "the third photo": code finds the list (a scrollable
  container with repeated rows) and numbers its items in reading order. When the list is plain, "first" is item 1
  and needs no call. When it isn't (a "Notes" row, a "Requests" header, a pinned chat above the first real
  conversation), Bind asks *"which item is the first conversation?"* over the numbered items.
- **Bounded find.** `find` scrolls at most three times. After each scroll the fused board asks whether the target is
  there now. It stops early when the screen fingerprint doesn't change (the end of the list).
- **Prepare while waiting.** While an app opens, the phone loads its App Map, builds the next question and warms
  the connection, so the call goes out the moment the screen settles.

### 5B.4 The chain's guard rails

- At most **4 steps, 6 moves, 8 s**. One screen-changing move per decision; the screen is re-read after every move.
- **Stops at the goal.** "Open the first DM" ends with the conversation open. It never types, and never taps a
  send, call, follow, like or delete control (`Pilot.irreversible`), whatever the board says.
- **Blockers:** a `popup` with a clearly dismissive control ("Not now", "Skip", "Close") may be dismissed **once**.
  `permission`, `sign_in` and `error` hand the run up with the baton. `loading` waits once, up to 1 s.
- **Any unsure answer** after two context climbs → Flash takes over **at the current step**, with the steps done on
  the baton. Nothing is redone.
- A sensitive screen anywhere in the chain → the Mind.

### 5B.5 Worked example (targets, to be measured)

"Open Instagram, scroll to my DMs and open the first DM":

| # | Step | Decided by | Context | Time (target) |
|---|---|---|---|---|
| — | Triage + slot plan: `open_app`, `go_to`, `open_item` | 1 call | C0 | 0.2–0.4 s, in parallel with the tree read |
| 1 | Open Instagram | intent, Bind not needed (the grammar knows the app) | C0 | app start: 0.5–2 s (the app, not Cyclone) |
| 2 | Go to DMs | App Map walk (0 calls); without a map, the fused board → the messenger icon is icon-only, so C2 | K, else C2 | 0.4 s with the map, ~0.9 s without |
| 3 | Open the first conversation | Code numbers the list; one fused call checks step 2 and picks the first real chat | C1 | ~0.7 s |
| ✓ | End: Verify (`prev_done` on the last screen) | 1 call | C1 | ~0.3 s, the sound plays on yes |

That is roughly **2.5–4 s** end to end, most of it Instagram starting, and **3–4 Luna calls** (about $0.0003). The
Mind takes 20–60 s for the same request today.

## 5C. Mapped at decision one: the destination index

**The problem.** App Maps (`mind/map/MindMap`) are built per app, lazily, only once a mission is already inside the
app. At decision one, the router doesn't know that "my Instagram DMs" is a screen Cyclone has already walked twenty
times. So it plans, binds and verifies its way there.

**The fix.** One small phone-wide **destination index**, in memory, that every request is checked against before
anything else. When the request names a mapped place, the router knows it at once. Then the place is either reached
with **zero model calls**, or offered to Luna as a choice in the Triage call it makes anyway.

### 5C.1 What is in the index

One entry per mapped screen that is worth going to, across all apps:

```kotlin
data class Destination(
    val app: String, val appLabel: String,      // com.instagram.android, "Instagram"
    val screenId: String, val title: String,    // the MindMap screen
    val aliases: List<String>,                  // "direct", "messages", "dms", "chats", "berichten" (§5C.2)
    val hopsFromStart: Int,                     // moves from the app's start screen (MindMap.route)
    val reliability: Double,                    // the weakest move on that route
    val walks: Int, val lastWalkOk: Boolean,    // how often a walk there was verified, and the last result
    val appVersion: String?,                    // the app version the route was learned on
)
```

- **Small.** A few hundred entries and a few hundred kB, rebuilt for one app whenever Learn or a walk changes that
  app's knowledge (`AppKnowledgeStore` upserts). Never rebuilt per request.
- **Safe moves only.** It is built from the same filter as `MindMap.from`: no stale moves, no risky actions,
  reliability ≥ 0.5. A destination behind a risky move isn't in the index at all.
- **Structure only.** Screen and control names, no content (the same rule as LearnedHints).

### 5C.2 Where the aliases come from (no model at request time)

- **The screen's title.**
- **The labels of the controls that lead there.** A move labelled "Messenger" or "Direct" into the inbox makes
  those words aliases of the inbox. This is the strongest source, and it is free.
- **The owner's own words.** Every **verified** run (Instant, Flash or Mind) that ended on a mapped screen adds the
  request's key words as an alias of that screen: "dms" → Instagram's Direct inbox. Next time that exact phrasing is
  a zero-call hit. Cyclone learns your vocabulary from your own runs.
- **Once, in the background:** when a new screen is mapped, the fast generative model may suggest a few synonyms and
  the Dutch words ("berichten", "inbox"). This happens at map time, never at decision time. These aliases are marked
  as suggested, and they only become trusted after a verified walk.

### 5C.3 How it is used at decision one

1. **Look up (on the phone, ~1–3 ms).** Stage 0 matches the request's words against app names
   (`InstalledAppLexicon`) and the index aliases, and scores each destination:
   - how well the words match;
   - whether the app is named, or the app in front is that app;
   - the destination's reliability and walk count;
   - a penalty for an app version different from the one the route was learned on.
2. **Strong hit → zero calls.** All of these must hold:
   - one destination clearly wins (its score ≥ 0.9 and it leads the next one by ≥ 0.3);
   - its route is verified (≥ 3 walks, the last one ok);
   - Stage 0's rules see no risk words.

   Then the router plans `open_app` → `go_to` itself, and Instant starts **with no Luna call**. A Triage call still
   runs **in parallel, as a check**. If Triage disagrees (risk flags, `writes_text`), the walk stops before its next
   move and the run goes up a rung. These moves are reversible, so racing is safe.
3. **Candidate hits → one choice in Triage.** Otherwise the top 8–15 destinations become the options of a
   `destination` choice question in the **same** Triage call, plus `none`. Its criteria look like this:
   `"instagram.direct": "Instagram › Direct inbox: messages, DMs, chats (2 taps from start, walked 14×)"`. Luna now
   knows at decision one what is mapped, at no extra cost: one more question in a call that is already being made.
   When `destination` is sure, the chain's `go_to` step is a map walk, not a fused-board search.
4. **No hit → as before.** Triage, chains and Flash work as already designed; the index only ever makes runs faster.

### 5C.4 The walk itself, made faster

- **Wait for the screen, not a fixed time.** Each map move knows which screen should come next (its fingerprint). The
  walker listens for the accessibility window/content-changed events and moves on **the moment that fingerprint
  appears**, instead of the fixed 300 ms settle plus ladder. If it hasn't appeared after the ladder's 1 s, the walk
  has diverged.
- **Start from where you are.** `MindMap.locate(pageKey)` finds the current screen. If you are already in the app, the
  route starts there, not from the start screen, so nothing is redone.
- **Pre-load.** On a hit, the app's MindMap and the next questions are loaded while the app is still starting.
- **Jumps, when an app offers them.** If Learn ever sees that an app's own link or a launcher shortcut opens a mapped
  screen directly, and a walk confirms it, the index stores it as a one-move "jump" that skips the taps.

### 5C.5 When the map is wrong

- **The walker checks every screen** (it does today). The first surprise stops it, reports `diverged` (which also
  drops the move's confidence), and the fused board continues from the current screen with the steps done on the
  baton.
- **An app update** marks that app's destinations "suspect". They still go into Triage as candidates, but stop being
  zero-call hits until one verified walk on the new version.
- **Two failed walks in a row** take a destination out of the zero-call path until Learn re-confirms it.

### 5C.6 How much faster

| "Open my Instagram DMs" | Calls | Time to the inbox (target, to be measured) |
|---|---|---|
| Today (the Mind) | many | 20–60 s |
| Instant chain without a map (§5B) | 2–3 | app start + ~1.5 s |
| Chain with the destination picked in Triage | 1 | app start + ~0.7 s |
| **Strong hit in the index** | **0** (1 in parallel as a check) | **app start + ~0.3–0.5 s** (two event-driven taps) |

The aim: everything you do often ends up in the bottom row. Every verified run adds aliases, and every verified walk
raises reliability. So more requests become zero-call hits, the same way the phone model earns actions today.

---

## 6. The capability registry (what "instant" can mean)

Today Instant knows 18 intents. A team would turn this into data: a **registry** of things Cyclone can do in one
move, each with what it needs and how to check it.

```kotlin
data class Capability(
    val id: String,                 // "camera", "settings.dark_mode", "open_app", "route:gmail.compose"
    val description: String,        // the Triage criteria text: what requests mean this
    val target: TargetKind,         // NONE, APP, LABEL, CONTACT, NUMBER, TIME
    val how: How,                   // INTENT(action, extras) · SETTINGS_PANEL · GESTURE · LEARNED_ROUTE(id) · COMPILED_SKILL(id)
    val risk: Risk,                 // REVERSIBLE · OWNER_VISIBLE (call, alarm) · NEEDS_APPROVAL (never Instant)
    val bar: Double,                // confidence needed to act; set in the Lab per capability (§10)
    val verify: Verify,             // what the screen should show after: a package, a label, a toggle state
)
```

Sources of entries, all on the phone:
- **Built-ins:** the 18 intents, plus Android standard intents (`MediaStore.ACTION_IMAGE_CAPTURE`,
  `AlarmClock.ACTION_SET_ALARM` / `ACTION_SET_TIMER`, `Settings.Panel.ACTION_WIFI` / `ACTION_INTERNET_CONNECTIVITY` /
  `ACTION_VOLUME`, `Settings.ACTION_*` screens, `ACTION_VIEW` with `geo:` / `https:` for maps and links).
- **Installed apps:** `open_app` with the launcher list as Bind's choices (`InstalledAppLexicon`).
- **Learned routes and compiled skills** (`skills/SkillRouteCompiler`, App Manual): "open Gmail compose" becomes a
  one-call capability once a route exists, so things get faster as Cyclone learns the phone.
- **Connectors** (plan 51): a connector's read actions can be capabilities; its writes are `NEEDS_APPROVAL`.

The registry respects the invariants: every move goes through `PhoneToolExecutor`; learned routes and
`phone.open_app` before coordinates; one screen-changing move per decision.

---

## 7. Safety: code decides what is allowed, Luna decides what is meant

- **Never Instant:** anything that sends, pays, deletes, posts or touches an account, a sensitive screen
  (`InstantScreen.sensitive`), or a `Pilot.irreversible` label. Kept in code; a Triage answer can only make a request
  *more* careful, never less.
- **The regex safety net stays.** If `ModeRouter.forced` fires and Triage disagrees, the stricter one wins and the
  disagreement is logged as a lesson.
- **Calls and alarms** (`OWNER_VISIBLE`) keep the 2 s cancel window.
- **A `refusal` answer** is treated as "no answer": one rung up.
- **Ambiguity is asked, not guessed:** when Bind's top two probabilities are within 0.2 (Mam / Mama), Instant asks
  the owner (Live speaks "which Mam?") instead of picking.
- Money moves only with an owner-confirmed exact price (unchanged).

---

## 8. Speed: how it stays under a second

1. **Stage 0 first, no network.** Grammar, local answers and the earned phone model settle the common commands in
   ~5 ms. Luna is for the rest.
2. **Parallel, not in a row.** The moment a request arrives: start the Triage call, read the accessibility tree, and
   (only if a screenshot may be needed) capture it, **at the same time**. Bind starts the moment Triage says Instant.
3. **Speculative Bind.** For requests whose Stage 0 parse is ambiguous between two apps or labels, send Triage and
   Bind together (they're independent once the candidates are known) and discard Bind if Triage says Flash.
4. **A warm connection.** One shared OkHttp client with HTTP/2 keep-alive to openrouter.ai; a tiny warm-up call when
   the Ask bar or Live opens.
5. **Small images.** Downscale to ~512 px on the long side, WebP, with set-of-mark numbers on the controls (the same
   marks the Mind uses), and only when `needs_screen`, or Bind's text candidates are weak.
6. **Deadlines per board** (§4). A late answer is no answer: one rung up. Today's 2.5 s routing deadline becomes
   1.2 s for Triage.
7. **The phone model keeps learning from Luna** (alpha 89's design, unchanged): every verified Luna decision is a
   lesson, so more requests move into Stage 0 over time and need no call at all.

**Budget for "pull up the camera" (not recognised by the grammar):** tree read ‖ Triage 150–400 ms → intent
50 ms → Verify (optional, off the critical path: the sound plays when Verify says yes) ≈ **0.4–0.6 s to the camera**.

---

## 9. Privacy

- Text context keeps today's rules: app words only, secret-looking lines dropped (`BoxWire.clean`).
- **A screenshot leaves the phone.** Taken only when a board needs it; **never** on a sensitive screen, never of an
  app kept out of quick actions, never in a profile the owner marked private. Settings → Speed gets a switch
  "Let decisions see the screen" (default on, a sentence on what is sent).
- Ask OpenRouter for zero-retention providers (`provider.zdr = true`) when the owner turns on "strict privacy";
  measure the speed cost.
- Lessons stay on the phone; the health report carries counts and times only (unchanged).

---

## 10. Measuring it (the part a big team never skips)

1. **A golden set.** ~300 requests in English and Dutch, each labelled with the right rung, capability and target,
   spread across easy, ambiguous, risky and out-of-scope. Kept in `mind/lab` beside the existing Lab missions.
2. **Shadow first.** Luna runs beside JEV in Watch mode (plan 41) on real use: both answer, only JEV acts. Record
   answer rate, agreement with the eventual outcome, latency p50 / p95, refusals, cost.
3. **Calibration.** For each question, a reliability curve: when Luna says 0.9, is it right 90 % of the time? Set each
   capability's `bar` from the cost of a false yes vs a false no, on a tuning half, and check on a held-out half.
4. **The numbers in Glass.** Brain → Decisions shows, per board: answer rate, time p50/p95, how often each rung was
   chosen, how often Instant was handed up, verify pass rate. (Extends `DecisionStats`.)
5. **Switch only on evidence.** `Decisions.ACTIVE` moves to Luna after shadow data shows it is at least as accurate
   as JEV and fast enough (p95 Triage ≤ 800 ms on the owner's network).

---

## 11. The build plan (final): eight releases, alpha.123–130

**Order.** This plan takes alpha.123–130. Plan 56 (VMOS fleet) moves to alpha.131+ (owner decision 5, §12).

**Each release:**
- is one plan stage;
- ships behind its own switch, default **off** or **shadow** until its gate passes;
- follows the release rhythm (code first, Mobile CI green, then the version bump).

Nothing reaches the owner's everyday routing until the Lab numbers say it is at least as good as what it replaces.

### 11.1 The engineering rules for every release

These hold for every work package below. A PR that breaks one doesn't merge.

**Correctness**
1. **Pure core, thin edges.** Question builders, wire formats, readers, combiners (`fromTriage`, the chain
   planner), the index and its scoring are pure Kotlin with JVM unit tests. Android code only gathers inputs and
   moves the phone, through `PhoneToolExecutor`.
2. **Typed everywhere.** Questions are `noul` / `choice` / `score`; answers carry `probabilities` and may be
   `refusal`. No string-sniffing readers: the documented shape is read exactly, and anything else is "no answer".
3. **Recorded fixtures.** Every reader is tested against **real** Luna and JEV answers, recorded once, redacted and
   kept in `src/test/resources/decisions/`. Every board also has tests for: an unknown option, a missing answer, a
   refusal, a 400, a 429, a timeout, and an empty body.

**Failure**
4. **Unsure, slow or broken goes up, never down.** Every board has a deadline. A late, low, abstaining, refused or
   failed answer moves the request one rung up with the baton. No request is ever lost (today's rule, kept and
   tested).
5. **Circuit breaker.** Three decision failures in 60 s, or a 429, pause the boards for 2 minutes. During the pause,
   routing uses Stage 0, the rules and the phone model, and anything else goes to Flash. One line shows it in the
   run trace and in Glass. Offline works the same way.
6. **Kill switches.** Each stage has its own switch in Settings → Speed → Advanced (and as a Lab variant): `triage`,
   `sight`, `verify`, `registry`, `index`, `chains`, `luna_step`. Turning one off falls back to the release before.
   Speed → "Instant for commands" stays the one-tap way back to grammar-only.

**Safety and privacy**
7. **Safety lives in code.** These are all CI guards in `scripts/ci/tests/test_luna_box_guard.py`:
   - a decision can make a request more careful, never less;
   - every target question has `not_listed` and `need_to_see`;
   - no screenshot is taken on a sensitive screen, or when "Let decisions see the screen" is off;
   - Instant never taps an irreversible label;
   - the decisions endpoint stays in one place.
8. **Privacy.**
   - Context is app words only, with secret-looking lines dropped.
   - Screenshots only per §5A.
   - Lessons, aliases and the index hold structure only, never content.
   - The health report and debug files carry counts and times. A redaction test covers every new store.

**Measurement**
9. **Measured, not assumed.** Every stage logs to `ModesTrace` and `AskLedger`: the board, the tier, the time, the
   answer, the confidence and the cost. `DecisionStats` grows per-board numbers (p50/p95 time, answer rate, abstain
   rate, hand-up rate, verify pass rate, cost per request) for Glass and the Lab.
10. **Languages and phones.**
    - The golden set covers English and Dutch from day one, plus 10 % other languages: Triage must route them,
      even though the grammar can't parse them.
    - The device matrix covers at least a Pixel, a Samsung and one other OEM skin, a small and a large screen, dark
      mode, font scale 1.3, and a slow network (3G profile).

### 11.2 The service levels each gate checks

All on the owner's phone, measured over the Lab golden set and a week of shadow use.

| What | Target |
|---|---|
| Stage 0 (grammar, rules, phone model, index lookup) | p95 ≤ 20 ms |
| Triage call | p50 ≤ 400 ms, p95 ≤ 900 ms |
| Zero-call index hit → first move | p95 ≤ 150 ms (app start not counted) |
| Instant (single step) → first move, not grammar-settled | p50 ≤ 700 ms |
| Instant chain (2–4 steps) end to end | median ≤ 5 s, p95 ≤ 9 s |
| **Risky request routed without approval** | **0, a hard gate** |
| Wrong action on Instant (golden set) | ≤ 1 % |
| Rung accuracy (golden set) | ≥ JEV's on the same set, and ≥ 90 % |
| Abstain or hand-up when the right answer was available | ≤ 10 % |
| Cost | ≤ $0.001 per request on average |

### 11.3 The golden set and the decisions Lab (built first, used by every gate)

- **`mind/lab/golden/requests.jsonl`:** about 300 requests at the start, growing with every bug. Each request is
  labelled with:
  - the rung, the capability and the target;
  - the risk flags;
  - for chains, the steps;
  - for screen-dependent requests, a recorded screen (tree + marked screenshot, from a test account).

  The mix: easy commands, paraphrases, ambiguous requests, risky ones, chatter, other languages, multi-step
  requests, and mapped destinations.
- **Offline scorer (JVM test):** runs the pure router against **recorded** answers. It is free and runs in CI, and it
  catches combiner and threshold regressions.
- **Live scorer (Lab):** a `lab.decisions` run, started from the PC through the gateway. It sends the golden set
  through the real boards (Luna and JEV side by side) and returns aggregates only: accuracy, calibration bins,
  times, cost. This is a gateway contract minor bump. It is read-only and never moves the phone.
- **Calibration:** reliability bins per question; each capability's bar is chosen on a tuning half and checked on
  the held-out half.

### 11.4 The releases

| Release | Stage | Work packages | Switch at ship | Gate to turn it on |
|---|---|---|---|---|
| **alpha.123** | **R1 · The wire and Luna in shadow** (L0, L1) | (a) A probe: the owner runs `scripts/dev/decisions_probe.py` with their key; it records real JEV and Luna answers for the fixtures and settles the `choices`/`criteria` question. (b) One `DecisionsWire` for modes, the Pilot and Drive's JEV watch: `criteria`, typed answers, `state` array with `image_url`, refusal. (c) `DecisionProvider.LUNA` (`openai/gpt-6-luna-decisions`, vision) replaces the frozen slot. (d) Watch: Luna answers beside JEV on every Auto request; only JEV acts. (e) Per-provider stats. (f) The golden set v1, the offline scorer and the guard file | Luna: **shadow** | JEV answers again (or the probe shows it already did); Luna's shadow answer rate ≥ 95 %, p95 known |
| **alpha.124** | **R2 · Triage** (L2) | `TriageBoard` (§5) incl. the slot plan and `destination` questions (asked, not yet acted on); `ModeRouter.fromTriage`; regex rules kept as a net with disagreement lessons; the circuit breaker; the `lab.decisions` live scorer | `triage`: shadow → **on** at gate | Golden set: rung accuracy ≥ JEV's and ≥ 90 %, 0 risky under-routes; one week of shadow without a risky under-route |
| **alpha.125** | **R3 · Context on demand, Bind, Verify** (L3, L3b, L4) | The numbered screen (C1) and marked screenshots (C2/C3) from one numbering; abstain choices; re-ask one question one tier up; per-screen tier memory; racing C1 and C2; `BindBoard` (owner asked on a tie); `VerifyBoard` after Instant moves, with the sound on yes; the "Let decisions see the screen" switch | `sight`, `verify`: on with the gate | Wrong taps ≤ 1 % on the screen set; fewer than "always screenshot" with a lower p95; the sensitive-screen guard passes; verify agrees with the human label ≥ 95 % |
| **alpha.126** | **R4 · The capability registry** (L5) | `Capability` data; the 18 intents moved in; Android standard intents and settings panels; learned routes and compiled skills as entries; per-capability bars (provisional until R8) | `registry`: on | "Dark mode on", "Wi-Fi settings", "timer 5 min" and 40 more go Instant in the Lab; nothing irreversible in the registry (guard) |
| **alpha.127** | **R5 · The destination index** (L5c) | `DestinationIndex` built from the maps (safe moves only), aliases from titles, incoming control labels and verified phrasings; Stage 0 lookup; zero-call hits with Triage as a parallel check that can stop the walk; event-driven map walks; suspect-on-app-update; two-failures-out | `index`: on, zero-call hits **off** until the gate | Mapped requests reach the screen at app start + < 0.5 s; zero wrong destinations on the mapped set; a stale map diverges safely in a forced test |
| **alpha.128** | **R6 · Instant chains** (L5b) | Acting on the slot plan; the fused per-step board; ordinals in code; bounded find; chain guard rails (4 steps, 6 moves, 8 s, no irreversible tap, one dismiss); hand-off to Flash at the current step | `chains`: on with the gate | The 2–4 step set: ≥ 90 % complete, median ≤ 5 s, 0 irreversible taps, every hand-off continues without redoing a step |
| **alpha.129** | **R7 · Flash steps on Luna** (L6) | The Pilot's Step board on the new wire with context on demand; Flash's plan still from the fast generative model | `luna_step`: on with the gate | Flash Lab missions: completion ≥ today's, Mind hand-ups down |
| **alpha.130** | **R8 · Calibrate and switch** (L7) | Calibration from the live scorer and shadow data; final bars; `Decisions.ACTIVE = LUNA` with JEV as the fallback provider; Settings copy; Glass Brain → Decisions page; release notes | Luna **active** | Every row of §11.2 met; owner signs off on the numbers |

Every release also brings:
- its `docs/RELEASE_*.md`, stating what is **UNVERIFIED** on a real phone;
- its rows in **`docs/LUNA_DEVICE_MATRIX.md`**, the owner's on-phone checklist (written in R1, grown each release).

### 11.5 The first build session: alpha.123, in order

1. **Probe script** (`scripts/dev/decisions_probe.py`). It is standard-library Python and reads the key from an
   environment variable, never from a file it writes. It sends Board 0 three ways:
   - JEV with today's `choices`;
   - JEV with `criteria`;
   - Luna with `criteria`, plus one image.

   It saves the redacted answers as fixtures. **The owner runs it once** (this session has no OpenRouter key).
2. **`mind/decide/DecisionsWire.kt`** (pure). `DQuestion` (`Noul`, `Choice`, `Score`), `DAnswer` (with
   probabilities, or a refusal), a `DState` list of text and images, the body builder, and the exact reader. Unit
   tests: the docs' example answer, plus every failure case.
3. **Port the callers onto it:**
   - `BoxWire.decisionsBody` / `parseDecisions`;
   - `PilotWire.decisionsBody` / `parseDecisions`;
   - Drive's JEV watch in `voice/OpenRouterVoice.kt`. Its call stays separate, because voice may not import mind
     code; the pure wire goes into a shared package both may use.

   `BoxQuestion` keeps its API; choices become `criteria` with the instructions as guidance.
4. **`DecisionProvider.LUNA`**; `Decisions.ACTIVE` stays `JEV`. **Watch:** on Auto requests, Luna answers Board 0 in
   the background. Its lesson is recorded with `provider = LUNA` and is never acted on.
5. **Stats:** `DecisionStats` per provider (answer rate, p50/p95, agreement, cost).
6. **Golden set v1 + offline scorer + `test_luna_box_guard.py`**; update `test_decisions_guard.py` for the new
   provider list.
7. **Validation:**
   - `./gradlew :app:testDebugUnitTest`;
   - `python -m pytest scripts/ci/tests -q`;
   - push the code without a bump; Mobile CI green;
   - then the alpha.123 bump, `docs/RELEASE_5.0.0-alpha.123.dev1.md`, and the first device-matrix rows.

---

## 12. Owner decisions (recommended defaults are taken unless the owner says otherwise)

1. **Screenshots to Luna:** on by default, with the switch in Settings. *(Taken.)*
2. **Decide-only vs. generation:** the Mind's generative model keeps writing messages and long plans; Decisions
   decides. *(Taken.)*
3. **JEV after the switch:** kept as the fallback provider when Luna is slow or down. *(Taken.)*
4. **Strict privacy (ZDR):** an opt-in switch; its speed cost is measured in R3. *(Taken.)*
5. **Release order:** this plan takes alpha.123–130; VMOS fleet (plan 56) moves to alpha.131+. *(Taken; say so if
   VMOS should go first.)*

**What this plan can and can't promise.** It builds the practices that make software reliable at scale:
- typed contracts;
- tests on real answers;
- safety in code;
- shadow before acting;
- calibrated thresholds;
- kill switches;
- service levels checked at every gate.

What it can't do is prove reliability ahead of use. The numbers come from the Lab, shadow use and the device matrix,
and every release says honestly what a real phone has and hasn't confirmed.

## 13. Sources

- [GPT-6 Luna Decisions on OpenRouter](https://openrouter.ai/openai/gpt-6-luna-decisions)
- [OpenRouter API: submit a Decisions request](https://openrouter.ai/docs/api/api-reference/alphadecisions/submit-a-decisions-questions-and-answers-request)
- [OpenRouter: Multimodal Decisions (images in `state`)](https://openrouter.ai/docs/guides/community/multimodal-decisions)
- [OpenRouter: Jev tutorial](https://openrouter.ai/docs/guides/community/jev-tutorial)
- [OpenAI Releases Decisions API in Public Beta (Unite.AI)](https://www.unite.ai/openai-releases-decisions-api-in-public-beta-powered-by-gpt-6-luna/)
- [OpenAI Decisions API beta: when to use it (RohitAI)](https://rohitai.com/blog/openai-decisions-api-gpt-6-luna-routing-classification-guide)
- Plans [41](41-fast-mode-decisions.md) (§15, the frozen slot) and [42](42-modes-instant-flash-mind.md) (modes, baton).
