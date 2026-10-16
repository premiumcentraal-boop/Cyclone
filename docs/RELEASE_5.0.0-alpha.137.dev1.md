# Cyclone V5 Alpha 137: Live voice from the Ask bar, decided while you speak

Developer alpha for owner testing. It builds on alpha.136 dev1 and includes it.

- **Mobile:** `5.0.0-alpha.137.dev1` (version code 291).
- **PC runtime:** gateway and MCP `5.0.0-alpha.137.dev1` (no change but the version).
- **Glass:** unchanged.

## Live voice replaces Send when nothing is typed

When the Ask bar is empty, its send arrow becomes a **voice wave**. Tap it and talk: this is **Live voice**, the same
spoken conversation Driver mode's button opens.
- Cyclone confirms in one line.
- It keeps listening after a quick action.
- It says when the task is done.

**How it fits with the rest:**
- The **mic** next to it is unchanged: it still dictates into the field.
- As soon as you type, the button is Send again.
- **Driver mode is now "Live voice"** in Settings, search, the Ask panel, the film and the Quick Settings tile.
- Its switch, **Voice button for the car**, keeps the large voice button on screen as before.
- Without that button, Live voice from the Ask bar closes on its own 2.5 s after the conversation, and any task it
  started, is over.
- While the voice panel is up, the Ask overlay steps aside and comes back exactly as it was. Nothing is stopped or
  changed.

## Deciding while you speak (plan 65 R1)

**Live voice:**
- When you pause (260 ms of quiet), Cyclone already transcribes what you said. The clip it sends is exactly the clip
  it will send if you stop there.
- If the conversation would then ask the understanding model, that call is made too.
- If it is a new task, its decision race starts as well.
- When you do stop, each step is used only if it is **exactly the same**: the same audio span, the same words, the
  same question.
- If you keep talking, the head start is dropped.
- With the default 0.7 s end-of-speech wait, each step gets about 440 ms of head start.

**Ask bar dictation:** the words so far (once they hold still for 250 ms), and the final words, start the decision
race early. When you tap Send with the same words on the same screen, the decision is already there.

**What stays the same:**
- The command grammar, the safety rules, Replay and the phone model still run on the words you actually send.
- **Nothing is carried out before you finish.**
- An early decision that isn't used is never counted in the engine numbers.

## Two answers again

The decision engines ask each model **once** again (two answers in total, as you asked).
- **Sprint** acts on the first sure, easy-to-undo answer unless the other model has already disagreed.
- **Consensus** acts only when both agree.
- The second phrasing (options in reverse order) stays built and tested, but switched off.

## The Live voice overlay in the current design

**Theme and light:**
- The voice windows now use the Ask overlay's dark theme.
- They follow the phone's light, as the Ask glass does.

**Dim:** the overlay's graphite smoke instead of teal-black. It now really fades in (before, it appeared at once).

**Panel:**
- It is the overlay's card: the same glass, 30 dp corners, 16 dp side margins, grabber and padding.
- It rises from the bottom on the overlay's one spring and resizes smoothly as the words change.
- Captions use the Ask field's and the overlay's question type.

**Lite** (and Android's animations-off) holds the orb still.

## Tests

- **`HeadStartTest` (5):**
  - a head start goes only to the same key, once;
  - other words cancel it;
  - on synthetic speech, a pause reports exactly the span the end will report;
  - talking on moves the span.
- **`SpeculationTest` (6):**
  - same words, screen and bar reuse the early decision;
  - anything different decides afresh;
  - a stale or failed one is no decision;
  - the engine record is kept only when used;
  - the router's engine says "decided while you spoke";
  - the rules still come first.
- **`EnsembleTest`:** Sprint acts on the first answer with one answer per model, and needs two with two per model.
- **Guards:** `test_live_voice_guard.py`.
  - Priming carries nothing out (no hands, mission, lesson or audit).
  - The voice probe is never dispatched.
  - The empty bar opens Live voice without touching the chrome's state.
  - The voice windows use the overlay's theme, light and motion.

## Limits, stated plainly

- **Physical device: UNVERIFIED.** Run the alpha.137 rows in `docs/LUNA_DEVICE_MATRIX.md`.
- **No blur behind the Live voice panel yet.** It shows the overlay's smoke glass, which is what the Ask overlay shows
  on phones without blur or in Lite. Real blur needs the Ask overlay's blur windows shared with the voice windows.
- **The head start in Live voice needs Cyclone's own recording** (the default).
  - With "Android's recognizer" chosen, Live voice starts only the decision race early, for quick commands.
  - Its partial words aren't used yet.
- **Extra calls:** a pause you talk on after costs one transcription, and maybe one understanding call, that is
  thrown away. Ask-bar dictation can start a few races per request.
- **The orb is still teal,** like the Ask bar's own listening orb.
- **The Drive intro film keeps its night-road look.**
