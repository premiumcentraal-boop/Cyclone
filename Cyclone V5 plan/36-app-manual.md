# 36 — The App Manual: a map builder that understands apps by itself

**Status:** plan, 2026-09-28, with the owner's decisions (§0). Three build runs (M1–M3, §13). Builds on the mapper
(plans 06, 20, 22), one map and grounded skills (23), the Mind's `go_to` (A37) and JEV watching (32).

**The owner's ask:**
> "Rethink how we can optimally write down each screen and function in it so that any AI can easily pick up on what
> it needs from human language. … that plus button in ChatGPT lets me also add a plugin to a chat. … For chats it
> should only think of how these chats are placed from top to bottom … if I want to find a specific chat I can search
> for the keywords. Keep it factual, not mapping every contact name, just how they are listed and how to find them.
> Also categories, like in Instagram people with subcategories: followers, following, DM general, DM primary, close
> friends. The system should just get these. These are only examples. Map out how to build the system that is
> intelligent enough to do this smart map building by itself."

**In one sentence:** a mapping pass stops writing "Screen → Menu → Screen" and writes an **App Manual**. It covers
every screen, panel, list and category in the app's own words; how each list is ordered and how to find one thing in
it; and an index of **things you can do** with the verified taps that do them. A **smart mapper** builds it without
app-specific rules. It recognises common UI patterns, asks itself what it doesn't know yet, runs small safe
experiments to find out, and quizzes itself at the end to find the gaps.

---

## 0. Decisions (owner, 2026-09-28)

- **Privacy: yes.** The promise becomes "Cyclone keeps the app's own words, never your content" (§4).
- **Model selector on the PC.** The mapping start sheet in Glass has a model picker.
  - **Default: the phone's current model** (the model selected in the phone's settings, `openrouter_model`), resolved
    when the pass starts.
  - The model's key stays on the phone; the PC only chooses which model.
- **Screenshots: an on/off switch** with one short, human note (§8). Off by default.
- **Lists are described by how they work, not by what's in them.** The manual records the list's order, its groups,
  how to find one item and what opening one shows. It never records the entries themselves.
- **Categories are found automatically:** tabs, segments, filters and sub-categories (Instagram's Followers /
  Following, DMs Primary / General / Requests, Close friends), with no app-specific code.

## 1. Why the map is vague today (found in the code)

In the ChatGPT pass in the screenshots, 12 places are all called "Screen · Learned app screen", and the doors are
"Menu", "Search", "Back". Five things cause this:

1. **Every name is squeezed into 33 fixed words.** `AtlasPrivacy.coarseStructure` (`brain/graphv2/AtlasContracts.kt`)
   maps any screen or control name onto a frozen list (Home, Search, Menu, Settings, …). Anything else becomes
   "Screen", "Learned app screen" or "Control". The goal was never to keep a chat title or a name, but the app's own
   words are thrown away too.
2. **The mapper drops labels at the door.** `MappingStructuralProjection` (`mapping/crawl/AndroidMapperPorts.kt`)
   reads each control's label, description, resource id and role. It keeps only a hashed key and one of 11 door
   kinds. A "+" described as, say, "Add photos and files" matches no kind and becomes `CONTENT_ROW`.
3. **Screen purpose is almost always UNKNOWN.** `inferPurpose` knows only LOGIN, MENU and LIST.
4. **Panels, lists and categories don't exist as ideas.**
   - The "+" sheet, ⋯ menus and dialogs are separate "places" or never opened.
   - A list is only "some content rows". Tabs are only "TAB doors".
5. **Nothing says what a thing is for.** The Mind's map card (`mind/map/MindMap.kt`) is `s3 Screen: "Menu" → s4`,
   capped at 40 lines. It can route to a screen it can name, but it cannot answer "where do I add a plugin?".

## 2. What we want, in two examples

**ChatGPT: "Add the GitHub connector to this chat."**
1. The goal is looked up in ChatGPT's **ability index**. The top hit is *"Add a connector (app) to a chat"* (score
   0.91; the next is *"Attach a file"*, 0.44).
