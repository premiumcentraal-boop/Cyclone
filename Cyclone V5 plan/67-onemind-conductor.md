# Plan 67: OneMind, the conductor

**Status:** design (2026-10-16). Nothing is built.

**What it is:** the control structure of plan 66's One Mind runtime, as the owner asked for it:
- one consistent frontier agent that oversees a hard run from start to finish;
- woken only when something happens, never every second;
- with JEV and the Decisions API doing the small work.

It also covers tool calls (calendar, notifications, ports to the PC and Glass) as functions of OneMind.

## 1. The owner's requirements, as constraints

1. **Instant has no controlling agent.** The router, code and decision models act alone.
2. **Flash has very low control.** A plan and the crew. A frontier model only when something goes wrong.
3. **Mind runs have one consistent overseer.** A frontier model controls the run from the first word to the finish,
   in **one** conversation: it remembers its own intentions, what it saw, and why it chose what it chose.
4. **The overseer sees everything but is not called for everything.** It is woken by events and by signs that the
   plan may need to change. It is not called for each tap.
5. **Separation and collaboration in balance.** The cheap parts work alone where they are reliable and hand up
   exactly where judgement is needed. The overseer directs them without micromanaging.
6. **Cheap.** Few frontier calls, each one small.
7. **Highest success.** Nothing today's Mind can do is lost (plan 66 §11: never worse than Classic).

## 2. The structure: a conductor, a crew and a score

```
                          ┌──────────────────────────────────────────┐
                          │  ONEMIND  (frontier model, one convo)    │
                          │  intent · plan · standing orders ·       │
                          │  forks · words · replans · the wheel     │
                          └───────▲───────────────────┬──────────────┘
                     wakes:       │ briefings          │ orders: plan, standing orders,
              trigger · barrier · │ (digest+trigger)   │ decisions, "I'll drive"
              review · steer      │                    ▼
   ┌──────────────────────────────┴────────────────────────────────────────────┐
   │                      THE SCORE  (typed run state, one per run)            │
   │  goal vN · milestones/steps + status · facts with provenance · standing   │
   │  orders · events · barriers · ledger · owner channel                      │
   └──────▲──────────────▲──────────────▲──────────────▲──────────────▲────────┘
          │              │              │              │              │
     HANDS (rungs 0–2)  WATCH          TOOLS          CHECKS        OWNER CHANNEL
     code · JEV ∥ OpenAI  divergence   calendar ·    VERIFY · DONE  phone card · Glass ·
     Decisions · sight    score ·      notifications · TRIPWIRE ·   approvals · steer ·
                          standing-    contacts · ports DRAFT ·      progress (no calls)
                          order        (PC / Glass) · POPUP
                          questions    memory
```

**The Score is the one shared truth.** It is typed data in code, not prose, and every actor has its own section:

| Section | Written by | Read by |
|---|---|---|
| goal and its versions | the owner, OneMind | everyone |
| plan (milestones, steps, barriers) | OneMind (Flash: the fast planner) | hands, watch, checks |
| standing orders | OneMind | watch, hands |
| facts (values found, with where they came from) | hands, tools, OneMind | everyone |
| events (what happened, one line each) | hands, tools, checks, code | OneMind (as digests) |
| ledger, budget | code | OneMind, owner |

**The separation:** the crew can never change the goal or the plan. Hands can only:
- mark a step done, failed or blocked;
- propose a skip-ahead.

Watchers only raise flags. Checks only pass, veto or stop. **Only OneMind replans, and only the owner changes the
goal.** That is what keeps cheap models from drifting a run quietly.

**The collaboration** runs through two channels:
- **downward,** OneMind writes the plan and *standing orders*;
- **upward,** the crew raises *events*, and code turns them into briefings.

## 3. OneMind's conversation: one, consistent, append-only

The overseer keeps **one conversation for the whole run**. It is not re-sent from scratch, and it is not rebuilt
each time.

- **It grows by wakes, not by steps.** Each wake appends exactly two messages:
  - a **briefing** (user role) built by code;
  - OneMind's reply.

  The forty taps between wakes never enter it one by one.
