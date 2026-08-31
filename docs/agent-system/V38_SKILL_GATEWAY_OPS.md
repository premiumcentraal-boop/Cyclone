# V3.8 Android skill gateway ops

Authenticated phone gateway operations: `skill.compile`, `skill.run`, `skill.match`.

- `skill.compile` → `SkillDraftSink.saveFromMcp` / `SkillCompiler.compile` into the existing `AutomationStore`. Always `status=draft`, `enabled=false`. 2+ verified steps with after-state required. Secrets stripped. Duplicate `(app, goal, start pageKey)` updates the same id. PC cannot request `verified`.
- `skill.run` → load capsule from `AutomationStore`. Live run only when the description marker is `status=verified`. Drafts require `dryRun=true` and never mutate. Live steps go through the existing `PhoneToolExecutor`. Per-step act envelopes: `ok`, `pageChanged`, `before`, `after.pageCard`, `delta`, `errorClass`, `generation`. GATE classes pay/send/delete/grant stay phone-side; PC `autoApprove` is ignored.
- `skill.match` → verified skill for `goal` + `pageKey` with `skipModel=true`. Drafts never match. Empty match is ok, not an error.

Capsules are never auto-enabled. Do not merge yet. Do not tag 4.0.0. No version bump.