2. Its path is `Chat › + (Add) › Connectors › pick one`.
3. The walker opens a chat, taps "+", checks that the **Add** panel appeared, then taps **Connectors**. There is no
   model call so far, and every step is checked against the screen.
4. Only the final pick goes to the Mind, with two lines from the manual.

That is about 1 model call instead of 6–10.

**Instagram: "Open my chat with Sam."**
1. The manual says:
   - *Messages* is a list with the categories **Primary · General · Requests**, newest first;
   - a **Search** field at the top finds a chat by a person's name.
2. The walker opens Messages and taps Search. The Mind types "Sam" (a normal task, with its usual rules) and picks
   the result.
3. The manual never knew Sam existed; it only knew how to find anyone.

## 3. What the manual contains

One manual per **app × version** (the Versions tab already tracks versions). Every entry carries **provenance** (seen,
probed, walked, used in a run, taught by the owner, inferred) and a **confidence**.

| Entry | What it holds | Example |
|---|---|---|
| **Screen** | The name in the app's words, one line on what it's for, how you get there, its regions | **Chat** (ChatGPT): "Talk to ChatGPT. Composer at the bottom, replies above." |
| **Panel** | A sheet, menu or dialog over a screen: what opens it, what it offers, how to close it | **Add** (from "+" on Chat): Camera · Photos · Files · Connectors · … |
| **Control** | Label (app's words + string resource id), role, region, **effect**, **risk**, selector bundle | "+" · opens panel Add · safe |
| **List** | What one item is (a typed slot), layout, **order**, **groups**, rough size, **how to find one**, what tapping or long-pressing one does | Chat list: ‹a chat›, newest first, grouped *Today / Yesterday / Previous 7 days*; find one with *Search chats* |
| **Categories** | A set of views of the same place (tabs, segments, chips, filters), their sub-categories, and which list each one shows | Messages › **Primary · General · Requests**; Profile › **Followers · Following** |
| **Ability** | A thing a person can do, as a verb phrase, with the path that does it and 8–15 other phrasings | "Add a connector to a chat" → `Chat › + › Connectors › ‹pick›` |
| **Fact** | A small learned truth for planning | "Temporary chats are not saved to history" |

- **Effects** are a fixed set: `navigate`, `reveal` (opens a panel), `switch` (changes category), `scroll`, `edit`,
  `toggle`, `choose`, `send`, `pay`, `delete`, `grant`, `sign-in`, `external`.
  - The last six keep today's approval boundaries (GATE and `MapperDoorRisk`).
  - The manual may *describe* "Delete chat" so that a goal can find it, but a pass never walks it and a task still
    asks.
- **Selector bundle:** resource id, string resource id, content description, role, class, region and relative
  position. The walker needs two of them to agree. Because the string id is in the bundle, a path still works after
  the phone's language changes.
- **Lists give positional abilities for free.** "Open the newest chat" means the first item, and "the third chat"
  means position 3. That works because the order is known, not the entries.

### 3.1 The manual as text (what any AI reads)

The same data renders as compact Markdown for the Mind, Claude, Codex or any AI. The excerpt below is an
**illustration of the format**; a real one comes from a pass.

```
# Instagram (com.instagram.android) · 402.0 · mapped 2026-09-28 · look only
## Screens
- **Home feed** — posts from people you follow. Top: Instagram logo, ♡ Notifications, ✉ Messages.
  Bottom bar: Home · Search · + Create · Reels · Profile.
- **Messages** — your direct messages.
  - Categories: **Primary · General · Requests** (switch at the top).
  - List: ‹a chat› rows, newest first; unread shows a dot. Long list, loads more as you scroll.
  - Find one: **Search** at the top matches a person's name or username.
  - Tap a row → **Chat** (‹a person›). Long-press → menu: Mute · Delete (never walked) · …
- **Profile** — your posts and account.
  - Categories: **Posts · Reels · Tagged**. Header links: **Followers**, **Following** (numbers are never kept).
- **Followers / Following** (one screen, categories **Followers · Following**) — ‹a person› rows.
  Find one: **Search** at the top. Row button: Remove / Following (never walked).
- **Close friends** (Settings › Close friends) — ‹a person› rows with a tick; Search at the top.
## Abilities
- Open a chat with someone → Messages › Search › ‹their name› › pick           [walked · 0.9]
- See message requests → Messages › Requests                                  [walked · 0.95]
- Open my newest chat → Messages › Primary › item 1                           [walked · 0.9]
- See who follows me → Profile › Followers                                    [walked · 0.95]
- Edit close friends → Profile › ☰ › Close friends                            [seen · 0.7 · changes ask you]
```

## 4. Privacy: the app's own words, never your content

A text is kept verbatim only if it is **the app's own words (chrome)**. Everything else is **content**. Content
becomes a typed slot (‹a chat›, ‹a person›, ‹a post›) or a *shape* ("a relative time", "a number"). The value itself
is never kept.

**What counts as chrome** (one test must pass):
- **In the app's own dictionary.** Cyclone reads the installed app's string resources
  (`PackageManager.getResourcesForApplication`) and builds a per-version **lexicon** of every string shipped in the
  APK, in the phone's language, stored as normalised hashes. "Primary", "Followers", "Search chats" and "Previous 7
  days" are in the APK. "Sam" and "Dinner plans" never are.
- **Stable in a chrome slot**, for words an app downloads instead of shipping. All of these must hold:
  - it sits in a chrome region: an app bar, tab bar, category strip, sheet or dialog title, section header or menu
    item;
  - it is outside list rows;
  - it had the same text in the same slot on two passes on different days.

**Never chrome:**
- list rows;
- editable fields and their values;
- numbers next to labels ("1,234 followers" keeps only "Followers");
- anything `AtlasPrivacy` flags (emails, tokens, codes);
- anything in a secure window or password field.

**What content may leave behind: shapes, never values.** Examples: "each row has a name, a line of preview text and a
relative time"; "sorted newest first" (§6.3); "unread rows show a dot".

**Guarded, not promised:**
- **Canary.** Seeded canary content (a chat titled `CANARY-7781 lighthouse`, a contact named `Canary Qx`) must never
  appear in the manual, the database, Glass, diagnostics, or anything sent to the model. CI and every Lab suite check
  this.
- **Echo check.** Each model-written sentence is compared with the observation's content text. Any run of 3 or more
  content words, or any content token of 5 or more characters, rejects the sentence.

## 5. The smart mapper: how it builds the map by itself

The mapper works like a careful person learning a new app. It looks, recognises what kind of thing each part is,
wonders what it doesn't know, tries something harmless to find out, writes it down, and moves on to the most useful
unexplored part. None of it is written for one app. It generalises because it reasons about **UI patterns**, which
every app shares, rather than about apps.

### 5.1 The loop (one screen)

```
 perceive ─► split chrome/content ─► recognise patterns ─► hypothesise ─► probe ─► record ─► choose next
    ▲                                                        (model)       (safe)                  │
    └──────────────────────────────────────────────────────────────────────────────────────────────┘
```

1. **Perceive.** The accessibility tree, grouped into regions (top bar, category strip, body, bottom bar, floating
   button, sheet). If screenshots are on (§8), a **masked** screenshot is taken: every content region is painted solid
   before the picture leaves the phone.
2. **Split** chrome from content (§4).
3. **Recognise patterns** (§5.2): cheap, deterministic detectors label the regions with evidence and a confidence.
   For example: "bottom navigation, 5 items", "category strip, 3 segments, Primary selected", "vertical list,
   ‹row›×14", "search field (hint: Search)".
4. **Hypothesise (the model).** One call per **new screen template** receives:
   - the pattern-annotated skeleton, which is chrome words, slots, shapes and pattern labels, never raw content;
   - the parent screens, and the masked screenshot if on.

   It returns strict JSON with the screen's name and purpose, each region's role, draft abilities, and **questions
   it can't answer yet**, each paired with a probe that would answer it:
   - "Does 'General' show a different list?" → switch category;
   - "What does the ✦ icon open?" → reveal;
   - "Is this list newest first?" → read row shapes, scroll one page;
   - "What does a row open?" → open one sample.
5. **Probe (the experiments).** Each probe is a safe action with a **predicted outcome**. The result confirms or
   refutes the prediction. The allowed probes:
   - **reveal**: open a panel, check that the screen under it is the same, then press Back;
   - **switch**: pick another category and check that the frame stayed and the list changed;
   - **scroll**: scroll the list one or two pages, reading row shapes and group headers;
   - **sample**: open the first item, learn the detail template, press Back;
   - **long-press reveal**: open a row's menu, record its items, press Back (items are described, never walked).

   Probes on the owner's own account never type, choose, toggle, send, follow or sign in. They never type in search
   either, because apps save search history. Search is described from its hint and position. On a **test account**
   (plan 22), a search probe with a nonsense word is allowed, to learn the results template.
6. **Record.** Findings go into the manual with provenance. A refuted hypothesis corrects the draft, and a second
   model call per screen (batched) revises it after the probes.
7. **Choose next** (§5.4).

### 5.2 The pattern library (generic, tested on many apps)

About 20 detectors, each pure and fixture-tested on recorded skeletons from at least 10 different apps:

| Pattern | Evidence it looks for | What it adds to the manual |
|---|---|---|
| App bar | Top region, title, icon buttons | Screen name, top actions |
| Bottom navigation / tab bar | 3–5 same-role selectables at the bottom, one selected | The app's main sections |
| **Category strip** (tabs, segments, chips, filters) | ≥ 2 sibling selectables in one container, one selected, above a body region | A category set, and each category's list (§6.2) |
| Navigation drawer / sidebar | A panel from the edge, with a list of destinations | A panel with sections |
| Overflow / "+" / FAB | An icon button with a reveal-like description, or a floating button | A revealer (probe it) |
| Sheet, menu, dialog | A new window over the same frame | A panel |
| **List / grid / feed / carousel** | Repeated same-shaped rows in a scroll container | A list (§6.1) |
| Section header | Chrome text between groups of rows | A list group ("Today", "Pinned") |
| Search field | An editable field with a search hint or icon, or a search button | "How to find one" |
| Index scroller | A column of letters beside a list | "A–Z, jump by letter" |
| Detail view | Reached from a list row, one large item, back arrow | An item template (‹a chat›) |
| Composer | An editable field at the bottom with a send button | "Write and send" (send always asks) |
| Settings list | Rows with switches or chevrons | A settings tree (toggles never walked) |
| Form / wizard / stepper | Labelled fields, Next or Continue | A form (typed slots only) |
| Empty state | An illustration and one call to action | A fact ("empty until you …") |
| Login wall, paywall, upsell | Sign-in or plan words from the lexicon | A boundary (the pass stops there) |
| Media player | Play/pause/seek controls | Player controls |
| Picker (files, photos, contacts) | A system or in-app chooser | "The pick is yours" (the Mind or the owner chooses) |

- **No app-specific rules.** A CI guard fails if the mapper or manual code names an app package.
- Every new pattern is added with fixtures from several apps, never tuned to one.

### 5.3 Templates, not copies

200 chat rows are **one** template, ‹a chat›, and 200 chats open **one** screen, *Chat (‹a chat›)*. The structural
fingerprint already groups screens with the same shape. The manual adds the template's parameter (which slot
changes) so that "open my chat with Sam" resolves to *Chat* reached through the list's "how to find one".

