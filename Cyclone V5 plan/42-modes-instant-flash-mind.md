# 42 — Cyclone Modes: Instant, Flash, Mind (smart mode routing)

**Written:** 2026-09-29, at 5.0.0-alpha.76 (the parallel Pilot). **Status:** build plan for the next major alpha;
nothing here is built yet.
**Owner's brief:**
- **Instant:** a new mode that does obvious one-step or very simple requests immediately, using decision boxes and
  Cyclone's tools, without a smart model planning first:
  - "swipe up" swipes up;
  - "click Pokemon Go" taps it;
  - "take a picture of me" opens the camera and takes it;
  - "call my mom" opens the call and continues while it's obvious.
- **Promotion:** when Instant can't go on (it needs typed input, a choice, a search), it promotes itself to Flash, and
  Flash to higher modes.
- **Smart mode routing:** modes are chosen instantly and promoted or demoted as the run needs. It replaces how
  requests are handled today.

## 0. The answer in short

Three modes, one router, one baton:

| Mode | What decides | Typical time to the first move | Plans? | Types text? | Irreversible? |
|---|---|---|---|---|---|
| **Instant** | Local grammar, then decision boxes (typed choices only) | **0.05–0.6 s** | No | No | Never (a call gets a 2 s cancel window) |
| **Flash** | A fast model writes a short plan; the rapid runner (plan 41's Pilot) walks it | ~1–2 s | Short plan | Only the plan's text | Only when planned, with approval |
| **Mind** | The smart model, one conversation (plan 16), with the Pilot for routine stretches | several s | Full plan | Yes | With approval |

- **The router** picks the lowest mode that can do the request, in one decision-box call or none at all.
- **Promotion** happens the moment a mode meets something it can't do. It carries a **baton** (goal, moves done, the
  screen now, why) so the next mode continues and never redoes a move.
- **Demotion** is the Mind handing routine stretches to the Pilot (built), and single commands during a run going to
  Instant.

## 1. What exists and is reused

| Piece | Where | Used for |
|---|---|---|
| Direct abilities: timer, alarm, calendar add and find, contacts find, notification reply | `PhoneToolExecutor` `phone.direct_*`, plan 29 layer 1 | Instant intents with no screen at all |
| Every phone move, with approvals, secret rules and settle | `PhoneToolExecutor`, the Mind's `act` | All modes' moves, unchanged |
| App names to packages; intent landings | `fastpath/InstalledAppLexicon`, `ImplicitAppRouter`, `FastPathLanding` | "open X", "take a picture" (camera intent), "call" (dial intent) |
| A zero-model command parser | `voice/VoiceIntents` (timers, alarms) | Grown into the Instant grammar |
| The rapid runner, the decision board, bumps, the parallel look-ahead, risk by code | `mind/pilot/Pilot.kt` (alpha.76) | Flash's executor; Instant's "is it done / next move" box |
| The fast decider over OpenRouter (fast model or decision endpoint) | `mind/pilot/FastMode.kt` | Every decision box |
| The One Mind, missions, the run card, the Task Kit | `mind/`, `mind/mission/`, `task/` | The Mind mode and the shared run surface |

## 2. The decision box (the one primitive)

A decision box is one fast call that answers **several typed questions at once**, each with a fixed answer set built
on the phone, plus a confidence per answer. It never writes free text.

- **Inputs:** the request, a compact screen (app, title, ≤30 labels, never secret fields or values), candidate lists
  prepared locally (on-screen labels, matching installed apps, matching contacts), and a marked screenshot only when
  the labels are weak (the privacy rules of plan 41 §8).
- **Transport:** `PilotDecider` generalised to `DecisionBox`. It uses OpenRouter's decision endpoint (JEV; OpenAI
  Decisions when available) or a fast model with a strict schema. There is one wire format and a tolerant reader, as
  built in `PilotWire`.
