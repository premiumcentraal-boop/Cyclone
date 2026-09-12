# ChatGPT Attach - stable connection

Three steps:

1. Open Cyclone One -> ChatGPT Attach. Add a VMOS pad (host/port/Connect Key stay on this PC) and click **Sync fleet**.
2. Click **Share to ChatGPT**. Cyclone starts Cloud Control on the Device Gateway (`http://127.0.0.1:<gateway>/cloud`) and publishes it over HTTPS (trycloudflare). The handoff is copied.
3. Paste the handoff into your Custom GPT. Auth is Bearer `SESSION_TOKEN`. Actions call `{CONTROL_API}/v1/...`.

## CONTROL_API

- Local stub (this PC only): `http://127.0.0.1:<gateway-port>/cloud`
- ChatGPT Plus Actions: `https://<trycloudflare-host>/cloud` from **Share to ChatGPT**
- Health (no auth): `GET {CONTROL_API}/v1/health`
- Observe/status/tap: `Authorization: Bearer SESSION_TOKEN`

VMOS is ADB transport only. Mutations run through Cyclone Mobile `PhoneToolExecutor`. Never paste SSH Connect Keys or VMOS AccessKeys.

Loopback URLs cannot be used as Custom GPT Action servers. Always Share to ChatGPT (or put a named Cloudflare tunnel in CONTROL_API base) before ChatGPT tries to observe.
