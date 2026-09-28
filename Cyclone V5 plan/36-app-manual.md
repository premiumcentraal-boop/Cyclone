# 36 — The App Manual: every screen and feature, written so any AI can use it

**Status:** plan, 2026-09-28. Three build runs (M1–M3, §11). Builds on the mapper (plans 06, 20, 22), one map and
grounded skills (23), the Mind's `go_to` (A37) and JEV watching (32).

**The owner's ask:**
> "These are very very vague. Rethink how we can optimally write down each screen and function in it so that any AI
> can easily pick up on what it needs from human language. … an agent later can identify: oh hey, that plus button in
> ChatGPT lets me also add a plugin to a chat. Then it can look for plugin inside the chat window and instantly know I
> need to click the plus. … Frontier AI optimisation and rapid navigation, even using JEV in places."

**In one sentence:** the mapper stops writing "Screen → Menu → Screen" and starts writing an **App Manual**. The
manual lists every screen, panel and control in the app's own words, says what each one is for, and keeps an index of
**things you can do** ("add a connector to a chat") with the verified taps that do them. An agent looks a goal up in
the index and walks the path, usually with no model call at all.

---

## 1. Why the map is vague today (found in the code)

In the ChatGPT pass in the screenshots, 12 places are all called "Screen · Learned app screen", and the doors are
"Menu", "Search", "Back". Five things cause this:

1. **Every name is squeezed into 33 fixed words.** `AtlasPrivacy.coarseStructure` (`brain/graphv2/AtlasContracts.kt`)
   maps any screen or control name onto a frozen list (`STRUCTURAL_TERMS`: Home, Search, Menu, Settings, …). Anything
   else becomes the fallback: "Screen", "Learned app screen", "Control". "Attach", "New chat", "Voice", "Temporary
   chat" and "Connectors" all vanish. This was a privacy choice: a chat title or a person's name must never be kept.
   But it throws away the app's own words along with the user's content.
2. **The mapper drops labels at the door.** `MappingStructuralProjection` (`mapping/crawl/AndroidMapperPorts.kt`)
   reads each control's label, description, resource id and role. It keeps only a hashed key and one of 11 door kinds
   (TAB, MENU, SEARCH, …, CONTENT_ROW). A "+" button described as, say, "Add photos and files" matches none of the
   kind patterns and becomes `CONTENT_ROW`.
3. **Screen purpose is almost always UNKNOWN.** `inferPurpose` recognises only LOGIN, MENU and LIST.
4. **Panels don't exist.** The "+" sheet, overflow menus and dialogs are either separate "places" or never opened. A
   feature that lives one tap deep inside a panel (the connector picker behind "+") is invisible.
5. **Nothing says what a thing is for.** Even the Mind's map card (`mind/map/MindMap.kt`) is a list of handles and
   labels (`s3 Screen: "Menu" → s4`), capped at 40 lines. It can route to a screen it can name. It cannot answer
   "where do I add a plugin?".

## 2. What we want: one example, end to end

The goal: "Add the GitHub connector to this ChatGPT chat."

**Today:** the Mind opens ChatGPT, reads the screen, guesses, taps around, and reads again. That is 6–10 model calls,
and it might never try "+".

**With the manual:**
1. The goal is looked up in ChatGPT's **ability index**. The top hit is *"Add a connector (app) to a chat"* (score
   0.91; the runner-up is *"Attach a file to a chat"*, 0.44).
2. The ability's path is `Chat › + (Add) › Connectors › pick one`. It needs to start in a chat, and its risk is
   *navigation, then a choice*.
3. The walker (`MapWalker`) opens a chat and taps "+". It checks that the **Add** panel appeared, then taps
   **Connectors**. It uses no model call for any of this; each step is verified by the screen's fingerprint.
4. Only the last choice ("GitHub" in the list) goes to the Mind, with a two-line excerpt from the manual.

That is about 1 model call instead of 6–10, and seconds instead of a minute.

## 3. The privacy key: the app's own words vs your content

The owner's promise stays: **Cyclone never keeps what was on your screen.** It changes from "structure only" to
**"the app's own words only"**. Screen titles, buttons, tabs, menu items and sheet headings are the same for every
user of the app. Chat titles, names, messages and typed text are yours and are never kept.

A text is treated as **the app's own words (chrome)** only when it passes one of these tests. Otherwise it is
**content**.

- **In the app's own dictionary (the strongest test).** Android lets Cyclone read an installed app's string
  resources (`PackageManager.getResourcesForApplication`). Cyclone builds a per-version **lexicon** of every string
  shipped in the APK, in the phone's language, and keeps it as normalised hashes.
  - "Add photos & files", "New chat" and "Connectors" are in ChatGPT's APK; "Dinner plans with Louella" never is.
  - The lexicon also gives each label a **string resource id**, so a path still works after the phone's language
    changes.
