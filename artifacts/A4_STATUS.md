# A4 status — Settings MCP tunnel terminal

Canonical copy: [`docs/A4_MCP_TUNNEL_SETTINGS.md`](../docs/A4_MCP_TUNNEL_SETTINGS.md).

## What shipped

Cyclone One Settings card **Remote MCP (ChatGPT / Grok chat)** starts/stops the bundled HTTPS auth-gateway tunnel.

- Pack: `apps/pc-companion/src-tauri/resources/mcp-tunnel/`
- Install: `%LOCALAPPDATA%\Cyclone One\mcp-tunnel\`
- Controls: Start / Stop / Restart / Smoke / Copy URL / Copy token (full token to clipboard only) / Rotate / Readonly vs Full
- Local Grok Build / Cursor stdio (`~/.grok/config.toml`) is not changed

## How to Start from Settings

Settings → Remote MCP → **Start tunnel** → wait for **Running** → Copy MCP URL + Copy token → **Smoke** → paste into ChatGPT / grok.com → **Stop** when done.

## Smoke results (Agent PC, 2026-09-08)

Bundled scripts, same path Settings will call:

| Check | Result |
|---|---|
| Start | **Running** — MCP `https://screening-fleet-parks-marketplace.trycloudflare.com/mcp` (quick tunnel; hostname will change next Start) |
| Token display | last-4 only (`38cc`); full token never printed |
| Local smoke | **SMOKE PASSED** — `/health` 200, `/mcp` no auth **401**, initialize with bearer **200**, `phone_act` blocked in readonly |
| Public `/health` | 200 |
| Public `/mcp` no auth | **401** |
| Stop | loopback `/health` down |
| `~/.grok/config.toml` | hash unchanged (`EDD9CFCF…`); no trycloudflare URL written |

Node unit smoke (CI): same 401/200/allowlist contract plus refuse `GATEWAY_HOST=0.0.0.0`. Frontend tests: 80 passed, including existing Settings cards still present.

## Residual risks

- Quick tunnel hostname **churn** on every Start/Restart — re-copy the URL into ChatGPT / grok.com
- Tunnel dies if the PC sleeps; Node 18+ and cloudflared must be installed
- `GATEWAY_MODE=full` is still bearer-gated but exposes mutating tools to the token holder
- Physical Pixel 8 chat-connector attach remains **UNVERIFIED**
