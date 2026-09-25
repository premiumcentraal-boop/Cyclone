# Research — the Jev + Astra hybrid, and what it means for Cyclone

**Status:** research input, not plan. No code in this repo changes because of this note.
**Written:** 2026-09-25
**Trigger:** the viral "AI beats Minecraft in 8 minutes 43 seconds for $0.97" post (eluna.ai / Ronak Malde).
**Question:** how does a fast System-1 decider (TypeSafe **Jev**) paired with a slow frontier "brain" (**GPT-6 Astra**, or Claude) actually work, and where would that split fit Cyclone's phone agent?

---

## 1. TL;DR

1. **The source is public:** [`rmalde/minecraft-agent`](https://github.com/rmalde/minecraft-agent), one commit `78b40ed`, Node + Mineflayer. The loop is about 170 lines in `nether-agent.mjs`, and the model calls are about 30 lines in `models.mjs`.
2. **What really happened is less magic than the post suggests, and it is more useful to Cyclone because of that:**
   - Jev never pressed WASD. The README says: *"This is structured-state control, not control from screenshots or individual key presses."* The harness builds a list of about 1 to 8 **pre-validated macro actions** (such as "Collect village bed at (x,y,z), have 3 of 8" or "Escape all breath clouds along checked path…"). Jev picks one of them with a typed `choice`. Mineflayer does the pathfinding, digging, crafting and aiming.
   - Astra never runs on the critical path. After the first plan, it refreshes **asynchronously**: on a stage change, every 15 seconds, or after 2 errors. It returns only `{objective, targets, waypoint, notes}`, and Jev reads that plan as part of its state. That is how 8m43s produced 35 calls, about one every 15 seconds. The refresh interval set the call count, not any extra reasoning.
   - The "skills as `.mjs` files that Astra added after each failure" were written **between runs, during development**, and each was gated by tests (29 local tests plus 17 run checks). During the final run, *"source and guidance hashes stayed unchanged."* Nothing learned or generated code at run time.
   - The run used a fixed, pre-surveyed speedrun seed on Peaceful difficulty, with known chest, bed and portal coordinates supplied in the state. It shows that the architecture works. It does not show that the agent generalizes.
3. **The transferable idea is a split by clock speed.** A frontier model decides *what to do next* (every ~15 s, off the critical path). A System-1 model decides *which of these N legal things to do right now* (~70–500 ms, on the critical path). Deterministic reflexes handle *what must never wait for a model*, and the harness owns *what counts as legal and what counts as done*.
4. **Cyclone already has two of the three tiers.** Tier 0 is the compiled skill or atlas door, with no model (`SkillRuntime.match`, `knownAppGraphAction`). Tier 2 is a synchronous frontier call per unknown page (`requestPageDecision`, with Astra at `reasoningEffort = "high"`). **The middle tier is missing.** Every page that isn't compiled pays for a full frontier turn, even when the right move is "tap one of the 12 controls on this Page Card."
5. **Recommendation:** add a **Selector tier** (Jev-shaped: typed choice over harness-built candidates from the current Page Card), and make the frontier call an **async planner** that emits a sketch stage, objective and slot values. Keep GATE, Goal Contracts, Fast Path settle and `PhoneToolExecutor` exactly as they are. Roll it out in shadow mode first, and ship only on measured agreement, not on the Minecraft headline.

---

## 2. The source, file by file

Repo: `github.com/rmalde/minecraft-agent` (MIT-style public research code, Minecraft Java 1.16.5, seed `8398967436125155523`, Survival/Peaceful).

| File | Role | What it tells us |
|---|---|---|
| `nether-agent.mjs` | The main loop for the 8m43s run (`nether-final-08`) | `stage()`, `state()`, `candidates()`, the decide→execute→log loop, async planner wiring |
| `models.mjs` | Model clients | `decide()` calls Jev `/api/alpha/decisions`; `plan()` calls Astra `/api/v1/chat/completions` (OpenRouter) |
| `async-planner.mjs` | 19-line async planner | Refresh on stage change or a 15 s interval; discard the plan if the stage changed mid-request |
| `model-relay.mjs` | Local loopback relay | Key held only in memory; **hedged requests** (a duplicate is fired after 3 s for Jev and 10 s for Astra, and the first answer wins) |
| `optimization/policy.mjs` | Action policy | `selectUsefulOptions` (escape-first when unsafe; drop `wait` when useful work exists), `failureCooldown`, `planTrigger` |
| `breath-reflex.mjs`, `breath-safety.mjs` | **Reflex** layer | Runs on every physics tick with no model. It samples 16 escape directions, rejects paths through any cloud, and steers out |
| `end-combat.mjs` | One hand-built "skill" | Offers bounded bed-attack and escape actions; the timing window is measured, not model-guessed |
| `ground-safety.mjs`, `corner-safety.mjs`, `tool-recovery.mjs`, `inventory-recovery.mjs` | More skills and reflexes | Every failure class found in development became code with a regression test |
| `evidence.mjs`, `verify-run.mjs` | Completion proof | `victory.json` only with dragon-death evidence **and** the exit-portal packet (reason 4) |
| `optimization/NOTES.md`, `COMPARISON.md`, `pass-2/RESULT.md`, `nether/REVIEW.md` | Iteration log | 49 min → 12m36s → 12m58s → 8m43s, mostly from harness fixes, not model changes |

### 2.1 The control loop (de-minified)

```text
every 1 s:        planner.refresh()          ← async; only fires on stage change / 15 s / 2 errors
every 50 ms:      read-only dragon sensor
every phys tick:  breath reflex, perch-retreat stop, cloud-edge path cancel   ← no model

loop:
  opts = selectUsefulOptions(candidates())     ← harness enumerates legal macro actions for this stage
  if no plan yet: await planner                ← only the very first plan blocks
  r = decide(state(), opts)                    ← Jev: one typed choice, ~70–500 ms
  result = bounded(r.selected.fn(), 25 s)      ← Mineflayer executes; hard timeout
  on failure: failed[key] = now + 3 s; errors++; resync inventory/cursor
  log decision + result to events.jsonl
```

### 2.2 The Jev call (exact shape)

```js
{ model: 'typesafe/jev-1.13',
  state: JSON.stringify(compactObservation(state)),   // position, health, inventory, kitNeeds, plan, recent 5, battle…
  questions: { action: { type: 'choice',
     instructions: 'Control the Minecraft player. Choose one available action that best advances
                    the current planner objective. FIRST choose an offered escape action when a
                    breath cloud threatens the player. … Survival has priority over item reserve targets. …',
     criteria: { a0: 'Loot surveyed supply chest at (…) distance 12',
                 a1: 'Collect village bed at (…) have 3 of 8', … } } } }
// → answers.action.choice = 'a1' (+ per-option probabilities and a confidence)
```

The harness rejects any answer whose key is not in `criteria` (`Invalid JEV action`), so Jev can only choose from the current legal set.

### 2.3 The Astra call (exact shape)

* System prompt: a stage-specific route brief (known seed, kit, rules such as "no cheats" and "do not repeat completed stages").
* User message: the same compacted state JSON.
* `reasoning: {effort: 'low'}`, `max_tokens: 1800`, `response_format: json_object`.
* Output: `{objective, targets, waypoint, notes}`. It contains **no actions**. Astra never touches the game.

### 2.4 Numbers

| Measure | Value | Source |
|---|---:|---|
| Wall time (fresh world → exit portal) | 8m 43.3s | README `nether-final-08` |
| Jev decisions | 131 (≈ 1 per 4 s) | README |
| Astra calls | 35 (≈ 1 per 15 s) | README |
| Cost | $0.01 Jev + $0.96 Astra ≈ $0.97 | author's post |
| Avg cost / Astra call | ≈ $0.027 | derived |
| Avg cost / Jev decision | ≈ $0.00008 | derived |
| Astra latency sample (low effort) | 1.7 s | `optimization/pass-2/astra-probe.json` |
| Jev latency (vendor claim) | 70–500 ms | TypeSafe launch post |
| Jev price (vendor claim) | $0.042 / M input tokens, output free | TypeSafe launch post |
| Jev choice limit | 255 options | TypeSafe launch post |

### 2.5 How it got from 49 minutes to 8 minutes

| Run | Time | Jev | Planner calls | Failed actions | Waits | What changed |
|---|---:|---:|---:|---:|---:|---|
| `recorded-06` | 49m08s | 785 | 78 | 54 | 215 | baseline |
| `optimized-final-04` | 12m36s | 161 | 20 | 10 | 0 | filtered completed work, killed idle waits, cleared stale pathfinder target, multi-cloud escape, surveyed route |
| `optimized-overlay-02` | 12m58s | 167 | 8 | 4 | 0 | planner swapped Sol → Astra; milestone-only planning |
| `nether-final-08` | 8m43s | 131 | 35 | — | — | new seed + Nether shortcut route, tighter bed-combat window (11 → 46 damage per bed) |

The author's own notes put it plainly: *"These failures required changes to action validity and execution. More detailed model instructions alone would not fix the stale movement target or missing crafting choices."* Most of the speed-up came from **harness quality**: the action set, cancellation, safety reflexes and route data. Swapping the planner model barely moved the time (12:36 with Sol, 12:58 with Astra) but cut planner calls from 20 to 8.

### 2.6 A useful counter-example in the same repo

`research/minecraft-demo.md` records a separate **direct-control** experiment in which the model picks 250 ms movement pulses and 30° turns instead of macro actions. Building a 26×13 wool flag took **1,848 decisions** over 13m21s, and the note says it "can still temporarily oscillate." That experiment also added two guards Cyclone should copy:

* **Stale-answer rejection:** if the player moved ≥ 0.8 blocks during inference, or the answer took > 5 s, the answer is logged and **not executed**. The controller re-observes and asks again, and three stale answers in a row stop the run.
* **Bounded retry on 429/5xx/timeout**, re-observing before each retry.

**Lesson:** a fast model is only as good as the **grain** of the actions it chooses between. At macro grain, 131 decisions finished the game. At micro grain, 1,848 decisions built one flag.

---

## 3. The pattern, stated generally

```text
             ┌───────────────────── slow clock (seconds, async) ─────────────────────┐
             │  BRAIN  (Astra / Claude / Sol)                                        │
             │  in:  compact state + goal + history                                  │
             │  out: objective, targets, waypoint/sub-goal, notes, (free-text slots) │
             │  when: start, stage change, repeated failure, no useful options, 15 s │
             └───────────────────────────────┬───────────────────────────────────────┘
                                             │ plan is part of state
┌──────────── fast clock (sub-second, on critical path) ─────────────────────────────┐
│ HARNESS enumerates legal, pre-validated actions  →  SELECTOR (Jev) picks one      │
│ → bounded execution → result logged → failure cooldown on that key                │
└──────────────────────────────────────────────┬────────────────────────────────────┘
                                               │
┌──────────── reflex clock (every tick, no model) ──────────────────────────────────┐
│ safety reflexes that pre-empt both models: breath escape, cliff/lava veto, stop   │
└───────────────────────────────────────────────────────────────────────────────────┘
        PROOF: completion only from environment evidence (dragon death + exit packet)
        LEARNING: offline, between runs, test-gated; code frozen during a run
```

Five properties make it work:

1. **Legal-set control.** The fast model can only choose an action the harness has already checked as possible right now. A type-safe answer is not a correct answer, so correctness has to come from the set itself.
2. **Plan as state, not as command.** The brain's output steers the selector's preference ("best advances the current planner objective"), but the selector still reacts to the live world.
3. **Planner off the critical path.** Only the first plan blocks. A plan that arrives after the stage has moved on is discarded.
4. **Reflexes outrank models.** The model is never asked whether to dodge.
5. **Proof and learning are outside the models.** Victory needs evidence from the environment. Skills are code that passed tests between runs.

---

## 4. Cyclone today, through the same lens

Where the current code sits (4.x tree on `main`):

| Tier | Minecraft hybrid | Cyclone today | Where |
|---|---|---|---|
| Proof | `victory.json` needs dragon death + exit packet | Goal Contracts, semantic witnesses, `verifyCompletion`; "transport success is not task success" | `OpenRouterAdaptiveAgent.verifyCompletion`, `AGENTS.md` |
| Reflex (no model) | breath escape, cloud-edge stop, perch retreat | deterministic GATE class, overlay minimize/yield, human-boundary detection, photo-effect ledger, verified simple navigation → DONE | `deterministicGateClass`, `deterministicHumanBoundary`, `plan()` preamble |
| Tier 0: no-model replay | surveyed route, `kitNeeds`, hand-built combat skill | **compiled skill replay** (2+ verified successes, SAFE tools, session/display bound); **known app-graph action**; V5 atlas `decisionSource: map` | `SkillRuntime.match` (`OpenRouterAdaptiveAgent.kt:309`), `knownAppGraphAction` (`:335`), V5 `07-ask-compiler.md` |
| **Tier 1: fast selector** | **Jev typed choice over candidates** | **missing** | — |
| Tier 2: brain | Astra, async, objective + waypoint | **synchronous** Page Agent call per unknown page; it *is* the actor (returns tool, controlId, params) | `requestPageDecision` (`:1224`), `PageAgentProtocol.SYSTEM_PROMPT` |
| Execution | Mineflayer, `bounded(…, 25 s)` | `PhoneToolExecutor`, Fast Path settle 300 ms + fingerprint (+500/+1000), nav isolation | `fastpath/FastPathLoop.kt` |
| Failure memory | `failed.set(key, now+3 s)` | `recentFailedActions`, `failedActions`, Unchanged ≠ second click, FREE mode after stalled progress | `plan()` `runtimeFeedback` |
| Offline learning | `.mjs` skills written between runs, test-gated | learn → compile → replay (`PlaybookHintStore` → `SkillRouteCompiler`); V5 App Maps mapper | `skills/`, V5 `06-atlas-and-mapper.md` |
| Run evidence | `events.jsonl`, 17 run checks | Brain run diagnostics; V5 Run inspector with cause of death | `AgentTraceRuntime`, V5 `11-run-inspector.md` |

**Three differences stand out:**

1. **Cyclone's brain is also its hands.** Each unknown page pays a synchronous frontier call (`callTimeout 60 s`, Astra profile at `reasoningEffort = "high"`) that returns concrete tool calls. In the Minecraft loop that call happens about once per 15 s *in the background*, and it never picks the tap.
2. **Cyclone already has a very good action grain.** The a11y Page Card with `elementIndex`, `FastPathLanding` (`open_app` / `launch_intent`), `phone.scroll`, `phone.back`, and atlas doors are close to the Minecraft macro grain. Nav isolation already enforces one screen-changing mutation per decision. The legal set for a Selector mostly exists already; it is simply handed to a big model as a prompt rather than to a small model as `criteria`.
3. **Cyclone already rejects the same failure modes.** "Unchanged is not a second click" does the job of Minecraft's failure cooldown, Goal Contracts do the job of `victory.json`, and GATE does the job of the survival-priority filter. The hybrid does not challenge Cyclone's physics. It challenges where the frontier model sits in the loop.

---

## 5. Proposed shape: a three-clock Cyclone

```text
Ask sentence (immutable)
  │
  ├─ BRAIN (Astra | Claude Fable | Sol — ModelRegistry, user choice)          async, milestone-driven
  │    in : sentence, atlas sketch, Page Card summary, completionState, recent outcomes
  │    out: stage, objective, target room/capability, slot values (search text, message body),
  │         done-criteria hints, "stop and ask human" flags
  │    when: start · app/place change · sketch stage change · 2 failures / no progress ·
  │          no useful candidate · completion claim · ~15 s periodic while acting
  │
  ├─ SELECTOR (Jev-class System-1 model)                                     sync, ~70–500 ms
  │    candidates = harness-built from the CURRENT observation only:
  │        Page Card controls (elementIndex + label + role, top-K by relevance),
  │        FastPathLanding options, atlas doors from this room, back / scroll fwd/back,
  │        planner-authored form fill (text comes from planner, not selector),
  │        ESCALATE_TO_BRAIN, NEED_HUMAN
  │    questions (parallel, one call):
  │        action: choice over candidates
  │        roomPurpose: choice (inbox | login | dm-list | thread | settings | …)   ← atlas/mapper
  │        looksLikeAuthWall / looksLikePayment: noul                               ← advisory only
  │        objectiveVisible: noul                                                   ← advisory only
  │    accept iff confidence ≥ τ AND fingerprint unchanged since observe AND key not cooling down
  │    else → synchronous BRAIN turn (today's Page Agent path, unchanged)
  │
  ├─ MAP / SKILL (Tier 0, no model) — unchanged, still tried first
  │
  └─ REFLEX + PHYSICS (no model) — unchanged and authoritative
       GATE (pay/send/delete/permission/auth), consent/notification defaults, overlay yield,
       Take control, Fast Path settle ladder, nav isolation, cancellation/deadline rechecks,
       Goal Contracts for completion
```

### 5.1 Decision sources

V5 already records `decisionSource: map | model` for every step (`07-ask-compiler.md`, `11-run-inspector.md`). The proposal extends that list:

| Source | Latency (target) | Cost (order of magnitude) | Who picked the action |
|---|---|---|---|
| `map` | ~0 | 0 | atlas door or compiled skill |
| `reflex` | ~0 | 0 | deterministic rule (consent, overlay yield, GATE stop) |
| `selector` | 0.1–0.5 s | ~$0.0001 per step | System-1 model over a harness legal set |
| `model` | seconds | ~$0.01–0.05 per call | frontier brain (sync fallback) |

The Run inspector header already splits **map steps vs model steps**. Adding `selector` shows how much of each run ran in the fast lane.

### 5.2 Rules the Selector may not break

These are the current invariants from `AGENTS.md`, restated so they hold for the new tier:

1. **It chooses and never constructs.** It never produces a selector, coordinate, URL or text value. Every candidate is built from the **current** observation, and `elementIndex` expires after each mutation, exactly as it does today.
2. **`PhoneToolExecutor` stays the only hands.** The selector's output is a key, and the key maps to an ordinary phone tool request.
3. **One screen-changing mutation per decision turn.** A candidate is either one navigating act or a same-page form batch that the planner authored.
4. **GATE is deterministic and runs before execution.** Candidates that touch pay, send, delete, permission or authentication are removed from the list or replaced with `NEED_HUMAN`, as the Minecraft `selectUsefulOptions` does for escapes. A `looksLikePayment` answer from the selector may *add* caution but must never *remove* a GATE.
5. **Stale-answer rejection.** If the UI fingerprint changed between observation and answer, or the answer took longer than a budget, the answer is dropped and the loop re-observes. This comes directly from the direct-control experiment and fits the Fast Path fingerprint.
6. **Completion is never a selector call.** `DONE` still needs Goal Contract witnesses. `objectiveVisible` can only *trigger* a completion check.
7. **Privacy.** The selector request uses the same redaction as the current provider path: no secrets, vault values, typed secret text or screenshots. Probabilities and the chosen key go into diagnostics; hidden reasoning never does (and the selector has none).
8. **No run-time code generation.** New skills come only through the existing learn → compile → replay gates (2+ verified successes, SAFE tools, session/display binding). That matches how the Minecraft run froze its source during a run.

### 5.3 What the brain becomes

The Page Agent prompt stays, because it is the sync fallback. The async planner gets a much smaller contract, similar in size to Astra's `{objective, targets, waypoint, notes}`:

```json
{ "stage": "Facebook in Chrome / OPEN_DM",
  "objective": "Open Louella's conversation",
  "targetRoom": "dm-list",
  "slots": { "search": "Louella" },
  "prefer": ["Messages", "Chats"],
  "avoid": ["Log out", "Marketplace"],
  "askHumanIf": ["login wall without vault slot"],
  "notes": "Native Facebook absent; stay in Chrome" }
```

The Selector's `instructions` read *"choose the control that best advances `objective` toward `targetRoom`"*, and the plan is part of `state`. This is the Minecraft mechanism, applied to the V5 Ask compiler's sketch (`07-ask-compiler.md`): the sketch stays law, and the selector takes the next door.

---

## 6. Use cases across the Cyclone studio

| Use case | Today | With the hybrid | Fit |
|---|---|---|---|
| **Ask Cyclone** on a mapped app, uncompiled route | frontier call per page | selector per page; brain at app/stage changes | **High.** This is the main win (speed and cost per step) |
| **Ask** on an unseen app or odd UI | frontier call; vision on escalate | selector confidence drops → sync brain, as today | Neutral. The fallback keeps current behavior |
| **V5 App Maps mapper** (walk doors, classify rooms) | model call per room | `roomPurpose` choice + door choice from selector; brain names capabilities and decides when to stop | **High.** Many decisions, each a classification. This is exactly Jev's stated niche |
| **Background workspaces / Session Kernel** | product gates to one hot background Ask | cheaper steps make more concurrent sessions affordable; the gate stays a product decision | Medium. Cost barrier drops, but OEM, display and focus limits remain |
| **PC agents over MCP** (Claude Code, Codex via `codex-phone-mcp`) | the external agent spends a frontier turn per `phone_act` | the external agent becomes the **brain**: a future `phone_objective`-style tool hands an objective, and the phone's selector runs until a milestone or escalation | **High,** but it needs a new gateway contract (`08-protocol-gateway.md`) |
| **Routines / Teach** | compiled replay or LLM | selector repairs a routine when one selector drifts (choose the equivalent control) before escalating | Medium |
| **Guardrail (TypeSafe "AutoMode" pattern)** | deterministic GATE only | selector `noul` second opinion on risky-looking actions that GATE did not classify → pause for human | Medium. Additive only; never relaxes GATE |
| **Model routing** | user picks a model | selector decides "cheap brain enough / needs Astra-high / needs vision" per escalation | Medium |
| **Glass Run inspector** | map vs model steps | adds `selector` steps with probabilities, showing where the fast lane hesitated | High for debugging |
| **Messaging, payments, account changes** | GATE + human | unchanged. The selector may navigate *to* the screen, and GATE still stops the act | Must not change |
| **Free-text content** (compose email, reply) | brain writes text | brain still writes text. Jev **cannot generate strings** | No fit for the selector |

---

## 7. Where the analogy breaks (read before building)

1. **Minecraft had a fixed seed and known coordinates.** Cyclone faces arbitrary app versions, A/B layouts, locales and dark patterns. Expect lower selector confidence and more escalations than 131 decisions against 35 calls. The V5 atlas is Cyclone's version of a "surveyed route," and the hybrid works best where the atlas is rich.
2. **Most of the Minecraft intelligence is in hand-written action generators.** Examples are the bed-timing window, the 16-direction escape sampler and the Nether bridge placer. Jev picked among options that were already good. Cyclone's equivalent is candidate quality: Page Card pruning, relevance ranking under the 255-option cap, and atlas doors. Budget engineering time for that, not only for model integration.
3. **The stakes are asymmetric.** In Minecraft a wrong pick costs seconds, but on a phone it can send a message. Cyclone's GATE, Goal Contracts and "transport success is not task success" rules are *more* conservative than the Minecraft harness, and must stay so.
4. **"Can't hallucinate" means "can't return an invalid type."** Jev can still pick the wrong valid option confidently. Accept on calibrated confidence plus a structural check, and measure calibration on Cyclone data before trusting τ.
5. **Latency matters differently.** A phone UI does not attack you in real time, so the selector's value is mostly **run time and cost per step**, and more sessions per dollar, rather than reflex speed. Fast Path already spends 300 ms or more on settle per tap, so a 300 ms selector roughly halves step time only when the frontier call is slow. Measure before claiming a multiple.
6. **Privacy and vendor surface.** A new third-party model receives Page Card text (email subjects, chat names). That needs the same redaction, a privacy-class flag like `PrivacyClass.CONTRIBUTOR`, and an explicit user setting. An on-device classifier is a valid alternative implementation of the same `SelectorPort` if the vendor is not acceptable.
7. **The post and the code disagree.** The post says "WASD, space, click, and mouse movements" and "Astra would add skills as mjs files." The repo says structured macro actions, Mineflayer pathfinding, and frozen source during runs. Base Cyclone decisions on the code.

---

## 8. Suggested path (if the owner wants to pursue it)

Each step has an exit metric. None of them changes release identity or ships to users until step 4.

| Step | What | Exit metric |
|---|---|---|
| 0. Measure | From Brain diagnostics on existing runs, count: provider requests per run, wall time per provider turn, and **% of model turns whose chosen action was a single `phone.click`/`scroll`/`back`/`open_app` on a control already in the Page Card** (the selector-addressable share) | A number. If it is small, stop here |
| 1. Offline replay | Put recorded Page Cards and the model's chosen action through a `SelectorPort` (Jev or an on-device baseline); compare picks | Top-1 agreement and calibration curve on ≥ several hundred decisions across ≥ 10 apps |
| 2. Shadow | Selector runs alongside the live Page Agent; its pick is logged as `decisionSource: selector-shadow`, never executed | Agreement ≥ target with confidence ≥ τ; zero GATE-class candidates ever chosen |
| 3. Async planner | Split the Page Agent into a sync fallback plus a milestone-driven async planner that emits `{stage, objective, targetRoom, slots}`; discard stale plans | No drop in completion rate; fewer frontier calls per run |
| 4. Selector live (STRUCTURED mode only) | Execute selector picks for non-GATE, single-mutation candidates above τ; everything else goes to the brain | Physical-device runs on the Pixel 8 acceptance set: false-completion rate, GATE incidents (must stay 0), wrong-room rate, p50/p95 run time, cost per run |
| 5. Mapper | `roomPurpose` + door choice in the V5 App Maps crawl | Rooms mapped per minute and per dollar; classification accuracy against operator pins |

Ownership follows `AGENTS.md`. Runtime work belongs to `apps/mobile/**` (a new `ai/selector/` package plus hooks in `OpenRouterAdaptiveAgent.plan()`). An objective-level MCP tool would need a gateway/MCP contract change under `apps/device-gateway/**` and `tools/**` in lockstep. A11y-first observation, Fast Path settle, nav isolation, Session Kernel binding, compiled-skill gates and GATE all stay as they are.

---

## 9. Claude or Astra as the brain?

Nothing in the pattern depends on a particular brain. The Minecraft author swapped Sol and Astra with little change in time (12:36 vs 12:58). Planner calls fell from 20 to 8, but that came with a move to milestone-only planning. Cyclone's `ModelRegistry` already carries both GPT-6 Astra and Claude Fable 5.1 as `SCHEMA_CONSTRAINED` profiles. So the brain should stay a **user or model-registry choice**, with two changes from today's Page Agent use:

* **Lower reasoning effort for routine plan refreshes** (Minecraft used `effort: 'low'`, 1,800 max tokens) and **high effort only for recovery and FREE mode**.
* **Hedged requests** for tail latency, as in the relay: a duplicate is fired after N seconds and the first answer wins. This fits the existing bounded `callTimeout` and cancellation rechecks, but doubles worst-case spend, so it should be budgeted.

For PC use through MCP, the external coding agent (Claude Code, Codex) is *already* a brain. The highest-leverage version of this idea there is to stop spending that agent's turns on single taps.

---

## 10. Sources

* Source code: [rmalde/minecraft-agent](https://github.com/rmalde/minecraft-agent) (commit `78b40ed`), specifically `README.md`, `nether-agent.mjs`, `models.mjs`, `async-planner.mjs`, `model-relay.mjs`, `optimization/policy.mjs`, `breath-reflex.mjs`, `end-combat.mjs`, `optimization/NOTES.md`, `optimization/COMPARISON.md`, `optimization/pass-2/RESULT.md`, `optimization/nether/REVIEW.md`, `research/minecraft-demo.md`
* Author thread: [Ronak Malde — result and cost](https://x.com/rronak_/status/2101544156757950697), [harness description](https://x.com/rronak_/status/2101544158502728002)
* Coverage: [MakeUseOf](https://www.makeuseof.com/astra-and-jev-kill-ender-dragon-in-minecraft/), [Jevfast](https://jevfast.com/jev-astra-speedrun/), [WindowsForum](https://windowsforum.com/news/minecraft-ai-demo-reportedly-beats-ender-dragon-for-0-97.445264/), [DeepWiki summary](https://deepwiki.com/rmalde/minecraft-agent)
* Jev / System One: [TypeSafe — Introducing System One Models & Jev](https://typesafe.ai/blog/introducing-system-one-models-and-jev), [LangChain — Building a harness with Jev](https://www.langchain.com/blog/building-a-harness-with-jev), [MindStudio explainer](https://www.mindstudio.ai/blog/jev-system-one-model-launch), [DataCamp](https://www.datacamp.com/blog/system-one-models-jev)
* Cyclone: `AGENTS.md`, `docs/ARCHITECTURE.md`, `docs/V4_STAGE1_FASTPATH.md`, `docs/V4_STAGE3_SKILL_COMPILER.md`, `Cyclone V5 plan/01-headset-and-laws.md`, `06-atlas-and-mapper.md`, `07-ask-compiler.md`, `11-run-inspector.md`, `apps/mobile/.../ai/OpenRouterAdaptiveAgent.kt`, `ai/PageAgentProtocol.kt`, `ai/model/ModelRegistry.kt`, `fastpath/FastPathLoop.kt`

Vendor numbers (latency, price, "can't hallucinate") are TypeSafe's claims and have not been reproduced here. Minecraft results are the author's single-run reports. Cyclone latency and cost figures in §5.1 are orders of magnitude to be replaced by step 0 measurements.
