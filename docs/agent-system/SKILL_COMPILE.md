# V4 skill compile

Owner lane B. Function: `SkillCompiler.compile`.

A verified path of two or more steps becomes a **disabled draft** in the existing `AutomationStore` (`enabled = false`, `status = draft`). Unverified steps and steps without after-state do not write. Params keep slots only; password and other typed secrets are stripped.

Do not add a second JSON brain or a parallel `routines.json` for Codex. Human review stays in Automations. Workers do not promote.
