# Cyclone One 1.5.4 — VMOS Cloud AI connection

## Product acceptance

One 1.5.4 is accepted when a fresh Cyclone One install can take one owned VMOS Cloud phone through this flow without a developer:

1. Open **ChatGPT Attach → Add pad**.
2. Paste the VMOS **Connect command** (for example `ssh s@192.0.2.10 -p 1824 -L 63670:localhost:1 -Nf`).
3. Paste the VMOS **Connect Key** in its separate password field. The key is stored only on the PC and is never written into the ChatGPT handoff.
4. Click **Sync fleet**. The pad must show ADB `device`, Cyclone Mobile `running`, and a green **Cloud AI session** check.
5. If the pad is not paired/trusted, finish **Cyclone Mobile → PC Gateway & QR pairing**, then Sync again.
6. Click **Share to ChatGPT**. The HTTPS CONTROL_API becomes reachable and the handoff is copied only when at least one real Cloud Control session exists.
7. Paste the handoff into the Custom GPT and verify an observe → tap → observe loop on the VMOS phone.

## 1.5.4 reliability changes

- Production no longer fabricates a `local-stub` session when Cloud Control session minting fails. Failed session minting is a visible, blocking readiness failure.
- Gateway inventory is rescanned with bounded retries before session minting so the freshly attached `localhost:<port>` VMOS serial can be bound to a Cyclone device.
- A minted session is checked for ADB, Mobile, Gateway pairing, and trust before it is exportable.
- Handoff generation and copy/save paths require a real `control-api` session.
- The VMOS Connect command can be pasted directly, avoiding manual host/port/forward transcription.
- SSH tunnel startup waits for the local forward, uses `ExitOnForwardFailure`, supports keyboard-interactive/password authentication, and deletes plaintext askpass/Connect-Key helper files in a `finally` block on success or failure.

## Security invariant

The Custom GPT may receive only `DEVICE_ID`, `SESSION_ID`, `SESSION_TOKEN`, `CONTROL_API`, `MOBILE`, `ADB`, goal, and notes. SSH Connect Keys, VMOS AccessKeys/admin keys, and private tunnel details stay on the PC. Phone mutation remains on Cyclone Mobile / PhoneToolExecutor.

## Release evidence

- Base: Cyclone One 1.5.3 (`a2eb65f14cfb177e96354744ba8469dae5ca6a61`).
- Mobile identity remains 4.3.6.
- Physical VMOS end-to-end acceptance is required before declaring the product demo complete; CI proves code/build/installer contracts but cannot prove a live provider account or phone.
