# 37 — The mission workspace: structured context that makes a run feel like it understands

**Status:** plan, 2026-09-28. Four build runs (W1–W4, §12), inserted before fleet health in plan 35. Builds on the
Mind (plan 16), Hands (21), planes (25–28), direct actions (29), the App Manual (36) and parallel sessions (26 §6).
Nothing is built yet.

**The owner's ask:**
> "Is it possible to have not only one global user notes but also per app manual that has filled in blocks like people
> and content. So for the Instagram example it lists the actual close friends or often asked about chats and remembers
> short notes about them like Louella is my girlfriend, Instagram name lo.06. Then my real idea was modules or context.
> A run with multi apps gets a section of the app map navigation in its prompt, gets the global user notes, and the run
> context and goals and current page info, all very structured so it can make decisions and think inside one run chat.
> Then it has a dedicated workflow for switching apps, home screen, etc., and it keeps the total conversation intact and
> the chain of thought and goal, and swaps out the current page context. The context of every previous page gets
> compacted or scrapped so the chain of thought doesn't become millions of tokens, but it can remember where it came
> from, what reasoning it did before, where it is going and what the plan is. If needed it can change the plan, and it
> should show the mission diverted in a nice way for the user too." … "How to make the structured context so good it
> will make a Cyclone run feel like AGI."

**In one sentence:** every model call is assembled from eight owned modules in a fixed order, so the stable part is
cached and the model always sees the goal, a checkable definition of done, the people and notes that matter, one short
block per app it already visited, a live state (plan, collected facts, where it is and how to get back, surprises),
the current app's section of the map and manual, and exactly one full screen — about 25–30k tokens whether the run is
10 turns or 200.

---

## 0. Decisions (locked unless the owner changes them)

| # | Decision | Why |
|---|---|---|
| D1 | **One conversation stays the mission's memory** (plan 16). The workspace changes *what each call shows*, not who decides: the model still decides every step. | The Mind's strength is one continuous line of thought; we keep it and remove the noise around it. |
| D2 | **The harness does bookkeeping, the model does judgement.** Where the phone is, what was collected, loops, what changed on the screen, and whether an action did what it said are computed by code, never by an extra model call. | Cheaper, faster and more reliable than asking a model to summarise itself. |
| D3 | **Stable first, changing last.** Rules, brief and "your world" never change inside a mission; the journal changes only when an app stay closes; the live state, app section and screen are rebuilt every turn and sent last. | Provider prompt caching reuses the prefix; the newest facts sit where attention is strongest. |
| D4 | **People and notes are a separate, owner-owned store** (§8), never part of the App Manual. | The manual's CI-guarded promise stays: "the app's own words, never your content" (plan 36 §4). |
| D5 | **Nothing personal is learned silently.** People cards and app notes are written by the owner or proposed by the model and saved only on the owner's OK. | Consent, and no surprise data on the phone. |
| D6 | **Behind a switch, promoted by the Lab.** The workspace ships as a Lab variant (`context: workspace`) and an owner setting; it becomes the default only when it wins every suite (§11). | "Consistently better", measured, never assumed. |
| D7 | **Boundaries are unchanged.** PhoneToolExecutor is the only mutation engine; one screen-changing action per turn; approvals for pay, send, delete, permission and sign-in; no secrets in any module, journal, record or model. | The laws of every plan since 16. |

## 1. What a run does today (the gaps this closes)

From the code (`mind/MindConversation.kt`, `mind/MindLoop.kt`, `mind/MindPrompt.kt`, `mind/PhoneMindToolbox.kt`,
`mind/MindMemory.kt`):

