# Cyclone V5 Alpha 123: the Luna Decision Box foundation

Developer alpha for owner testing. It builds on alpha.122 dev1 and includes it.

- **Mobile:** `5.0.0-alpha.123.dev1` (version code 277).
- **PC runtime:** gateway and MCP `5.0.0-alpha.123.dev1` (the phone-care health report passes the new decision numbers).
- **Glass:** `1.0.0-alpha.65` (unchanged).

Release 1 of 3 of plan 58 ("The Luna Decision Box", §11.4 "Foundation"). Nothing new acts on your phone yet:
GPT-6 Luna Decisions and the new Triage board are **watch only**. They answer beside the decision model you have
today and are measured, so the next releases can be tuned on real numbers.

## A likely bug, fixed: the decision box may have answered nothing

Every decision Cyclone asked (the router's Board 0, the Pilot's steps, Drive's JEV watch) was sent with a `choices`
list. OpenRouter's Decisions API documents `criteria` instead, which is required, and reads answers from
`answers.<name>` with a type.

**If JEV enforced that,** every Auto request the grammar didn't settle got no answer and went to Flash. That would
explain "make it louder" taking 15–70 s.

**This release sends and reads exactly the documented shape.** Run the probe below once to settle whether the old
shape was ever accepted.

## GPT-6 Luna Decisions, beside JEV

- **Settings → Speed → Decided by:** JEV (text only, the default) or GPT-6 Luna Decisions (`openai/gpt-6-luna-decisions`,
  also reads the screen). Switching is a setting, not a new build. **Keep JEV until the numbers say otherwise.**
- **Compare beside it** (on): after each Auto request is routed, the other model answers the same questions on its own
  thread. It never acts. The line under the switch shows how often it answered and agreed.
- **Strict privacy** (off): decisions only go to providers that keep no data. Answers can be slower.

## Triage, watch only

**One call asks everything about a request:**
- how much work it is (a 0–3 score);
- which single action does it, if any;
- the risk flags (sends, money, deletes, account);
- whether it writes text, needs several apps, points at the screen, or is for later;
- the steps of a short chain.

**Code combines the answers.** It can only make a request more careful: the existing rules still send writing, money,
deleting and accounts to the Mind before Triage is asked, and unsure always goes one rung up.

**Settings → Speed → Triage:** Off / **Watch only** (default) / On. Leave it on Watch only until the Lab gate passes
(plan 58 §11.4: rung accuracy ≥ JEV's and ≥ 90%, no risky request routed too low, about a week of watch data).

## Safety in front of every call

- **A breaker per decision model:** three failures in a minute, or one "too many requests", pause that model for
  2 minutes. Routing carries on with the grammar, the rules and the phone model, and anything else goes one rung up.
- **Every call is counted:** provider, board, time, how it ended, cost. Never the request, the answer or the key. The
  log is written off the routing thread, so a decision never waits for the disk.

## The decisions Lab and the golden set

- **Golden set v1:** 308 labelled requests in English, Dutch, Spanish, French, German, Italian and Portuguese, covering
  every rung, with 42 risky ones. The script that builds it is `scripts/dev/golden_build.py`; add a row for every bug.
- **Offline scorer (CI, every push):**
  - on-phone routing (grammar, local answers, rules) never puts a request below its label;
  - Triage's rules reproduce every label from perfect answers.
- **It found a real bug, now fixed:** "open insta", "open yt", "open mijn agenda", "open music", "open the bank app"
  and "open the second one" were answered "I don't see X on this phone" instead of being done.
- **Settings → Speed → Test JEV / Test LUNA:**
  - sends the golden set through that model's Triage board, four at a time, on a fixed made-up phone;
  - nothing on your phone is read or moved;
  - about a minute and a few cents;
  - the report shows the score, risky requests routed too low, p50/p95, failures, cost and calibration (when it says
    90% sure, is it right 90% of the time?).

  It reaches the PC with the health report.
- **Not in this release:** the plan's `lab.decisions` command from the PC. The Lab runs from the phone's Settings, so
  the gateway contract is unchanged.

## The probe (you run it once)

```bash
OPENROUTER_API_KEY=sk-or-... python scripts/dev/decisions_probe.py
```

**It sends four synthetic requests:**
- JEV in the old shape;
- JEV in the documented shape;
- Luna in the documented shape;
- Luna with a small red picture.

**It prints:**
- whether the old shape was ever accepted;
- p50 times;
- whether Luna read the picture.

It saves the answers (no ids, no key) under `apps/mobile/app/src/test/resources/decisions/recorded/` for the tests.
The key is read from the environment only.

## Numbers on the PC

The phone-care health report carries, as counts only:
- `decisions.calls`: per model and board, the answer rate, p50/p95, failures and cost;
- `decisions.watch`: answered, Board 0 agreement, Triage rung agreement, **Triage below the rules** (must stay 0);
- `decisions.paused`;
- `decisions.lab`.

Glass doesn't draw them yet (plan 58, alpha.125).

## Tests

- **`DecisionsWireTest` (6):**
  - the documented request;
  - malformed requests refused before they leave the phone;
  - the API reference's example answer (rebuilt from its documented values) read exactly;
  - out-of-contract answers;
  - refusals;
  - the breaker;
  - status codes.
- **`TriageTest` (12):**
  - the board as one valid request;
  - open camera is Instant;
  - the owner's Gmail-and-grandma example is Mind;
  - a short chain is Flash for now;
  - risk never Instant;
  - unsure goes up;
  - writing, later and questions go to the Mind;
  - refusals;
  - Triage only acts when switched on, after the rules;
  - the watch board and its record;
  - the gate's numbers.
- **`GoldenSetTest` (4)** and **`DecisionLabReportTest` (1)**.
- **`CallLogTest` (2).**
- Updated: `DecisionsTest`, `ModesTest`, `PilotTest`, `JevShadowTest` (documented shape only).
- **Gateway:** `test_plan58_call_and_watch_numbers_pass_as_counts_only`.
- **Guards:**
  - new `test_luna_box_guard.py` (7);
  - `test_decisions_guard.py` updated: one endpoint location, JEV default, Luna live with vision, the provider as a
    setting.

## Limits, stated plainly

- **Physical device: UNVERIFIED.** Run the rows in `docs/LUNA_DEVICE_MATRIX.md`.
- **The API reference example only.** The answer fixtures are rebuilt from the API reference's example, not recorded
  from JEV or Luna. Real recordings come from the probe.
- **Each Auto request now makes one extra call** (the watch) while "Compare beside it" is on. With Luna that is about
  $0.0001 per request.
- **No screenshots yet.** The watch is text only. A screenshot goes to Luna only if you choose it under "Decided by",
  and only where Instant's follow-up questions already take one. Context on demand (when to send the screen) is
  alpha.124.
