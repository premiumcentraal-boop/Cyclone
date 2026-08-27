# VMOS extraction board

## P0 — hard-launch value

| Extraction | Category | Evidence | Acceptance contract |
| --- | --- | --- | --- |
| Capability preflight + typed backend adapter | ADAPT/CLEAN-ROOM | Control `/base/version_info` + `/base/list_action`; DeviceBackend seam in mission | Unsupported actions return structured unavailable; no false VERIFIED state. |
| Durable inventory/groups/reconnect | CLEAN-ROOM | Desktop DAOs/cache/status poller; Qt serial→port map | Device identity survives media reconnect; independent status/diagnostics and per-device action results. |
| Device wall with thumbnails/focus/multi-select | ADAPT/CLEAN-ROOM | Desktop Group/Device managers and scrcpy resources | Explicit target selection, mixed online/offline wall, safe typed batch operations. |
| Virtual provider boundary | CLEAN-ROOM | Container lifecycle endpoints and AOSP topology | Provider can honestly report unavailable; no public ADB; booted endpoint registers as normal backend. |
| Evidence-first Teach compiler | CLEAN-ROOM | workflow-skill recording/compilation contract | Observe→action→after-state evidence, selector quality ≥90, replay verification and Brain/App Graph write. |
| Agent reliability | CLEAN-ROOM (or isolated MIT adapter) | SDK README defaults/events | Plans, bounded retries/timeouts, convergence/repeated-call guard, pause/resume and structured logs. |

## P1 — strong follow-up

- Clone/snapshot/restore only after a chosen provider proves state semantics (`/clone`, `/clone_status` is API evidence, not local proof).
- WebCodecs channel-state and thumbnail optimizations inspired by web SDK; keep Cyclone's existing pinned scrcpy implementation.
- Typed locale/GPS/sensor/file/APK operations with explicit policy and post-state checks.
- JSON batch/YAML-like routine export under Cyclone naming after MCP schema is stable.

## P2 — experimental

- ReDroid on compatible Linux host (binder, GPU, storage and ADB isolation proof required).
- AVD/Cuttlefish/Waydroid providers on explicitly supported hosts.
- Sensor simulator direct dependency (ISC) if a real product requirement emerges.
- Snapshot acceleration, proxy and media injection integrations behind feature flags.

## Reject / do-not-copy

- GPL VMOS Edge Desktop source or linked modules in a non-GPL Cyclone distribution.
- VMOS Pro AOSP source/ROM/binaries due to noncommercial authorization.
- VMOS proprietary APK/EXE/branding/assets, bundled proxy binaries, secrets or any bypass of authentication/DRM/signature controls.
- Generic shell/ADB exposure to AI; use typed semantic operations only.
