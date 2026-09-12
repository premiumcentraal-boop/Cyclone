# Cyclone One 1.5.4 — VMOS Cloud AI connection

## Product acceptance

One 1.5.4 is accepted when a fresh Cyclone One install can take one owned VMOS Cloud phone through this flow without a developer:

1. Open **ChatGPT Attach → Add pad**.
2. Paste the VMOS **Connect command** (for example `ssh s@192.0.2.10 -p 1824 -L 63670:localhost:1 -Nf`).
3. Paste the VMOS **Connect Key** in its separate password field. The key is stored only on the PC and is never written into the ChatGPT handoff.
4. Click **Sync fleet**. The pad must show ADB `device`, Cyclone Mobile `running`, and a green **Cloud AI session** check.
5. If the pad is not paired/trusted, finish **Cyclone Mobile → PC Gateway & QR pairing**, then Sync again.
6. Click **Share to ChatGPT**. The HTTPS CONTROL_API becomes reachable and the handoff is copied only when at least one real Cloud Control session exists.
7. One-time Custom GPT setup: paste **Copy instructions** + **Copy OpenAPI**, and set the Action editor's Authentication setting to **None**. The schema itself requires `X-Cyclone-Session-Token` on protected calls.
8. Paste the handoff into the Custom GPT. It reads `SESSION_TOKEN` from that attach block, supplies it as `X-Cyclone-Session-Token`, and verifies an observe → tap → observe loop on the VMOS phone.

## 1.5.4 reliability changes

- Production no longer fabricates a `local-stub` session when Cloud Control session minting fails. Failed session minting is a visible, blocking readiness failure.
- Gateway inventory is rescanned with bounded retries before session minting so the freshly attached `localhost:<port>` VMOS serial can be bound to a Cyclone device.
- A minted session is checked for ADB, Mobile, Gateway pairing, and trust before it is exportable.
- Handoff generation and copy/save paths require a real `control-api` session plus a live public HTTPS CONTROL_API.
- The VMOS Connect command can be pasted directly, avoiding manual host/port/forward transcription.
- SSH tunnel startup waits for the local forward, uses `ExitOnForwardFailure`, supports keyboard-interactive/password authentication, and deletes plaintext askpass/Connect-Key helper files in a `finally` block on success or failure.
- Quick Tunnel URLs are treated as ephemeral runtime state: a fresh running Share beats a stale saved `trycloudflare.com` URL.
- Short-lived Cloud sessions no longer require editing the Custom GPT's stored API-key secret after every Sync. The allowed `SESSION_TOKEN` travels in the handoff and the Action schema passes it in `X-Cyclone-Session-Token`; traditional `Authorization: Bearer SESSION_TOKEN` remains supported for non-GPT clients.
- The public GPT Action schema does not expose session minting, so ChatGPT cannot request a new session or obtain the local Cyclone gateway bearer.

## Security invariant

The Custom GPT may receive only `DEVICE_ID`, `SESSION_ID`, `SESSION_TOKEN`, `CONTROL_API`, `MOBILE`, `ADB`, goal, and notes. SSH Connect Keys, VMOS AccessKeys/admin keys, the local Cyclone gateway bearer, and private tunnel details stay on the PC. The short-lived session token remains device-scoped, and phone mutation remains on Cyclone Mobile / PhoneToolExecutor.

## Release evidence

- Base: Cyclone One 1.5.3 (`a2eb65f14cfb177e96354744ba8469dae5ca6a61`).
- Mobile identity remains 4.3.6.
- CI covers Gateway session-header auth, PC handoff/auth tests, PowerShell syntax, build, installer acceptance and provenance.
- Physical VMOS end-to-end acceptance is still required before declaring the live provider demo complete; CI cannot prove an external VMOS account, SSH login, Cloudflare tunnel, or real phone interaction.
