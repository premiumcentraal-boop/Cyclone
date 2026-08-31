# Cyclone Phone MCP: four-tool default loop

Default PC AI surface (V3.7):

```
phone_status → phone_locate(goal) → phone_act → phone_skill_save | phone_skill_run
```

Everything else on this server is advanced. Do not invent a fifth default tool.

1. `phone_status` — gateway, ADB, bridge, Accessibility readiness.
2. `phone_locate(goal)` — bounded Page Card (`pageText` + `pageSummary` must survive) plus goal-ranked hits.
   If a **verified** skill matches `goal` + `pageKey`, say so and call `phone_skill_run` instead of debating the screen.
3. `phone_act` — one typed mutation. HTTP 200 is not success. Read `ok`, `pageChanged`, `before`, `after.pageCard`, `delta`, `errorClass`, `generation`.
4. `phone_skill_save` / `phone_skill_run` — the existing AutomationStore / `SkillCompiler.compile` draft path. No second JSON brain.

## Locate, then act

Use `phone_locate` before every phone mutation.

1. Pick a current `elementId` from the Page Card or semantic search result.
2. Pass that ID to `phone_act`; do **not** create text, fuzzy, bounds, or coordinate selectors.
3. Treat every `elementId` as observation-scoped. It expires after a mutation — never reuse it.
4. Prefer the verified route or matched skill returned by Cyclone.
5. If Page Card context is truncated, search and inspect before using screenshots.

## Skills

- `phone_skill_save` compiles **only** when 2+ steps are verified. It writes `status=draft` (disabled for review) into the same AutomationStore Agent 3 uses. Unverified steps do not write. Secret slots are stripped, never persisted.
- `phone_skill_run` runs only `status=verified`. A draft without `dryRun=true` is denied. Each step returns an act envelope. Execution goes through PhoneToolExecutor via the gateway, not a side executor.
- Workers cannot flip `draft → verified`. Policy (pay/send/delete/grant) stays on the phone.

## Forbidden

The server exposes no shell, ADB, root, PowerShell, or generic command execution.
Do not dump raw accessibility trees. Do not put plaintext passwords in locate or skill payloads.
