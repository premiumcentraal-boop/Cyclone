# Plan 68: The Chronicler

**Status:** design (2026-10-16). Nothing is built.

**What it is:** a background writer, hard-coded to GPT-6 Luna, that keeps a compact, always-current history of a
run. OneMind (plan 67), the Flash mind, Glass, handovers and resumes then never wait for a summary, and the
frontier model reads as few tokens as possible.

**Asked by the owner:** "a smarter compacted session history for the OneMind overseer … compact this so fast that
the session info is ready for the OneMind overseeing model at any time."

## 1. What happens today

- `MindConversation.compact` (`mind/MindConversation.kt:85-108`) runs only when the conversation passes **100k
  characters**.
  - It shrinks old tool results to their one-line brief, down to 60%, and keeps the last 8 messages whole.
  - It is code, not a model. The briefs are mechanical ("tapped e14 · screen changed").
- `MissionWorkspace.fold()` folds closed app visits. It is off by default.
- **Compaction happens in the call path.** The turn that crosses the ceiling pays for it.
- **Compaction changes the prompt's prefix,** so the next call misses the provider's cache.

**Nothing summarises *meaning*:** what was learned, what was decided and why, what is still open. The Mind
re-derives that from raw history on every turn.

## 2. The requirements

1. **Ready at any moment.** When a wake fires, the briefing must already exist: zero added latency.
2. **Smart, not just short.** It keeps:
   - what matters (decisions and their reasons, facts found, open questions, what failed and why);
   - and drops the noise (taps, scrolls, dismissed popups).
3. **Never wrong about facts.** A summary must never change a number, a name or an amount. Summaries describe;
   facts are copied.
4. **Token-efficient for the frontier model,** and friendly to its prompt cache.
5. **Never a point of failure.** If Luna is slow or down, the run doesn't notice.
6. **Private.** Secrets never reach the Chronicler, and Strict privacy is respected.

## 3. The design: a watermark and a tail

The core idea: **the briefing is always the Chronicler's latest summary plus the raw events since that summary was
written.**

```
events (typed, from the crew, code, tools) ──► Event log (append-only, in the Score)
                                                   │
                         ┌─────────────────────────┴───────────────────────────┐
                         │ CODE: renders every event as one line, instantly     │
                         │ (the floor, always available)                        │
                         └─────────────────────────┬───────────────────────────┘
                                                   │  every 2–4 events, or at a milestone end,
                                                   ▼  in the background
                         ┌──────────────────────────────────────────────────────┐
                         │ CHRONICLER (GPT-6 Luna, hard-coded)                   │
                         │ in: last summary + new events (a small delta)         │
                         │ out: updated summary ─► watermark = last event folded │
                         └──────────────────────────────────────────────────────┘

briefing at any moment = latest summary (up to the watermark) + code lines for events after it
```

- **The watermark** is the id of the last event the latest summary covers.
- **The tail** is every event after the watermark, rendered by code in microseconds.

So at wake time the briefing is ready:
- most of it is meaning, written by Luna;
- the last 0–3 events are exact lines;
- nothing waits on a model.

**Staleness is bounded:** the summary is at most one update behind, typically 1–3 events. Those events are in the
tail, word for word.

### 3.1 Incremental, not from scratch

Each Chronicler call gets **only**:
- the current summary (about 300–900 tokens);
- the new events since the watermark (about 100–400 tokens);
- the run's fixed header: goal, plan outline, and its own instructions, which are cached.

It returns the updated summary. **Cost:** about 1–2k input tokens per update, at Luna's price. A 40-step run makes
about 10–20 updates, a fraction of a cent in total.

**The pace adapts:**
- an update after every 2–4 events;
- an update at once on a milestone end, a fact found, a failure or a decision;
- **never more than one in flight.** If events arrive while Luna is writing, they wait for the next update and
  stay in the tail meanwhile.

### 3.2 Many resolutions: a summary per milestone, sealed

The summary has a fixed shape, not free prose:

```
GOAL v2: Pay Mark's invoice from my budget sheet and reply thanks (steer at 14:02: "use the business card")
DONE
  M1 Find Mark's invoice email ✓ — found in Gmail (2nd result; 1st was a newsletter). [facts: invoice_id, amount]
  M2 Read the invoice ✓ — PDF opened; total read twice and agreed.
NOW
  M3 Add to Budget sheet — sheet open, row "October" found; next: type amount in column C.
OPEN
  - Owner hasn't confirmed which card (asked 14:03, waiting)
LEARNED
  - Budget sheet needs a double tap to edit a cell (one tap only selects).
FAILED / AVOIDED
  - Search for "Mark invoice" returned nothing; "factuur" worked.
FACTS (copied by code, never written by Luna)
  invoice_id = INV-2026-1043 (Gmail, M1)   amount = €482.10 (PDF, M2)
```

**Sealing.** When a milestone ends verified, its line is **sealed**. Later updates never rewrite it. So:
- finished history stays stable, and the frontier model's cache keeps hitting on it;
- old detail shrinks to one line per milestone, however long the milestone was.

Only **NOW**, **OPEN** and **LEARNED** keep changing.

### 3.3 Facts are never summarised

