# Cyclone Agent MCP

Generic official-SDK MCP server for Cyclone Desktop V1. The same `cyclone-phone` tool surface is used by Codex, OpenCode, Copilot CLI, and generic MCP harnesses.

- Primary transport: STDIO.
- Backend: authenticated loopback Cyclone PC Device Gateway only.
- Multi-device: `phone_list`; all phone-scoped tools accept optional `device_id`; omission is allowed only with exactly one READY device.
- No shell, PowerShell, arbitrary command execution, arbitrary ADB, root, `su`, subprocess, or script-evaluation tools.
- No gateway/model API secrets are written to generated MCP configs or audit logs.

Use `cyclone-agent-mcp --help` for connector and profile commands.
