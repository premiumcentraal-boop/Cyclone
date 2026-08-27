# Agent 2 handoff — fleet and virtual Android infrastructure

- Branch: `feature/v35-fleet-virtualization`
- Base: `e0149ab0638c77fa3d99d9d383f1d912fcbca25e`
- Head: `9967a5c` (full SHA reported after push)

## Changed implementation

`apps/device-gateway/cyclone_device_gateway/backends/` adds the provider-neutral
`DeviceBackend` contract and the governed desktop Android adapter.

`apps/device-gateway/cyclone_device_gateway/virtual/` adds the provider interface, persistent
instance registry, loopback port allocator, bounded Android Emulator/AVD provider and lifecycle
service. The desktop API exposes authenticated provider/image/instance lifecycle and endpoint
routes.

`apps/device-gateway/cyclone_device_gateway/desktop_runtime/` adds fleet workspace persistence and
typed per-device batch operations, and annotates physical/virtual inventory metadata.

`apps/pc-companion/src/pages/fleetPage.ts`, service types/HTTP adapter and styles add search/source
filters, persistent selection/groups, typed batches and reconnect-aware fleet presentation.

## Verification

Focused and full gateway suites pass from `apps/device-gateway` (`python -m pytest tests/test_v35_fleet_virtualization.py -q`; `python -m pytest -q`). PC Companion passes `npm test` and `npm run build`.

The connected Pixel 8 is authorized and remains the physical acceptance target. Virtual Android is
**UNAVAILABLE / UNVERIFIED** on this host: no SDK/AVD tooling or image is installed, Docker is
stopped, and WSL2 has no binder device. The API reports provider health instead of fabricating a
booted phone.

## Integration notes and limitations

Agent 3 should reconcile this lane's `TEST_PLAN.md` with any release-wide test plan, preserve the
loopback-only and no-shell security defaults, and wire the fleet/virtual routes into governed AI/MCP
capabilities without adding a second phone engine. Clone and snapshot/restore are not advertised;
ReDroid remains experimental until an actual compatible Linux host proves lifecycle and endpoint
registration.