- **Stable in a chrome slot.** For words the app downloads rather than ships (server-driven menus), a text counts as
  chrome only if all of these hold:
  - it sits in a chrome region (toolbar, tab bar, navigation drawer header, sheet or dialog title, menu item);
  - it sits outside any list or recycler container;
  - it was seen with the same text in the same slot on **two passes on different days**.
- **Never chrome:**
  - editable fields and their values;
  - list and recycler rows (except their shape);
  - anything `AtlasPrivacy` flags (emails, long tokens, codes);
  - anything inside a secure window or password field.

**Content becomes a typed slot, never a value.** A row in the chat list becomes `‹a chat title›`, and a contact row
becomes `‹a person›`. The manual can say "the sidebar lists your chats (‹a chat title› rows)" without keeping one.

**Guarded, not promised:**
- **Canary check.** A CI guard and a Lab check seed canary content (for example a chat titled
  `CANARY-7781 lighthouse`). They assert it never reaches the manual, the database, Glass, diagnostics, or anything
  sent to the describer model.
- **Echo check.** Every model-written sentence is checked against the observation's **content** text. Any run of 3 or
  more content words, or any content token of 5 or more characters, rejects the sentence.

## 4. The App Manual: the data model

One manual per **app × version code** (the Versions tab already tracks versions). It has five kinds of entry, and
each entry carries **provenance** (seen, walked, used in a run, taught by the owner, inferred by a model) and a
**confidence**.

