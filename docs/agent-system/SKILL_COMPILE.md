# V4 skill compile + GATE

Owner lane B + Policy.

Functions:

- `SkillCompiler.compile` — verified 2+ step path → disabled draft (`enabled = false`, `status = draft`) in the existing `AutomationStore`.
- `SkillDraftSink.saveFromOverlayDone` / `saveFromMcp` — overlay DONE and MCP `phone_skill_save` land in that same store. No second JSON brain.
- `GatePolicy.evaluate` — GATE classes **pay / send / delete / grant**. Phone-side only. PC `autoApprove=true` is ignored.
- `SkillPromotion.requestStatus` — workers and PC cannot flip `draft → verified`.

Unverified steps and missing after-state do not write. Params keep slots only; password and other typed secrets are stripped. Duplicate (app, goal, start page) updates the same automation id and does not mint a second app-graph node.

Human review stays in Automations. Capsules are never auto-enabled.
