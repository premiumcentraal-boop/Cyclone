# Cyclone V3.3 Android Trust Protocol

Owner: Agent 2 Android lane  
Frozen Android implementation base: `65dd2a04e2fe4f8bfcf3924e72302dcd2afcf053`  
Protocol ID: `cyclone.android.trust.v3`  
Protocol version: `3.3`

This document freezes the Android/PC trust seam for Agent 3. It does not authorize release publication and it does not change the independent scrcpy media plane.

## Security model

Cyclone keeps two independent trust layers:

- Android ADB trust authorizes USB discovery and the media plane.
- Cyclone AI trust authorizes semantic context and typed remote/AI capabilities.

Normal V3.3 USB setup never asks the user to copy a bearer token or type a four-letter code. The PC owns a persistent P-256 identity key protected by Windows DPAPI. The phone owns a persistent P-256 identity key generated and used through Android Keystore. Neither private key crosses the protocol.

First trust is bound to both public-key fingerprints, a fresh PC nonce, a fresh phone nonce and a short-lived challenge. Cyclone Mobile must visibly show **Allow this PC** and **Reject**. `trust.complete` fails with `PHONE_CONFIRMATION_REQUIRED` until the local user has allowed the exact active challenge.

Trusted-PC records contain only public identity material and metadata. Sessions are fresh, memory-only bearer credentials with a five-minute maximum lifetime. App process restart drops sessions but preserves revocable trust. App-data clear/factory reset removes both the Android Keystore identity and trust records, making old PC trust unusable.

A trusted PC does not change Cyclone's AI authority profile. Every mutation still passes through the gateway action authority/policy governor and canonical `PhoneToolExecutor`.

## Transport

Normal transport remains:

```text
adb forward tcp:8766 localabstract:cyclone_gateway
```

No LAN listener is introduced. Wire format remains one UTF-8 JSON object per line, bounded to 1 MiB. Each request contains `id`, `op`, `args`, and `auth` when the operation requires a trusted session.

## Cryptography

- Identity key: ECDSA P-256 / `secp256r1`.
- Signatures: SHA-256 with ECDSA, DER encoded then base64url without padding.
- Public keys: X.509 SubjectPublicKeyInfo DER, base64url without padding.
- `phoneId` / `pcId`: base64url(SHA-256(public-key DER)).
- Nonces are fresh URL-safe strings of 16–200 characters.
- Transcript strings are UTF-8 and must match the canonical field order exactly.

Do not create a new cipher, encrypt messages with ECDSA, log private material, or put reusable credentials in command lines/UI/debug bundles.

## Operations

### `trust.negotiate`

Unauthenticated and non-authorizing.

Request args:

```json
{"protocolVersion":"3.3"}
```

Response includes `protocolId`, `protocolVersion`, supported versions, phone public identity and capabilities. Any version other than exact `3.3` returns actionable `PROTOCOL_MISMATCH`.

### `trust.begin`

Unauthenticated and non-authorizing.

Required args:

```json
{
  "protocolVersion":"3.3",
  "pcId":"<optional claimed public-key fingerprint>",
  "pcLabel":"<safe user-visible label>",
  "pcPublicKey":"<P-256 SPKI base64url>",
  "pcNonce":"<fresh nonce>"
}
```

Android derives `pcId` from `pcPublicKey`; a conflicting claimed `pcId` fails. The response returns the phone identity, challenge ID, phone nonce, expiry and exact trust transcript. Cyclone Mobile shows the pending PC label/fingerprint to the user.

### Local phone confirmation

There is deliberately no remote `allow=true` field. The Android UI calls the in-process trust manager for the exact `challengeId`. PC polling/retry of `trust.complete` before local approval receives `PHONE_CONFIRMATION_REQUIRED`. Reject receives `TRUST_REJECTED`.

### `trust.complete`

Unauthenticated but cryptographically authenticated. Required args:

```json
{
  "protocolVersion":"3.3",
  "challengeId":"<active challenge>",
  "pcSignature":"<signature over returned trust transcript>"
}
```

Success persists/updates a trusted-PC public record and returns `trustId`, `generation`, phone identity and a phone signature over the trust receipt. No session token is returned from first-time trust.

### `trust.session.begin`

Unauthenticated challenge creation for an already trusted PC. Required args:

```json
{
  "protocolVersion":"3.3",
  "trustId":"<trusted record>",
  "generation":1,
  "pcNonce":"<fresh nonce>"
}
```

Returns a 30-second signed-session challenge transcript bound to phone ID, PC ID, trust generation and both nonces.

### `trust.session.complete`

Unauthenticated but cryptographically authenticated. Required args:

```json
{
  "protocolVersion":"3.3",
  "challengeId":"<active session challenge>",
  "pcSignature":"<signature over returned session transcript>"
}
```

