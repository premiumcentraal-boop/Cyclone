# Cyclone Agent MCP

Generic official-SDK MCP server for Cyclone One. The same `cyclone-phone` tool surface is used by Codex, OpenCode, Copilot CLI, and generic MCP harnesses.

- Primary transport: STDIO.
- Backend: the **Cyclone One Companion** loopback Device Gateway (USB READY). Do not point agents at a standalone classic `:8765` process. Keep Cyclone One open; MCP inherits the loopback URL from the Companion store.
- Multi-device: `phone_list`; all phone-scoped tools accept optional `device_id`; omission is allowed only with exactly one READY device.
- No shell, PowerShell, arbitrary command execution, arbitrary ADB, root, `su`, subprocess, or script-evaluation tools.
- No gateway/model API secrets are written to generated MCP configs or audit logs.

`phone_locate` ranks controls; it does not navigate. Use `phone.open_app`, `phone.launch_intent`, or `phone.wait_for`.

Examples (`cyclone-agent-mcp --help`, `verify`, and `copy-config` each print these):

```text
phone.click / phone.tap  {"elementId": "<current id>"}
phone.open_app           {"package": "com.android.vending"}
phone.type               locate → click to focus → {"elementId": "<current id>", "text": "Cyclone"} with user_authorized=true
phone.launch_intent      {"uri": "market://details?id=com.android.chrome"}
phone.wait_for           {"timeoutMs": 8000, "condition": {"type": "package_equals", "package": "com.android.vending"}}
```

If `pageChanged` or `afterPackage` matches, `ok` follows the UI effect. `PROTOCOL_MISMATCH` is a warning, not a stop.

Use `cyclone-agent-mcp --help` for connector and profile commands.