| Part | Today | Gap |
|---|---|---|
| Opening message | Goal, situation, recent missions, `MindMemory.digest()` (≤200 flat facts, 3,000 chars) | No people, nothing per app, retrieval by shared words only ("my girlfriend" finds nothing unless a fact says "girlfriend") |
| Map and manual | `mapCard` shows once per app, on the **first** visit | After compaction it is gone; on a **return** to the app it is never shown again |
| Plan | `plan_update` goes to the owner's card; the model gets "Plan updated (n steps)" | The plan is never restated; it lives in an old tool call |
| Notes | `note` answers "Noted." | No ledger of collected facts; they scatter through history |
| History | Every screen stays verbatim until **150,000 chars** (`MindBudget.maxContextChars`), then old results shrink to one line | Cost grows with the square of the turns; the previous app's screens are noise for dozens of turns |
| App switch | No boundary (`rebind` only after a plane move) | No "where I came from, what I got there" |
| Plan change | Silent | The owner never sees the mission divert |
| Actions | A tool succeeding means the phone accepted it | Nothing checks whether the result matched what the model meant |

## 2. The module stack

Every request is `modules 1–4` (the stored conversation, compacted) followed by `modules 5–8` (rebuilt each turn,
never stored in the conversation, journaled only as the last snapshot for resume).

| # | Module | Contents | Budget | Refreshed | Source |
|---|---|---|---|---|---|
| 1 | **Rules** | Identity, boundaries, how a turn works, the workspace legend | ≤10k chars | Never in a mission | `MindPrompt.system` |
| 2 | **Brief** | The owner's goal verbatim; the definition of done (§5); resolved people and places; constraints; deadline | ≤6k chars | Only when the owner adds something mid-run (appended, not rewritten) | Goal + `mission_define` + resolver (§8) |
| 3 | **Your world** | People in this mission; relevant facts (`MindMemory.search` + owner notes); app notes for apps in the plan; 1–3 recipes of similar past missions (W4) | ≤8k chars | At start; when a new app enters the plan (appended) | `OwnerNotes`, `MindMemory`, `MissionRecipes` |
| 4 | **Journal** | One block per closed app stay (§3); the model's own reasoning lines and one-line tool calls of closed stays | ~600 chars per stay | Appended when a stay closes | `MissionWorkspace` |
| 5 | **This app stay** | The last 6 turns of the current stay in full; older turns of this stay as one-liners | ≤32k chars | Every turn | Conversation |
| 6 | **Live state** | Plan with the current step; collected facts with source; open questions; surprises; where + the way back; time left | ≤6k chars | Every turn, sent after module 5 | `MissionWorkspace` |
| 7 | **App section** | The map near the current screen; manual abilities that fit the current plan step; owner notes for this app; learned traps (W4) | ≤8k chars | On arrival in an app and when the plan step changes | `MindMaps`, manual, `OwnerNotes`, `AppTraps` |
| 8 | **Screen** | The current screen (refs, field values), "what changed since the last screen", the expectation check (§4); one screenshot | ≤16k chars + 1 image | Every turn | `MindScreen`, `ScreenDelta`, `ExpectCheck` |

**Total target:** ≤100k chars (≈25–30k tokens) at any turn. `MindBudget.maxContextChars` stays as the hard ceiling
for classic runs; workspace runs get `MindBudget.workspaceChars = 100_000` and compact per module, not globally.

**Caching:** within one app stay the wire of turn *n* is a byte-exact prefix of turn *n+1* up to module 5
(CI-tested, §10). For providers that need explicit breakpoints (Anthropic models through OpenRouter), `MindModel`
adds `cache_control` after module 3 and after module 4. Diagnostics record cached vs uncached input tokens per turn.

**Legend in the rules** (module 1), so the model reads the workspace the same way every time:

```
## Your workspace (rebuilt every turn, always in this order at the end)
LIVE STATE — plan, collected facts, where you are, surprises. Trust it over older messages.
APP SECTION — how this app is laid out and what it can do, near where you are.
SCREEN — the only full screen. "Changed:" says what is new. "Check:" says whether your last expect held.
Older screens are gone on purpose; what you learned is in COLLECTED and JOURNAL.
```

## 3. App stays, the journal and `switch_app`

**An app stay** is the time the mission spends in one app (the launcher counts as "Home"; Cyclone's own overlay and
the Secrets Card never open a stay). It opens when an observation shows a new package and closes when the next one
does, when `switch_app`/`home` runs, or when the plane moves (plan 25 `rebind`).

