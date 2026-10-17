# Plan 71: prove it did it

Alpha 142 failed the owner twice, and both runs said they had worked:

1. **"Open my profile", asked inside Instagram.**
   - Cyclone answered "I don't see Profile on this phone."
   - Cause: the router read "open X" as "open the app X". No app is called Profile, so it said the app was missing.
   - It never looked at the screen, which showed Instagram with a Profile tab.
2. **"Open Instagram and then my profile."**
   - Instagram opened on its home page, and the run ended as done.
   - Cause: nothing checked the result against the request. A step was done as soon as the tool said "ok". A run was
     done once the app was open, even if no decision model answered the DONE question.
   - Whatever made that call (the router's model, the phone model, Replay, a Flash plan), it acted on half the request
     and reported the whole of it.

Both were reproduced against alpha.142's code before the fix (see `InstantProofTest`).

## The rule

**Nothing counts as done until the phone shows it. A claim that something can't be done needs proof as well. Anything
Instant can't prove goes up to Flash, along with what it already did.** Reliability comes before speed. A request that
Instant used to "finish" in 1 s now takes Flash a few seconds when Instant can't prove its result.

## 1. Instant takes the whole request, or none of it (`Coverage`)

- Every route a model decides is checked before Instant acts on it: Board 0, Triage, the decision engine, the phone
  model and Replay.
- The route fails the check when the request names a second place that its one move doesn't reach:
  - a place inside an app (profile, orders, chats, reels, wifi…);
  - another app on this phone;
  - another control on the screen.
- A route that fails goes to Flash.
- Casual wording still passes: "time for some insta", "take me back a page", "pull up google maps".
- The grammar's own matches are exact by construction, so they skip this check.

## 2. "Not on this phone" needs proof (`ModeRouter.missingApp`)

- The claim is never made in any of these cases:
  - for "my X" or "your X";
  - for a place inside an app;
  - for something that is on the screen;
  - while any app other than the home screen is in front.
- "Open X" now reads the screen, so the router knows which app is in front.
- Inside an app, "open my profile" is the control called Profile on this screen. Instant taps it and checks the result,
  the same as "tap Profile".

## 3. Instant proves each result (`InstantRun`)

| Command | Proof required | If there is no proof |
|---|---|---|
| Open an app | The app is in front. Checked up to 3 times, 400 ms apart. | Flash |
| Tap, drag, call button, shutter, camera, zoom | The screen changed. | Flash |
| Back, Home, swipe, scroll | The screen changed. | Done, with the words "but nothing on the screen moved", and never counted as proof |
| Timer, alarm, flashlight, volume, media | Android's own result. | Mind |

Only a proven run teaches Replay and the phone model (`InstantOutcome.verified`). An unproven "done" teaches nothing.

## 4. Flash and OneMind steps need proof too (`StepEngine.implied`)

When the plan gives a step no check, the step gets one by default:
- `open_app`: the app must be in front.
- `tap`, `long_press`, `double_tap`: the screen must change.

A miss goes back to the mind. Before this, "the tool said ok" counted as done.

## 5. "The app is open" never finishes a run (`Conductor.doneConfirmed`)

| Code's done check | DONE question needed to finish |
|---|---|
| Missed | None: the run is never done |
| Holds, and names what the goal shows (text, a control, a field, a setting, a fact) | Must not say no (no answer is fine) |
| Holds, but only says the app is open, the screen changed, or a field has focus | Must answer yes |
| No check, or one code can't judge | Must be sure |

- The DONE question now sees the screen's controls, so it can tell Instagram's home page from the profile page by
  "Edit profile".
- It is told that the right app on the wrong page is not achieved.
- The plan prompt asks for a done check that proves the whole goal, for example
  `{"all":[{"app":"Instagram"},{"control":"Edit profile"}]}`.

## Guards

- **`scripts/ci/tests/test_instant_proof_guard.py`** checks each of the five rules above in the code:
  - routes pass `Coverage.check`;
  - `missingApp` looks at the app in front, possessives and the screen;
  - opens are proven by the app in front, and the hands report it;
  - `VERIFIED` is only learned with `outcome.verified`;
  - steps get implied checks, and finishes go through `doneConfirmed`.
- **Unit tests:** `InstantProofTest`, plus the new cases in `OneMindCrewTest` and `OneMindConductorTest`.

## Not proven here

- **Physical device: UNVERIFIED.** The rows are in `docs/LUNA_DEVICE_MATRIX.md` (alpha.143).
- **Toggle taps.** A tap whose only effect is a switch changing state counts as changed only if the screen's
  fingerprint covers that state. If it doesn't, Flash takes over the tap: slower, never wrong.
