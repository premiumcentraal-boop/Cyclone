# Plan 74: only what only you can do

**Status:** design and build plan. Nothing in it is built yet. It builds on alpha 144 (plan 73).

The owner, after alpha 144: *"I still notice a lot of extra 'Cyclone needs you' requests which are not needed. First fall
back to Cyclone Flash and let it consider if it needs me. Only for things like sending a message, paying or a login should
it send out these cards, for actions it really cannot do."*

## 1. The idea in one paragraph

A "Needs you" card costs the owner something: they stop what they're doing, read, decide and tap. So Cyclone pays that
cost only when **code can prove** the owner is the only one who can answer. That happens when:

- the next move can't be taken back (send, pay, delete…);
- it needs a secret;
- it needs their hands (a CAPTCHA, a fingerprint);
- it needs a fact that exists only in their head.

Everything else is Cyclone's own problem: being stuck, choosing between two candidates, a pop-up, a typing failure,
where to work, "is this right?". Cyclone settles those itself in a fixed order. Code first. Then a quick Flash judgement,
as the owner suggested. Then OneMind with fresh eyes. Then a safe, reversible guess that it tells the owner about without
waiting. If none of that works, it gives an honest "couldn't finish" with a one-tap Continue. A model can never turn one
of these into a card, and it can never talk its way past a real one.

## 2. What raises a card today (alpha 144, traced)