**Closing a stay** (in `MissionWorkspace.closeStay`, no model call):
1. The stay's tool results become their one-line briefs; its screenshots are removed; provider `reasoningDetails` of
   its assistant turns are dropped (they are opaque and large; they stay only for the open stay).
2. The model's own visible text of each turn stays, trimmed to 300 chars per turn.
3. A **stay block** is appended to the conversation as a HARNESS message:

```
JOURNAL · stay 2 · Maps (turns 9–15)
Did: searched "Kerkstraat 12", chose bike.
Got: ETA = 22 min (collected)
Left: route screen (s4). Back: Recents, or open Maps.
Surprise: the Directions sheet opened collapsed once.
Carry: reply to Louella with the ETA.
```

`Did` comes from the stay's successful screen-changing calls (tool + target text), `Got` from ledger entries added
in the stay, `Left` from the last screen and the map locator, `Surprise` from failed checks (§4), and `Carry` from the
model's `carry` argument when it used `switch_app` (otherwise the current plan step).

**`switch_app(app, why, carry)`**, a new phone tool:
1. Validates `app` against installed apps (`MindDevicePort.apps()`), else an error listing close matches.
2. Closes the current stay with `carry`.
3. Opens the app by intent through PhoneToolExecutor (the existing `open_app` path, planes included), waits for
   Fast Path settle, and verifies the package.
4. Opens a new stay; module 7 is rebuilt for the new app (map, manual, notes), **also on a return** to an app visited
   before (the first-visit rule of `mapCard` no longer applies in workspace runs).
5. Returns the new screen.

`home(why)` and `back_to(stay)` use the same close/open path. `back_to(2)` uses Recents when the app's task is still
alive, otherwise opens the app and hands the model the stay's `Left` screen so it can `go_to` there.

`open_app` stays available (classic runs, and a model that uses it anyway); in workspace runs it also closes and opens
stays, with `carry` taken from the current plan step.

## 4. Every turn: expect, act, check

**The turn contract.** Every screen-changing tool gains two optional arguments, prompted in the rules:
- `step`: the plan step number this action serves;
- `expect`: what should be true after it, in plain words ("chat with lo.06 opens", "the toggle is on").

**The check** (`mind/workspace/ExpectCheck.kt`, pure and tested) compares the new observation with `expect` and the
previous one:

| Result | When | Shown in module 8 |
|---|---|---|
| `✓` held | The expected app is in front and the expected words or state are present | `Check: ✓ chat with lo.06 is open` |
| `✗` missed | Another app, or a named thing is absent while something else is present | `Check: ✗ expected the lo.06 chat, got a profile page "lo.06_official"` |
| `~` unchanged | The screen fingerprint did not change (Fast Path settle already ran) | `Check: ~ nothing changed; the tap may not have landed` |
| `?` can't tell | The expectation names nothing checkable | nothing (no noise) |

It matches: app names (installed labels), quoted words and handles (exact), states ("on/off/checked/selected" against
the element's state), and "opens/shows X" against visible text and the map locator. It never calls a model.

**What follows from a check:**
- `✗` and `~` add a **surprise** to the live state (`S2: turn 14, expected chat, got profile`).
- Two surprises on the same step give a harness note: "Step 3 has surprised you twice. Change the approach (another
  route, abilities_find, or ask the owner)." This replaces nothing in the existing loop breaker (A41-3); it fires
  earlier and names the step.
- Lab metric: **expect hit rate** = `✓ / (✓ + ✗ + ~)` per mission and suite (§11).

**"What changed"** (`mind/workspace/ScreenDelta.kt`): the difference between the previous and the new element trees,
at most 6 lines: new dialogs and sheets, fields whose value changed, controls that appeared or disappeared, the title.
It uses the same fingerprints as the Fast Path settle.

## 5. The definition of done

**`mission_define(done, people?, constraints?)`**, a new tool the rules ask the model to call before its first
screen-changing action:
- `done`: 1–5 checks, each `{kind, value}` with `kind` in `app_shows` (words visible in an app), `sent_to` (a message
  to a recipient in an app), `setting` (a named setting is on/off/a value), `answer` (an answer the owner asked for),
  `other` (plain words, verified by evidence);
