# Cyclone Mobile V2.5 Beta — App Learner integration plan

## Reusable components found

- **Agent 1 / Phone Toolbox**: `PhoneToolRegistry`, `PhoneToolExecutor`, `CycloneAccessibilityService`, normalized `UiSnapshot`, semantic selectors, waits/assertions, screenshot fallback and HUMAN/AGENT controller lock.
- **Agent 2 / Automation Studio**: `AutomationRuntime`, workflow DSL, `AutomationProposalCompiler`, persisted automations/skills, retries, checkpoints and run history.
- **Agent 3 / AI runtime**: OpenRouter quick-agent/context harness, Hermes/Core bridge, model selection and secure OpenRouter key storage.
- **V2.4 Guided Recorder**: semantic evidence capture, screenshots + Accessibility snapshots, deterministic workflow compilation and optional AI optimization.

## V2.5 architecture

App Learner is a new Level-3 knowledge layer. It never bypasses the existing phone toolbox and never executes a workflow directly.

1. `AppExplorer` observes a selected package through Agent 1 APIs, recognizes/creates semantic `LearnedScreen` nodes, classifies visible actions, and records safe transitions.
2. `AppKnowledgeStore` persists app/screen/action/transition records in local SQLite for fast graph queries and mirrors safe human-readable summaries to an Obsidian-compatible `Cyclone Brain/Apps/...` directory.
3. `AppGraphRetriever` returns only goal-relevant screens/transitions instead of the whole map.
4. `GraphAutomationCompiler` converts a verified graph path to Agent 2 `AutomationDefinition` objects for review/test/save.
5. `SkillCandidateGenerator` proposes reusable skills from repeated/high-confidence paths without enabling uncertain skills automatically.
6. `AppLearnerAiPlanner` uses the existing OpenRouter model selection only when deterministic exploration needs semantic help. Screen/app text is always treated as untrusted environment data.
7. `AppLearnerScreen` exposes app selection, learning modes, progress, map, Ask App, path-to-automation creation and inspectability.

## Safety rules

- Exploration is hard-bound to the selected package. Cross-app transitions stop unless explicitly allowed later.
- Consequential labels/actions (purchase/pay/send/delete/submit/account/security/etc.) are mapped but never clicked automatically.
- CAPTCHA, 2FA, authentication and identity verification stop exploration and use existing takeover semantics.
- `DISCOVERED`, `UNDERSTOOD`, `VERIFIED`, `STALE`, `UNKNOWN` are separate states. CI never marks real-device behavior VERIFIED.
- No passwords, tokens, cookies, 2FA codes, payment credentials or sensitive form values are written to Markdown/Obsidian.

## Incremental implementation order

1. semantic graph models + safe-action policy
2. SQLite knowledge store + Obsidian mirror
3. screen recognition/dynamic normalization + graph retrieval/path planning
4. controlled `AppExplorer`
5. graph-to-Automation compiler + Skill candidate generation
6. optional OpenRouter semantic planner/recovery
7. App Learner Beta UI + visual map + Ask App
8. deterministic unit tests + Android CI + BUILT vs VERIFIED report