| # | Where | What the owner sees | Kind today | Verdict |
|---|---|---|---|---|
| 1 | `AndroidMindPorts.approve`/`gated` (`OwnerRequestKind.APPROVAL`), raised by the code gates before sending, paying, deleting, installing, granting or account changes | "Cyclone wants to: Send …" | Consent | **Keep.** These are the cards that matter. Make them carry everything (§6). |
| 2 | `AndroidMindPorts` secret fill (`SECRET`), vault and Secrets Card | "Fill password for …" | Secret | **Keep.** Plus a login account choice (`AccountUse.GATE`). |
| 3 | `owner_takeover` tool, `takeover()` and `awaitControl()` (`CONTROL`) | "Do this yourself: …" | Hands | **Keep, but only for CAPTCHA, biometric, device unlock or a system sheet that code detects.** Today the model can hand over for anything. |
| 4 | `owner_fill` tool (`VALUES`) | A form card for name, birth date, address… | Personal facts | **Keep after the phone has been searched** (contacts, the owner's own card, autofill, memory). Today it can be the first move. |
| 5 | `Conductor.ask`: the mind's `ask` decision, retried once by the mind, then `owner.ask` (`QUESTION`) | Any question the model writes | Anything | **Change.** The model chooses the kind and code checks it. Most of these become SOFT (§4). |
| 6 | `owner_ask` tool (the wheel, the classic loop) | Any question | Anything | **Change.** It goes through the same gate as #5. |
| 7 | `Conductor.checkIn`: `Breaker` CheckIn (same trigger ×3, 8 wakes, the same failing step twice, Halt) | "This seems stuck… Keep going, change course, or stop?" | Stuck | **Remove as a card.** Stuck is Cyclone's problem: the ladder, then handover, then "Couldn't finish" with Continue (§5). |
| 8 | `PhoneMindToolbox.handoff`, typing failed 3× | "I wrote this but could not enter it. Paste it…" | Input | **Mostly remove.** Try the other input methods first. Only if the text is going to be *sent* does it fold into that send's consent card. |
| 9 | `MissionPlanes` `StartPlan.Ask` (fallback ASK) | "Where should Cyclone work in X?" | Place | **Remove by default.** It is asked only when the owner chose "Ask me each time". The default is already the screen. |

Smaller ones fold into the same rules:

- Instant's "more than one contact matches; ask the owner which one" goes to Flash, which then asks: this becomes #5.
- The sign-up confirmation ("Create this account now?") is consent: it is #1 and stays.

## 3. The rule

> **A card only when code can prove the owner is the only one who can answer. Otherwise Cyclone settles it, says
> what it assumed, and stays out of the way.**

Three properties make this safe at scale:

1. **Code decides the kind, not the model.** The model may *claim* a need, but code verifies it against the screen and
   the next action. A soft need can't be promoted to a card by a model; a hard one can't be dodged by a model.
2. **Only reversible things are assumed.** Anything that can't be taken back is consent by definition, and consent is
   always a card. An assumption is always easy to undo.
3. **Every assumption is visible.** It shows as one quiet line on the work card ("Picked Anna de Vries, your recent
   chat · Change"). Tapping Change steers the run through Task Kit. Nothing is decided silently.

## 4. The need kinds

New `OwnerNeed(kind, question, choices, default, why, evidence)`.

**HARD: a card.** Code must verify the kind; if it can't, the need is SOFT.

| Kind | Verified by code when… | Card |
|---|---|---|
| `CONSENT` | the next action is gated: `GatePolicy`, `Pilot.irreversible(label)`, or step risk `gated`/`irreversible` | The approval card with a preview (§6) |
| `SECRET` | a password, OTP or card field is on the screen (`StepScreen.sensitive`, `ActionSafetyPolicy`), or `vault_fill` was refused | The Secrets Card or vault, as today |
| `HANDS` | a CAPTCHA, biometric prompt, keyguard or system permission sheet is on the screen (`Surfaces.transient`, keyguard state, the CAPTCHA classifier) | The take-over card |
| `UNKNOWABLE` | R0 (§5) searched the phone and found nothing, **and** a consequential step needs the value | The `owner_fill` card |

**SOFT: never a blocking card.** These go to the resolver (§5).

| Kind | Examples |
|---|---|
| `CLARIFY` | "Which Anna?", "Which of these two products?", "Do you mean the Display page or Display size?" |
| `STUCK` | A tap keeps missing, a dialog is in the way, the breaker fired |
| `VERIFY` | "Is this the right screen?", "Shall I continue?" |
| `PREFERENCE` | Dark or light, which playlist, which of two equal routes, as long as the choice can be undone |
| `INPUT` | The text could not be typed |
| `PLACE` | Screen or background |

Folding a SOFT need into a HARD card. When a consequential act follows, the clarification rides along on its consent
card instead of becoming a second card:

> **Send "on my way" to Anna de Vries?** · [Send] [Another Anna] [Edit]

That is one card instead of two.

## 5. The resolver: Cyclone settles it itself

A SOFT need never waits for the owner. It goes down this ladder. Each rung is cheap, and most needs end at R0 or R1.

- **R0. The phone knows.**
  - Code lookups, no model: contacts ranked by recent chats and calls, frequent contacts, memory and user notes, the
    calendar, notifications, what is on the screen, the per-app move memory (plan 72), Replay.
  - For `CLARIFY`, a candidate with a clear lead wins: the only one with a recent chat, or the one used 5× more often.
- **R1. Get unstuck.** For `STUCK`: plan 73's recovery ladder (close what's in the way, Back, reopen).
- **R2. The Flash judgement** (the owner's proposal). One fast decision call, raced across both decision models like
  routing (plan 63, with the folded reversed phrasing of plan 72), with typed questions:
  - `settle`: can Cyclone settle this itself? (`yes`/`no`/`unsure`)
  - `pick`: which option (the choices, reversed in the folded copy)
  - `confidence`
  - `reversible`: can this be undone?
  - `owner_only`: does only the owner know this? (`yes` needs code's `UNKNOWABLE` check to agree)
  - The answer stands only when both models agree, both phrasings agree and the confidence clears the bar. That is the
    same consensus that already decides routes.
  - This is the "fall back to Flash and let it consider if it needs me". It costs one call, and only where a card would
    have been shown.
- **R3. Fresh eyes.** In a Flash run, hand over once to OneMind (`Conductor.handover` already exists). Pass the need as
  its brief: "You were about to ask the owner '…'. Settle it yourself; pick the safest reversible option if you must."
  The ASK_HINT retry becomes this, done properly.
- **R4. Assume and tell.**
  - Take R2's pick, or the safest reversible default when R2 was unsure.
  - Continue, and post a `NOTICE`: a non-blocking line on the work card and island with **Change**.
  - Change opens the choices. The answer reaches the run as a steer (Task Kit), and the run corrects course.
  - An assumption is never made for a gated act; that is consent (§4).
- **R5. Finish honestly.**
  - When nothing is reversible, or the run is stuck after R1–R3, the run ends as **Couldn't finish**.
  - It says where it stopped and why, with one tap on **Continue** (resume, which exists today).
  - This is a result, like "Done", not a question waiting on the owner.

`STUCK` never reaches the owner as "keep going, change course or stop?":

- Flash goes to R1, then R3, and R5 if needed.
- OneMind goes to R1, then a change of approach (rewritten orders or the wheel), and R5 if needed.
- `SPIRAL_WAKES` and `MAX_ACTIONS_WITHOUT_PROGRESS` still end runs that go round in circles, as an R5 result.

## 6. The cards that stay, made better

- **Consent:**
  - What, to whom, exactly: the message text, the amount, the item, the recipient.
  - One tap to approve. Change and Edit steer the run.
  - Folded clarifications (§4).
  - The gates stay where they are: code, `GatePolicy`, the Lab's test-only list.
  - The fleet layer and Glass show the ask and never answer it (AGENTS.md).
- **Secret:** as today: the Secrets Card and vault, never through the model.
- **Hands:** "Solve this check, then tap I'm done". Shown only when code saw the CAPTCHA, biometric prompt, lock or
  system sheet.
- **Unknowable:** the `owner_fill` card, prefilled with whatever R0 found, with **Remember** on by default for
  non-sensitive fields so it is asked once.
- **One card at a time:** a new hard need replaces the run's previous card (`OwnerInbox` already does this).

## 7. How it is built

### Code

- **`mind/needs/OwnerNeed.kt`:** the kinds and the need.
- **`mind/needs/NeedGate.kt`:** pure; one table test covers it.
  - It takes the claim and the facts: the screen, the next step and its risk, `GatePolicy`, R0's results, the
    difficulty, and the owner's "Ask me each time" settings.
  - It returns `Card(kind) | Resolve(kinds) | Assume(choice) | Finish(reason)`.
  - Unknown or unverifiable claims become SOFT.
- **`mind/needs/Resolver.kt`:**
  - R0 lookups through the existing toolbox ports (contacts, chats, memory, calendar).
  - R1 is `Recovery`.
  - R2 is `NeedBoard`, a `FusedBoard`-style request through `Decisions.engine`.
  - R3 is a hook into `Conductor.handover`.
- **`OwnerRequestKind.NOTICE`:** non-blocking. `OwnerInbox` keeps the run's latest notice and never waits on it. The work
  card shows it as one line with Change. `TaskCommands.Steer` answers it.
- **Call sites:**
  - `Conductor.ask` and `owner_ask` go through `NeedGate`, and the model's reply line gains `"need":"<kind>"` and
    `"default":"<choice>"` (`PlanLines`, `OneMindPrompt.FORMAT`).
  - `Conductor.checkIn` goes to `Resolver` for `STUCK`, then R5.
  - The `owner_takeover` tool needs a HANDS proof.
  - The `owner_fill` tool runs R0 first.
  - Typing handoff tries the other input methods: set_text, paste, the IME commit, focus and type. If the text is a
    message to send, the "paste it" card becomes the send's consent card.
  - `StartPlan.Ask` only with the explicit setting.
- **Prompts:**
  - `OneMindPrompt.RULES` gets: "Ask only for consent, secrets, the owner's hands or a fact only they know; for anything
    else pick the safest reversible option and say what you assumed."
  - The `owner_ask` schema says the same.

### Privacy and safety (unchanged invariants)

- Gates stay in code. A NOTICE is never an approval. No new route approves anything. Task Kit carries every answer.
- R0 never sends contacts or memory to a model. Code ranks them; R2 sees only labels already on the screen or the
  candidate names in the question.
- Assumptions are never made on a sensitive screen, never fill a secret and never touch a system sheet (plan 73).

## 8. How we know it works

- **Every card is logged** with its kind, who claimed it, how code verified it and which resolver rungs ran. Logged in
  the run diagnostics, without content beyond what the card already shows.
- **Lab metric: cards per 100 requests, by kind.** Target:
  - only `CONSENT`, `SECRET` and `HANDS`;
  - `UNKNOWABLE` under 1 per 100;
  - SOFT kinds at 0.
- **Lab metric: the assumption correction rate.** How often the owner taps Change. Above about 10% for an app means R0
  or R2 is ranking badly there.
- **New Lab missions with an expected card kind:**
  - "message Anna" with two Annas, one with a recent chat;
  - a dialog over Maps;
  - a missed tab on a playing feed;
  - "pay the electricity bill" (consent expected);
  - a sign-in (secret expected);
  - a CAPTCHA page (hands expected).
- **Field test protocol:** the field test 143 set again, counting cards. Field test 143 saw 2 needless cards in about 10
  minutes; the target is 0.

## 9. Build order

| Checkpoint | Release | What | Guard |
|---|---|---|---|
| C1: measure and remove the obvious | alpha 145 | `OwnerNeed`/`NeedGate` (pure, table-tested); every card logged with its kind; `StartPlan.Ask` only with the setting; `STUCK` never a card (R1, then the R3 handover, then R5 Couldn't finish with Continue); `owner_takeover` needs a HANDS proof; typing tries the other methods | `test_owner_need_guard.py`: no `owner.ask` outside `NeedGate`; `checkIn` raises no card |
| C2: the Flash judgement | alpha 146 | R0 lookups for `CLARIFY` and `UNKNOWABLE`; R2 `NeedBoard` (consensus, both phrasings); `Conductor.ask` and `owner_ask` through the gate; the `"need"` and `"default"` reply lines | `NeedGateTest` covers every row of §2 |
| C3: assume and tell | alpha 147 | `NOTICE` cards with Change (steer through Task Kit); clarifications folded into consent cards; `owner_fill` prefilled from R0 with Remember | The overlay shows a NOTICE as a line, never as the Needs-you card |
| C4: prove it | after C3 | The Lab card audit and the new missions (§8); a field test on the Pixel | Lab report: cards per 100 requests by kind |

Each checkpoint is one release on the fast lane: tests, guards, Mobile CI, and the owner's "merge and publish".

## 10. Risks and answers

- **A wrong assumption:** only reversible things are assumed, each one says so with Change, and the correction rate
  is measured. Corrections teach the per-app memory and the contact ranking, so the same mistake isn't made twice.
- **Cost:** R2 is one decision call, and only where a card would have been shown. R3 is the existing single
  handover.
- **Hiding a real problem:** R5 ends honestly, with where it stopped and Continue. A run never pretends it finished.
- **A model gaming the kind:** code verifies HARD kinds from the screen and the gates, so a model can't create a card
  and can't skip consent.

## 11. Questions for the owner

1. Should a NOTICE also buzz or speak in Live voice, or stay a silent line?
2. "Couldn't finish": should it notify, like a finished task, or only show on the card?
3. For messages: approve every send (today's rule), or allow "send to my recent chats without asking" as a setting?
   The default stays: always ask.
