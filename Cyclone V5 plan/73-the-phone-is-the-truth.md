# Plan 73: the phone is the truth

Alpha 144. Field test 143 ran the alpha 143 build on the owner's Pixel 8 for about three hours, through the Ask router
and the Lab. Each fix here was traced in the alpha 143 source; the field report itself had to trace alpha 135. This
plan says what was wrong underneath, what changed, and what still waits.

## The rule

Cyclone works for anyone, in any app, in any language, on any network. So the fixes below must not depend on:

- one app's labels;
- one language's words;
- a connection that happens to be fast today.

**What the owner can see on the phone decides; nothing else does.**

## 1. A tap is proven by the screen, not by motion (B1, S3)

**What happened.** On Instagram's feed, "open my profile" reported "Tapped Profile" while the feed stayed.

- Alpha 143 already demanded that a tap change the screen. But "changed" meant that the page key differed.
- The page key hashes every row's text and tree position, so on a live feed (autoplay, new rows, a story tray) it
  changes on its own.
- In Flash and OneMind it was worse. A tap without a check counted as done when the tool was "the kind that changes
  screens", and the done check compared the most volatile fingerprint.

**What changed.**

- **Screen identity** (`PageContext.screenIdentity`) is made from what stays put:
  - the app, the window class, the selected tab and the title;
  - the controls outside every scrolling container, with their selected or checked state.

  Labels count in any script, with digits masked. A page that is all one list falls back to the full structure.
- **Screen proof** (`AgentStateDelta.screenProof`): another app in front, another screen identity, or a switch, field
  or focus that changed state. A selection change alone doesn't count, because carousel dots change by themselves.
- **Instant:** taps and drags need the proof. Swipes, scrolls and zoom are allowed to move the content, since that is
  their job. `InstantMove` carries both values.
- **Flash and OneMind:**
  - A tap without a check needs `MindToolResult.screenProof`.
  - `Expect.Changed` compares identities.
  - The breaker no longer counts "a tap tool ran" as progress.
  - The model reads "only moving content changed: this is still the same screen" instead of "The screen changed".
  - Per-app memory learns only from proven taps, so a false proof is never learned.
- **Settle:** waiting for the screen to stop moving uses the same identity. A feed or a chat list (typing dots, times)
  used to keep every tap and launch "moving" for the whole 8 s extension (B8: open WhatsApp took 11 s, tap Profile
  5 s).

## 2. The route depends on the request, not the clock (B2, S1)

**What happened.** 5 of 19 routed requests ended with no decision model answering in time, every one at 1500–1503 ms,
so the same sentence went Instant once and Flash the next time.

Plan 72 folded the second phrasing into each call, which made every call bigger, but the race deadline stayed at
1.5 s.

**What changed.**

- The engine's deadline is 2.5 s, the same as the routing deadline. A model that answers sooner still decides
  sooner.
- When no model answers at all (`Ensemble.NO_ANSWER`), the phone model's own guess stands for an action that is easy
  to undo, if it is sure enough (`NO_ANSWER_GUESS` = 0.6). Instant proves the result and goes up if it fails.
- The coverage check still applies to that guess.

## 3. Names resolve against what the phone has (B13, S2)

