# Cyclone V2.7 Beta — BUILT vs VERIFIED

This file deliberately separates source implementation, CI evidence and physical Android evidence.

## Built in source

- [x] app/version wiring for `Cyclone V2.7` / `0.9.0-v2.7-beta`
- [x] Android 14+ minimum retained (`minSdk 34`)
- [x] V2.7 AI-first five-tab product shell
- [x] existing Agent 1 phone toolbox reused
- [x] existing Agent 2 Automation Studio / Guided Recorder reused
- [x] existing Agent 3 OpenRouter/Hermes boundary retained
- [x] existing V2.5 App Learner graph retained
- [x] existing V2.6 task reports retained
- [x] new Adaptive Brain SQLite store
- [x] launcher app inventory
- [x] per-action privacy-safe micro-skills
- [x] per-skill success/failure evidence
- [x] per-skill confidence updates
- [x] learned ordered task paths
- [x] Brain recall before model decision 1
- [x] deterministic `phone.home` direct planning
- [x] deterministic installed-app launch planning
- [x] high-confidence repeated-path replay gate
- [x] fresh observation after each replay/model action
- [x] failed action evidence lowers only matching skill/path
- [x] successful action evidence raises matching skill/path
- [x] background Brain refinement after foreground task completes
- [x] background AI refinement restricted to non-executable notes
- [x] executable confidence remains based on real action evidence
- [x] Obsidian-compatible Micro Skills mirror
- [x] Obsidian-compatible Apps mirror
- [x] Obsidian-compatible Learned Paths mirror
- [x] Obsidian-compatible User Notes mirror
- [x] Brain Chat UI inside AI tab
- [x] local Brain retrieval works without OpenRouter
- [x] `Remember that ...` knowledge ingestion
- [x] explicit Save knowledge action
- [x] Brain Chat JSONL history + Markdown mirror
- [x] outcome-first AI History redesign
- [x] All / Success / Failed history filters
- [x] result/learning summary separated from technical trace
- [x] technical trace toggle hides model/observe noise by default
- [x] task-scoped V2.7 trace overlay
- [x] overlay session-id filtering
- [x] clear Completed / Stopped overlay state
- [x] automatic fade/slide dismissal
- [x] overlay preference remains for next task without persistent window
- [x] Follow Me cross-app learning mode
- [x] Follow Me forces HUMAN controller
- [x] Follow Me performs no autonomous clicks
- [x] Follow Me ignores text-change contents
- [x] Follow Me rejects password/sensitive click fields
- [x] Follow Me extends existing per-app semantic graphs
- [x] Follow Me records cross-app/app-launch Brain evidence
- [x] typed values omitted from Adaptive Brain micro-skill params
- [x] unit tests for privacy sanitization
- [x] unit tests for confidence increase/decrease
- [x] unit tests for goal normalization
- [x] unit tests for stable Home/app-open identities
- [x] V2.7 architecture + integration documentation

## CI verification

Pending until the final V2.7 source commit passes all GitHub Actions gates:

- [ ] `Cyclone Mobile V2.7 Beta APK` — Android unit tests
- [ ] `Cyclone Mobile V2.7 Beta APK` — APK assembly
- [ ] `Cyclone Mobile V2.7 Beta APK` — artifact upload
- [x] `Cyclone Mobile V2.7 Integration` — Core/Hermes/Mobilerun Python tests (first PR run)
- [ ] `Cyclone Mobile V2.7 Integration` — Android integration tests
- [ ] `Cyclone Mobile V2.7 Integration` — APK assembly/artifact
- [ ] `Cyclone Mobile V2.7 Embedded Runtime` — Mobilerun source check
- [ ] `Cyclone Mobile V2.7 Embedded Runtime` — Android tests
- [ ] `Cyclone Mobile V2.7 Embedded Runtime` — unified APK assembly/artifact

## Physical Android verification — NOT YET CLAIMED

- [ ] install the exact final V2.7 APK on Android 14+ hardware
- [ ] launcher displays `Cyclone V2.7`
- [ ] five-tab UI renders correctly
- [ ] AI remains central/default destination
- [ ] ask `Go Home` and verify real Android Home appears
- [ ] confirm `Go Home` creates/updates a `phone.home` micro-skill
- [ ] repeat `Go Home` and confirm Cyclone uses local deterministic planning with 0 AI decisions
- [ ] ask `Open <installed app>` and verify local app inventory resolves the correct package
- [ ] repeat an already successful multi-step simple task twice and verify learned-path reuse reduces model decisions
- [ ] force one stale selector failure and verify only that micro-skill confidence drops
- [ ] verify prior unrelated successful skills remain intact after a failed run
- [ ] verify task report + Micro Skills/App/Path Markdown files survive process restart
- [ ] add a note using `Remember that ...` in Brain Chat and verify persistence after restart
- [ ] ask Brain Chat about learned apps/skills and verify answer is grounded in stored knowledge
- [ ] verify background refinement runs after task return and cannot increase executable confidence itself
- [ ] run a task with overlay enabled and verify overlay appears only for that session
- [ ] verify overlay announces task complete then removes itself
- [ ] verify stopped/failed task gets a stopped state then removes itself
- [ ] verify the next task can show overlay again without manually retoggling it
- [ ] verify history All / Success / Failed filters
- [ ] verify history default timeline is easy to read and technical mode reveals hidden model/observe events
- [ ] start Follow Me and verify controller is HUMAN
- [ ] navigate manually through at least two third-party apps
- [ ] verify Follow Me records visited apps/screens/navigation without generating autonomous input
- [ ] verify cross-app route evidence appears in Brain
- [ ] verify typed form contents/passwords/OTP values do not appear in SQLite mirrors/history
- [ ] stop Follow Me and verify controller returns safely with fresh observation requirement
- [ ] 24-hour reliability and battery test

No unchecked physical item may be described as VERIFIED without real-device evidence.