- **Why multi-question:** one call answers mode, intent and target together. Instant's first move needs one round
  trip, or none.

## 3. The router: which mode, in one step

The router runs on every request from the Ask bar, voice, Drive, Glass and the Command Center. It replaces the direct
`MindMissions.start` in `OverlayChromeRuntime.runAiRequest` and friends.

**Stage 0: local grammar (no model, about 5 ms).** It matches:
- gestures: "swipe up/down/left/right", "scroll up/down", "go back", "home", "recent apps";
- "open <app>", with the app found in the installed lexicon;
- "tap/click/press <label>", with a unique fuzzy match to a label on the screen;
- timers and alarms (`VoiceIntents`);
- "take a picture", "take a selfie" / "picture of me";
- "call <name>", with a unique contact match;
- flashlight, volume, and play/pause.

A unique, sure match goes straight to Instant with its arguments. Nothing leaves the phone.

**Stage 1: Board 0 (one decision box, about 0.2–0.5 s),** only when the grammar is unsure. It asks three questions:

| Question | Answers |
|---|---|
| `mode` | `instant`, `flash`, `mind` |
| `intent` | the Instant catalogue (§4), or `none` |
| `target` | the prepared candidates (on-screen labels, apps, contacts), or `none` |

**The rules** (code, over the box's answers):
- **Instant** only when `intent` ≠ none, the target (when needed) is picked with confidence ≥ the bar, and the
  request has no second clause ("and then", "after that") and no free text to write.
- **Mind** is forced when the request asks a question to be answered, composes a message, names more than one app, or
  mentions money, deleting or accounts.
- **Flash** otherwise.
- **Unsure answers route one mode up, never down.**

## 4. Instant mode

**The catalogue.** Each intent is a typed tool call with its arguments filled from candidates, never generated:

| Intent | Argument source | Tool | Follow-up moves (max 3) |
|---|---|---|---|
| swipe / scroll (up, down, left, right) | the grammar | `phone.swipe` / `phone.scroll` | — |
| back, home, recents | — | `phone.back` / `phone.home` | — |
| tap a named thing ("click Pokemon Go") | an on-screen label (unique or boxed) | `phone.click` | — |
| open app | the installed lexicon | `phone.open_app` | — |
| take a photo / selfie | camera intent (front camera extra for "of me") | `phone.launch_intent` | box: shutter control → tap |
| call someone | contacts (`direct_contacts_find`) | dial intent | box: the right number (mobile first); 2 s cancel window |
| timer, alarm | `VoiceIntents` | `phone.direct_timer` / `direct_alarm` | — |
| flashlight, volume, play/pause | the grammar | system actions | — |
| open a setting page | `PhoneSettingsPages` | `phone.open_settings` | — |

**The loop:**
1. Act.
2. Settle (Fast Path fingerprint; the plan 41 F0 ledger when built).
3. Box B:
   - **Questions:** `done` (yes/no), `next` (a screen control, or `none`), `promote` (yes/no plus a reason).
   - **Decision:** done → finish. A sure next move within the 3-move limit → act. Anything else → promote.

**Worked examples:**
- **"swipe up":** grammar → `phone.swipe(up)`. No model. About 0.1 s.
- **"click Pokemon Go":**
  - the grammar fuzzy-matches "Pokémon GO" on the home screen (unique) → `phone.click`;
  - if two labels match, Board 0 picks with the screenshot;
  - about 0.1–0.5 s.
- **"take a picture of me":**
  1. the grammar gives the selfie intent → camera intent with the front camera;
  2. Box B finds the shutter (with the screenshot, because shutters are icons) → tap;
  3. Box B confirms the photo was taken → done.
  About 1.5–2.5 s, most of it the camera opening.
- **"call my mom":**
  1. the grammar gives "call" and the name "mom" → contacts search finds "Mam" (a nickname or relation match);
  2. one sure match → dial intent;
  3. the run card shows "Calling Mam in 2 s — Cancel", then the call starts.
  Two contacts, no number, or no match → **promote to Flash** with the baton: "choose which Mom" becomes the owner's
  question there.
- **"search for pizza near me":** needs typing → Board 0 says `flash` (or Instant promotes right after opening Maps).

**What Instant never does:**
- type or search;
- compose a message;
- send, pay, delete or post: a matching label (`Pilot.irreversible`) promotes to the Mind, which asks your approval;
- act on sensitive screens or in banking, payment and authenticator apps (opening them is fine; acting inside is
  not);
- take more than 3 moves;
- run while another mission holds the screen: a command during a run is a steer or a queue item, per plan 38.

## 5. Flash mode

**How it works:**
- A fast model (the Fast mode model) writes a short plan, up to 8 steps, in the Pilot's step format, from the request
  and the baton.
- The parallel Pilot walks it.
- The smart model's side channel (look-ahead and bumps) is the Mind's model, asked briefly, exactly as built in
  alpha.76.

**It promotes to the Mind when:**
- the fast planner is unsure or can't plan;
- a bump returns `return`;
- the plan needs composition or a judgement;
- two failures happen on the same step.

**Examples:**
- **"search Instagram for lo.06":** plan open Instagram → search → type lo.06 → open the profile.
- **"turn on dark mode":** plan open Settings → Display → Dark theme toggle.

## 6. The Mind

The Mind is unchanged: the One Mind with Fast mode's Pilot, and the owner's approvals.

**It receives the baton on promotion.** Its opening message says what the lower mode already did and why it
stopped, then the screen now. It starts where the run is, never from scratch.

## 7. The baton (promotion without redoing)

`RunBaton`: goal, mode history (`instant → flash`), moves done (with their record lines), the screen now, the
reason for promotion, candidate data already found (for example the two contacts named Mom), and elapsed time.

- **One run, one card.** The run card shows a mode chip ("⚡ Instant 0.4 s" → "Flash" → "Mind") and one timeline.
- **Diagnostics** say which mode decided each move (plan 14 lesson L14).

## 8. Reliability rules (the lessons, applied)

- **Goal first, always** (plans 14 and 15): the request travels with every box. Nothing acts on the screen alone;
  Stage 0 matches the request's own words against the screen, not the screen against nothing.
- **Unsure goes up, never down.** A promotion is cheap; a wrong Instant tap is not.
- **Code decides risk:** irreversible labels, sensitive screens, kept-off apps, calls behind a cancel window.
  Approvals are unchanged.
- **One screen-changing move per decision**, through `PhoneToolExecutor`, with settle and re-observe.
- **Transport success isn't task success:** Instant's Box B confirms `done` from the screen (or the direct tool's
  result) before saying so.
- **Watch first, per intent** (plan 41 §9): each Instant intent ships behind Auto only after its Lab numbers are in;
  until then it runs Flash with Instant watching.

## 9. Settings

Settings → Model & intelligence → **Speed**. It replaces the alpha.76 "Fast mode" card.

- **Speed:**
  - **Auto** (the router; default once promoted);
  - **Always Mind** (today's behaviour);
  - **Instant only for commands** (Instant for grammar matches, everything else the Mind).
- **Instant calls:**
  - call after a 2 s cancel window (default);
  - always ask;
  - never instant.
- **The fast model / decision model, How sure before acting, Screenshots when needed and Smart model checks ahead**
  stay as in alpha.76, now shared by Flash and Instant.
- **Show the mode on the run card** (on).

## 10. The build: next major alpha

**Numbering:** alpha.76 ships the parallel Pilot. This plan is **5.0.0-alpha.77, "Cyclone Modes"**; B1 of plan 39
moves to alpha.78. Each milestone is a code-only push checked by CI; one release at the end.

| # | Milestone | Delivers | Tests and guards |
|---|---|---|---|
| **M1** | Decision box core | `mind/modes/DecisionBox.kt`: multi-question typed choices, candidates, confidences; `PilotDecider` becomes a one-question use of it; tolerant wire for both routes | `DecisionBoxTest` (wire, parsing, candidates, privacy filter) |
| **M2** | The grammar | `mind/modes/InstantGrammar.kt`: gestures, open app, tap a label (fuzzy, unique), photo/selfie, call a name, timers/alarms (`VoiceIntents`), flashlight, volume, media, setting pages; English and Dutch | `InstantGrammarTest`, with the owner's examples as fixtures |
| **M3** | The router | `mind/modes/ModeRouter.kt`: Stage 0 → Board 0 → rules; every entry (Ask, voice, Drive, Glass, Command Center) goes through it | `ModeRouterTest`; guard: no entry calls `MindMissions.start` directly |
| **M4** | Instant engine | `mind/modes/InstantRun.kt`: the catalogue as typed tool calls; act → settle → Box B; 3-move limit; the call cancel window; never type, send, pay, delete or post; sensitive screens and kept-off apps | `InstantRunTest` (fake phone): swipe, tap by name, selfie with shutter, call with one and two matches, promote on typing |
| **M5** | Flash mode | `mind/modes/FlashRun.kt`: fast planner (strict JSON plan) + the parallel Pilot + the Mind's side channel | `FlashRunTest`: plan, walk, bump, promote |
| **M6** | Baton and promotion | `RunBaton`; Instant → Flash → Mind handovers; the Mind's opening message from the baton; commands during a run route to steer or queue (plan 38) | `BatonTest`, `PromotionTest`; guard: a promotion never replays a done move |
| **M7** | Surfaces | The mode chip and one timeline on the run card, the Speed card in Settings, search keywords, the Drive voice path through the router | Compose contract tests; `test_modes_guard.py` |
| **M8** | Lab and release | An Instant suite in the Lab (the owner's examples plus 20 more); time to first move p50/p90; promotion accuracy; wrong Instant moves; alpha.77 release notes | Promotion rule (§8): Instant on by default only for intents at ≥ 98% agreement when sure |

**Order:** M1 → M2 → M3 → M4 give Instant end to end behind "Instant only for commands". M5 → M6 add Flash and the
promotion ladder. M7 → M8 finish the surfaces and the proof.

**Targets** (to be measured, not promised):

| Request | Time to the first move, p50 |
|---|---|
| Grammar matches | ≤ 0.15 s |
| Board 0 Instant | ≤ 0.8 s |
| Flash first move | ≤ 2.5 s |
| Selfie end to end | ≤ 3 s, the camera's own start dominating |
| "Call my mom" to ringing | ≤ 3 s, including the 2 s cancel window |

## 11. Risks

- **A confident wrong tap** from a fuzzy label match. Answers:
  - Stage 0 acts only on a unique match above a strict similarity;
  - otherwise Board 0 decides, with the screenshot;
  - Box B checks the result;
  - it is on the Lab's watch list.
- **Calls are consequential.** Answers: the cancel window, a setting, and never when the match is ambiguous.
- **Camera apps differ** (the shutter is an icon). Answers: a screenshot in Box B; the camera intent's own
  capture-and-return mode where available.
- **The decision API contract is unknown** (plan 41 §1). The same port works with JEV or a fast model.
- **Replacing the entry path** touches every surface. Answers: one router function with the Task Kit rules (AGENTS.md)
  guarded, and "Always Mind" as a one-tap way back.

## 12. Owner decisions

1. **Calls:** a 2 s cancel window (recommended), always ask, or never instant?
2. **"Picture of me":** front camera and the shutter tapped immediately (recommended), or a 3 s self-timer?
3. **Default Speed:** Auto after the Lab passes (recommended), with "Instant only for commands" until then?
4. **Numbering:** alpha.77 for Cyclone Modes, shifting B1 to alpha.78 (recommended)?