Numbers, names, amounts, dates, ids and addresses live in the Score's **facts**, each with its source, written by
code and tools. The **FACTS** block of every briefing is copied from the Score by code. Luna's text may *refer* to
a fact, but cannot create one.

**The guard** runs on every Chronicler answer before it is accepted. Any number, amount, email address, phone
number or capitalised name in Luna's text must match:
- a fact;
- an event;
- the goal;
- or the earlier summary.

If one doesn't, the update is **rejected**. The previous summary stays, the tail grows by those events, and the
next update tries again. Two rejections in a row switch that run to the code-only digest. Hallucinated details
never reach OneMind.

### 3.4 What the frontier model reads

OneMind's own conversation stays **append-only** (plan 67 §3). Each wake appends:
1. the briefing: the Chronicler summary at the watermark + the code tail + the trigger + focused evidence;
2. OneMind's reply.

Because finished milestones are sealed, consecutive briefings share most of their text. And because each briefing
is appended rather than replacing the last, the prompt prefix stays byte-identical and cached.

**Rebasing, the only time the prefix changes.** When OneMind's conversation passes its ceiling (about 40k tokens):
- the runtime rebases it at the next **milestone boundary**, the quietest moment, where a Glance often happens
  anyway;
- old wake briefings are replaced by the sealed milestone lines;
- OneMind's own words (plans, decisions, reasons) are kept whole: they are its memory of intent;
- the rebased conversation is **pre-built in the background by the Chronicler** before the boundary arrives, so
  the swap costs no time. It costs exactly one cache miss.

So the frontier model reads:
- sealed history at about 1 line per milestone;
- the current milestone in detail;
- the last few events exact;
- all facts exact;
- and nothing else.

`recall` reopens any raw event, screen or result on demand.

## 4. Who else reads the Chronicle

The same summary serves every reader, at no extra cost:

| Reader | Gets |
|---|---|
| OneMind (Mind runs) | briefings at every wake (§3.4) |
| The Flash mind (the owner's chosen model) | the same briefing at its wakes; its own context stays tiny |
| Flash → Mind handover | the summary *is* OneMind's first briefing. Instant handover. |
| Glass and the phone card | the NOW line and sealed milestones as live progress. Zero extra calls. |
| Resume after a crash or restart | the summary + facts reload the run's meaning in one step (today: the raw journal) |
| The end of the run | the owner's report ("Done: paid €482.10 to Mark, logged in October, replied thanks") is built from the summary and facts by code |
| Recipes (plan 66 §3.8) | sealed milestone lines become the recipe's step descriptions |
| Lab and diagnostics | LEARNED and FAILED / AVOIDED are exactly what a failure review needs |

## 5. Speed and cost (estimates; R0 measures)

| | Today | With the Chronicler |
|---|---|---|
| Wait for a briefing at a wake | — (today's Mind re-reads everything) | **0 ms** (it is already written) |
| Frontier input per wake, uncached | grows with history (15–25k tokens a turn) | about 1–2k (the new briefing) |
| History in OneMind's prompt, late in a 40-step run | about 60–100k characters | about 8–15k tokens, mostly cached |
| Chronicler cost per run | — | ≈ 10–20 Luna updates ≈ **< $0.01** |
| A Luna outage | — | none visible: the code digest fills in |

## 6. Privacy and safety

- The Chronicler sees only events that **already passed `MindRedaction.scrub`**: no passwords, codes, keys, card
  numbers or IBANs. Screens with secrets never leave the phone (as today).
- With Strict privacy on, Chronicler calls set `provider.zdr = true`. If no zero-retention route is available, the
  run uses the code-only digest.
- **The Chronicler has no tools and no authority.** It writes text that only OneMind, the Flash mind and the owner
  read. It can't wake anyone, change the plan or act. A prompt injected through the screen ("ignore your goal…")
  reaches it only as quoted event text. The fact guard (§3.3) and OneMind's own judgement stand between it and any
  action.

## 7. Settings

- **Hard-coded to GPT-6 Luna** (`openai/gpt-6-luna`), as the owner asked. It is not a picker. It is shown in
  Settings → Speed as "History writer: GPT-6 Luna", with an off switch that falls back to the code-only digest.
- Its calls are counted in the run's ledger (plan 66 §3.7) under their own line.

## 8. Releases

- **R0:** the Chronicler runs in shadow beside today's Mind. It writes summaries and the fact guard checks them, but
  nothing reads them yet. It measures freshness (events behind), guard rejections, cost per run, and whether a
  Lab reviewer could answer "what has happened and what is next" from the summary alone.
- **R1:** the Flash mind's wakes and Flash → Mind handovers use the briefing. Glass shows the NOW line.
- **R2:** OneMind's briefings, sealed milestones and background rebasing.

## 9. Limits, stated plainly

- **Luna's summaries can omit something that matters.** That is why the tail is exact, facts are copied, `recall`
  reopens anything, and OneMind can always ask for the raw events. R0 measures omissions on Lab runs before
  OneMind relies on it.
- **A long milestone grows its NOW part.** At more than 12 events in one milestone, the Chronicler writes interim
  sub-lines inside NOW, which are sealed when the milestone ends.
- **One more model in the run.** It is on a separate path: its failure falls back to code, and it never blocks the
  hands or OneMind.
