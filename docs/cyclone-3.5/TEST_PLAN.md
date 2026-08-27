# Cyclone 3.5 fleet and virtual-device test plan

This section covers the Device Gateway and PC Companion ownership lane. Integration adds the
mobile, AI/MCP and release gates around it.

## Automated fleet gates

- DeviceBackend structural conformance and explicit capability reporting.
- Fleet workspace schema, group/selection persistence, filtering and malformed-target rejection.
- Port pair allocation, collision avoidance, lease/release and loopback bind defaults.
- Virtual registry migration/reconstruction and lifecycle state transitions.
- Provider health and fixed argument vectors; unavailable SDKs fail closed with a bounded error.
- Endpoint registration metadata and physical/virtual source annotation.
- Batch validation, typed operation allow-list, per-device result/verification aggregation and
  cancellation.
- Authenticated HTTP fleet/virtual routes and unauthenticated rejection.
- PC Companion TypeScript tests plus production build.

## Host acceptance evidence

At this checkpoint the connected Pixel 8 is the only available physical target. A harmless
observe/scan and existing focus-control regression may be used for physical evidence; no virtual
acceptance claim is made. Android Emulator is unavailable because the launch host has no SDK/image,
and ReDroid is unverified because WSL2 has no binder device and Docker is stopped.

When a provider is installed on a compatible host, record create → start → wall registration →
observe/action/screenshot → stop/restart → delete, with exact source SHA and endpoint binding.