| Entry | What it holds | Example (ChatGPT) |
|---|---|---|
| **Screen** | A name in the app's words, one line on what it is for, how you get there, its regions, and its content slots | **Chat**: "Talk to ChatGPT; the composer is at the bottom, and replies appear above." Reached from the Home screen via New chat or a ‹a chat title› row |
| **Panel** | A sheet, menu or dialog that opens *over* a screen: what opens it, what it offers, how to close it | **Add** (opened by "+" on Chat): Camera, Photos, Files, Connectors, Create image, … |
| **Control** | The label (app's own words, plus the string resource id), role, icon hint, region, **effect**, **risk** and **selector bundle** | "+" · content description "Add photos and files" · effect *opens panel Add* · risk *safe* |
| **Ability** | A thing a person can do, as a short verb phrase, with the path of controls that does it | "Add a connector (app) to a chat" · path `Chat › + › Connectors › ‹pick›` · needs *in a chat* · ends with *a choice* |
| **Fact** | A small learned truth that helps planning | "Temporary chats are not saved to history"; "Connectors need a plan that supports them" |

- **Effects** are a fixed set: `navigate`, `reveal` (opens a panel), `edit` (focus or type), `toggle`, `choose`,
  `send`, `pay`, `delete`, `grant`, `sign-in`, `external` (leaves the app).
  - The last six keep today's approval boundaries (GATE and `MapperDoorRisk`).
  - The manual can *describe* "Delete chat" so that a goal like "delete that chat" finds it; it never walks it, and a
    task still asks.
- **Selector bundle:** resource id, string resource id, content description, role, class, region and relative
  position.
  - The walker resolves them in that order and needs two to agree.
  - This is Fast Path's semantic-selector-first rule, made durable across app updates and languages.
- **Abilities** carry about 8–15 **paraphrases**, written once when the ability is created ("attach a plugin", "use
  an app in ChatGPT", "link GitHub", "koppel een app", …). Matching a goal is then cheap and works in any language
  the owner speaks. Search engines call this write-time query expansion.

### 4.1 The manual as text (what any AI reads)

The same data renders as a compact Markdown manual. This is what the Mind, Claude or Codex receive. The excerpt below
is an **illustration of the format**; the real one comes from a pass.

```
# ChatGPT (com.openai.chatgpt) · version 1.2026.265 · mapped 2026-09-28 · look only
## Screens
- **Home / Chat** — talk to ChatGPT. Composer at the bottom: + (Add), text field, Voice, Send.
  - Top bar: ☰ Sidebar · model picker ("ChatGPT") · New chat · ⋯ More
- **Sidebar** (panel, from ☰) — Search chats, New chat, GPTs, ‹a chat title› rows, your name → Settings.
- **Settings** — Personalization, Data controls, Voice, Security (never walked), Log out (never walked).
## Panels
- **Add** (from + on Chat) — Camera · Photos · Files · Connectors · Create image · Deep research · …
## Abilities
- Add a connector to a chat → Chat › + › Connectors › ‹pick one›   [walked · 0.92]
- Attach a file to a chat → Chat › + › Files › ‹system picker›      [walked · 0.95]
- Start a temporary chat → Chat › Temporary (top bar)              [seen · 0.7]
- Delete a chat → Sidebar › ‹a chat title› (long press) › Delete   [described, never walked · asks you]
```

## 5. How a pass builds the manual (mapper v3)

1. **Observe.** As today, plus the chrome split (§3): the app's own words are kept with their string resource ids,
   and content becomes typed slots.
2. **Reveal.** New: the mapper opens **revealers** (the "+" button, the ⋯ overflow, the ☰ drawer, the model picker,
   tabs) and records the panel as part of its screen.
   - A reveal is safe only if two things are proven: a panel appeared over the **same** underlying screen (same
     screen fingerprint under the panel), and Back closed it again.
   - Items *inside* a panel are walked only if they navigate (not choose, toggle or send) and pass `MapperDoorRisk`.
     Otherwise they are recorded as "described, not walked".
3. **Describe.** One model call per new screen (panels are batched in with their screen) fills the §4 schema.
   - **Input:** the app name, the screen's chrome words with their regions, the typed slots, the controls with
     effects, and the parent screens.
   - **No screenshot by default.** A masked screenshot, with content regions blacked out, is an owner setting
     (§12).
   - **Output:** strict JSON checked against the schema, then the echo check (§3). A failed field falls back to the
     app's own words, never to "Screen".
   - **Cost:** about 1–2k tokens per screen, and a whole app for well under a cent on a small model.
4. **Abilities.** The same call proposes abilities from the controls and panels, each tied to a real path.
   - Abilities start as *seen*. They become *walked* when the pass or a later run completes the path, up to the last
     safe step.
   - Anything a run or "Teach on the phone" proves is added or confirmed. This is the existing Learn engine, which now
     writes into the manual.
5. **Keep it fresh.**
   - When the app's version changes, the manual is copied forward as *unverified*.
   - The next use of each path checks it and fixes a broken selector by looking the control up again by its
     description and string id.
   - "Map again" re-checks only what changed (a diff pass), which is much faster than a full pass.

## 6. How agents use it: rapid navigation in three tiers

| Tier | Who decides | When | Cost |
|---|---|---|---|
| **0 — Index + walker** | No model | The goal matches one ability clearly (score ≥ 0.8 and a margin ≥ 0.3 over the next), and its path is navigate/reveal only | About 0 tokens, 1–3 s |
| **1 — JEV picks** | JEV (a typed choice with calibrated confidence) | Two to five abilities are close. JEV is asked "which of these fits the goal?", with "none" as a choice | About 0.2 s, nearly free |
| **2 — Mind with an excerpt** | The Mind | Anything else, and every choose/send/pay step | The Mind gets 3–5 relevant manual lines instead of the 40-line map dump |

- **The index lives on the phone:** BM25 over ability names, paraphrases, screen and panel names, and the app's
  words. There is no network call and no new dependency.
  - An embedding index is optional later, if the Lab shows lexical matching misses too often.
- **JEV starts watch-only**, exactly as in Drive (plan 32):
  - its pick is logged next to what the Mind chose and never used;
  - the Lab and Glass show agreement, speed, and accuracy when it was sure;
  - it is promoted to deciding Tier 1 only after the numbers earn it (≥ 95% agreement when confident, over at least
    200 decisions);
  - even then it only picks among navigation abilities, and never approves or chooses content.
- **New Mind tool, `find`:** "find how to ‹goal› in ‹app›" returns the top abilities with their paths and
  confidence. `go_to` gains `go_to(ability)`, which walks an ability's path the way it walks to a screen today.
- **Shortcuts first:**
  - when an app exposes a deep link or intent for a screen (found in its manifest or in the owner's runs), the manual
    records it;
  - `phone.open_app` / intent landing already comes first in Cyclone's order, and the manual makes it discoverable.
- **Unchanged rules:**
  - every tap still goes through PhoneToolExecutor, with a fingerprint check after each step;
  - the first surprise hands control back to the Mind, and the ability's confidence drops;
  - approvals for pay, send, delete, permission and sign-in are unchanged.

## 7. Glass

- **Map tab:**
  - places show their real names ("Chat", "Sidebar", "Settings › Data controls"), with a one-line purpose under
    each;
  - panels hang off their screen instead of being separate boxes;
  - zones get real names instead of "Screen, Screen, Screen".
- **Place inspector:**
  - *What it's for*;
  - *Controls*, grouped by region, each with its effect and risk;
  - *Panels*;
  - *Abilities that start here*;
  - Facts (today "None recorded").
- **New Abilities tab, "What you can do in ChatGPT":**
  - a search box ("try: add a connector");
  - each ability with its path drawn on the map, its provenance and its confidence;
  - **Try it**, which walks the safe part on the phone.
- **Export manual** gives the §4.1 Markdown to copy into any AI.
- **Agent MCP:** a read-only `app_manual(app, query?)` tool, so Claude or Codex on the PC can ask "how do I … in
  ChatGPT?". It returns manual text and never content.

## 8. Measuring it: the Lab "find the feature" suite

- **The intents:** 30 plain-language intents per app across 5 apps (ChatGPT, Gmail, Chrome, WhatsApp, Settings).
  - Each has the ground-truth path, written once by hand.
  - Examples: "add a plugin to this chat", "turn on dark mode", "find the chat about taxes" (search, not a row),
    "attach a photo".
- **Measured:**
  - top-1 and top-3 ability match;
  - task success, taps, seconds, model calls and tokens per intent;
  - JEV agreement and calibration.
- **Arms compared:**
  - Mind alone (today);
  - Mind + map card (A37);
  - Mind + manual excerpt;
  - Tier 0/1/2 routing.
- **Privacy:** the canary scan (§3) runs in every suite, and any hit fails it.
- **Targets for M3:**
  - ≥ 85% top-1 ability match;
  - ≥ 90% success on navigation intents;
  - ≥ 60% fewer model calls than Mind alone;
  - 0 canary hits.

## 9. Safety (CI-guarded where marked)

- **Never kept:** content. Only the app's own words and typed slots are stored. *(guard: canary, echo check)*
- **Only checked words:** the describer never receives text that failed the chrome tests, and no screenshot unless
  the owner turns it on. *(guard)*
- **Look-only passes** open only revealers and navigation. They never choose, toggle, send or sign in. *(guard, via
  `MapperDoorRisk`)*
- **The manual is advice.** Walks verify each step, stop at the first surprise, and keep every approval boundary.
- **JEV is watch-only** until promoted. Even then it never approves, sends or chooses content. *(guard)*
- **The MCP tool is read-only** and returns manual text only. *(guard)*

## 10. What changes in the code

- **`brain/graphv2/AtlasContracts.kt`:**
  - `AtlasPrivacy` gains `AppLexicon` and `ChromeFilter`;
  - `coarseStructure` stays only as the last fallback;
  - the canary guard moves to CI.
- **`mapping/crawl/`:**
  - `MappingStructuralProjection` keeps chrome labels and string ids, and classifies effects;
  - new `Revealer` and panel detection;
  - `SafeMapperWalker` gains the reveal step.
- **New `manual/`:**
  - `AppManual` (store, schema, versions, provenance);
  - `ManualDescriber` (one model call per screen, JSON schema, echo check);
  - `AbilityIndex` (BM25, paraphrases);
  - `ManualRenderer` (Markdown, Mind excerpts).
- **`mind/`:**
  - the `find` tool and `go_to(ability)`;
  - the map card is replaced by excerpts;
  - Learn writes abilities.
- **`voice/JevShadow.kt`:** generalised into a shared `Jev` client with a second watch-only question, "which ability
  fits?".
- **Gateway:** `manual.get`, `manual.search` and `manual.export` ops, plus the read-only agent MCP tool.
- **Glass:** Map tab names and panels, the inspector, the Abilities tab, and Export.
- **Lab:** the find-the-feature suite and the JEV ability tally.

## 11. Releases

| Release | Contents | Exit criteria |
|---|---|---|
| **M1: Real names and panels** | App lexicon and the chrome filter; labels and string ids kept; effects; revealers and panels; real screen, panel and zone names in Glass; canary guard | A ChatGPT pass shows "Chat", "Sidebar", "Add" (from +) and "Settings" with their controls; 0 canary hits |
| **M2: The manual and abilities** | Describer (JSON schema, echo check); abilities with paraphrases and provenance; Learn and "Teach on the phone" write abilities; Abilities tab; Export; `app_manual` MCP tool | "Add a connector to a chat" is found and its path walked to the Connectors panel; the manual exports as Markdown |
| **M3: Rapid navigation** | Ability index; Tier 0 walks; `find` and `go_to(ability)`; Mind excerpts; JEV ability watching; Lab find-the-feature suite; diff passes; self-healing selectors | The §8 targets on the owner's phone, stated honestly |

The recommendation is to build these next, before parallel sessions: every task and routine on the phone gets faster
and more reliable from it. Plan 35 has them as runs 3–5. JEV's promotion for abilities follows the Lab numbers,
exactly like Drive.

## 12. Decisions for the owner

- **The promise changes** from "structure only" to "the app's own words only; never your content" (§3). This plan
  assumes yes.
- **Describer model:** the phone's configured model (default, cheap), or the PC runtime's, for owners who map from
  Glass.
- **Masked screenshots for the describer:** off by default. With them on, icons without labels are named better, but
  a masked picture of your screen goes to the model provider.
- **Sharing manuals** between your phones through the Command Center is in scope for M2. They hold no content. A
  public manual library, as marketplace cards, is later and needs its own decision.
