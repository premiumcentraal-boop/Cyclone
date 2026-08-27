# Cyclone 3.5 DeviceBackend contract

`DeviceBackend` is the provider-neutral seam used by the desktop fleet layer. It keeps a
physical USB/LAN Android session and a virtual Android endpoint interchangeable to higher
Cyclone layers while preserving source/provider diagnostics.

## Contract

Every backend implements:

- `identify()` — stable `deviceId` and diagnostic identity metadata.
- `status()` — state, source (`USB`, `LAN`, or `VIRTUAL`), provider and last-seen timestamp.
- `capabilities()` — explicit observe/search/action/screenshot/stream/recovery support.
- `observe(mode)`, `search(query)`, `app_state()` — structured evidence only.
- `act(capability_id, params, goal)` — typed semantic mutation. The Android adapter re-observes
  immediately before mutation and delegates to the existing governed desktop agent/phone path.
- `screenshot(profile)`, `stream(profile)` — bounded media profiles (`thumbnail`, `focus`).
- `diagnostics()` and `recover()` — safe, bounded health/reconnect operations.
- `close()` — release only backend-local resources; shared fleet ownership remains with the
  `DeviceFleetManager`.

Unsupported capabilities are reported as false/empty and fail with a structured capability error;
they are never represented as a successful no-op. Transport success is not action success: callers
must inspect execution and verification fields returned by `act`.

## Identity and lifecycle

Cyclone IDs are deterministic for a transport serial and are not exposed as raw arbitrary shell
targets. A virtual instance receives a stable `dev_…` ID and registers through the same ADB fleet
inventory after its loopback endpoint reaches `device` state. Re-observation is required after any
page-changing action.

## Security boundary

The backend surface contains no shell, PowerShell, Docker, WSL or generic ADB command. Provider
implementations may use fixed allow-listed executables internally, with `shell=False`, bounded
arguments/timeouts and loopback-only virtual endpoints. Authentication is required for all HTTP
routes and WebSocket streams.