- `people`: the names the goal refers to, resolved (§8);
- `constraints`: plain words ("don't post publicly", "under €20").

The harness stores it in module 2 and shows it on the task card ("Done when: …"). The owner can correct it with the
existing steer path (Task Kit `Reply`), which appends to the brief.

**Finishing:** `task_finish` is checked against it. `app_shows` and `setting` are checked on the final screen;
`sent_to` against the approved `MindSend` of this mission (recipient and app); `answer` against the summary. A check
that fails refuses the finish once with the reason (the existing "evidence is required" refusal pattern); a second
finish with evidence text is accepted and recorded as `unverified` in the run record. A goal the model marks as
single-step may give one `other` check.

## 6. Plan, diversions and what the owner sees

**Plan steps** gain `app` (the app a step happens in, used to prefetch module 7) and a required `why` whenever a step
that is not done is removed, reworded or replaced, or a step in a new app is added. `plan_update` without `why` in
those cases is refused with the reason.

**A diversion** (`mind/workspace/Diversion.kt`) is detected by comparing plan versions:

| Kind | Example | What happens |
|---|---|---|
| Minor | Reordered steps, an extra step in the same app | Plan card updates; nothing else |
| Diverted | A step replaced ("DM on Instagram" → "message on WhatsApp") | A **Mission diverted** line on the task card, the overlay island and Glass: ~~DM on Instagram~~ → WhatsApp — "her DMs are closed to non-followers" |
| Needs your OK | The change alters the recipient, the channel of a message, money, or public visibility, or brings in an app that sends or posts | A diversion card through the shared inbox: **Go ahead** / **Stop** (Task Kit `Approve`/`Stop`); the mission waits |

