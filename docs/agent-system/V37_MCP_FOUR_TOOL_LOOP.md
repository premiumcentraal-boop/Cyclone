# V3.7 MCP default surface

Default PC AI loop: `phone_status` → `phone_locate(goal)` → `phone_act` → `phone_skill_save` | `phone_skill_run`. Skills write drafts through existing AutomationStore / SkillCompiler.compile; do not add a second store.
