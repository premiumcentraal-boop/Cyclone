# Codex × Cyclone connection architecture

This is the implementation contract for future agents changing Codex phone access.

## Supported path

`Codex desktop / CLI / IDE` → local MCP STDIO → `CycloneAgentMCP.exe` → authenticated loopback Device Gateway → selected paired Android session → typed Android policy/executor.

Use the PC Companion **AI connections** page as the primary onboarding surface. The CLI commands in `README.md` are diagnostics and automation fallbacks, not the normal user journey.

The current OpenAI Codex MCP documentation is the source of truth for `config.toml` fields and approval behavior: <https://developers.openai.com/codex/mcp>.

## Invariants

- Never write the Gateway bearer token, Android pairing secret, typed phone content, or model credential to Codex TOML.
- Keep the MCP server on STDIO unless a separately reviewed authenticated loopback Streamable HTTP design replaces it.
- Preserve `default_tools_approval_mode = "writes"` or a stricter policy. Read tools may be immediate; mutation tools must remain approval-aware.
- `phone_list` is the only unscoped inventory tool. With multiple READY phones, every other tool requires an explicit `device_id`.
- Observe before mutation; discard the observation witness after mutation; observe again for verification.
- Do not add shell, PowerShell, arbitrary ADB, root, subprocess, script evaluation, or generic command tools.
- A Companion restart may rotate both port and token. Recover only by loading the current DPAPI-protected loopback session and retrying a failed request once.
- Configuration writes must preserve unrelated Codex settings, validate the complete TOML candidate, and replace atomically.

## Change checklist

When adding a phone capability:

1. Add it to Android/Gateway typed capability discovery and policy first.
2. Add the exact typed MCP tool or action only after the Gateway contract exists.
3. Update server instructions/descriptions so Codex knows the intended observe → act → verify workflow.
4. Update the expected tool inventory and multi-device schema tests.
5. Verify connector configuration, Gateway recovery, MCP tools, PC UI build, frozen sidecar packaging, and the Windows installer lane.

Do not claim physical-device verification unless the released installer and APK were exercised together on real hardware.
