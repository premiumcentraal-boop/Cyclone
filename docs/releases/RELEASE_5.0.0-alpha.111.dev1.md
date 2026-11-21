# Cyclone V5 Alpha 111: Human Hands

Cyclone now moves and types more like the person whose phone it is. This alpha builds runs 1–4 of plan 52
(`Cyclone V5 plan/52-human-hands.md`) in one release: natural motion, swipes chosen by a hand model, finger-pressed
buttons, the keyboard on screen with key-by-key typing, and short human pauses. Everything is controlled by one new
setting.

## Settings › Hands

The setting is in **Cyclone AI settings**, above Fast mode.

| Style | What Cyclone does |
| --- | --- |
| **Precise** | As in earlier versions: straight-to-the-point touches, text filled in one go, no pauses. |
| **Natural** (default) | All of the changes below. |
| **Relaxed** | Natural, with slower typing and longer pauses to read each page. |

Other options:

- **Hand:** the hand you hold your phone in (right or left), which decides where the thumb rests and which way swipes
  bend.
- **Occasional typos:** off by default. When on, a neighbouring key is pressed now and then and fixed at once with
  Backspace. Only in ordinary sentences, never in names, emails, links, codes or numbers, and the text always ends up
  exactly right.

## What changed

**Swipes speed up and slow down.**
- Android plays a stroke at one even speed. Cyclone now plays each swipe as up to 14 linked pieces with their own
  timing:
  - a controlled stroke follows a smooth bell-shaped speed curve;
  - a flick is still accelerating when the finger lifts, so the list keeps going;
  - a careful drag slows and rests on the glass before lifting.
- If a phone refuses the linked pieces, Cyclone lifts the finger, uses one stroke along the same path for the rest
  of that session, and records it.

**Swipes vary in shape.** Cyclone picks among:
- a thumb arc around the holding hand's pivot;
- a single bow;
- a gentle S for long travel;
- (drags only) a small overshoot that comes back.

Taps and long presses roll 0–3 px between finger-down and finger-up, always inside the target and far below
Android's tap tolerance.

**Swipes start somewhere new each time.**
- The Mind, Instant and background screens now ask for a swipe by direction, amount (peek, half, page, far) and
  speed (gentle, normal, flick).
- A hand model on the phone chooses where the thumb lands, how far it travels, how long it takes and how it lifts.
  It keeps clear of the screen edges used by gesture navigation, the status bar and the "back" swipe.
- Scrolling a large list in Natural style uses the thumb in the same way, but never starts on a carousel inside the
  list or under another window.
- The old `x1,y1,x2,y2` form still works.
- The approval check still classifies the exact point the finger will start on.

**Buttons are pressed with a finger.**
- In Natural style, a clickable control is pressed with a real touch instead of an Accessibility click when:
  - it is plainly visible and on screen;
  - it is at least 32 px in each direction;
  - nothing else in the app or another window (such as the keyboard) covers it;
  - no button of its own sits inside it.
- Anything doubtful keeps the Accessibility click.
- Cyclone picks one way before acting: a press that changes nothing is never followed by a second click.

**The keyboard opens, and text goes in key by key.**
- Cyclone touches the text field like a person, so the app opens your keyboard itself. Before, it focused the field
  silently and the keyboard never appeared in Cyclone mode.
- With the keyboard showing, text is typed through Android 13's Accessibility input method, one key at a time, at a
  quick thumb pace (about 7–10 keys a second in Natural). The rhythm varies:
  - gaps depend on the letter pair;
  - short pauses come between words and before `@` and `.`;
  - switching to the symbol keys takes extra time;
  - there is an occasional hesitation.
- Long messages type their opening and add the rest in one go.
- Read-back still decides the result:
  - if the field does not hold exactly the text, Cyclone falls back to set-text, then paste;
  - if you take over mid-way, typing stops at once and nothing else is written.
- Passwords and codes from your Vault are still filled in one step, the way a password manager does, and are never
  typed key by key.
- Typed text never appears in results, traces or diagnostics: only the length and how it went in (`method: keys`,
  `keyboard: shown`).

**Short human pauses.**
- Before each Mind action, Natural waits about 0.15–0.9 s to read the page and find the control; Relaxed waits up
  to 2.2 s.
- Time the model already spent thinking counts toward the pause, so a slow model adds no extra wait.
- Stopping a task ends the pause at once.
- Instant voice actions are not paused.

**The PC sees it.** The phone's capability report carries a `hands` block (style, hand, and whether speed curves,
swipe intents, finger presses, key-by-key typing and pauses are on). The gateway passes it on as a fixed vocabulary.

## What did not change

- Approval boundaries (pay, send, delete, permissions, sign-in), GATE, ownership, fresh observation and the Fast Path
  settle are unchanged.
- Unchanged is still never a second click.
- The PC still cannot send raw gesture paths, and `phone.swipe` still has no MCP route.
- An app that inspects raw touch events can still tell they come from an Accessibility service. This release does
  not try to hide that, and natural motion cannot guarantee that an app never flags an account.

## Versions and evidence

- Product / Android / gateway / MCP: **5.0.0-alpha.111.dev1**; Android version code **259**.
- Glass is unchanged (**1.0.0-alpha.61**).
- Tests:
  - New unit tests: speed curves, shapes and finger roll (11); hand model and swipe intents (7); finger-press and
    thumb-scroll safety (6); typing rhythm and pauses (6); typing ladder with the keyboard (6).
  - Updated: the source-order safety contract and the Mind swipe test.
  - The gateway projects `hands` with a fixed vocabulary (2 new tests).
- Physical phone: **UNVERIFIED**. Nobody has yet checked on a Pixel how chained stroke pieces play back, keyboard
  coverage across apps (WebView, Compose, games) or finger presses in real apps. Precise style keeps the previous
  behaviour if anything misbehaves.