The model is told the rule in module 1 ("a diversion that changes who gets a message, how it is sent, money or what is
public waits for the owner"); the harness enforces it: the send/post approval is refused until a pending diversion is
answered.

**The owner's view:**
- **Narration:** one line per step, built from the plan step and the model's `why` (no model call): "Getting her
  address from WhatsApp…", "Checking the bike time in Maps…". It replaces the raw status text on the task card.
- **Plan card:** steps with ✓ / → / ☐, the current step highlighted, the app icon per step.
- **Journal chips:** WhatsApp → Maps → WhatsApp, tappable on the phone's mission panel and in Glass.
- **Finish:** "Sent at 18:42 · delivered ✓ · chat with lo.06", from the definition-of-done checks.

## 7. The live state in detail

```
LIVE STATE · turn 17 · 4 min 10 s used of 30
Plan:  ✓1 read Louella's address (WhatsApp)  ✓2 bike time (Maps)  →3 reply with ETA (WhatsApp)  ☐4 confirm delivered
Collected:  address = Kerkstraat 12 (WhatsApp, t7) · ETA = 22 min (Maps, t14)
Open: —
Surprises: S1 t11 Directions sheet collapsed (resolved)
Where: WhatsApp › chat "Louella ❤️" · came from Maps · Home and back: Recents
```

- **Collected** is the ledger: `note(text)` becomes `note(key?, value, source?)`; the harness stamps app and turn.
  Values pass `MindRedaction.scrubText` and the `MindMemory` secret refusal; a refused value is never stored. The
  ledger survives every compaction and is journaled for resume. At most 30 entries; the oldest resolved ones fold
  into the stay blocks.
- **Where** comes from the package, the map locator (`MindMap.locate`) and the stay trail.
- **Loops:** the same screen fingerprint plus the same action three times adds a hard line here ("You are repeating
  e12 on the same screen") and counts as a surprise.

## 8. People and notes (the owner's own store)

**Store:** `mind/people/OwnerNotes.kt`, a JSON document encrypted with AES-GCM under an Android Keystore key
(`cyclone-owner-notes`), at `Cyclone Brain/Owner notes.enc`. Schema `cyclone-owner-notes-v1`:

```
Person  { id, name, aliases[], relation?, handles{package → handle}, note (≤200), updatedAt, source: owner|proposal }
AppNote { id, package, kind: note|chat|list, title, text (≤300), items[] (list only, ≤50), updatedAt }
```

Examples: Louella · girlfriend · Instagram `lo.06`, WhatsApp "Louella ❤️"; WhatsApp chat note "Family" = the family
group; Instagram list "Close friends" with the names the owner typed or approved; Instagram note "post from
@mybrand, not my personal account".

**How entries are made (always with consent):**
1. **By hand:** Settings → AI → **People & notes** (next to the Mind memory list in `CycloneAiSettingsActivity`):
   add, edit, delete, "Forget everything".
2. **Proposed in a run:** a new tool `propose_note(person|app_note)` posts a card through the shared inbox (a new
   `OwnerRequestKind.PROPOSAL`): "Remember: Louella is your girlfriend, Instagram lo.06? **Save** / **Edit** / **No**".
   Answers go through Task Kit (`Approve`/`Decline`/`Reply`). Nothing is saved without Save.
3. **Suggested:** a name the owner's goals mention in three missions within 30 days gets a "Add Louella to People?"
   suggestion on the chat page. Names are never taken from screens for this.

**Retrieval (before the first turn):** `mind/people/Resolver.kt` matches the goal against names, aliases, relations
(girlfriend, mom, boss…, in English and Dutch) and handles. Unique matches go into module 2 ("People: Louella —
girlfriend; Instagram lo.06; WhatsApp 'Louella ❤️'"); several matches go in as candidates with the rule "ask the owner
which one"; nothing matched means nothing is added. Module 7 shows only the handle for the current app.

**The send check:** when the mission's `sent_to` check or a person card names a handle for the current app and the
chat on screen shows a different one, the send approval card says so plainly ("This chat is lo.06_official, not
lo.06") and the model is told. It never sends silently to a near match.

**Privacy rules (CI-guarded):**
- Owner notes never enter the App Manual, the dictionary, map exports, the Marketplace, recipes, Glass manual views or
  learning stores.
- Lab runs never read them (`freshMemory` covers owner notes too).
- They reach the model only through modules 2, 3 and 7 of the owner's own missions, and the PC only if the owner opens
  People & notes in Glass (read-only in W3; editing on the phone).
- The secret refusal of `MindMemory` applies to every field.

## 9. Recipes, traps and speed (the memory that compounds)

- **Mission recipes** (`mind/learn/MissionRecipes.kt`): when a mission completes with its definition of done met, the
  harness stores a recipe: goal shape (verbs and app names only), the apps in order, the key moves as app words (from
  the stays' `Did` lines, filtered through the dictionary's chrome filter so no content survives), and the surprises
  that were resolved. Retrieval by goal similarity (the `AbilityIndex` scorer); 1–3 go into module 3 as "Last time a
  mission like this worked like: …". The canary guard of plan 36 covers them.
- **App traps** (`mind/learn/AppTraps.kt`): a surprise with the same screen and the same kind in two missions becomes a
  trap line in module 7 for that app ("After typing the caption, Post moves below the keyboard; hide the keyboard
  first"). The owner can clear them per app.
- **Prefetch:** when the current plan step is done and the next step names another app, module 7 for that app is
  prepared on a background thread.
- **Known routes without a model call:** when the next step matches a manual ability with a verified path, the rules
  steer the model to `go_to(ability=…)`, which walks it with plain-code checks (alpha.64).

## 10. Build: files and tests

### Phone (`apps/mobile`)
- **New `mind/workspace/`:**
  - `MissionWorkspace.kt`: stays, ledger, plan versions, surprises, where; `closeStay`, `openStay`, `liveState()`,
    `journalBlock()`, `toJson()/fromJson()` for resume.
  - `WorkspaceAssembler.kt`: builds the wire from the conversation (modules 1–5) plus modules 6–8; per-module
    budgets; the prefix-stability invariant.
  - `ExpectCheck.kt`, `ScreenDelta.kt`, `Diversion.kt`, `DoneCheck.kt` (pure).
- **Changed:**
  - `MindConversation.kt`: stay markers on messages; `closeStay(range)` compaction; `toWire(native, tail)` appends
    the rebuilt modules as the last user message (after tool results and screenshots, as screens are today).
  - `MindLoop.kt`: `MindBudget.workspaceChars`; asks the assembler instead of `conversation.toWire` when the
    workspace is on; surprise notes; checkpoint includes the workspace.
  - `MindPrompt.kt`: the workspace legend and turn contract (only in workspace runs); `mission()` unchanged for
    classic.
  - `PhoneMindToolbox.kt`: `switch_app`, `home`, `back_to`, `mission_define`, `propose_note` (W3); `step`/`expect` on
    screen-changing tools; `note` as ledger; `plan_update` with `app` and `why`; `mapCard` per stay in workspace runs;
    `task_finish` through `DoneCheck`.
  - `MindTools.kt`: `MindToolResult.check` (the expectation result) and `stayChange`.
  - `mission/MindMissions.kt`: builds modules 2–3 at start (resolver, memory search, notes); the workspace switch
    (Lab variant or setting); narration, plan card, journal chips and diversion events to the task card, overlay and
    behind notifications (alpha.65 surfaces).
  - `mission/OwnerInbox.kt`: `OwnerRequestKind.PROPOSAL` and `DIVERSION`; `task/TaskCommands.kt` routes them.
  - `mind/lab/MindLab.kt`: variant key `context` (`classic` | `workspace`, default `classic` until promoted).
  - `ui/overlay/CycloneAiSettingsActivity.kt`: **Mission workspace** switch (W1–W2, off by default), **People & notes**
    (W3).
  - Run record (A41-3) and `runs.*` ops: stays, plan versions, checks, diversions, done checks, cached-token share.
- **Tests (JVM):** `MissionWorkspaceTest` (stays open and close on package changes; the ledger survives compaction;
  budgets hold at 200 turns; resume restores the workspace), `WorkspaceAssemblerTest` (prefix stability within a stay;
  modules in order; nothing over budget), `ExpectCheckTest`, `ScreenDeltaTest`, `DiversionTest`, `DoneCheckTest`,
  `OwnerNotesTest` (encryption round trip with a fake key; secret refusal; forget), `ResolverTest` (relations,
  aliases, ambiguity), `SendCheckTest`, `MissionRecipesTest` (canary never kept).

### Gateway (`apps/device-gateway`)
- `lab/missions.py`: suites `multiapp` (8 missions: address from Messages → Maps ETA → reply; a code from Gmail into
  Keep; a calendar event from a message; a Wi-Fi name into a note; …), `long` (5 missions of 30+ turns), `people`
  (W3: seeded cards with a canary recipient; a wrong recipient fails the mission).
- `lab/stats.py`, `lab/verdict.py`: expect hit rate, surprises caught before a wrong action, tokens and cached share
  per mission, stays, diversions, done-check pass rate, wrong-recipient count.
- `runs.*` pass the new record fields through to Glass and the agent tools.
- **Tests:** suite validation, stats maths, verdict with the new fields.

### Glass (`apps/glass`)
- Run inspector (`pages/runPage.ts`): a stays timeline (app chips with the journal block), plan versions with
  diversions struck through, the check mark per step, the definition of done with pass/fail.
- Lab page: the new metrics per arm; the `context` knob in the experiment form.
- People & notes (W3): a read-only view under Knowledge, opened on request only.
- **Tests:** vitest for the timeline, plan diff and Lab metrics; `glass_guard.py` stays green.

### Guards (`scripts/ci/tests/test_mission_workspace_guard.py`)
1. Workspace modules are built only in `WorkspaceAssembler`; nothing else appends model-visible context in workspace
   runs.
2. Every module passes `MindRedaction` before it is sent; the ledger refuses secrets.
3. Owner notes are read only by `Resolver`, `WorkspaceAssembler` and the People UI; never by manual, dictionary,
   mapping, recipes, Lab or export code.
4. `OwnerNotes` writes only encrypted bytes; no plaintext file with that name exists.
5. The diversion rule: a send, post or pay approval is refused while a DIVERSION request is open.
6. `task_finish` goes through `DoneCheck`.
7. Recipes and traps pass the chrome filter; the plan 36 canary guard covers them.
8. Proposal and diversion cards are answered only through Task Kit.

## 11. How we prove it (and when it becomes the default)

**Arms:** `context: classic` vs `context: workspace`, same build, same model, ≥30 trials per arm per suite, run by
Cyclone Lab (plan 18).

**Promotion rule** (all must hold, per model the owner uses):

| Measure | Rule |
|---|---|
| Success on `smoke`, `core`, `map`, `hands`, `planes` | Workspace ≥ classic within the Lab's noise band (no regression) |
| Success on `multiapp`, `long` | Workspace better, significant at the Lab's threshold |
| Tokens per mission | ≥40% fewer on `long` and `multiapp`; not more on `core` |
| Wrong recipient / wrong account (`people`) | 0 |
| Done-check pass rate on completed missions | ≥95% |
| Median model turn latency | Not worse than classic |

**Targets that define "feels like it understands"** (tracked every build, not gates for W1):
- first-try success on `multiapp` ≥ 90%;
- questions asked for information already in People & notes: 0;
- surprises caught before a wrong action: 100%;
- a 3-app errand done in under a minute;
- expect hit rate rising release over release.

**A blind review** each week: 20 classic and 20 workspace recordings side by side, rated "would I trust this with my
phone?".

## 12. Releases

| Run | Release | Delivers | Exit criteria |
|---|---|---|---|
| **W1** | **alpha.66: Mission workspace** | Modules 1–8 with budgets and caching; app stays, journal blocks, `switch_app`/`home`/`back_to`; the ledger, where-line and loop line; module 7 on every return; plan `app`/`why`; diversions (minor, diverted, needs your OK) with the card, narration, plan card and journal chips; the setting (off) and the Lab `context` knob; suites `multiapp` and `long`; stays in the run record and Glass inspector | All unit tests and guards; Lab: no regression on `core` with the workspace on; the first `multiapp`/`long` numbers published in the release notes |
| **W2** | **alpha.67: Expect and done** | `step`/`expect` on screen-changing tools, `ExpectCheck`, `ScreenDelta`, surprises and the step note; `mission_define` and `DoneCheck` on `task_finish`; expect hit rate and done-check metrics in the Lab and Glass | Lab: expect hit rate reported; done-check pass rate ≥95% on completed missions; still no regression |
| **W3** | **alpha.68: People and notes** | `OwnerNotes` (encrypted), People & notes on the phone, proposals and suggestions, `Resolver`, the send check, Glass read-only view; the `people` suite | `people` suite with 0 wrong recipients; privacy guards; canary absent everywhere |
| **W4** | **alpha.69: Recipes, traps, speed; promotion** | `MissionRecipes`, `AppTraps`, prefetch, `cache_control` breakpoints, cached-token metrics; the promotion run of §11 and, if it passes, the workspace becomes the default for owner missions | The §11 promotion rule holds for the owner's model and its backup |

Fleet health and alerts (plan 33 §10–11, planned as alpha.66) moves to **alpha.70**; plan 35 is renumbered.

Every release states physical acceptance as owed until the owner tests it on the Pixel.

## 13. Risks

| Risk | Guard |
|---|---|
| A closed stay loses a detail the model needs later | The ledger holds collected values with their source; the stay block says where the app was left; `back_to` returns there |
| The model ignores the live state | Same place, same headings every turn; `expect` makes the model name its intent; surprises are listed where it reads |
| Structure costs more tokens on short runs | Modules are empty when there is nothing to say; the Lab rule "not more on `core`" |
| A weaker model is confused by the format | Per-model promotion (§11); classic stays available per model |
| An outdated person card | The send check on screen; the card's age shown in module 2; editing is one tap |
| A prompt injection through a screen, a recipe or a note | Screen text stays information, never instruction (the trust rule); recipes hold app words and steps only; notes are owner-written or owner-approved |
| Caching differs per provider | The cached share is measured; the design is still cheaper without caching because the history stays small |

## 14. Not in this plan

- A second model for summaries or planning (D2: the harness does bookkeeping; the Mind decides).
- Reading the owner's messages to learn people automatically (D5).
- Changing approval boundaries, the one-mutation rule or PhoneToolExecutor.
- JEV (parked).
