# Cyclone Copilot instructions

Before making changes, read the repository root `AGENTS.md` and
`docs/agent-system/FAST_WORK_AND_TOKEN_PLAYBOOK.md`. Follow their source-of-truth order, ownership
lanes, product invariants, scope-first context rules and minimal-gate release rules.

Then run:

```bash
python scripts/agent/cyclone-context.py --markdown
```

Classify the changed paths first, then load only the owner-lane context from
`docs/agent-system/README.md`. For any `apps/mobile/**` change,
also read `apps/mobile/AGENTS.md` and `docs/agent-system/FAST_RELEASE_PLAYBOOK.md`. Use the checked-in
Gradle wrapper, never create a version-specific workflow, and record whether the change requires a
new distributed APK and `versionCode` increment.