Success returns `sessionId`, `sessionToken`, expiry, `trustId`, generation and phone receipt signature. `sessionToken` is memory-only on Android, short-lived, and is used as top-level `auth` for protected gateway requests. It must never be displayed, logged or placed in a persistent plaintext file.

### `trust.rotate`

Requires the current V3.3 session in `auth` and `args.protocolVersion=3.3`. Increments trust generation and invalidates every active session/challenge for that trust. The PC must open a fresh session using the new generation.

### `trust.revoke`

Requires the current V3.3 session in `auth` and `args.protocolVersion=3.3`. A PC may revoke only its own trust record. Cyclone Mobile settings separately expose local user revocation of trusted PCs without needing PC credentials.

## Canonical transcripts

Trust completion:

```text
cyclone.android.trust.v3
purpose=trust-complete
protocol=3.3
challengeId=<challengeId>
phoneId=<phoneId>
pcId=<pcId>
pcNonce=<pcNonce>
phoneNonce=<phoneNonce>
expiresAtMs=<expiresAtMs>
```

Session open:

```text
cyclone.android.trust.v3
purpose=session-open
protocol=3.3
challengeId=<challengeId>
trustId=<trustId>
phoneId=<phoneId>
pcId=<pcId>
generation=<generation>
pcNonce=<pcNonce>
phoneNonce=<phoneNonce>
expiresAtMs=<expiresAtMs>
```

Receipt transcripts are frozen in `apps/mobile/app/src/test/resources/gateway/v33/android_trust_protocol_fixture.json` together with P-256 public keys and valid test signatures. Private fixture keys are intentionally not stored.

## Protected operations and legacy transition

V3.3 protected semantic/action operations require a valid short-lived V3.3 session. For one transition release only, the old `pair.begin`, `pair.complete` and `pair.qr.complete` flow remains available as an explicitly labeled fallback. Credentials from that flow are restricted to a fixed read-only set:

```text
bridge.status
observe.semantic
observe.page_debug
capture.screenshot
ui.search
ui.element
app_graph.get
brain.recall
teach.status
debug.snapshot
```

A legacy credential attempting `action.execute`, `manual.execute`, `clipboard.set`, teaching mutation or trust mutation fails with `PROTOCOL_MISMATCH`. This prevents an old PC from silently inheriting new V3.3 authority.

## Typed errors

The PC implementation must preserve these exact safe codes where applicable:

- `PROTOCOL_MISMATCH`
- `PHONE_CONFIRMATION_REQUIRED`
- `TRUST_EXPIRED`
- `TRUST_REPLAY`
- `TRUST_REJECTED`
- `TRUST_REVOKED`
- `TRUST_PHONE_MISMATCH`
- `AUTH_SIGNATURE_INVALID`
- `AUTH_REJECTED`
- `PHONE_LOCKED_OR_UNAVAILABLE`
- `AUTH_REQUIRED`
- `CAPABILITY_UNAVAILABLE`
- `POLICY_DENIED`
- `STALE_OBSERVATION`
- `STALE_ELEMENT`
- `REQUEST_TOO_LARGE`
- `INVALID_JSON`
- `INVALID_REQUEST`

`PROTOCOL_MISMATCH` includes phone/requested version and recovery text when version negotiation fails.

## Action seam

The Android action path is unchanged in authority:

```text
trusted V3.3 session
  -> typed gateway capability
  -> AI permission profile / policy governor
  -> PhoneToolExecutor
  -> fresh semantic after-observation
  -> verification classification
```

V3.3 adds strict observation freshness for mutations. The PC must send the exact current `currentObservationId`. Coordinate fallback uses normalized `0..1` coordinates and is accepted only when the current Accessibility fingerprint still matches that observation; Android maps the normalized point to current display pixels immediately before calling `PhoneToolExecutor`.

Responses keep transport success, Android execution, after-state evidence and verification as separate facts. HTTP/socket success is never equivalent to phone-action success.

## Agent 3 integration checklist

1. Implement the PC identity as P-256 and protect the private key with Windows DPAPI.
2. Use `trust.negotiate` before trust/session operations; fail closed on any non-3.3 authority rule.
3. First use: `trust.begin` -> wait for visible phone approval -> sign transcript -> `trust.complete`.
4. Reconnect: `trust.session.begin` -> sign transcript -> `trust.session.complete`; keep session token only for the live short session.
5. Verify Android phone receipt signatures before accepting trust/session completion.
6. Rotate/revoke on explicit user command and discard stale generations immediately.
7. Keep bridge/trust state independent from media readiness.
8. Consume the frozen JSON fixture in PC tests before integration.

## Shared-file patch request

None from Agent 2. Android Keystore uses platform APIs already available to the app and requires no new manifest permission, service/provider/receiver, Gradle dependency or version-file edit.
