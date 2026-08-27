# VMOS architecture map (clean-room evidence)

## Product-level topology

```text
VMOS Edge Desktop (Electron main + preload + Vue renderer)
          | HTTP/JSON, WebSocket/media, Electron IPC
          v
Edge host/container service (:18182)
          | /android_api/v2/{db_id}/*
          v
Guest Android control service (:18185 direct cloud route)
          | accessibility, input, package, screenshot
          v
Android guest (AOSP-derived framework/HAL + isolated app/data space)
```

This is a subsystem map from public source/API evidence, not a claim that proprietary VMOS Pro internals were recovered.

## Edge Desktop

- Main process: `src/main/core/store/modules/*Manager.ts` owns hosts, devices, groups, images, workflows, batch tasks, proxy, FRP, shared folders, state cache and status polling.
- Persistence: SQLite (`SQLiteDB.ts`, `Schema.ts`) plus DAOs; renderer receives typed IPC from `src/shared/ipc/*.types.ts`.
- Media/control: resources include platform-specific `scrcpy`/`adb` and MediaMTX. Qt reference also shows ADB reverse/forward fallback and deterministic local port allocation.
- Automation: `AgentWorkerManager` imports `@vmosedge/workflow-agent-sdk/runtime`; FlowEngineClient uses host+port HTTP with bounded timeout; workflow/script/agent IPC is explicit.
- Packaging: Electron Builder resources include FRP binaries, AAPT, media server and flow engine. These are third-party/proprietary distribution obligations; Cyclone must not copy them blindly.

## Guest AOSP

The published guest is AOSP 5.1.1_r38-derived. README describes an Android app-layer VM containing independent application, Framework and virtualized HAL layers; host hardware can be passed through or filled with virtual values. Build scripts produce ARM/ARM64 ROM archives consumed by VMOS Pro. The authorization notice prohibits commercial use without a license, so Cyclone may only use this as CLEAN-ROOM architecture inspiration.

## VMOS Edge skills/runtime separation

1. Host discovery/health and container lifecycle operate on `host_ip:18182`.
2. `get_db` resolves instance IDs (`db_id`); control routes through host proxy `/android_api/v2/{db_id}`.
3. Direct guest control uses `cloud_ip:18185/api` when LAN mode is enabled.
4. UI observation and typed input stay in Control API; image/instance lifecycle, clone/reset/ROM readiness stay in Container API.
5. Workflow and CLI layers compose those APIs but should not become a second device engine.

## Cyclone translation

```text
Cyclone Brain/AI/Teach/MCP
          | one semantic phone.* contract
          v
DeviceBackend + VirtualDeviceProvider
          | gateway adapters
  physical Android (ADB/localabstract/scrcpy)
  virtual Android (ReDroid/AVD/Cuttlefish provider)
```

Adopt the separation and evidence/reconnect patterns; do not adopt VMOS names, branding, ROMs, proprietary host service or unrestricted shell.