**What happened.** "pull up google maps" became "it spans several apps (Maps, Google)", and the Mind's `open_app
"Google Maps"` was "ambiguous".

The router matched raw substrings, and a Pixel lists Maps and the Google app side by side.

**What changed.** `AppNames` is one resolver for the router, the coverage check, the grammar and `open_app`:

- whole words only ("Meet" is not in "meeting");
- the longest label claims its words;
- a word next to a named app that is part of that app's package ("google" in `com.google.android.apps.maps`) is its
  maker, not another app.

The grammar uses the strict form, because its matches skip the coverage check. It also knows "pull up", "bring up",
"open up" and "fire up" now.

## 4. Stuck means no progress, and a person is the last resort (S5)

**What happened.** Two "Needs you" check-ins came in ten minutes:

- a Google Maps "dark theme" dialog sat over the map;
- an Instagram tap kept missing.

The same-trigger count was cleared only at the end of a milestone, so three misses with finished steps between them
still asked the owner.

**What changed.**

- A finished step clears the same-trigger counts. The milestone's wake count stays as the backstop.
- Before a check-in, the **recovery ladder** (`Recovery`) runs once per milestone. Each rung counts only when the
  screen shows it worked:
  1. Close what is in the way with its own way out ("Not now", "No thanks", "Close", the ✕):
     - "Cancel" and "Close" only on a dialog-sized screen;
     - never a label that confirms, allows, sends, pays or deletes;
     - never with half-typed text on screen.
  2. Press Back, kept only while the same app is still in front.
  3. Reopen the run's app.
- The ladder never touches a system sheet (permission, sign-in, install, share) or a sensitive screen. Even Back there
  would answer the sheet, and those answers are the owner's.

## 5. A request follows its own mission (B3–B6, S7)

- **Hand-over:** a request handed up says why ("handed up to flash: the tap on "Profile" changed nothing…").
- **Linked to its mission:** the request carries its mission's task id, and it finishes when that mission ends. It
  used to read "running" for good.
- **New requests:** a request that is still waiting reads idle; it no longer shows the last task's "failed". Neither
  does a mission request whose own mission is a different task.
- **Waiting for the owner:** while the mission waits for the owner, the request reads "action-needed".
- **Stop:** it stops the request's own mission, through Task Kit.
- **Busy phone:** it says what is in the way and that it can be retried. The PC passes the phone's reason through,
  bounded and printable, instead of a bare "Android rejected ask.start". `ASK_BUSY` is retryable.
- **Not built:** a queue, so that a request waits instead of being refused (S7). It needs the ask contract extended
  and is its own change.

## 6. A busy screen is read, not retried away (B7, B10)

- **Feeds that never settle:** a read during which only the content moved is accepted on the last of three tries.
  The window set, size, rotation, scope, profile and session must still hold, and the executor revalidates every tap
  anyway.

  Field test 143 lost 5–7 turns per Instagram run to "the screen changed during capture".
- **PC page reads:** `GET …/agent/page/current` reads the phone again when its record is older than 3 s, and says
  `observedAtMs`, `ageMs` and `stale`.

## 7. Smaller fixes

- **B11, the brain store:** it looks a skill or path up by its key instead of loading and sorting 500 rows to find
  one. That line was named in four freezes. The mirror is now written off the main thread.
- **B9, tool errors for Flash:**
  - `go_to` says it takes a map handle, not a goal, and lists the screens.
  - `open_link` refuses words and app schemes and says to tap inside the app instead.
- **Plane messages:** asking from inside an app to do something in that app is no longer "the owner busy
  elsewhere". So the Background Check advice no longer appears mid-run, and the default reason is true ("Cyclone works
  on your screen, where you can watch").
- **B12, testbench:** it no longer crashes on a Windows console that can't show ✓ and ✗.

## 8. Not built here, and why

- **S4, screen diffs to the model:** this needs the crew's prompt reshaped. The identity in §1 is the first piece.
- **S6, replayed recipes per app and screen:** plan 72's move memory is the start. It now learns only from real
  proof, which a replay needs first.
- **S8, cost per request:** the $0.00 is by design. `/v1/cc/ai` counts only the Command Center's own AI manager, and
  mission cost is per mission in the Lab report. Showing it per request is a separate change.
- **S9, semantic Lab checks:** the testbench's own work.
- **The "granted Superuser rights" toast:** Safe's root writes (plan 61) run on a 10-minute debounce of file changes,
  which is the likely source. Changing how often Safe saves is the owner's call, because it trades freshness of the
  snapshot for fewer root prompts.
- **"killBackgroundUserProcessesWithAudioRecordPermission":** Android's own message on a profile switch for apps that
  hold the microphone permission. Cyclone holds no microphone while live voice is off.

## 9. How to check it next time

- **"open my profile" from the feed, 5 times:** no done without the profile tab selected. Let the feed play.
- **"pull up google maps" and "open google photos" from home:** Instant, opened.
- **The same 10 sentences, 5 times each:** identical routes on a slow network.
- **A dialog over an app during a Flash run:** closed without a "Needs you" card.
- **A permission sheet:** never touched.
- **`ask.status` of a Flash request:** "done" within a second of its mission ending; the first poll of a new request
  reads "waiting".
- **Instagram Flash runs:** turns and "could not be read" counts down from field test 143's 20 turns and 150 s.
