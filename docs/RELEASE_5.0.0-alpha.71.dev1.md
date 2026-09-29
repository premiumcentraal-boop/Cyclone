# Cyclone V5 Alpha 71: the app redesign

Developer alpha for owner testing. It builds on Alpha 70 and includes the Home redesign (R4) and the full in-app
redesign (R5).
- **Mobile:** `5.0.0-alpha.71.dev1` (version code 216).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.71.dev1.exe` (runtime `5.0.0-alpha.68.dev1`, unchanged).
- **Glass:** `1.0.0-alpha.40` (unchanged).

Every page in the app now sits on the AI page's material: the Cyclone rain behind smoked glass. Navigation is a
darker glass that clearly floats above the content. Older phones get a Lite version that looks the same. The contract
is `docs/design/redesign/rounds/R5-app.md`.

## What changed

**One glass world.**
- The rain is drawn once for the whole app, so changing tabs never restarts it.
- The official scene (or your own video) plays only on the AI page. Other pages show the drawn rain and decode
  nothing.

**Home (R4).**
- Home is in the AI page's style without the video:
  - the burger top left and the Cyclone mark top right;
  - the greeting;
  - four quick actions that fill the Ask bar;
  - recent activity and your routines in smoked lists;
  - the smoked Ask bar with its dots.

**Navigation above content.**
- The tab bar, header buttons, the model selector, sheets, both Ask bars and back buttons are the darker "chrome"
  glass. It blurs more, bends more at the edge, has a brighter rim and casts a deeper shadow.
- Cards and lists stay the lighter content glass.

**Every page.**
- Profiles, Routines, the Marketplace, Brain and Settings follow through the shared parts:
  - smoked cards;
  - a neutral white-and-graphite scheme instead of teal;
  - white icons and rings;
  - glass back and status chips.
- Settings groups have their own tile colours.

**Visual quality: Auto, Full or Lite** (Settings › Appearance).
- **Full** is the design.
- **Lite** keeps the same look with less work:
  - one shared blur for all cards instead of one each;
  - no lens or shadow on cards (navigation keeps both);
  - the rain at 20 instead of 30 frames a second.
- **Auto** picks Lite for clearly older phones (low-RAM flag, under 4.5 GB, fewer than 6 cores, or under 6 GB
  without a performance class). It also switches to Lite if frames keep running slow.
- Settings shows what Auto chose and why. Only your choice is stored.

**Always faster.**
- The rain holds still while you scroll a list.
- The moving shine is its own small layer, so it never re-runs a blur.

**Unchanged.** The floating overlay stays teal and as it was. Other activities (PC Gateway settings, the overlay's
AI settings) keep the teal theme.

## Tests

- `AskQualityTest` (new): Auto's rules, your choice winning, the explanations, the slow-frame window and the scroll
  hold.
- `AppR5ContractTest` (new):
  - one world reused by Home and the AI page;
  - navigation on the chrome tier;
  - Lite's shared blur, with lens and shadow dropped on content only;
  - the frame watch and the setting;
  - only the choice stored;
  - no teal in the new parts;
  - the overlay untouched.
- `HomeR4ContractTest`: Home's order, no teal parts, the smoked Ask bar.
- Updated: `AskScreenR3ContractTest`, `CycloneTealMatrixTest`, `CycloneAppleUiContractTest`.

## Physical acceptance

UNVERIFIED. Not yet seen on a phone:
- how every page reads on the rain;
- chrome against content;
- Lite against Full side by side;
- whether Auto's step-down triggers on a slow phone and never on a fast one.
