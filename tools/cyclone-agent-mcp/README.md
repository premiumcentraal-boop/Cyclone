# Cyclone Agent MCP

Generic official-SDK MCP server for Cyclone Desktop V1. The same `cyclone-phone` tool surface is used by Codex, OpenCode, Copilot CLI, and generic MCP harnesses.

- Primary transport: STDIO.
- Backend: authenticated loopback Cyclone PC Device Gateway only.
- Multi-device: `phone_list`; all phone-scoped tools accept optional `device_id`; omission is allowed only with exactly one READY device.
- `session_id` is required on observe/act/locate/search/inspect/screenshot/skill_run/group_act. Pass `default-foreground` for the live human display (display 0). Named workspace sessions require `display_id > 0`. Read session ids from `phone_status` when the gateway returns a sessions inventory.
- No shell, PowerShell, arbitrary command execution, arbitrary ADB, root, `su`, subprocess, or script-evaluation tools.
- No gateway/model API secrets are written to generated MCP configs or audit logs.

Use `cyclone-agent-mcp --help` for connector and profile commands.
