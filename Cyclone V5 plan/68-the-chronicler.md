# Plan 68: The Chronicler

> **Design history.** The final plan is [plan 69](69-onemind-final-plan.md). Where this plan differs from it, plan 69 wins.

**Status:** design (2026-10-16). Nothing is built. **§10 revises the cadence:** compaction is an economic
decision computed from cache prices, Luna is called on demand (0–3 times a run), and code writes the record.

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

## 10. Revised after the owner's review: compaction is an economic decision, not a habit

The owner asked two questions:
- does compaction really save money once **cached tokens** are counted?
- with run length unknown, can **summarising all the time** end up costing more than it saves?

Working it through says **yes, it can**. §3.1's cadence (a Luna update every 2–4 events) is withdrawn. This section
replaces it.

### 10.1 The facts that decide it

**Cache prices** (OpenRouter's prompt-caching docs and published model pricing):
- a cache **read** costs about **0.1–0.5×** the normal input price, depending on the model (Anthropic 0.1×; OpenAI
  0.25–0.5×; others in between);
- a cache **write** costs from **nothing to 2×** (Anthropic 1.25× for 5 minutes, 2× for 1 hour; OpenAI's newest
  models 1.25×).

**Cache life:**
- typically about **5 minutes** without use;
- Anthropic refreshes it on every read;
- some providers don't;
- a fallback to another provider starts cold.

**Two more facts:**
- **Any compaction changes the prompt's prefix,** so the next call pays full price (often the write price) on
  everything. Compacting every step would make every call a cache miss: the most expensive way to run.
- **OpenRouter publishes `input_cache_read` / `input_cache_write` prices per model,** and every response reports how
  many input tokens were cached. Cyclone can know its own model's cache economics instead of guessing.

### 10.2 Where the tokens actually are

In OneMind's design (plan 67 §3), the frontier conversation grows **by wakes, not by steps**:
- each wake adds a code-built briefing: the events since the last wake, about 30 tokens per event line;
- plus OneMind's reply.

For a typical 40-step Mind run with about 10 wakes:
- the prefix grows from about 6k tokens (system, tools, goal, plan) to about 21k;
- with Anthropic-style caching, the whole run's frontier input costs roughly **$0.08–0.12**. Most of it is cache
  reads.

**Compacting that conversation would not pay for itself.** It is already small, and almost all of it is cached.

**Compaction only matters in three places:**
1. **Long runs:** 25+ wakes, hours of work, many milestones. The prefix passes 40–60k tokens and every wake carries it.
2. **The wheel (Mind drive):** each turn adds a whole screen (1–3k tokens). A long stretch of driving bloats quickly.
3. **Models with poor or no caching:** every token is paid at full price on every wake.

**Luna summarising every few events helps none of these by itself.** A code line per event is already compact and
exact. A summary of 4 event lines isn't meaningfully shorter, is less exact, and is mostly never read: only wakes
read briefings, and there are fewer wakes than updates.

### 10.3 The rebase rule: computed, not guessed

Compaction of OneMind's conversation, a **rebase**, is considered **only at a milestone boundary or when the cache
is cold**, never in mid-milestone.

At that moment code knows:
- `P`: the current prefix size;
- `R`: the size after a rebase (about 1 line per sealed milestone, OneMind's own decisions kept, old evidence
  folded);
- `c` and `w`: the model's cache read and write price ratios, from OpenRouter's pricing (or measured from responses'
  cached-token counts);
- `k`: the expected number of wakes still to come (§10.4).

Without a rebase, the next `k` wakes pay `k · P · c`. With a rebase, they pay `R · w + (k − 1) · R · c`.

**So a rebase pays when `k · (P − R) · c > R · (w − c)`.** Break-even is `k* = R(w − c) / ((P − R) c)`.

**Worked example,** with P = 40k and R = 10k:

| Cache kind | c | w | Break-even k* | Meaning |
|---|---|---|---|---|
| Anthropic-like | 0.1 | 1.25 | ≈ 3.8 wakes | rebase only if 4+ more wakes are likely |
| Gemini-like | 0.25 | 1.0 | ≈ 1.0 | rebase if more than one wake is likely |
| OpenAI-like (0.5 read) | 0.5 | 1.0 | ≈ 0.3 | rebase whenever another wake is likely |
| No caching | 1.0 | 1.0 | 0 | always rebase once P is large |

**The rules, in order:**
1. **The cache is cold:** more than about 5 minutes since the last call (the owner was asked, a port was waiting, a
   long crew stretch) or a provider switch. The next wake pays full price anyway, so a rebase is **free**. Rebase
   whenever `R` is meaningfully smaller than `P`.
2. **The quality ceiling:** a prefix over about 60k tokens, or 70% of the model's context. Rebase regardless of
   cost. Very long prompts slow models down and blur their attention.
3. **Otherwise the economics:** rebase only if `P ≥ 20k` (below that the absolute saving is pennies) and the
   expected `k ≥ k*`.
4. **Never in the last milestone.**

**The asymmetry favours acting when `P` is large.** A needless rebase costs at most `R · (w − c)`, about 1¢ at
R = 10k on a $3/M model. A missed one costs `(P − R) · c` on *every* remaining wake. So under real doubt, with a big
`P`, the rule leans to rebasing.

### 10.4 Estimating "how much is left": where a decision model helps, and where it doesn't

The one uncertain number is `k`, the wakes still to come. **JEV must not do this arithmetic:** counting and maths are
on its documented weak list. So:

- **Code estimates `k` from the plan** whenever there is one: remaining milestones × wakes per milestone observed so
  far in this run (1.5 until there is data), plus open forks and pending barriers. Most runs have a plan, so this
  is most of the time.
- **A decision model is asked only where the plan can't tell:**
  - the wheel (Mind drive);
  - an open-ended goal ("find…", "compare…");
  - a plan that was rewritten twice.

  It gets one **Score** question in the next fused call: "How much of `goal` remains, given `plan` and `recent`?",
  with levels *nearly done* / *a quarter left* / *about half* / *most of it* / *can't tell*. It never asks for a
  number. Code maps the level to a `k` range, and "can't tell" falls back to the asymmetric rule (rebase if
  `P ≥ 40k`).
- **Code checks itself afterwards.** Every rebase logs `P`, `R`, `k` estimated and `k` actual, and the real cost from
  the responses' cached-token counts. After 20 rebases the Lab shows whether the estimates over- or under-shoot, and
  the 1.5 prior is corrected from data.

**This answers the owner's suggestion that the decision API should make the call.** It makes **the one judgement
code can't** (how far along a fuzzy task is). Code does the money arithmetic, which is exact and needs no model.

### 10.5 What the Chronicler becomes: on demand, only when its words will be read

Luna is called **only** when a reader for its language exists, and the call pays for itself:

| When | Why it is worth it | Calls |
|---|---|---|
| A rebase is predicted at the next milestone boundary | Luna writes the folded part's LEARNED / OPEN notes (about 300 tokens). It is prepared before the boundary, so the swap waits for nothing. | 1 per rebase (0 in most runs) |
| A Flash → Mind handover | OneMind's first briefing needs the Flash run's meaning, not just its events | 1 per handover |
| A resume after a crash or restart | Reloading meaning beats re-reading a raw journal | 1 per resume |
| A recipe is compiled at the end of a verified run | Step descriptions and LEARNED notes for next time | 1 per new recipe |

**Everything else is code:**
- the briefing deltas (event lines);
- the sealed milestone lines (T3);
- the NOW line for Glass, from the plan's milestone and step names;
- the end report, from facts and milestones.

**Expected Luna calls per run: 0–3,** instead of 10–20. Most short and medium runs make **none**.

**"Ready at any time" still holds:**
- a briefing never waits, because its code part always exists;
- Luna's parts are prepared *ahead* when code predicts they'll be needed;
- if one isn't ready in time, the code version is used, and that is never wrong, only less concise.

### 10.6 The wheel (Mind drive) and the Flash mind

- **In Mind drive,** old screens fold to their one-line brief **in batches at rebases** (the existing
  `MindConversation.compact` mechanism, now triggered by §10.3 instead of a fixed 100k-character ceiling), never one
  at a time. Batches keep the cache stable between rebases.
- **The Flash mind's conversation** is tiny (2–5k tokens) and short-lived. It is **never** compacted. At a handover
  it is summarised once (§10.5).

### 10.7 What changes in the build

- **Run 3 becomes "the history economy".** It has:
  - code briefings and sealed milestone lines;
  - the rebase rule with live cache prices;
  - the `k` estimate with its Score question;
  - on-demand Luna with the fact guard and code fallback;
  - logging of every rebase's predicted and actual cost.
- **Its tests:** the break-even function against the table above; the cold-cache rule; the "never in the last
  milestone" rule; the guard; the fallback.
