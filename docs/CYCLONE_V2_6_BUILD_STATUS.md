# Cyclone V2.6 Beta — BUILT vs VERIFIED

This document deliberately separates code/CI evidence from physical Android verification.

## Built

- [x] Android app label: `Cyclone V2.6`
- [x] Android version: `0.8.0-v2.6-beta` / versionCode 9
- [x] new five-destination bottom navigation
- [x] center/default AI destination with visually dominant circular AI control
- [x] Home page
- [x] Learn page
- [x] AI page
- [x] Automations page
- [x] Brain page
- [x] global top-left Cyclone `C` navigation button
- [x] Cyclone navigation drawer available above primary pages
- [x] Settings hub
- [x] Connections page
- [x] Permissions page
- [x] AI History page
- [x] About page
- [x] App Learner moved from modal bottom sheet to full-page V2.6 experience
- [x] V2.5 `AppLearnerRuntime` reused rather than duplicated
- [x] Guided / Task / Passive learning controls retained
- [x] Pause / Take over / Stop retained
- [x] learned-app detail, local Ask App, screen knowledge and path display retained
- [x] existing Agent 1 `phone.*` toolbox reused
- [x] existing Agent 2 AutomationRuntime reused
- [x] existing V2.4 Guided Recorder reused
- [x] existing Agent 3 Quick Agent / OpenRouter runtime reused
- [x] custom OpenRouter model slug input
- [x] persistent AI session history in SQLite
- [x] decision/action/verification/failure event timeline
- [x] user-facing `display_summary` channel on phone tool calls
- [x] display summaries stripped before subsequent model context
- [x] trace privacy redaction
- [x] typed values intentionally omitted from generated trace summaries
- [x] model-authored `phone.type` display summaries ignored so typed values cannot be echoed into trace history
- [x] screenshot base64 omitted from persistent trace
- [x] user-only live trace overlay built with `TYPE_ACCESSIBILITY_OVERLAY`
- [x] trace overlay is non-focusable and non-touchable
- [x] trace overlay listens only to local `AiTraceBus`
- [x] trace overlay can be toggled from AI page
- [x] enabled trace overlay is restored when Cyclone resumes and Accessibility is connected
- [x] explicit UX language that trace is not raw hidden chain-of-thought
- [x] `cyclone_brain.db` persistent post-task learning store
- [x] post-task reports
- [x] reusable phone-tool sequence extraction
- [x] routine signatures
- [x] success/failure evidence
- [x] confidence model that increases with repeated success and drops with failure evidence
- [x] Brain lookup for matching known routine goals
- [x] Obsidian-compatible `Cyclone Brain/Task Reports/...` mirror
- [x] Obsidian-compatible `Cyclone Brain/Memory/Overview.md`
- [x] existing `Cyclone Brain/Apps/...` App Learner knowledge retained
- [x] tests for trace privacy
- [x] tests that trace humanization does not include typed value
- [x] tests that model-provided typing summaries cannot leak typed content into AI history
- [x] tests for arbitrary custom model slug
- [x] tests for Brain goal normalization
- [x] tests for Brain confidence changes
- [x] tests for reusable tool-sequence extraction
- [x] V2.6 architecture documentation

## CI verified

The following GitHub Actions runs completed successfully for the final V2.6 source commit `64ffc7626b4f80502cdc0fa82fb69eaf0d2c9c65`:

- [x] `Cyclone Mobile V2.6 Beta APK` — Android unit tests
- [x] `Cyclone Mobile V2.6 Beta APK` — APK assembly
- [x] `Cyclone Mobile V2.6 Beta APK` — artifact upload
- [x] `Cyclone Mobile V2.6 Integration` — Core/Hermes/Mobilerun Python tests
- [x] `Cyclone Mobile V2.6 Integration` — Android integration tests
- [x] `Cyclone Mobile V2.6 Integration` — APK assembly/artifact
- [x] `Cyclone Mobile V2.6 Embedded Runtime` — Mobilerun source check
- [x] `Cyclone Mobile V2.6 Embedded Runtime` — Android tests
- [x] `Cyclone Mobile V2.6 Embedded Runtime` — unified APK assembly/artifact

CI artifact:

- `cyclone-mobile-v2-6-beta-apk`

## Physical Android verification — NOT YET CLAIMED

- [ ] install exact V2.6 APK on Android 14+ hardware
- [ ] launcher displays `Cyclone V2.6`
- [ ] default first product destination is AI
- [ ] five bottom navigation destinations render correctly on target phone
- [ ] center AI button remains the visual focal point across screen sizes
- [ ] top-left `C` opens Cyclone navigation on every primary destination
- [ ] Connections settings reconnect to actual Cyclone Core
- [ ] Accessibility, notification and calendar permission buttons open the correct Android settings
- [ ] custom OpenRouter model slug successfully runs a real request
- [ ] AI History survives process restart
- [ ] trace history does not persist typed sensitive values in a real task
- [ ] trace overlay appears over a third-party app
- [ ] trace overlay remains visible without becoming the agent's active automation target
- [ ] trace overlay summaries update live while Quick Agent works
- [ ] disabling trace overlay removes it immediately
- [ ] App Learner full-page flow starts a real learning session
- [ ] learner Accessibility overlay still appears during learning
- [ ] Pause stops exploration immediately
- [ ] Take over blocks AI/agent mutations
- [ ] Return requires fresh observation and resumes safely
- [ ] Stop terminates learning immediately
- [ ] learned app graph remains persisted across process restart
- [ ] Automations page runs an existing V2.5/V2.4 automation correctly
- [ ] Guided Recorder still works from V2.6 Automations page
- [ ] successful Quick Agent task creates Brain report on-device
- [ ] repeated successful task increases routine-memory confidence on-device
- [ ] failed task creates usable failure report
- [ ] Obsidian-compatible Task Report/Memory files are created and readable
- [ ] App Learner `Apps/` mirror remains intact
- [ ] no secrets appear in human-readable Brain Markdown during adversarial privacy test
- [ ] 24-hour reliability / battery test

No unchecked physical item may be described as VERIFIED until there is real-device evidence.
