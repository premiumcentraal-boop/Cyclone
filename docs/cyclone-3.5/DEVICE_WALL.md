# Cyclone 3.5 Device Wall

The PC Companion fleet view presents physical and virtual Android sessions as one inventory.

## Inventory and wall behavior

- USB and approved ADB-over-LAN devices are discovered by event tracking with a bounded fallback
  scan; virtual emulator serials are annotated through the persistent provider registry.
- Each record has a stable Cyclone ID, source/provider, model, display, state, last-seen timestamp,
  reconnect health and safe diagnostic reason.
- Search and source filters operate over name/model/provider/state. User-owned groups and explicit
  multi-selection persist in a Cyclone workspace file.
- Cards default to low-cost thumbnail mode. Full-rate focus streams are opened only for the
  selected phone; large inventories use a bounded virtualized grid.
- Disconnected/attention cards show bounded reconnect attempts and a one-click retry/debug bundle.

## Typed batch operations

The wall can submit explicit, unique targets for `home`, `back`, `open_app`, `screenshot` and
`recover`. Each result records transport, execution and verification outcomes independently. There
is no global success shortcut, arbitrary shell, or implicit “all devices” target. Batch size is
bounded to 32 and worker concurrency to eight.

## Focus and recovery

Opening a paired card enters the existing focus control view (live screen, typed controls,
navigation, screenshot and diagnostics). Fleet reconnect keeps the remembered Cyclone identity and
bridge port, avoids duplicate sessions, and retries with bounded backoff. Stream failures are
isolated per device.

## Performance target

Thumbnail cards do not auto-start HD streams. The existing video limiter bounds thumbnail sources
and focus sources, while the grid virtualizes inventories over twelve records. Measure discovery,
first frame, reconnect and two-device operation on each release host; do not infer physical metrics
from mocks.
