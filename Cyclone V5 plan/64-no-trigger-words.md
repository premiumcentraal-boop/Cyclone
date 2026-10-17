# 64 · No trigger words: understanding requests and screens in any language

Status: **Plan (2026-10-16), nothing built.** (First written as plan 61; renumbered because 61 is Cyclone Safe.) Written at alpha.134 dev1. It supersedes the keyword parts of plan 42
(the grammar and the router's rules) and extends plan 58 (the Luna Decision Box). Physical device: **UNVERIFIED**.

> **Naming (2026-10-16, plan 62 §1):** "Luna" in this plan means **OpenAI Decisions** (`openai/gpt-6-luna-decisions`),
> the typed Decisions API. **GPT-6 Luna** (`openai/gpt-6-luna`) is the separate generative model, used only where
> something must be written.

**Owner's ask (2026-10-16):**
> No trigger words. People use different languages and different words. Build it the way Google or Apple would with
> today's technology.

This plan is:
- the honest inventory of what is hard-coded today (§1);
- the three rebuilds that replace it (§2–§4);
- what deliberately stays deterministic (§5);
- how we migrate without ever weakening safety (§6);
- the releases (§7) and the owner decisions (§8).

---

## 0. The answer in short

**Today Cyclone understands people and screens mostly through word lists.** There are **183 case-insensitive
keyword regexes in 69 Kotlin files**, plus a 265-line English/Dutch command grammar. A request in Spanish, a slang
phrase, or a Dutch word missing from a list falls through or, worse, is misread.

**Frontier assistants don't work that way.** They run three things:
1. a **multilingual meaning model**, fast and partly on the device;
2. a **semantic model of the screen**;
3. **one decision core that measures its own confidence and learns** from verified outcomes.

Words are data the models read, not rules the code matches.

| # | Rebuild | Replaces | In one line |
|---|---|---|---|
| **A** | **The Intent Engine** | The grammar, the router's word rules, the 6+ overlapping request routers, voice "and then" / "no, I mean" lists | Any sentence in any language becomes one typed **Request Graph** (steps, actions, slots, risks, time), from an on-device multilingual embedding cache plus Luna Decisions, with a validated slot extractor |
| **B** | **Screen Semantics** | "Is this button irreversible?" word lists, the approval gate's keyword fallback, shutter / call-button / OTP-field / submit lists | Every control gets a typed **effect** (navigate, toggle, input, submit, send, pay, delete, …) from the accessibility structure plus vision, cached per screen in the App Maps. Safety reads effects, not words |
| **C** | **One Decision Core that learns** | The scattered thresholds and hand-tuned confidence bars; the bag-of-words phone model | One cascade (on-device cache → Luna → the Mind) with **calibrated confidence per capability**, every verified outcome feeding back, and a multilingual eval harness that gates every release |

**What stays deterministic, on purpose:** structural safety floors (password fields, secure windows, money
confirmation), format parsers (URLs, e-mail addresses, digits, card-number checksums) and privacy redaction. These are
not trigger words: they don't guess meaning from vocabulary (§5).

---

## 1. Inventory: the hard-coded understanding in Cyclone today

Read on alpha.134 (`7a266ba8`). "Kind" says what the word list is trying to do.

### 1.1 Understanding the request (the core of the problem)

| Where | What it does with words | Failure it causes |
|---|---|---|
| `mind/modes/InstantGrammar.kt` (265 lines) | An English/Dutch grammar for "swipe up", "open X", "call Mam", "take a selfie", timers, alarms | Every other language, and most paraphrases, miss Instant entirely |
| `mind/modes/ModeRouter.kt` (10 regexes) | `MIND_WORDS`, `QUESTION`, `SECOND_CLAUSE`, `CHATTER`, `OPEN_ONLY`, `NOT_AN_APP`, time/date/battery questions | "open my mail" goes to the Mind (it contains "mail"); "text size bigger" goes to the Mind; "and" anywhere makes a request multi-step; "qué hora es" isn't a time question |
| `mind/modes/CycloneModes.kt` | `CANCEL` ("no", "stop", "nee", "wacht") | "para", "arrête", "warte" don't stop a call in progress |
| `voice/VoiceIntents.kt` (162 lines), `voice/VoiceQuick.kt` | Voice commands, "and then" leads, "no, I mean" corrections | Live voice only works in two languages and fixed phrasings |
| `agent/contract/PhoneIntents.kt` (12) | Alarm/timer words and duration units | "pon una alarma a las 7" isn't an alarm |
| `agent/nav/TaskClauses.kt` (11), `agent/nav/ClauseProof.kt` (7), `agent/nav/LiveTaskFacts.kt` | Splitting sentences into clauses; "dm/chat/thread with X"; proof words | Clause splitting by "and"/"en"; names after fixed prepositions only |
| `agent/contract/GoalContract.kt` (8), `agent/plan/DestinationAuthority.kt` (7), `agent/plan/TaskDifficulty.kt` | Login intent, scroll intent, "find/search/which", difficulty by words | Difficulty and goal type guessed from vocabulary |
| `fastpath/ImplicitAppRouter.kt` (8), `fastpath/FastPathLanding.kt` (5) | "near me", "directions to", "check my inbox" → which app | Implicit app choice only for the listed phrasings |
| `mind/SettingGoals.kt` (4) | "turn on / zet aan / uit", filler words | Settings requests in other languages fail |
| `runtime/background/ExecutionTargetResolver.kt` (3) | "in the background", "use profile X" | Phrasing-dependent |
| `ai/RequestIntentRouter.kt`, `ui/overlay/OverlayChromeRuntime.kt` | Another request router; matching app labels inside the request text | A sixth place deciding what a request means |

### 1.2 Understanding the screen

| Where | What it does with words | Failure it causes |
|---|---|---|
| `mind/pilot/Pilot.kt` `IRREVERSIBLE` | "send, pay, buy, delete, post, share, confirm, agree, allow, install, … verstuur, betaal, …" in a control's label = irreversible | A Spanish "Enviar" or an icon-only send arrow is **not** caught; a harmless "Share feedback" or "Confirm email address" **is** |
| `policy/GatePolicy.kt` (keyword fallback, `GateClassifier`) | Approval gates from words near a control | False SEND gates (alpha.113.dev2, .dev5). False gates teach owners to approve without reading, which is the real safety risk |
| `mind/modes/InstantRun.kt` `SHUTTER`, `CALL_BUTTON` | Finds the camera shutter and the call button by name | Icon-only shutters and other languages hand Instant up |
| `mind/PhoneMindToolbox.kt` `CODE_FIELD`, `CODE_ERROR`, `RESEND`, `SENSITIVE` | Recognises OTP fields, wrong-code errors and resend links by words | Other languages and wordings; Android already exposes better structure (§3.2) |
| `agent/nav/NavigationActionPolicy.kt` `SUBMIT`, `agent/nav/LiveNavigationScreen.kt` | "sign up / register / submit / continue / next" | Same |

### 1.3 Legitimate patterns (stay deterministic, see §5)

`MindMemory.looksSecret`, `MindRedaction`, `GatewayPrivacy`, `SkillSecrets`, `codes/CodeExtractor` (digit patterns),
URL/host/e-mail patterns, `ai/ProviderFailure` (parsing provider error bodies). These match **formats**, not meaning.
They stay, with structure added where it helps (§5).

---

## 2. Rebuild A: the Intent Engine (understanding any request, in any language)

### 2.1 The output: one Request Graph

Every surface (the Ask bar, Live voice, Drive, Glass, the Command Center, connectors) sends text, or audio
transcribed by the existing speech-to-text, to **one** engine. It returns a typed graph, never free text:

```kotlin
data class RequestGraph(
    val language: String,              // BCP-47, detected ("es", "nl", "pt-BR")
    val addressedToCyclone: Double,    // probability: chatter vs a request
    val kind: Kind,                    // DO, ASK, CHAT, CANCEL, CORRECT, CONFIRM, DECLINE
    val steps: List<Step>,             // ordered, 1..N
    val risk: RiskFlags,               // sends, money, destroys, account, writes: probabilities
    val time: TimeSpec?,               // now, at, in, every, when <condition>
    val confidence: Double,            // calibrated (Rebuild C)
)
data class Step(
    val capability: String,            // an id from the capability registry: "camera.open", "app.open", "settings.toggle"
    val slots: Map<String, SlotValue>, // typed, validated: AppRef, ContactRef, Duration, Toggle, ScreenRef, Text
    val dependsOn: Int?,               // step index ("message grandma *from that account*")
)
```

The existing pieces map onto it:
- `RunBaton`, the Triage reading, the slot plan and `TaskClauses` all become views of one graph;
- the router (`ModeRouter`) reads `steps`, `risk` and `confidence`, never the words.

### 2.2 How a sentence becomes a graph: three tiers, cheapest first

1. **On the phone, about 10 ms, works offline: a multilingual embedding cache.**
   - A small multilingual sentence-embedding model runs with LiteRT on the phone (quantised, tens of MB). It maps
     "open camera", "abre la cámara", "doe de camera open" and "the thing I take pictures with" to nearby vectors.
   - Each capability in the registry carries a handful of **exemplar** requests. So does every phrasing the owner has
     used that a later step **verified** (plan 58 §5C.2 aliases, generalised).
   - A request whose nearest neighbours agree, with a calibrated margin (Rebuild C), is decided here with **no network
     call**.
   - This replaces the grammar and the bag-of-words phone model. It is language-independent by construction: there is
     no word list to extend, only examples.
   - On phones with Android's on-device foundation model (AICore), that model can be a second on-device tier for slot
     extraction. It is optional and measured; Cyclone never depends on it.
2. **GPT-6 Luna Decisions, about 150–400 ms: the typed decision.**
   - What it decides:
     - `kind`, `addressedToCyclone`;
     - the capability per step, chosen from the registry's shortlist;
     - the risk flags, the time kind;
     - targets chosen from **candidates the phone supplies** (installed apps, contacts, on-screen controls).
   - It already reads any language. This is Triage (plan 58), generalised to the graph.
3. **Slot extraction for free values: a small generative call, validated.**
   - Some values aren't choices: "in 37 minutes", "at half past seven", a message's words.
   - Decisions can't write, so **GPT-6 Luna** (the generative model, not the Decisions API) fills a **strict JSON
     schema** for just those slots.
   - **Code validates every value:** a duration in range, a time that parses, a contact that exists. Anything invalid
     is no answer.
   - Numbers are parsed locale-aware with ICU (`android.icu`), which reads spelled-out numbers in many languages, not
     with digit-and-word regexes.
   - The Mind still writes message text and asks before sending.

**Voice:** the same engine runs on partial transcripts. `kind = CORRECT` replaces the "no, I mean" list, and
`CANCEL` replaces the stop words. Whether the sentence is complete is a yes/no answer, not "and then" matching.

### 2.3 Capabilities are data, not code

The capability registry (plan 58 §6) becomes the single list of everything Cyclone can do in one move. Each entry
carries:
- its id and its typed slots;
- multilingual exemplars;
- its effect class and risk (Rebuild B's taxonomy);
- how to do it (an Android intent, a settings panel, a gesture, a learned route, a compiled skill, a connector);
- how to verify it.

Adding a language means adding nothing. Adding a capability means adding one entry with a few examples. The
golden-set tests check it in 20+ languages.

---

## 3. Rebuild B: Screen Semantics (understanding any screen, in any language)

### 3.1 The output: every control has a typed effect

```kotlin
enum class Effect { NAVIGATE, TOGGLE, INPUT, SELECT, SEARCH, DISMISS, MEDIA, CAPTURE, CALL,
                    SUBMIT, SEND, PAY, DELETE, ACCOUNT, PERMISSION, INSTALL, UNKNOWN }
data class ControlSemantics(
    val ref: Int,                      // the numbered control (plan 58 §5A)
    val effect: Effect,
    val effectConfidence: Double,
    val sensitive: Sensitivity,        // NONE, SECRET_FIELD, OTP_FIELD, PAYMENT_FIELD
    val source: Set<Source>,           // STRUCTURE, MAP_HISTORY, VISION, MODEL
)
```

The approval gate, Instant's "never tap an irreversible control", the Pilot and the Mind all read `effect`. None of
them reads a label's words any more.

### 3.2 Where the effect comes from (cheapest first, combined)

1. **Structure, on the phone, deterministic:**
   - **What Android already tells us:**
     - `isPassword`;
     - `inputType` (number, phone, e-mail, password);
     - autofill hints (`AUTOFILL_HINT_SMS_OTP`, `AUTOFILL_HINT_CREDIT_CARD_NUMBER`, …);
     - editable vs clickable, checkable state, the view class;
     - resource ids, which are developer names and mostly language-independent;
     - `FLAG_SECURE` windows;
     - the role in the hierarchy (a FAB beside a text field in a chat, a button under a payment summary).
   - **OTP fields are found by autofill hints and input type,** not "code/verificatie".
2. **What Cyclone has seen happen (the App Maps):**
   - A control whose press was observed to lead to a "Sent" state, a payment confirmation or an item disappearing
     carries that **observed effect**, per app and screen fingerprint.
   - Learn already records transitions; this gives them an effect.
3. **Vision plus a multilingual model:** for anything unresolved, one Luna Decisions call with the marked
   screenshot:
   - the question: "for each numbered control, what happens if it is pressed?" over the fixed `Effect` list;
   - it reads icons (a paper-plane send arrow, a trash can, a shutter) and any language;
   - the answer is **cached per screen fingerprint**, so it is asked once per screen, not per tap.

### 3.3 Safety only tightens

- **A control is treated as consequential if any source says it is** (structure, map history or model): OR, never
  AND.
- **A model can never downgrade** a structural finding (a password field, a secure window, a payment field).
- **A gate corpus**, the `(screen, control, true effect)` pairs from Lab runs and traces that the 2026-10-13 handoff
  asked for, scores every change:
  - **missed consequential effects must be zero;**
  - false gates are measured and pushed down.

This fixes both failures at once: "Enviar" and the icon-only arrow are caught, and "Share feedback" stops being a
false SEND.

---

## 4. Rebuild C: one Decision Core that measures itself and learns

### 4.1 One pipeline instead of six routers

Today at least six places decide what a request means:
- `ModeRouter`;
- `RequestIntentRouter`;
- `ImplicitAppRouter`;
- `FastPathLanding`;
- `TaskDifficulty` / `DestinationAuthority`;
- `ExecutionTargetResolver`;
- plus the overlay's own label matching.

They become **readers of the one Request Graph**: the engine decides meaning once, and each component asks the graph
what it needs. Plan 58's boards (Triage, Bind, Step, Verify) are this core's questions.

### 4.2 Calibrated confidence, per capability

- Every tier reports a **calibrated** probability, not a raw score:
  - on-device neighbours: margin → probability, fitted on the eval set;
  - Luna: its probabilities, checked with reliability curves.
- Each capability has its own **acting bar**, set by what a mistake costs:
  - opening an app: low;
  - a call: higher, plus the 2-second window;
  - anything consequential: never automatic.
- Below the bar, the request **goes up a tier**, never down. This replaces every hand-tuned 0.8 / 0.9 in the code.

### 4.3 The learning loop (distillation, on the phone)

- **Every verified outcome becomes a training example,** labelled by the outcome, not the guess: Instant done and
  verified, a Flash run completed, a Mind mission finished and approved.
- **The on-device tier learns from them**, the way the phone model learns from JEV today:
  - new exemplars for the embedding cache;
  - re-fitted calibration;
  - the owner's own vocabulary in any language.

  So the share of requests decided on the phone, with no network call, grows with use.
- **Privacy:** lessons stay on the phone; the health report carries counts only (unchanged). A federated or opt-in
  shared improvement is a separate owner decision (§8).

### 4.4 The eval harness gates every release

- **Golden set v2:** 20+ languages, paraphrases, slang, code-switching ("open de camera please"), typos, voice
  transcripts with errors. Every bug adds a row.
- **The gate:**
  - **0 consequential requests routed below their label;**
  - **0 consequential controls missed;**
  - per-language accuracy at or above English's minus a fixed margin;
  - latency p95 per tier;
  - calibration error under a bound.
- **Shadow first, always:** each rebuilt part answers beside the old one (plan 58's watch) until its numbers pass.

---

## 5. What stays deterministic, and why it isn't a "trigger word"

| Stays | Why |
|---|---|
| Password, secure-window, payment-field and OTP-field protection from Android's **structure** | It is ground truth the OS gives us, not a guess from words. The model may add protection, never remove it |
| Money moves only with an owner-confirmed exact price; send/delete/post wait for approval | Product law (AGENTS.md); the trigger becomes the **effect class**, not a word |
| Format parsers: URLs, hosts, e-mail addresses, digits, card-number checksums (Luhn), IBAN checksums | These are formats, identical in every language |
| Redaction of secrets in logs and memory | Defence in depth: patterns plus structure, never fewer |
| The kill switches and the breaker | Engineering safety, not understanding |

---

## 6. Migrating without ever weakening safety

1. **Build each rebuild beside the old code, in shadow** (the plan 58 watch, generalised). Measure both on the golden
   set and live.
2. **Replace understanding first, safety last.**
   - The grammar, the router's word rules and the extra routers go as soon as the Intent Engine beats them on every
     language **and** never routes a consequential request lower.
   - The irreversible/gate word lists go last, only when Screen Semantics misses **zero** consequential controls on the
     gate corpus. Until then they keep running as an extra "OR" source, so the model can only add gates.
3. **Delete, don't deprecate.** When a word list is replaced, it is deleted. A CI guard then fails any new
   case-insensitive vocabulary regex in the understanding paths (`mind/modes`, `agent/nav`, `agent/plan`,
   `agent/contract`, `fastpath`, `voice`, `policy`, `mind/pilot`). The guard holds a short, reviewed allowlist for the
   §5 format patterns.

---

## 7. The releases

Release numbers are assigned when building starts (the alpha line is shared with other work). Plan 58's remaining two
releases fold into these.

| Release | Builds | Gate to switch on |
|---|---|---|
| **R1 · Engine in shadow** | The Request Graph and the capability registry (data, multilingual exemplars). The on-device embedding tier (LiteRT, a quantised multilingual encoder, shipped in the APK or downloaded once, measured for size and speed). Triage generalised to the graph. ICU-based slot validation. Golden set v2 (20+ languages). The vocabulary guard, in report-only mode | Engine accuracy ≥ the old router's in English and Dutch, and ≥ 90% in every other language; 0 consequential under-routes; on-device tier p95 ≤ 30 ms |
| **R2 · Engine on, words out** | The router, voice and the other routers read the graph. The grammar, `MIND_WORDS` and the rest of §1.1 deleted. The vocabulary guard enforcing. Screen Semantics in shadow: structure plus map history plus cached vision effects, and the gate corpus | R1's gate holds live for a week; the voice cancel and correct flows pass in 5+ languages on the device |
| **R3 · Screens on, learning on** | Gates, Instant and the Pilot read `effect`; the §1.2 word lists kept only as an OR source until the corpus says 0 misses, then deleted. Per-capability calibrated bars. The on-phone learning loop. Bind/Verify/chains (plan 58 §5A–5C) on the graph and effects | 0 missed consequential controls on the corpus; false gates down ≥ 50% from today; the on-phone share of decisions rising week on week |

Each release follows the release rhythm and states plainly what a real phone has and hasn't confirmed.

## 8. Owner decisions

1. **On-device model size:** ship the multilingual encoder in the APK (bigger download, works immediately) or fetch it
   once after install (smaller APK)? *Recommended: fetch once, verified by SHA-256 like the release assets.*
2. **Languages first:** the golden set v2 starts with English, Dutch, Spanish, French, German, Portuguese, Italian,
   Turkish, Arabic, Hindi, Indonesian and Japanese. Add or change any?
3. **Learning beyond one phone:** keep all learning on the phone (recommended for now), or later an opt-in, aggregated
   improvement across the owner's own phones (the VMOS fleet)?
4. **Order with plan 58:** fold plan 58's remaining two releases into R1–R3 (recommended), or finish plan 58 first?