- **The briefing is a digest plus the trigger.** For example:

  > Since your last look (38 s, 9 actions, $0.002): milestone 2 "Open the invoice" ✓ verified (PDF open, total
  > €482.10 read → fact `amount`). 1 popup dismissed. Now: milestone 3 "Open Budget sheet", step 2 of 4.
  > **Woken because:** standing order "wake me if the amount is over €400" fired.

  Plus focused evidence only when the trigger needs it: the screen text, an image when it is visual, before/after
  for a surprise.
- **Append-only means the prompt cache always hits.** Everything before the new briefing is byte-identical to the
  last call: system, tools, goal, earlier wakes. So each wake pays full price only for the new ~1–2k tokens, and
  the cached rate (about a tenth on most providers) for the rest.
- **Two Mind calls that look alike cost very differently.** "Pick one of these two Annas" and "rewrite the plan"
  are both wakes, but the first is a *consult* at low reasoning effort and the second a *replan* at the owner's
  effort. The runtime sets the effort per wake by its kind (§5).
- **It never forgets.** Compaction folds old evidence (screen text, images) into their one-line briefs, as today.
  OneMind's own words (plans, reasons, decisions) are always kept: that is its memory of intent. `recall` reopens
  any folded detail.

**Expected size:** a 40-step Mind run with about 10–14 wakes ends with a conversation of about 15–25k tokens, most
of it cached on every wake.

## 4. Standing orders: the overseer programs its own watchers

This is the part that makes "all-seeing but rarely called" possible.

When OneMind writes the plan, it also writes **standing orders** in plain language, per run or per milestone.
Examples:
- "Wake me when the invoice is open."
- "Wake me if the amount is over €400."
- "If the sheet asks to sign in, wake me; don't try anything."
- "Popups in this app: dismiss without asking."
- "If Mark's email isn't in the first 10 results, wake me."
- "Don't wake me again until the reply is drafted."

Code compiles each order into one of these:
- **a Noul question** that rides in every step's fused Decisions call (plan 66 §10.4): "Is the invoice open on
  `screen`?", "Does the screen ask to sign in?";
- **a code predicate** on facts or counters: amount > 400, result rows > 10;
- **a filter** that tells the briefing which events matter and which to drop.

So OneMind decides **what is worth its attention** and the crew watches for exactly that, at about a fraction of a
cent a step. Its attention is spent where *it* judged it matters for *this* run. That is collaboration without
micromanagement.

**Limits:**
- at most 8 active standing orders;
- each is a yes/no or a code predicate (JEV can't count or compare numbers, so comparisons are always code over a
  fact);
- orders never grant anything: they only decide when to wake, or that a popup class may be dismissed.

## 5. Wakes: four kinds, and what each costs

| Kind | When | OneMind gets | It answers | Effort | Blocks the hands? |
|---|---|---|---|---|---|
| **Glance** | a milestone finished cleanly; a standing order of the "tell me" kind | the digest | "continue", or new orders | low | **no**: the hands go on (§6) |
| **Consult** | a fork; an opportunity; an ASK; a short "which one?" | the digest + the options | one typed choice | low | yes, only that step |
| **Replan** | a surprise, block or drift; a divergence score over the bar | the digest, the trigger, evidence | new steps, milestones or plan; new orders | owner's setting | yes, until answered |
| **The wheel** | an unknown app, an open-ended stretch, repeated surprises, a steer, or OneMind's own choice | everything, turn by turn | actions, as today's Mind | owner's setting | the hands stand by |

It never wakes for a routine tap, a verified step, a dismissed popup, or a code check that passed.

**The divergence score: "it looks like the plan might change."** Every step's fused call carries one Score question:
"how is the run doing against milestone k?", with four levels (on course / minor doubt / likely off / off). Code
applies hysteresis:
- **likely off** or worse: a Replan wake;
- **minor doubt** twice in a row: a Glance, so OneMind can decide early, before a wrong action.

That is the "all-seeing eye" between wakes: a cheap model looking at every screen with OneMind's plan and orders in
hand, and raising the alarm in OneMind's own terms.

