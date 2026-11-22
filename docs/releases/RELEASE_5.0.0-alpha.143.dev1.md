# Cyclone Alpha 143: prove it did it

Developer alpha for owner testing. It is built on Alpha 142 and installs over it as a normal update.

## Versions

- **Mobile:** `5.0.0-alpha.143.dev1` (version code 297).
- **Cyclone for Windows, gateway and MCP:** unchanged, `5.0.0-alpha.138.dev1`.
- **Glass:** unchanged, `1.0.0-alpha.68`.

## Why this release

On Alpha 142, Cyclone said it had done things it hadn't:

- **"open my profile", asked inside Instagram:** it answered "I don't see Profile on this phone."
- **"open Instagram and then my profile":** it stopped on Instagram's home page and called it done.

The rule from now on: **nothing counts as done until the phone shows it.** Plans 71 and 72 have the details.

## What changed

**"Not on this phone" needs proof** (plan 71):
- It is never said for "my X", for a place inside an app (profile, orders, chats…), for something on the screen, or
  while an app is in front.
- "Open X" now reads the screen first. Inside an app, "open my profile" taps the control called Profile and checks
  that the screen changed.

**Instant proves each result** (plan 71):
- An opened app counts only when it is the app in front. Cyclone looks up to 3 times, 400 ms apart.
- Taps, drags, the camera, the shutter, the call button and zoom must change the screen.
- A gesture that moved nothing says so.
- Anything Instant can't prove goes up to Flash, carrying what it did.
- If a model's route would leave part of the request undone ("open Instagram my profile"), the request goes to Flash
  instead.
- Only proven runs teach the phone model and Replay.

**Flash and OneMind** (plans 71 and 72):
- A step without a check still needs proof: an opened app must be in front, and a tap must change the screen.
- "The app is open" never finishes a run by itself. The done check must agree, and it now sees the page itself (its
  controls and a screenshot).
- **Two that decide, one that proves.** Every step makes one call per decision model, in parallel. The screenshot goes
  only to the model that reads images; JEV gets the text. There is no second "now with the image" round. The done
  check is a single call, with the screenshot. The cross-checks that used to cost extra calls are inside each call:
  - the pick is asked twice, the second time with the options reversed, so an answer that only won by coming first
    doesn't count;
  - the controls code thinks fit the step are each judged on their own;
  - a pick is acted on only when all of these agree, and so do both models.
- **Full context in each call:** the run's last 6 moves and whether each was proven, plus what this app taught
  Cyclone before. Code keeps each call under 30,000 characters, so JEV stays fast.
- **Per-app memory, on by default.** Every proven tap is remembered for its app, for example "on Instagram's feed,
  Profile led to a page with Edit profile". The next run on that page sees it.
  - It stores structure only: control labels and page keys.
  - It never stores typed text, field values, anything from a sensitive screen, or labels that look like numbers,
    amounts, addresses or links.
  - A remembered move that fails twice is forgotten.
  - It lives in `Cyclone Brain/Moves` on the phone.

**The router:**
- Each decision model is still asked once. The same call now also carries the action and target questions with their
  options reversed.
- A model that answers two ways is "not sure", and the request goes up a mode instead of acting.

**The home screen:** Cyclone asks Android which apps are home screens, so a launcher whose package name doesn't contain
"launcher" (such as Samsung's) is recognised.

## Calls per movement

| | Alpha 142 | Alpha 143 |
|---|---|---|
| Code knows the target | 0 | 0 |
| A crew step | 2, plus 2 more with the screenshot | **2** (the screenshot is in the first round) |
| The vision shadow on a step | 1 | **0** (OpenAI Decisions sees the screenshot itself) |
| The done check | 2 | **1**, with the screenshot |
| Routing a request | 2 | 2 (with the second phrasing inside the call) |

## So it can't come back

- **`test_instant_proof_guard.py`** checks:
  - every route passes the coverage check;
  - opens are proven by the app in front;
  - only proven runs teach;
  - finishes go through the finish rule.
- **`test_call_budget_guard.py`** checks:
  - a step asks once;
  - one lane per model;
  - the done check is one call;
  - the router asks each model once;
  - the cross-checks live in the call.
- **New unit tests:** `InstantProofTest`, `OneCallTest`, and new cases in `EnsembleTest`, `OneMindCrewTest` and
  `OneMindConductorTest`.

## Limits

- **Physical device: UNVERIFIED.** Run rows 9.1–9.7 in `docs/LUNA_DEVICE_MATRIX.md`.
- **The cross-checks inside one call aren't measured yet against separate calls.** No decision-model key was available
  where this was built. They can only make Cyclone more careful, never less, because an action still needs every check
  and both models to agree. The engine log's self-agreement number shows how often the two orders differ.
- **More careful means slower in places.** A model that answers two ways, or a tap that changes nothing, now goes to
  Flash or the mind instead of acting.
- **The JEV vision shadow is off.** It was a measurement, and OpenAI Decisions now sees the screenshot in the deciding
  call.
