# Cyclone Alpha 145: measure and harden

Developer alpha for owner testing. It is built on Alpha 144 and installs over it as a normal update.

## Versions

- **Mobile:** `5.0.0-alpha.145.dev1` (version code 299).
- **Cyclone for Windows, gateway and MCP:** `5.0.0-alpha.145.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.68`.

## Why this release

This is the first release of plan 75 ("every tier, one spine"). Before Cyclone gets faster, it has to know where its
time goes. Field test 143 measured whole runs: Flash took a median of 76 s. Nothing measured the parts of a run, so
nobody could say how much time went to reading the screen, the decision models, the mind or the app itself.

This release measures every request. It also fixes the faults that ended runs for reasons that had nothing to do with
the task.

## What changed

**Every request now records where its time and money went:**
- `ask.status` has a `record` with:
  - the time of each stage (read the screen, route, Instant's move, the mind's thinking);
  - every model call by the rung that made it (router boards, step, done, plan, consult…), with its median and slowest
    time and its cost;
  - how the crew's moves were decided: by code or by the decision models, and how often the two models disagreed,
    doubted or didn't answer;
  - the mind's wakes by kind, recipe use and questions to you.
- A request that went up to Flash or Mind keeps its own part and adds the mission's when the mission ends.
- The same record is in the mission's metrics, for the Lab.
- It holds numbers under fixed names only: never your request, screen text, a typed value or a model's answer. The PC
  checks this and rejects anything else.

**A run no longer ends because one model had a bad minute (OneMind, Flash and Mind):**
- A failed or slow answer is asked again once.
- After that, a rate limit, or a model that refuses the request, hands the run to your backup model, which keeps it to
  the end.
- With no backup left, a rate limit is waited out: its own wait time, at most 5 s a time, twice.
- Before, two failures on the selected model ended the run, even with a backup set.

**Your working time applies to every run:**
- OneMind runs (all Flash and Mind requests) now stop at your working-time setting. Before, only the old classic loop
  used it.
- Time spent waiting for you (a question, a check-in, a pause) doesn't count.
- A run that runs out of time stops honestly, as "gave up", with what it got done.

**Removed:**
- The Pilot (plan 41's Fast mode), which plan 69 had already switched off, and its `pilot` tool and prompts.
- What is left is the sureness setting, and the code's own rules for which presses can't be undone and which apps
  Cyclone stays out of.
- The unused vision shadow. Had it ever been switched on, it would have sent screenshots to JEV, a text-only model,
  outside zero-retention routing.

**The decisions Lab tests both models on the same requests:**
- Settings → Speed → Test both runs JEV and OpenAI Decisions on the same 300 labelled requests.
- It reports how often they agree, how often they're right when they agree, how often both send a risky request too
  low (this must be 0), and who is right when they split.
- The step board relies on these numbers when it asks both models.
- The report reaches Glass and the health read, as the single-model reports do.

**Lab checks by meaning:**
- A new `page` check reads the screen the same way in every language:
  - `ids`: view names such as `profile_tab`;
  - `selected`: the selected tab, or a switch that is on;
  - `activity`: the activity, as a regex.
- So "mijn profiel" and "my profile" pass the same check.
- The testing skill now prefers `page`, `foreground` and `setting` over checks on screen words.

## What did not change

- Approvals, the gates on sending, paying, deleting and permissions, and the secret rules.
- The Lab's test-only list, and the fleet's never-approve rule.
- Task Kit for every stop.
- How moves are decided. Speed changes come in Alpha 146, once these numbers are in.
- The overlay and Glass.

## A correction to plan 75

Plan 75 listed the compaction model id (`openai/gpt-6-luna`) as missing. It is not: OpenRouter lists that model.
Nothing was changed.

## Not in this release (plan 75 order)

- **Alpha 146:**
  - stopping the decision race early;
  - the follow-up question that settles a split;
  - one screen read per move;
  - observing changes rather than whole screens;
  - routing that acts and judges at once.
- **Alpha 147:** the first officer (a fast Flash model), the goal contract, and a review at barriers in Flash.
- **Alpha 148–150:** one proof for every tier, recipes by meaning, plan 74's cards, the request queue, and the
  captain's background check.

## Validation

- **Android:** `:app:testDebugUnitTest` passed. New tests:
  - Conductor fail-over and working time (`ConductorFailoverTest`);
  - the run record and its join with the mission (`RunRecordTest`);
  - the crew's move counts;
  - the both-models Lab report;
  - the Pilot's remaining safety rules (`PilotSafetyTest`).
- **PC:** the gateway tests passed, with new tests for the ask run record contract (numbers only) and the Lab page
  check. The MCP, PC web-only, Ports SDK and testbench tests passed.
- **Guards:** `scripts/ci/tests` passed, with the new `test_run_record_guard.py` and the rewritten
  `test_pilot_guard.py`. `release_versions.py --check` is coherent.

## What is UNVERIFIED on a real phone

- Real numbers in `ask.status` → `record` for an Instant request and for a Flash request.
- A run moving to the backup model when the selected one is rate-limited or refuses.
- Test both on the Pixel with both providers.
- A `page` check on Instagram's profile tab, in English and in Dutch.