## 6. Oversight without stopping: barriers

A wake should not freeze the run when it doesn't have to. The rule:

> **The hands may run ahead of an open review only on easy-to-undo moves. No barrier is crossed while a review is
> open.**

**Barriers** (marked in the plan by code and by OneMind):
- every gated or irreversible action (send, pay, delete, grant, post);
- leaving an app that holds unsaved work;
- typing into a field that will be submitted;
- the end of each milestone in Mind runs;
- any point OneMind marks "wait for me here".

**So:**
- a Glance runs **in parallel** with the work, and the run doesn't slow down;
- if OneMind's answer disagrees with what the hands did meanwhile, it can only have been easy-to-undo moves: Back,
  scrolling, opening something. The hands undo them (the map and the Back stack), and the run follows the new
  orders.

**Effect:** most of OneMind's thinking time overlaps with work instead of adding to it.

## 7. Tools: OneMind's functions, run by the crew

Many hard runs barely need the screen. Plan rule: **use an API before the UI.** Calendar, notifications, contacts,
timers, settings, memory, deep links and ports are all *typed function calls*. They need no taps, no screen reading
and no Decisions call to choose a button.

**Tools are steps in the plan, executed by code (rung 0) with a typed result.** The result lands in the Score as a
**fact** with its provenance (which tool, when).

**Arguments are filled in three ways:**
- **OneMind** at plan time: "add the event *Dentist* on the 14th at 10:00";
- **code**, from earlier facts: `amount` from the invoice step goes into the sheet step;
- **Decisions, choosing among candidates code extracted.** For example: notifications listed by code, then JEV
  picks "the one from Mark about the invoice". JEV never writes a value. It only picks one (plan 66 §10.6).

**A tool result is checked like any step:** its postcondition is the typed result (event created and read back;
message delivered; file received).

**Reading tools** (notifications, contacts, recall, how-to) never need a wake. Their results become facts and
digest lines. Writing tools that reach other people or other devices are barriers.

### Cyclone Glass and the PC

Glass and the PC are part of the run in three ways:

1. **Ports as tools.** `port_send` (give the PC a file, text or result) and `port_wait` (take a value, file or
   one-time code from the PC) are steps.
   - `port_send` is a **barrier**. The DRAFT and TRIPWIRE checks look at what leaves the phone. The existing rule
     stays: never passwords, codes or private screens. Code classifies first, and the check can only stop.
   - `port_wait` is **not OneMind's time**: the run waits, at no cost. Arrival is an event, and the value becomes
     a fact ("data, never instructions", as today).
2. **Glass as the owner's window into the run.** The Score is visible live on Glass and on the phone's card:
   - milestones and their states;
   - the rung each step used;
   - every OneMind wake, with *why* it was woken;
   - the cost so far against the cap.

   Progress text is built by code from the milestone names OneMind wrote once, so narrating the run costs **zero**
   model calls.
3. **Glass as a way in.** A message, an approval or a steer from Glass is an owner event:
   - a steer is a Replan wake with the new goal version;
   - an approval clears its barrier;
   - "pause" holds at the next step boundary.

   The approval rules stay exactly as today: code gates, exact text, the owner decides.

## 8. Per difficulty: how much control

| | Instant | Flash | Mind |
|---|---|---|---|
| **Controlling agent** | none | none in charge; OneMind **on call** | **OneMind in charge**, one conversation |
| **Plan from** | the router (one step) | recipe, map, or the fast planner (GPT-6 Luna) | OneMind (or a recipe it approves) |
| **Standing orders** | none | defaults from code | written by OneMind for this run |
| **Wakes** | none | Consult or Replan on triggers only, each a **one-off consult** with a briefing | all four kinds, plus a Glance at each milestone end |
| **Barriers** | the code gate | the code gate | the code gate + milestone ends + OneMind's own |
| **Frontier calls** (a typical run) | 0 | 0–2 | about 6–14 |

