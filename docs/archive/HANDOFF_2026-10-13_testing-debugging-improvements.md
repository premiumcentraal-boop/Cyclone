# Handoff: Cyclone testing, debugging, modes research and ranked improvements (2026-10-13)

For the next agent. Start with §0 and §1, then follow the work plan in §5. Everything here was read from the repo
at `5b44de1c` (dev branch `claude/cyclone-v5-handoff-review-9qrs40` + open PR #215). Line numbers drift; search by
symbol if one is off.

## 0. Where Cyclone stands (one screen)

- **Version:** `5.0.0-alpha.127.dev1`, versionCode 281 (`release/version.toml`). Gateway/MCP are at alpha.123, and
  Glass is at 1.0.0-alpha.65. **README.md and AGENTS.md line 3 still say "V4 4.0.0"; they are stale.** The real design
  record is `Cyclone V5 plan/` (plans 16–58) and `docs/RELEASE_5.0.0-alpha.*.md`.
- **Last 10 releases:**
  - 127: one overlay motion (`OverlayMotion`), blur steps aside while panels move, and text after a finished mission
    becomes a new request.
  - 126: glass Profiles, living overlay, blur with a smoke fallback.
  - 125: room for up to 100 profiles.
  - 124: the new-profile flow can be finished.
  - 123: **Luna Decision Box foundation** (plan 58, release 1 of 3).
  - 122–118: profiles and Cyclone Cloak (plan 57).
  - 117: VMOS fixes.
- **Open PR #215 (not released):**
  - the Ask bar glides with the keyboard (`OverlayChromeController.glideTo`, 280 ms);
  - the `AnimatedContent` swaps are anchored at `BottomCenter`;
  - the working card keeps its last content while folding.
- **Physical-device truth is thin and old.**
  - The only testbench results on record are three runs on a Pixel 8 with **alpha.107 (2026-10-05)**: tb-smoke 83%,
    smoke 75%, judgement 73%, 0 safety failures.
  - **Every release from alpha.111 to 127 says "UNVERIFIED on a physical phone"**, except a partial pass on
    113.dev6.
  - The device matrices are entirely UNVERIFIED: `docs/LUNA_DEVICE_MATRIX.md` (18 rows),
    `docs/PROFILES_DEVICE_MATRIX.md` (27), `docs/HUMAN_HANDS_DEVICE_MATRIX.md` and `docs/FLEET_ACCEPTANCE.md` (6).
- **The findings ledger** exists only on remote branch `origin/testbench/results`, as
  `testbench-results/findings.jsonl`. It holds 7 findings from alpha.107, all `open`, never re-checked. Six of them are
  very likely fixed by alpha.108 (tap-guard ancestor fix) and alpha.109 (screen reads no longer thrown away), but were
  never re-run.

## 1. Rules you must not break

These come from AGENTS.md and `.claude/skills/cyclone-testing/SKILL.md`. Read both.

1. **Never approve anything for the owner.**
   - The Lab's only exception is `CYCLONE_LAB_APPROVALS=test-only`, set where the gateway runs. It covers exactly two
     actions, both declared in the mission's `owner.approve`:
     - deleting `cyclone-lab-note.txt`;
     - sending to `cyclone-lab@example.com`.
   - The phone enforces the same list in `GatewayV5LabAdapter.labMayApprove`.
   - Never widen it. Widening is a reviewed change to both lists.
2. **No secrets.** Never type, read back, store or log passwords, OTPs, keys or payment data. Stopping at the Secrets
   Card is a pass, not a failure.
3. **Nothing irreversible or paid.** No messages to real people. ADB is for evidence (`logcat`, `dumpsys`) and resets
   only, never to do a task step.
4. **Use accounts only the owner owns, at human pace, with no CAPTCHA solving.**
5. **Results are written only by `cyclone-testbench`**, and pushed only to `testbench/results`. They are redacted.
6. **Fixes go on a branch and a PR.** Never change the owner's Cyclone install, phone settings or data.
7. **Task buttons go through Task Kit (`TaskCommands`).** The fleet layer never answers approvals.

## 2. The modes, as built

### Routing: `OverlayChromeRuntime.submitRequest` (`ui/overlay/OverlayChromeRuntime.kt` ~:420)

All of these surfaces arrive at the same function: the overlay, the in-app chat, voice, Glass/MCP `ask.start`, and the
queue.

1. **A running mission's hooks get first refusal.** The text becomes a steer, an answer or a cancel word.
2. **Phone busy:** the request is queued (`WorkspaceTasks.queueRequest`).
3. **`ExecutionTargetResolver`:** phrases like "in the background" or "without taking over my screen" go to
   `WorkspaceTaskService`. That service runs the **classic agent** (`OpenRouterAdaptiveAgent`) on a virtual display. It
   needs Android 15+ and Shizuku.
4. **`runAiRequest`:** if `MindMissions.enabled` (default true), the request goes to `CycloneModes.handle`. Otherwise
   it goes to the classic agent in the foreground.
5. **Command Center / fleet `cc.start`** skips the router and starts `MindMissions.startAssigned` directly.

### `CycloneModes` / `ModeRouter` (`mind/modes/`): INSTANT, FLASH, MIND, ANSWER, IGNORE

The router tries these in order (`ModeRouter.route`):

1. **Speed = MIND** sends everything to the Mind.
2. **Stage 0**, all local:
   - time, date and battery are answered on the phone;
   - chatter ("ok", "thanks") is ignored;
   - `InstantGrammar.parse` recognises clear commands;
   - a request for a missing app is answered "I don't see X".
3. **Rules:**
   - `MIND_WORDS` (message, mail, pay, delete, account…), two or more apps, or a question go to the Mind;
   - in COMMANDS speed, anything else goes to the Mind;
   - a second clause ("and", "then") goes to Flash.
4. **On-phone `PhoneModel`** (naive Bayes). It acts only on earned intents (≥ "sure") or on short reversible commands.
   One request in N is audited by the decision provider.
5. **No OpenRouter key:** the request goes to the Mind.
6. **Triage**, only when switched on. It ships in **SHADOW**.
7. **Board 0:** one Decisions call with route, intent and target questions. A null answer goes to Flash. "Unsure goes
   up, never down."

Any exception hands the request to the Mind ("never lose a request").

### Instant (`mind/modes/InstantRun.kt`, `AndroidInstantHands.kt`)

- **What it does:** at most `MAX_MOVES = 3` moves. Intents include open app, tap, swipe, scroll, back, home, recents,
  camera, photo, selfie, call (with a 2 s cancel window), timer, alarm, flashlight, volume, media, zoom and drag.
- **How it acts:** every move goes through `CycloneAgentEnvironment.act`, then `PhoneToolExecutor`, which applies the
  approvals, settle and proof.
- **What it never does:** type, compose, send, pay, delete or post. A sensitive screen, an irreversible label or a drag
  onto Trash promotes the request to the Mind.
- **On failure:** it promotes to Flash or the Mind with a `RunBaton` ("don't redo what was done").

### Decisions API: JEV / Luna (plan 58, `mind/decide/`, `decisions/`, `modes/DecisionBox.kt`, `modes/Triage.kt`)

- **What it is:** typed multiple-choice, yes/no and score questions, sent to OpenRouter
  `https://openrouter.ai/api/alpha/decisions`. It never returns free text.
- **Providers:** JEV `~typesafe/jev-latest` (text only) is the **default**. `openai/gpt-6-luna-decisions` can read
  images.
- **Deadlines:** routing 2.5 s, Triage 1.2 s, watch 6 s.
- **Breaker:** 3 failures in 60 s pause that provider for 120 s.
- **Watch:** the other provider answers the same board beside the decider and never acts. It runs on each Auto request
  while "Compare" is on.
- **Logs:** `filesDir/decide/{calls,watch,lessons}.jsonl`. Counts, times and cost only, no request text.
- **Built in alpha.123:**
  - the wire format and its fix (the old `choices` wire "may have answered nothing");
  - the breaker, the Luna setting, Triage in shadow, and the watch;
  - golden set v1 (308 rows) and the on-phone DecisionLab;
  - `scripts/dev/decisions_probe.py`.
- **Not built:** plan 58 releases 2 and 3 ("See and act", "Chains and switch"). That includes BindBoard, VerifyBoard,
  DestinationIndex, InstantChain and Glass drawing the numbers. The alpha.124–127 version numbers went to profiles and
  overlay work instead.
- **Fixtures were never recorded from the real API.** `src/test/resources/decisions/recorded/` doesn't exist; the
  fixtures were rebuilt from the API reference example.

### Flash, Pilot, Mind, classic agent and the rest

- **Flash:** a Mind mission with `flash=true`, meaning a "quick run" prompt hint. Fast mode (`mind/pilot/`, a rapid
  `gemini-3.1-flash-lite` model walking the smart model's plan) is **off by default**, so Flash is normally the full
  Mind model.
- **Mind** (the default engine):
  - `MindMissions.execute`, then `MindLoop`. Budget: 30 min, 400 turns, 180 s per call, 100k context characters.
  - **One screen change per turn**: after the first screen-changing tool call, the rest of that turn returns "NOT RUN".
  - Tools are in `PhoneMindToolbox` (2431 lines). Every action goes through `env.act`.
  - Approval: GATE_REQUIRED becomes an owner approval card. A send carries the exact text in the message box and is
    re-checked after approval.
  - **COMPLETED is declared by the model's `task_finish`**, with evidence nudges, not by an independent verifier.
- **Classic agent** (`ai/OpenRouterAdaptiveAgent.kt`, 2581 lines). It runs only when the Mind is off, for background
  workspace tasks, and in `OpenRouterQuickAgent`. Its order per turn: local policy, Atlas route, **compiled skill
  replay** (`skills/`, used by the classic agent only), the App Graph, then the LLM. Fast Path settle is 300 ms, then
  +500 ms and +1000 ms; an unchanged screen is never clicked twice.
- **Task Kit** (`task/TaskKit.kt`): MIND, CLASSIC_FOREGROUND and BACKGROUND_WORKSPACE controllers, chosen by task id.
- **Gates, in three layers:**
  1. the policy pre-check in `CycloneAgentEnvironment` (`GateClassifier`: PAY, SEND, DELETE, GRANT);
  2. the accessibility click interceptor (`ClickGateIntercept`);
  3. the background `workspaceGate`.

  Grants are one-shot and signed by action, labels and session. A challenge lasts 60 s and a grant 30 s.

## 3. How to test

### Lab and testbench (`tools/cyclone-testbench/`, PC side)

- **Read first:** `GUIDE.md`, `HANDOFF.md`, `seat/SEAT.md` and the skill.
- **Setup:** `pip install -e apps/device-gateway -e tools/cyclone-testbench`. The gateway must run on the owner's PC
  (`cyclone`, loopback `127.0.0.1:8765`). The phone must be paired, READY, on ADB, on the charger, with Stay awake on.

```
cyclone-testbench doctor --fix          # readiness; exit 4 = not ready → wait and tell the owner
cyclone-testbench soak --reads 50       # gate 1: 50 screen reads, 0 failures, before any round means anything
cyclone-testbench install && cyclone-testbench validate
cyclone-testbench next                  # re-checks of open findings first, then the campaign slot
cyclone-testbench run --next --wait     # or --suite S / --missions a,b / --reps N / --variant '{...}'
cyclone-testbench report EXP ; cyclone-testbench findings ; cyclone-testbench dashboard
cyclone-testbench finding F-xxxxxxxx --status fixed --note "re-run on alpha.127: pass"
cyclone-testbench profiles --rounds 17  # the 51-switch profiles run
```

- **Missions:** 78 testbench missions in 11 packs, plus the 59 built into the Lab. The GUIDE's "56" is stale.
- **Verdicts:** pass, fail, infra, skipped. Fail causes: `missed_boundary`, `boundary_broken`, `stuck`, `timeout`,
  `false_success`, `gave_up`, `out_of_budget`.

### Locally (any machine)

```
cd apps/mobile && ./gradlew :app:testDebugUnitTest            # ~2.6k tests
python3 -m pytest scripts/ci/tests -q                          # 340 repo guards (mostly source pins)
python3 scripts/ci/mobile_product_guard.py ; python3 scripts/ci/release_versions.py --check
python -m pip install -e 'apps/device-gateway[test]' -e tools/codex-phone-mcp -e tools/cyclone-ports-sdk
python -m pytest apps/device-gateway/tests -q ; python -m unittest discover -s tools/cyclone-testbench/tests
cd apps/glass && npm ci && npm test && npm run build
```

- **CI:** `mobile-ci.yml` and `_mobile-build.yml` run the guards, the gateway, MCP and testbench tests, Gradle test,
  lint and assemble. **No workflow touches a phone.**
- **Publishing:** `v5-publish.yml` runs on a push of `release/version.toml` to the dev branch. Don't push to the dev
  branch while a publish runs.

### Debugging tools

- **Phone:**
  - `AgentTraceRuntime` / `AgentTraceStore` (SQLite `cyclone_ai_history.db`, redacted);
  - `AgentRunDiagnosticV39` (shareable, at most 1 MB);
  - the mission journals under `Cyclone Brain/Missions/`;
  - `decide/*.jsonl`;
  - the profile debug file.
- **Gateway:**
  - `POST /v1/debug/bundle`;
  - `/v1/diagnostics/status`;
  - `/v1/devices/{id}/runs/...`;
  - `/v1/devices/{id}/care`.
- **MCP:** `phone_debug_bundle`, `phone_lab_*`, `phone_status`. Add the server with
  `claude mcp add cyclone -- "%LOCALAPPDATA%\Cyclone One\CycloneAgentMCP.exe" serve`.
- **Glass:** Runs → the run inspector ("cause of death" and the step it happened on), the Lab page, phone care.
- **UI renders without a phone:** a temporary Robolectric harness can screenshot Compose states. It needs Robolectric
  4.14.1, `ui-test-junit4` and `isIncludeAndroidResources`, and it was used for every overlay sheet so far. Never commit
  it.

## 4. Ranked improvements (most important first)

**How strong the evidence is:**
- **A:** reproduced or measured on a device, or a failing test.
- **B:** the repo says it outright (release notes, plan, code comment).
- **C:** read in the code; the mechanism is clear but no device run has seen it.
- **D:** a plausible hypothesis that still needs a measurement.

Rank weighs safety, then whether the owner can trust results, then user impact, then cost.

1. **Restore device ground truth before anything else.** Evidence **B** (strong).
   - **Why:** nothing since alpha.107 has been measured on a phone. 20 releases (profiles, Luna routing, overlay
     motion and blur) shipped on CI alone. Every other item in this list is a guess until the Lab runs again.
   - **Evidence:** "UNVERIFIED on a physical phone" in every release note from alpha.111 to 127. The ledger's only
     data is from 2026-10-05 (`origin/testbench/results`, commit `8b494bc4`). All rows of the Luna, profiles,
     human-hands and fleet matrices are UNVERIFIED.
   - **Do:** run `doctor --fix`, then the soak, then `next` (which re-checks the 7 open findings). Then run the smoke,
     judgement and safety suites on alpha.127 and mark the findings fixed or regressed. Then run
     `docs/LUNA_DEVICE_MATRIX.md` and the overlay acceptance below.
2. **Make routing decisions measurable and correct (Decisions API / Instant).** Evidence **C+B**.
   - **Default speed contradiction.** `ModeSettings(speed = Speed.COMMANDS)` says "the default until the Lab passes"
     (`ModeRouter.kt:12`). But `Speed.of(null)` returns **AUTO** ("Alpha 89: Auto is the default", :17-18), and
     `settings()` always goes through `Speed.of` (`CycloneModes.kt:56`). So every fresh install routes through the
     Decisions API, which the Lab never approved. **C**, read in code.
   - **The fixtures are synthetic.** `recorded/` was never created, so the wire format is unproven against the real
     endpoint. **B** (alpha.123 release notes, "Limits").
   - **Over-broad words.** `MIND_WORDS` contains "mail" and "text", so "open my mail" or "text size bigger" goes to the
     full Mind (slow and costly). Plan 58 §2.5 names this; the code still has it (`ModeRouter.kt:89`). **B+C.**
   - **The watch call.** It runs on each Auto request while Compare is on. That is extra latency (6 s deadline) and
     cost per request. **B** (alpha.123 notes).
   - **Do:**
     - run `scripts/dev/decisions_probe.py` with the owner's key to record real fixtures;
     - add a routing test matrix of about 50 sentences against expected modes, run on the device through
       `CycloneModes`, and compare the `AskLedger`;
     - decide the default speed explicitly and make the code agree;
     - narrow `MIND_WORDS` to verbs of writing or sending.
3. **Reduce false gates without weakening real ones.** Evidence **B**.
   - **Why:** the approval gate classifies by keywords in the labels near a control (`policy/GatePolicy.kt`, "legacy
     keyword fallback"). It has raised a false SEND when merely opening a post skill (alpha.113.dev2:42). It also gated
     a Run button that had "acquired the entire nearby explanation" (113.dev5:48). False gates teach owners to tap
     "Do this" without reading, which is the real safety risk.
   - **Do:** build a gate corpus of `(labels, action)` pairs, mined from Lab runs and traces, that should and should
     not gate. Score `GateClassifier` against it, and tighten label collection to the control itself. Never loosen a
     class without a corpus row that proves it.
4. **The overlay's own costs and regressions.** Evidence **C** (my own alpha.126/127 work; verify on the device first).
   - **Glass clock.** `GlassClock` loops `withFrameNanos` forever while glass is visible (`TiltGlass.kt`, private
     `GlassClock`). That requests a frame on every vsync even though state writes are throttled to about 20 Hz. **C.**
     Battery and GPU cost the whole time the overlay shows. **Fix:** `delay(50)` between ticks when no ripple is
     active, and stop entirely when the overlay is hidden.
   - **Blur and keyboard (PR #215).** The keyboard glide moves the window every frame. `syncBlur` treats a window move
     as a jolt, so the blur drops to smoke on every keyboard open or close. **C.** Decide whether that's wanted, or have
     the blur follow window moves only after the glide.
   - **Glide timing.** The 280 ms glide is a fixed curve. It does not read the IME's real animation
     (`WindowInsetsAnimation` exists at `OverlayChromeController.kt` ~:773 but accessibility overlays often get zero
     IME insets). **C.**
   - **Blur windows count as windows.** Each blur window is a `TYPE_ACCESSIBILITY_OVERLAY` Dialog, so it appears in
     the accessibility window list. Check that observation filters it out: `CycloneAccessibilityService` filters
     `TYPE_ACCESSIBILITY_OVERLAY`, good. Also check it never intercepts the agent's own gestures. **C/D.**
   - **Acceptance to run on the phone:**
     - open and fold the overlay;
     - keyboard open and close;
     - triple-tap the launcher;
     - the working card closing at task end;
     - blur on, then battery saver on;
     - Steer, Queue and Parallel only while a mission runs.
5. **Two engines doing the same job.** Evidence **C+B**.
   - **Why:** explicit "in the background" tasks run on the classic agent, not the Mind. They get different prompts,
     different completion logic (`GoalContract`) and compiled-skill replay that the Mind lacks. The Mind meanwhile gets
     planes, steering, plan diversion, owner moments and Pilot.
   - **Effect:** behaviour, approvals copy and test coverage differ by which phrase the owner used. Both the 2581-line
     classic agent and the 2431-line toolbox must be maintained. alpha.126 itself had to support both ("the Mind's plan
     step … or the classic agent's displaySummary").
   - **Do:** route background requests to a Mind mission on the background plane (the planes already exist), port
     compiled-skill replay into the Mind's act path, and then retire the classic agent's foreground role.
6. **Completion is self-declared.** Evidence **C** (and the testbench has a `false_success` category for this
   reason).
   - **Why:** a Mind mission is COMPLETED when the model calls `task_finish` with evidence text. There are nudges (the
     final screen is appended, settings-only goals are read back, steers that were never re-planned are flagged), but
     no independent check for most goals.
   - **Do:** extend read-back verifiers (`PhoneSettingReader`, the direct tools' read-backs) to common goal shapes:
     message sent and visible in the thread, alarm exists, file gone. Count `false_success` per suite as a top-line
     metric.
7. **Lab approval matching is looser on the PC than on the phone.** Evidence **C**, medium.
   - **Why:** `lab/runner.py:approval_matches` passes a delete when the target name appears anywhere in the moment
     text (a substring test), so text naming the lab file *and* another file matches on the PC. The phone re-checks
     that the file name set is exactly the lab file, but by **name, not path**, and it doesn't itself check that the
     mission declared approvals or that the switch is on.
   - **Do:** make the PC side exact (parse the file set like the phone does), and add the path to both checks. This is
     a safety boundary, so keep the change reviewed.
8. **Backups may carry learning data off the phone.** Evidence **C**, medium.
   - **Why:** `AndroidManifest.xml:72` sets `android:allowBackup="true"` with no `dataExtractionRules` or
     `fullBackupContent`. So `Cyclone Brain/` (missions, people memory, learned maps) and the trace database can go to
     cloud or device-transfer backups. Keystore-sealed parts won't decrypt on another device; plain files will.
   - **Do:** add backup rules that exclude `Cyclone Brain`, traces and `decide/`, or set `allowBackup=false`. The
     owner should decide.
9. **The legacy gateway credential can still read the screen.** Evidence **C**.
   - **Why:** `LEGACY_READ_ONLY` sessions (`gateway/GatewaySessionStore.kt`) are still accepted, and their op list
     includes `capture.screenshot` and `observe.semantic`. The helper is marked "one-release legacy transition" but has
     stayed for many releases.
   - **Do:** remove it, or time-box it behind an owner switch.
10. **Main-thread accessibility load and always-on wakeups.** Evidence **B+C**.
    - **Why:** the accessibility service handles every content, scroll and text event on the main thread
      ("hundreds a second in a busy app", `CycloneAccessibilityService.kt` ~:186; past freezes noted, alpha 87).
      `MainThreadWatchdog` posts every 500 ms forever. `PlanePill` polls a bitmap every 1 s. About 8 foreground
      services exist.
    - **Do:** measure first. Use `dumpsys gfxinfo` and `batterystats` during a soak with the overlay idle and working,
      then move heavy event handling off the main thread.
11. **Tests guard strings, not behaviour.** Evidence **C** (counted).
    - **Why:** about 9% of the mobile tests (~246 in 35 files), and nearly all 340 CI guards, read source text and
      assert substrings, for example `"task != null -> OverlayWorkCard(task, diversion, minimize)"`. They break on
      harmless renames and miss real regressions. Safety wiring is partly guarded this way. The last overlay commit
      changed behaviour with no new test.
    - **Do:** for the safety boundaries (gates, Lab approvals, Task Kit), add behavioural tests next to the pins, such
      as the JVM `OverlayChromeMachine`, `GateClassifier` with the corpus, and `labMayApprove` with tricky inputs. Keep
      the pins only where a behavioural test is impractical.
12. **Stale guidance misleads agents.** Evidence **A** (read directly).
    - README.md and AGENTS.md:3 ("V4 4.0.0") and `CONTINUE.md` ("alpha.102", another repo) contradict
      `release/version.toml`.
    - The testbench GUIDE's mission counts are stale.
    - Plan 57 says profiles "default 8", but alpha.125 shipped "default 20".
    - **Do:** one refresh pass, so a new agent's first read is true.
13. **Dead and legacy code still compiled.** Evidence **C** (counted).
    - **Dead code:** `MobilerunEmbedded` (deprecated, no callers), `OverlayAppleToolsMenu` (only a test asserts it is
      not called), `CycloneKyantLiquidIconButton`.
    - **Legacy:** `debug/PageDebugSandboxV293` is still a manifest Activity. About 103 version-suffixed files
      (~25.7k lines), and more than 50 one-off publish workflows.
    - **Do:** delete in small reviewed PRs. Each deletion lowers the surface the next agent has to read.
14. **Small hardening items.** Evidence **C**, low.
    - `trust_v33.py` `_is_current` compares the credential with `==`, not `hmac.compare_digest` (loopback only).
    - Instant's `MAX_MOVES` is checked only on the photo path. Tap, call and drag are fixed short sequences, so this is
      low risk, but the stated cap isn't enforced generally.
15. **Plan 58's remaining releases.** Evidence **B**.
    - "See and act" and "Chains and switch" are planned but not built.
    - Do these *after* items 1–2 prove the foundation on the device. Building chains on unrecorded fixtures repeats the
      alpha.123 risk.

## 5. Work plan for the next agent

1. **Read:** AGENTS.md (ignore its stale version line), the testing skill, the testbench GUIDE and HANDOFF, plan 58
   §1–§3 and §14, and the alpha.123–127 release notes.
2. **With the owner's PC and phone:**
   - doctor, then soak;
   - `next` (re-checks the 7 findings);
   - smoke, judgement and safety on alpha.127;
   - update the ledger;
   - the Luna device matrix;
   - the overlay acceptance (§4 item 4);
   - the profiles 51-switch run.

   Push results only through the testbench.
3. **Without a phone:** fix items 2 (default speed, word list), 4 (glass clock), 7 (Lab matching) and 12 (stale docs).
   These are small and well evidenced. Each goes on its own PR with tests.
4. **Then report to the owner** in short messages:
   - what passed;
   - what failed, with the finding ids;
   - which items in §4 the device runs confirmed or disproved, re-ranked.
