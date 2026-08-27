# Cyclone gap map

| Finding | Current Cyclone seam (frozen base) | Gap | Recommendation / owner |
| --- | --- | --- | --- |
| Durable device identity, groups and reconnect state | `apps/device-gateway/**`, PC Companion stores | Fleet state must survive transient ADB/media failure and preserve per-device evidence | Add provider-neutral identity/capability/status cache and reconnect state; Agent 2 |
| Host lifecycle is separate from guest control | no VM provider at baseline | Virtual instance lifecycle must register as ordinary DeviceBackend only after endpoint authorization | Implement `VirtualDeviceProvider` adapter and truthful unavailable diagnostics; Agent 2 |
| Capability preflight | gateway status/observation contracts | Avoid invoking unsupported actions on virtual/physical backends | Map provider capabilities to constrained MCP; Agents 2/3 |
| Evidence-first authoring | mobile Teach/Follow Me and App Graph | Execution logs need before/after evidence, selector quality and repair loop | Extend existing routine path; do not add a second store; Agent 3 |
| Reliability controls | mobile AI/automation infrastructure V3 | Need per-tool retry/timeouts, convergence, pause/resume, repeated-call stop and event stream | Integrate into existing runtime; Agent 3 |
| Typed fleet actions | `tools/codex-phone-mcp`, `tools/cyclone-agent-mcp` | Group operations need explicit selection and independent outcomes | Add bounded device list/select/batch result schemas; Agent 3 with Agent 2 contracts |
| Media plane | pinned scrcpy 4.0 + WebSocket PC companion | Device wall thumbnails and session recovery should not degrade semantic readiness | Reuse current media plane; add health states and thumbnail policy; Agent 2 |
| File/APK distribution | gateway policy boundary | Batch install/file push needs consent, allow-list and post-install verification | Keep typed operations; no generic shell; Agents 2/3 |
| Virtual Android proof | no Docker/AVD/binder on launch host | Cannot claim virtual hard-launch acceptance | Mark ReDroid/AVD experimental and expose unavailable capabilities; Agent 3 release gate |

## Suggested contract anchors

Agent 2 should implement `identify/status/capabilities/observe/search/act/screenshot/stream/app_state/diagnostics/recover` for each backend and lifecycle methods `list_images/list_instances/create/clone/snapshot/restore/start/stop/reset/delete/configure/endpoint` for providers. Agent 3 should keep `phone.*` public vocabulary and route every mutation through `PhoneToolExecutor`/gateway policy.
