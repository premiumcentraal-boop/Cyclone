# Cyclone Agent MCP

`CycloneAgentMCP.exe` is Cyclone Desktop V1's single agent-facing MCP server. Codex, OpenCode, Copilot CLI, and generic MCP harnesses receive the same typed phone tools. It never speaks ADB or Android directly; it talks only to the authenticated loopback Cyclone PC Device Gateway.

Primary transport is MCP STDIO. No LAN MCP listener is enabled. The V1 package intentionally does not expose an HTTP MCP mode until a separate loopback authentication design is integrated and tested.

## Device selection

Call `phone_list` to inspect safe device readiness. A phone-scoped tool may omit `device_id` only when exactly one device is READY. If more than one is READY, Cyclone returns `DEVICE_SELECTION_REQUIRED` and a safe list of device IDs; the agent must choose explicitly.

The exact-base PC gateway is legacy single-device. The MCP client falls back to that behavior when `/v1/devices` is absent. Multi-device operation therefore assumes Agent 1's Desktop runtime exposes `/v1/devices` and accepts `device_id` scoping while preserving the existing V1 capability endpoints.

## Host connectors

Examples:

```text
CycloneAgentMCP.exe connect codex --dry-run
CycloneAgentMCP.exe connect codex --verify
CycloneAgentMCP.exe disconnect codex
CycloneAgentMCP.exe copy-config generic
CycloneAgentMCP.exe profile opencode-deepseek
CycloneAgentMCP.exe profile copilot-deepseek
CycloneAgentMCP.exe status
```

Generated MCP configuration never embeds `CYCLONE_DEVICE_GATEWAY_TOKEN`, Android session credentials, or model-provider API keys. Runtime secrets stay in the companion's secure runtime environment/credential mechanism.

## DeepSeek

DeepSeek is a model provider, not a phone-control implementation. For OpenCode, configure the DeepSeek provider through OpenCode's provider/auth flow and use Cyclone as the MCP server. For Copilot CLI, use its BYOK provider settings only with a DeepSeek/OpenAI-compatible endpoint/model that supports the CLI's required streaming and tool-calling behavior. The Cyclone tool surface does not change.

## Security boundary

There is no MCP tool for shell, PowerShell, subprocess, arbitrary command execution, arbitrary ADB, root, `su`, or script evaluation. Phone mutation remains inside Cyclone's Android policy and canonical `PhoneToolExecutor` path.
