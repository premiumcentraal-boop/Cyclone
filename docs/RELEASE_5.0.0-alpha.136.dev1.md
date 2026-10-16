# Cyclone V5 Alpha 136: two decision engines that ask JEV and OpenAI Decisions at once

Developer alpha for owner testing. It builds on alpha.135 dev1 and includes it.

- **Mobile:** `5.0.0-alpha.136.dev1` (version code 290).
- **PC runtime:** gateway and MCP `5.0.0-alpha.136.dev1` (the phone-care health report passes the engine numbers).
- **Glass:** unchanged.

The first build of plan 63 ("Decisions from first principles", §6). You asked for the two most confident options,
switchable in Settings, because nothing can be live-tested.

## Settings → Speed → Decision engine

| Engine | What happens to a request the command grammar, the rules and the phone model don't settle |
|---|---|
| **Consensus** (default) | JEV and OpenAI Decisions answer the same question **at the same moment**. Cyclone acts only when **both agree on the same action**. Any disagreement goes to the smarter mode. If only one answers in time, only an easy-to-undo action goes ahead (volume, flashlight, media, Back/Home, scrolling, opening an app or the camera) |
| **Sprint** | Both answer at the same moment, and the **first** one that is sure of an easy-to-undo, risk-free action acts at once. Everything else waits for both and is decided as in Consensus |
| **Classic** | alpha.123's single decision model, with "Decided by", "Compare beside it" and the Triage switch, kept as the way back |

**What doesn't change, in every engine:**
- the command grammar and the phone's own answers come first;
- writing, money, deleting and accounts still go to the Mind, which asks you, before any model is asked;
- consequential actions never happen on a model's word.

**Why these two:**
- **Their safety is proven offline** (see Tests).
- **Their worst case is today's behaviour, or one rung up.** If a model is slow, wrong or down, the other decides
  under the one-answer rule, or the request goes to Flash.

## Sight goes to OpenAI Decisions

In Consensus and Sprint, Instant's follow-up questions that may need the screen go to **OpenAI Decisions** with the
screenshot. Today those are the camera's shutter and the call button.
- **JEV is never sent an image.** Its docs say images put it in a slower, less accurate long-context mode.
- **Sensitive screens never give one.**

## Names

The `LUNA` provider is now **OpenAI Decisions** (`openai/gpt-6-luna-decisions`). GPT-6 Luna, the chat model, is a
different thing and doesn't decide here. Your stored choice carries over. "Test LUNA" is now "Test OpenAI Decisions".

## Numbers

**Settings → Speed shows one line for the active engine:** how many it decided, how often both models answered and
agreed, and each model's median time.

**The health report carries `decisions.engine`, as counts and times only:**
- per engine: decisions, both-answered share, agreement, early acts, rungs chosen;
- per model: answer rate, p50/p95, how often it answered first.

**The engines measure both models on every request,** including answers that came too late to count. So the separate
watch call isn't needed in these engines.

## Also

- A fresh install's Speed default in code now matches what it reads (Auto).

## Tests

- **`EnsembleTest` (12):**
  - every combining rule;
  - the race with fake models:
    - Consensus waits for both;
    - Sprint returns on the first sure easy-to-undo answer;
    - a 3-second model doesn't stall a decision;
    - a broken or silent model;
  - the router asks the engine only after the rules;
  - the engine log;
  - **seeded property tests** over the 308-request golden set: 20 rounds of random, well-formed model answers, half
    of them agreeing. Over all of them:
    - Instant only when both said the same action;
    - never lower than the more careful model;
    - "not meant for Cyclone" only when both say so;
    - one model alone never acts on something that isn't easy to undo;
  - **perfect models reproduce every golden label.**
- **The property test found a rule worth fixing before release.** One model saying "not meant for Cyclone" while the
  other wanted an easy-to-undo action had let the action go ahead. It is now a disagreement, and goes up.
- Updated: `DecisionsTest` (the new name; the stored "luna" still reads back), `GoldenSetTest` (shared oracle).
- **Gateway:** `test_plan63_engine_numbers_pass_as_counts_only`.
- **Guards:** `test_luna_box_guard.py` gains the agreement rule, the easy-to-undo list (no tap, photo, selfie or call),
  the engine after the rules and the phone model, Consensus as the default, no request text in the engine log, and
  sight only to OpenAI Decisions with no screenshot of a sensitive screen.

## Limits, stated plainly

- **Physical device: UNVERIFIED.** Run the alpha.136 rows in `docs/LUNA_DEVICE_MATRIX.md`.
- **No live calls were made while building.** Model speed and accuracy on Cyclone's requests are unmeasured. The
  engines start measuring them on your first requests.
- **The board changed with the engine.** Consensus and Sprint use Triage (risk flags, difficulty, action) instead of
  Board 0. Expect a little more Flash where the two models disagree, and fewer wrong Instants, until the numbers say
  otherwise.
- **Cost:** two decision calls per undecided Auto request, about the same as alpha.123 with Compare on: a fraction of
  a cent.
