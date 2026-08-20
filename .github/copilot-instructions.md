# Cyclone Copilot instructions

Before making changes, read the repository root `AGENTS.md` and follow its source-of-truth order, ownership lanes, product invariants and release rules.

Then run:

```bash
python scripts/agent/cyclone-context.py --markdown
```

Use `docs/agent-system/README.md` as the canonical knowledge hub. For any `apps/mobile/**` change,
also read `apps/mobile/AGENTS.md` and `docs/agent-system/FAST_RELEASE_PLAYBOOK.md`. Use the checked-in
Gradle wrapper, never create a version-specific workflow, and record whether the change requires a
new distributed APK and `versionCode` increment.
