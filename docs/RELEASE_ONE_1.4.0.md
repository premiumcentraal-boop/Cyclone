# Cyclone One 1.4.0 — Cyclone Interaction Protocol

Cyclone One 1.4.0 makes Live Phone a small, explicit, transport-neutral phone-control contract while preserving the existing native On PC AI and Background Phone paths.

## Primary Live Phone surface

The cloud-facing default is **Cyclone Interaction Protocol v1** (`cyclone.live.v1`) with six operations:

- `cyclone_devices`
- `cyclone_see`
- `cyclone_find`
- `cyclone_inspect`
- `cyclone_act`
- `cyclone_session`

The older `cyclone_phone_*` tools remain accepted as hidden compatibility aliases. Native Codex continues to use its existing local stdio MCP path and is not routed through Live Phone.

## See → act → verified after-state

A fresh `see` binds semantic UI evidence, screenshot geometry, physical device identity, Live Phone generation, `default-foreground`, and display `0` into one short-lived observation. Mutations consume that authority and return typed execution evidence plus a fresh after-observation when available.

Transport success alone is never treated as phone success. Every mutation normalizes to `VERIFIED`, `FAILED`, or `UNCERTAIN`; an uncertain mutation is never automatically replayed.
## Visual control and safety

CIP supports observation-bound normalized visual points and true normalized swipes. Coordinates are converted using the current screenshot dimensions and are never reusable after the observation becomes stale.

Mutation `request_id` values are durable at-most-once keys. Cyclone writes a PENDING tombstone before Android dispatch; if the runtime dies at the ambiguous boundary, the same request replays as `UNCERTAIN` rather than injecting input twice.

The Live Phone protocol exposes no shell, PowerShell, ADB, root, arbitrary executable, arbitrary file path, or arbitrary network execution. Android policy, GATE, Device Gateway authority, and `PhoneToolExecutor` remain authoritative.

## Optional CIL

Cyclone Input Language is a small human/test shorthand that compiles into validated CIP requests. It is not a second execution plane and cannot bypass CIP validation.

## Packaging and compatibility

Recommended pair:

- Cyclone One 1.4.0
- Cyclone Mobile 4.2.5 / versionCode 86
- Device Gateway / MCP components remain 4.1.0 where their existing wire contract is unchanged.

The Windows installer continues to bundle `CyclonePCRuntime`, `CycloneAgentMCP`, and `CycloneLivePhone`. Live Phone remains physical foreground only; Layer 2/background workspaces remain separate.
## Validation state

Automated Windows CI must pass gateway, native MCP, Live Phone/CIP, PC frontend, frozen-sidecar, NSIS installer, installed-candidate, provenance, and artifact checks before publication.

Physical phone acceptance is reported separately. A green build does not by itself claim that real-device acceptance was performed; release metadata keeps that distinction explicit.
