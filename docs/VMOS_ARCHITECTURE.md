# VMOS target architecture

This document defines the only supported VMOS execution shape for Cyclone. It is intentionally strict so cloud-phone support cannot introduce a second mutation engine.

## Component ownership

```text
Cyclone One (`apps/pc-companion`)
        │
        ▼
Device Gateway (`apps/device-gateway`)
        │  constrained session/device API
        ▼
Cyclone Mobile local gateway (`com.cyclone.mobile`)
        │
        ▼
PhoneToolExecutor
        │
        ▼
Android Accessibility / approved root helpers
        │
        ▼
VMOS-hosted Android UI
```

VMOS supplies the Android host, lifecycle control, remote ADB bootstrap transport, optional H5 live view, and cloud-instance metadata. VMOS does **not** own task planning, semantic observation, verification, approvals, learned routes, or phone mutation policy.

## Bootstrap/control separation

Bootstrap path:

```text
VMOS OpenAPI
→ enable/get remote ADB
→ install/start `com.cyclone.mobile`
→ establish Cyclone Mobile local-gateway trust
```

Agent control path:

```text
Cyclone One
→ Device Gateway
→ Cyclone Mobile local gateway
→ PhoneToolExecutor
→ Android
```

Direct VMOS `simulateTouch`, `simulateClick`, `simulateSwipe`, H5 `triggerPointerEvent`, or generic `executeAdbCommand` must never be exposed as an alternate model mutation channel. They may be used only for infrastructure/bootstrap diagnostics or an explicitly human-operated fallback. This preserves the repository invariant that `PhoneToolExecutor` is authoritative.

## VMOS API surfaces used by later implementation stages

Current VMOS OpenAPI endpoints required for provisioning/transport:

- `POST /vcpcloud/api/padApi/openOnlineAdb` — enable/disable online ADB.
- `POST /vcpcloud/api/padApi/adb` — obtain expiring ADB connection information.
- `POST /vcpcloud/api/padApi/restart` — instance restart/recovery.
- `POST /vcpcloud/api/padApi/reset` — destructive reset; never part of ordinary agent execution.

VMOS H5 SDK may provide view/input helpers such as `saveScreenShotToLocal()` and `executeAdbCommand(...)`, but Cyclone's normal observation/action path remains Mobile + Device Gateway.

## Security boundary

VMOS AccessKey/SecretAccessKey credentials belong only on the PC side. They must never be copied into Cyclone Mobile, task prompts, Brain, diagnostics, or model-visible context. Remote ADB credentials are transport secrets and are likewise not model-visible.

## Acceptance invariant

A VMOS phone is compatible only when all of the following are true:

1. VMOS instance is running and reachable through the approved PC transport.
2. `com.cyclone.mobile` is installed and running inside that VMOS Android instance.
3. Cyclone Mobile's local gateway is reachable through the Device Gateway.
4. Cyclone One binds the session to that VMOS-backed device identity.
5. A requested phone mutation is executed by `PhoneToolExecutor`, not by a VMOS-native touch/ADB shortcut.
6. Cyclone re-observes/verifies the resulting state through its canonical phone path.

The executable form of these invariants lives in `apps/device-gateway/cyclone_device_gateway/vmos/architecture.py` with tests in `apps/device-gateway/tests/test_vmos_architecture.py`.

## ChatGPT Attach (Custom GPT Actions)

Cyclone One's **ChatGPT Attach** tab is an operator surface for VMOS fleet bootstrap and handoff export. It may open remote ADB tunnels, detect `com.cyclone.mobile`, and mint short-lived Cloud Control sessions. **Share to ChatGPT** publishes Device Gateway Cloud Control (`http://127.0.0.1:<gateway>/cloud`) over HTTPS (trycloudflare) so Plus Custom GPT Actions can reach it. The one-file ChatGPT handoff may contain only `DEVICE_ID`, `SESSION_ID`, `SESSION_TOKEN`, `CONTROL_API`, `MOBILE`, `ADB`, `GOAL`, and `NOTES`.

VMOS AccessKeys, SSH Connect Keys, and other transport secrets stay in the PC secret store and must never enter the handoff, GPT instructions, or model-visible context. ChatGPT Plus talks to the Cloud Control OpenAPI surface (`/cloud/v1`), which maps observe/tap/swipe/type/launch/key onto Device Gateway → Mobile → `PhoneToolExecutor`. Auth is Bearer `SESSION_TOKEN` from Sync/mint. Direct VMOS touch/ADB input remains forbidden as an agent path.
