# ChatGPT + Grok chat connector setup

Use **Cyclone One → Settings → Remote MCP (ChatGPT / Grok chat)** for the live URL and bearer. Do not hunt PowerShell scripts.

Local Grok Build and Cursor keep stdio MCP (`CycloneAgentMCP.exe serve` in `~/.grok/config.toml`). Do **not** paste the public `*.trycloudflare.com` URL there.

## Before you paste anything

1. Settings → Remote MCP → **Start tunnel**.
2. Wait until status is **Running**.
3. Copy **MCP URL** (`https://…/mcp`) and **Copy token**.
4. Run **Smoke**. It must show 401 without auth and 200 initialize with the bearer.

Quick tunnels mint a **new hostname every Start/Restart**. Re-copy the URL after each restart.

## ChatGPT (web, Developer Mode)

1. ChatGPT web → profile → Settings → enable **Developer mode** (under Security / Apps / Connectors — OpenAI moves this toggle).
2. Settings → Apps / Connectors / Plugins → create a custom **Remote MCP** app.
3. Name: `Cyclone Phone`.
4. MCP server URL: the Settings MCP URL (`…/mcp`).
5. Authentication: **Bearer token** (the token from Copy token). Never pick “No authentication”.
6. Trust this application (it is your own MCP).
7. Phase 1 (readonly, default): enable observe tools only (`phone_status`, `phone_observe`, `phone_locate`, `phone_screenshot`, `phone_devices`). `phone_act` is hidden until you switch Settings to **full**.

ChatGPT cannot use `localhost`. Free / Go plans cannot add custom MCP.

## grok.com connectors

1. Open [grok.com/connectors](https://grok.com/connectors).
2. Add a custom connector with the **same** MCP URL and bearer.
3. xAI rejects localhost and RFC1918 URLs — the cloudflared HTTPS URL is required.
4. Do not use OpenAI Secure MCP Tunnel as the dual-front URL.

## Safety

- Default mode is **readonly**. Full mode exposes mutating phone tools to anyone who has the bearer.
- Rotate the token from Settings after sharing it, and after a suspected leak.
- `session_id=default-foreground` is required for live-display observe/act/locate.
