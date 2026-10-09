# 58 · The Luna Decision Box: every request triaged in one fast call, Instant in under a second

Status: **Plan (2026-10-09), nothing built.** Written at alpha.122 dev1. Builds on plan 41 (Fast mode and decisions)
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

## 11. The runs

| Run | What | Done when |
|---|---|---|
| **L0 · Fix the wire** | One real call to `/api/alpha/decisions` with today's body, then with `criteria`. Rewrite `BoxWire.decisionsBody`, `PilotWire.decisionsBody` and both readers to the documented shape (`criteria`; `answers.<name>.choice/confidence/probabilities`, `noul`, `score`, `refusal`). Tests built from the docs' example answer | JEV answers Board 0 again (or we learn it already did), `DecisionsTest` covers the documented answer |
| **L1 · Luna live** | `DecisionProvider.LUNA` (rename the frozen `OPENAI_DECISIONS`): model `openai/gpt-6-luna-decisions`, same endpoint, `vision = true`, `live = true`. `state` as an array with optional `image_url` items. Watch mode compares Luna and JEV | Shadow numbers in Glass; `test_decisions_guard.py` updated |
| **L2 · Triage** | The Triage board (§5) and `fromTriage`; Board 0 retired behind a flag; regex rules kept as a net | Golden set: rung accuracy ≥ JEV's, no risky request below Flash |
| **L3 · Bind + sight** | The Bind board with marked screenshots; ambiguity asks the owner; Instant's shutter / call-button boxes use it | "Tap the blue one", "take a selfie" on icon-only cameras work in the Lab |
| **L4 · Verify** | The Verify board after Instant moves and at the end of Flash; silent success waits for it | Verify pass rate shown; a failed verify hands up with the baton |
| **L5 · Capability registry** | `Capability` data, Android standard intents and settings panels, learned routes and compiled skills as entries, per-capability bars | "Turn on dark mode", "open Wi-Fi settings", "set a timer for 5 minutes" go Instant |
| **L6 · Flash on Luna** | The Pilot's Step board on Luna with images; Flash's plan still written by the fast generative model | Flash runs in the Lab complete with fewer Mind hand-ups |
| **L7 · Calibrate and switch** | Golden set, calibration curves, bars from the Lab; set `ACTIVE = LUNA`; Settings copy and release notes | Owner signs off on the numbers |

Each run follows the release rhythm (code first, Mobile CI green, then the bump). Physical device checks per run go
into a short owner checklist; until then each run is **UNVERIFIED**.

---

## 12. Owner decisions

1. **Screenshots to Luna:** on by default with the switch in Settings (recommended), or off by default?
2. **Decide-only vs. generation:** keep the Mind's generative model for writing messages and long plans
   (recommended; Decisions can't write), or try to push Flash planning onto decisions too (choosing from a menu of
   learned plans)?
3. **JEV after the switch:** keep it as the fallback when Luna is slow or down (recommended), or retire it?
4. **Strict privacy (ZDR):** worth a slower answer?

## 13. Sources

- [GPT-6 Luna Decisions on OpenRouter](https://openrouter.ai/openai/gpt-6-luna-decisions)
- [OpenRouter API: submit a Decisions request](https://openrouter.ai/docs/api/api-reference/alphadecisions/submit-a-decisions-questions-and-answers-request)
- [OpenRouter: Multimodal Decisions (images in `state`)](https://openrouter.ai/docs/guides/community/multimodal-decisions)
- [OpenRouter: Jev tutorial](https://openrouter.ai/docs/guides/community/jev-tutorial)
- [OpenAI Releases Decisions API in Public Beta (Unite.AI)](https://www.unite.ai/openai-releases-decisions-api-in-public-beta-powered-by-gpt-6-luna/)
- [OpenAI Decisions API beta: when to use it (RohitAI)](https://rohitai.com/blog/openai-decisions-api-gpt-6-luna-routing-classification-guide)
- Plans [41](41-fast-mode-decisions.md) (§15, the frozen slot) and [42](42-modes-instant-flash-mind.md) (modes, baton).