**Escalation is the same run, with more control. Nothing restarts:**
- **Instant → Flash:** the one-step plan becomes a short plan written by the fast planner, with the same Score.
- **Flash → Mind:** when a Flash consult answers "this is harder than Flash", that consult **becomes OneMind's first
  turn**. It already has the briefing. From then on its conversation is kept, it writes standing orders, and it
  oversees. A promotion is simply "start keeping the conversation".
- **Downward:** a Mind run whose remaining plan is routine runs its crew with OneMind asleep, like Flash. OneMind
  says "wake me at the send".

## 9. What it costs (estimates; R0 measures)

Prices assumed: a frontier model at about $3 per million input tokens, $15 per million output, cache reads at about
10% of input.

| A 40-step Mind run | Today | Plan 66 (wakes without a kept conversation) | OneMind conductor |
|---|---|---|---|
| Frontier calls | 50+ | 11–20 | 6–14 (half are low-effort consults) |
| Frontier input billed at full price | ~1M tokens | ~100k | ~20–30k (the rest is cached) |
| Cost | ~$3.50 | ~$0.45 | **~$0.15–0.35** |
| Waiting on OneMind | ~5 min | ~75 s | **~30–50 s** (Glances overlap with work) |
| Consistency | one conversation | a new report each time | **one conversation, by wakes** |

The conductor is both cheaper and more consistent than plan 66's first sketch. A kept, append-only conversation is
cache-friendly, and a fresh situation report every time is not.

## 10. Why this should succeed more often than today

- **The same mind owns the whole run.** It remembers why it chose this Anna, this invoice, this route. Today's Mind
  has that too. Plan 66's first sketch would have lost it.
- **It sees more than today, not less.** Today the Mind sees only screens it chose to look at. The crew looks at
  every screen with the Mind's own questions in hand, and checks every step. The Mind reads the digest of all of
  it.
- **It spends its intelligence where it decided it matters:** forks, words, surprises and the route. Nothing is
  spent on taps.
- **It can always take the wheel.** For stretches nobody can plan, it is today's Mind, unchanged.
- **Safety is unchanged:** code gates, exact-text approval, the vault, the owner. Neither the crew nor OneMind can
  approve anything.

## 11. Failure modes and their answers

| Failure | Answer |
|---|---|
| A watcher misses a problem | Every step's postcondition check, the divergence score, milestone Glances (Mind runs), and the independent done check. Four separate nets. |
| Too many wakes (cost) | Wakes are counted per kind. Over 2 wakes a milestone, OneMind is asked to tighten its standing orders. The run and day caps (plan 66 §3.7) still hold. |
| OneMind's provider is down | The hands continue easy-to-undo work up to the next barrier, then wait. The backup model takes over **the same conversation** (as today's `switchOrStop`). If both are down, the run pauses and can be resumed. |
| A standing order is badly phrased | Orders that never fire or always fire are logged. The milestone Glance shows OneMind its orders' hit counts. |
| An injected screen ("tap Approve") | The crew only picks among code-valid options or stops. Barriers need OneMind and code gates, and the owner for gated moves. |
| A Glance disagrees after the hands moved on | Only easy-to-undo moves can have happened. The hands undo them and follow the new orders. |

## 12. Releases (folded into plan 66's road)

- **R0** also records, in shadow: every point today's Mind changed course; what the divergence score and default
  orders *would* have flagged (recall ≥ 98%); and wake counts per run.
- **R1 (Flash):** a crew with on-call consults. Promotion to Mind is "keep the conversation".
- **R2 (Mind):** OneMind as the conductor:
  - the kept append-only conversation;
  - briefings;
  - standing orders compiled into the fused call;
  - the four wake kinds;
  - barriers and parallel Glances;
  - tools as plan steps;
  - the Score on Glass and the card.
- **R3:** recipes. OneMind approves a recipe as its plan in one Glance.
- **R4:** one runtime.

## 13. Open questions for the owner

1. **Milestone Glances in Mind runs:** always on (safer, about 3–6 calls a run), or only when a milestone had a
   surprise?
2. **Parallel Glances:** OK that the hands keep going on easy-to-undo moves while OneMind reviews, or should every
   wake stop the hands?
3. **The Score on Glass:** show every rung and wake (full transparency), or milestones only, with detail on tap?