### 5.4 Choosing what to explore next

Each unexplored thing gets a **value**:
- how much of the app it probably opens;
- whether it answers an open question;
- whether it is a new pattern;

minus its cost and its risk. The mapper then takes the best one. In practice:
1. revealers and category strips on known screens first (cheap, and they uncover the most);
2. then navigation to unseen sections;
3. then one sample per list;
4. never a second copy of a template.

A section stops when new probes stop adding anything (saturation). The budget chips (10 min, 30 min, …) still cap
the whole pass.

### 5.5 The self-quiz: finding its own gaps

When a pass ends, the mapper tests itself:
1. **Quiz.** The model writes about 20 plain-language goals a person might have in this app ("see message requests",
   "find a chat from last week", "turn on dark mode"). It then tries to answer each one **from the manual alone**,
   without the phone.
2. **Gaps.** A goal it can't answer, or can answer only with low confidence, becomes a target for the next pass. "Map
   deeper" then means "answer these", not "wander longer".
3. **Contradictions.** A review call reads the whole manual for conflicts ("Search is on Messages" vs "Search is in
   Chat") and marks them for re-checking.

The report in Glass shows how many quiz goals the manual can answer: "Answers 17 of 20 goals · 3 to explore".

### 5.6 Learning after the pass

- **Runs teach it.** A task that succeeds writes or confirms its path as an ability (the Learn engine, now writing
  into the manual). A task that hits a surprise lowers the confidence and queues a re-check.
- **Teach on the phone** records the owner's own taps as an ability with the owner's wording.
- **App updates.** The manual is copied forward as *unverified*. Each path is checked on its next use, and a broken
  selector is found again by its description and string id. "Map again" re-checks only what changed.

## 6. Lists and categories in detail

### 6.1 Lists: how they work, not what's in them

For each list, the mapper records:
- **Item:** a typed slot named from the context (‹a chat›, ‹a person›, ‹a post›, ‹an email›), plus the row's
  *shape* ("name, preview line, relative time, unread dot").
- **Layout:** vertical list, grid, feed or carousel.
- **Order** (§6.3): newest first, oldest first, A–Z, the app's own ranking, or unknown.
- **Groups:** section headers in the app's words, in order ("Pinned", "Today", "Yesterday", "Previous 7 days").
- **Size:** a coarse size ("a few", "tens", "long, loads more as you scroll"), never a count of your items.
- **How to find one**, listing what exists:
  - search, with its hint and where it is;
  - an A–Z scroller;
  - filters or categories;
  - "scroll to it" as the last resort.
- **What a row does:** tap → which template. Long-press → which menu, with its items described and never walked.
- **Positions:** "item 1 is the newest", so positional goals work.

### 6.2 Categories and sub-categories, found automatically

- **What makes a category set:** a category strip (§5.2) where a **switch** probe keeps the screen frame and changes
  the list region. Header links that open the same screen with a different category selected (Instagram's Followers
  and Following) are recognised by the same probe: the same template is reached, with a different selection.
- **The tree:** the mapper nests what it finds as `Screen › Category set › Category › List`, and a category set found
  inside a category becomes a sub-category. Instagram's Messages might be `Messages › Primary | General | Requests`,
  with a category set like *Unread* inside one of them. Close friends is found as its own screen under Settings.
- **Names** are the app's words (from the lexicon). Numbers are dropped.
- Each category gets its own list entry (order, how to find one), because categories often differ. Requests, for
  example, may have no search.

### 6.3 Knowing the order without keeping anything

Order comes from the **shapes** of what the rows show, read and then discarded:
- **Time shapes** ("2m", "3h", "Yesterday", "Mon", dates) are parsed as ages. If they only ever grow down the list,
  the order is **newest first**.
- **First letters** are compared. If they never go backwards, the order is **A–Z**.
- **Group headers** from the lexicon ("Today", "Earlier") confirm the time order.
- **Pinned** sections are recognised by their header, or by a pin icon, and noted as "pinned first".

Only the conclusion is stored ("newest first, pinned first"), and the ages and letters are thrown away. If there is
no clear signal, the order is written as "the app's own order".

## 7. How agents use the manual: rapid navigation in three tiers

| Tier | Who decides | When | Cost |
|---|---|---|---|
| **0 — Index + walker** | No model | One ability matches clearly (score ≥ 0.8, margin ≥ 0.3 over the next) and its path is navigate, reveal or switch only | About 0 tokens, 1–3 s |
| **1 — JEV picks** | JEV (a typed choice with calibrated confidence) | Two to five abilities are close. JEV is asked "which of these fits the goal?", with "none" as a choice | About 0.2 s, nearly free |
| **2 — Mind with an excerpt** | The Mind | Anything else, and every type, choose or send step | 3–5 relevant manual lines instead of the 40-line map dump |

- **The index lives on the phone:** BM25 over ability names and paraphrases, screen, panel, list and category names,
  and the app's words. Paraphrases are written once when an ability is created, in several languages, so matching is
  cheap. There is no network call and no new dependency.
- **JEV starts watch-only**, as in Drive (plan 32):
  - its pick is logged next to the Mind's and never used;
  - it is promoted only after the Lab numbers earn it (≥ 95% agreement when confident, over at least 200
    decisions);
  - even then it picks only among navigation abilities, and never approves, types or chooses content.
- **New Mind tools:**
  - `find(goal)` returns the top abilities, with their paths and confidence;
  - `go_to(ability)` walks an ability's path the way `go_to` walks to a screen today;
  - `how_to_find(list)` returns a list's search, filters and order.
- **Shortcuts first:** deep links and intents the app exposes are recorded, and `phone.open_app` / intent landing
  already comes first.
- **Unchanged rules:**
  - every tap goes through PhoneToolExecutor, with a check after each step;
  - the first surprise hands control back to the Mind;
  - the approval boundaries are unchanged.

## 8. Settings: the model and screenshots

**In Glass, on the mapping start sheet** (remembered per app, with a default in Glass settings):

> **Model** [ Phone's model · Claude Fable 5.1 ▾ ]
>
> **Use screenshots** [ off ]
> Better results. Your chats and names are blacked out, but a picture of the app goes to the AI.

- **The model list** comes from the phone: a new `models.list` op returns the phone's `ModelRegistry` profiles and
  whether each one reads images.
  - The first entry is always **Phone's model**, showing its current name. The phone resolves it from
    `openrouter_model` when the pass starts.
  - The choice is sent with `mapping.start` as `describer: { model: "phone" | <cycloneId>, screenshots: bool }`.
  - Keys never leave the phone.
- **A model that can't read images** keeps the switch off and shows "This model can't read pictures."
- **Masking** paints every content region solid before a screenshot leaves the phone, using the same chrome/content
  split. The echo check (§4) still applies to what the model writes back.
- **On the phone,** App Maps settings shows the same two choices for passes started there.

## 9. Glass

- **Map tab:**
  - real screen names with a one-line purpose under each;
  - panels hang off their screen;
  - lists show as one card ("‹a chat› · newest first · Search");
  - category sets show as tabs on the card;
  - zones get real names.
- **Place inspector:**
  - *What it's for*;
  - *Controls*, by region, with their effect and risk;
  - *Panels*;
  - *Lists* (order, groups, how to find one);
  - *Categories*;
  - *Abilities that start here*;
  - Facts.
- **Abilities tab, "What you can do in Instagram":**
  - a search box ("try: see message requests");
  - each ability with its path on the map, its provenance and confidence;
  - **Try it**, which walks the safe part on the phone.
- **Report:**
  - "Answers 17 of 20 goals · 3 to explore" (§5.5);
  - **Map deeper** explores exactly those.
- **Export manual** gives the §3.1 Markdown.
- **Agent MCP:** a read-only `app_manual(app, query?)` tool. It returns manual text, never content.

## 10. Measuring it: the Lab suites

- **Find the feature:**
  - 30 plain-language goals per app across 5 apps (ChatGPT, Instagram, Gmail, WhatsApp, Settings), each with its
    ground-truth path written once by hand;
  - measured: top-1 and top-3 match, success, taps, seconds, model calls and tokens;
  - compared: Mind alone, Mind + map card (A37), Mind + manual, and the tiered routing.
- **Map quality:** for each app, a hand-written checklist of the lists (with their order and search), the category
  sets (with sub-categories) and the key panels. Scored as the share the pass found correctly, with and without
  screenshots, and per model.
- **JEV:** agreement and calibration on ability picks.
- **Privacy:** the canary scan in every suite. One hit fails the suite.
- **Targets for M3:**
  - ≥ 85% top-1 ability match;
  - ≥ 90% success on navigation goals;
  - ≥ 60% fewer model calls than Mind alone;
  - ≥ 80% map-quality score;
  - 0 canary hits.

## 11. Safety (CI-guarded where marked)

- **Never kept:** content. Only the app's own words, typed slots and shapes are stored. *(guard: canary, echo
  check)*
- **Only checked words** go to the model. Screenshots are sent only when the switch is on, and always masked.
  *(guard)*
- **Look-only probes** are reveal, switch, scroll, sample, long-press reveal and back. They never type, choose,
  toggle, send, follow or sign in. *(guard, via `MapperDoorRisk`)*
- **No app-specific rules** in the mapper. *(guard)*
- **The manual is advice.** Walks check every step, stop at the first surprise, and keep every approval boundary.
- **JEV is watch-only** until promoted, and never approves, types or chooses content. *(guard)*
- **The MCP tool is read-only** and returns manual text only. *(guard)*

## 12. What changes in the code

- **Phone, `brain/graphv2/AtlasContracts.kt`:**
  - `AtlasPrivacy` gains `AppLexicon`, `ChromeFilter` and `ContentShapes`;
  - `coarseStructure` stays only as the last fallback.
- **Phone, `mapping/crawl/`:**
  - `MappingStructuralProjection` keeps chrome labels and string ids, and groups regions;
  - new `patterns/` (the detectors), `Probe` (reveal, switch, scroll, sample, long-press) and
    `ExplorationPlanner` (§5.4);
  - `SafeMapperWalker` runs probes with predicted outcomes.
- **Phone, new `manual/`:**
  - `AppManual` (store, schema, versions, provenance);
  - `ManualDescriber` (hypothesise and revise calls, JSON schema, echo check, masked screenshots);
  - `SelfQuiz`;
  - `AbilityIndex` (BM25, paraphrases);
  - `ManualRenderer` (Markdown, Mind excerpts).
- **Phone, `mind/`:**
  - `find`, `go_to(ability)` and `how_to_find`;
  - excerpts replace the map card;
  - Learn writes abilities.
- **Phone, `voice/JevShadow.kt`:** becomes a shared `Jev` client, with a second watch-only question.
- **Phone ops:** `models.list`, `mapping.start` gains `describer`, and `manual.get`, `manual.search`, `manual.export`.
- **Gateway:** forwards the new ops and adds the read-only agent MCP tool.
- **Glass:**
  - the start sheet's model picker and screenshots switch;
  - Map tab, inspector, Abilities tab, report and Export.
- **Lab:** find-the-feature, map quality, JEV ability tally and the canary scan.

## 13. Releases

| Release | Contents | Exit criteria |
|---|---|---|
| **M1: The app's own words, lists and categories** | App lexicon, chrome filter and content shapes; the pattern library; reveal, switch, scroll and sample probes; lists (order, groups, how to find one) and category sets with sub-categories; real names in Glass; the model picker and screenshots switch; canary guard | On the owner's phone: ChatGPT shows Chat, Sidebar, the Add panel from "+" and the chat list "newest first · Search chats". Instagram shows Messages' Primary / General / Requests and Profile's Followers / Following. 0 canary hits |
| **M2: The describer, abilities and self-quiz** | Hypothesise → probe → revise loop; abilities with paraphrases and provenance; the self-quiz and targeted Map deeper; Learn and teaching write abilities; Abilities tab; Export; `app_manual` MCP tool | "Add a connector to a chat" and "see message requests" are found and walked; the report shows the quiz score |
| **M3: Rapid navigation** | Ability index; Tier 0 walks; `find`, `go_to(ability)`, `how_to_find`; Mind excerpts; JEV watching; the Lab suites; diff passes; self-healing selectors | The §10 targets on the owner's phone, stated honestly |

These are runs 3–5 in plan 35 (alpha.59–61), ahead of parallel sessions. JEV's promotion for abilities follows its
Lab numbers, as in Drive.

## 14. Still open

- **Sharing manuals** between the owner's phones through the Command Center: planned for M2. Manuals hold no content.
- **A public manual library**, as marketplace cards, is later and needs its own decision.
