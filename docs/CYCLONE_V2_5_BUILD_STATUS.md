# Cyclone V2.5 Beta — BUILT vs VERIFIED

This document intentionally separates implementation/CI evidence from physical Android verification.

## Built

- [x] App label/version: Cyclone V2.5 / `0.7.0-v2.5-beta`
- [x] App Learner Beta entry point and installed-app selector
- [x] Guided Learning mode
- [x] Task Learning mode
- [x] Passive Learning mode (explicit selected-app opt-in only)
- [x] natural-language learning instructions and in-session focus updates
- [x] AppExplorer controlled graph discovery
- [x] selected-package exploration boundary
- [x] semantic Screen representation
- [x] dynamic screen-instance normalization
- [x] semantic/structural fingerprints
- [x] advertised Android Accessibility actions in phone observations
- [x] safe / consequential / authentication action classification
- [x] consequential/authentication action blocking during autonomous exploration
- [x] pause / resume / take-over / return / stop controls
- [x] Accessibility-overlay learner controls over the selected app
- [x] local SQLite App Knowledge Store
- [x] screen/action/transition graph with confidence and knowledge states
- [x] DISCOVERED / UNDERSTOOD / VERIFIED / STALE / UNKNOWN states
- [x] app-version metadata in learned records
- [x] success/failure timestamps, failure count and alternative selector storage
- [x] Obsidian-compatible `Cyclone Brain/Apps/...` mirror
- [x] sensitive-value exclusion/redaction rules for human-readable memory
- [x] visual consumer App Map
- [x] per-screen “Why does Cyclone think this?” evidence UI
- [x] mark-screen-incorrect / stale-map correction
- [x] Ask App from local learned graph
- [x] goal-relevant graph retrieval for compact AI context
- [x] learned graph path planner
- [x] graph path → Agent 2 AutomationDefinition compiler
- [x] generated graph automations remain disabled until review
- [x] learned Skill candidate generation
- [x] uncertain Skills do not automatically become active
- [x] selector self-healing candidate path using fresh semantic search
- [x] deterministic learned-route executor
- [x] optional OpenRouter semantic exploration planner using existing model selection/key store
- [x] deterministic learning fallback with no AI/API key
- [x] existing V2.3 Quick Agent, V2.4 Guided Recorder, Agent 1 toolbox, Agent 2 runner, Agent 3 Core/Hermes bridge and embedded Mobilerun preserved
- [x] unit tests for dynamic normalization, safety, privacy anchoring, graph retrieval, path planning, workflow compilation and Skill candidates

## CI verified

- [x] Cyclone Core/Hermes mobile test suite passes on the V2.5 branch
- [x] Mobilerun source/embedded module is included in unified build configuration
- [x] Kotlin/Android unit-test compilation passes after fixing the Android `ACTION_SCROLL_TO_POSITION` constant
- [ ] Final V2.5 APK artifact gate — mark only when the final branch build uploads successfully
- [ ] Final embedded-runtime APK gate — mark only when the final branch build uploads successfully

## Physical Android verification — NOT YET CLAIMED

- [ ] install exact V2.5 APK on Android 14+ hardware
- [ ] first launch and V2.5 labeling
- [ ] Accessibility permission/service survives app switching
- [ ] select Android Settings in App Learner
- [ ] Task mode: `Learn where Battery settings are`
- [ ] learner overlay appears over Settings
- [ ] Pause stops exploration immediately
- [ ] Take Over technically blocks agent mutations
- [ ] Return requires a fresh observation and resumes safely
- [ ] Stop immediately terminates exploration
- [ ] selected-app boundary prevents autonomous navigation into another package
- [ ] Settings and Battery are recognized as separate semantic screens
- [ ] Settings → Battery transition is persisted
- [ ] fresh session `Open Battery settings` reuses learned route without rediscovery
- [ ] Passive Learning observes only the explicitly selected app
- [ ] consequential/authentication controls are not clicked on a real app
- [ ] graph → Automation proposal runs correctly on hardware
- [ ] self-healing selector path recovers a deliberately stale selector
- [ ] App Map/Ask App reflect persisted data after process restart
- [ ] 24-hour reliability/battery test

No unchecked physical item may be described as VERIFIED until there is device evidence.
