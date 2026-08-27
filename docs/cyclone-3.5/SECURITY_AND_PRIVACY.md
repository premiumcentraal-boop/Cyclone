# Cyclone 3.5 security and privacy

The 3.5 AI/MCP additions preserve Cyclone's local-first security boundary.

## Typed actions only

MCP accepts the existing semantic `phone.*` vocabulary and the explicit-target,
non-secret `phone_group_act` operation. Group actions are limited to 32 unique device IDs,
observe each device first, and return per-device outcomes. `phone.type` is intentionally excluded
from batch operations and still requires explicit intent acknowledgement plus Android policy.

Nested parameter validation rejects command-shaped keys (`command`, `shell`, `adb`, `powershell`,
`subprocess`, `root`, `su`, `docker`, `script`, and equivalent host-operation names). No model-facing
tool launches a process, opens arbitrary network access, or exposes raw ADB/root.

## Data minimization

Reliability signatures hash targets. Teaching notes and model analysis redact credential assignments,
Bearer tokens, API keys, OTP/PIN values, and payment-card-like numbers before storage and reports.
Quality gates reject sensitive-looking evidence instead of compiling it. Screenshots and UI files
remain local evidence under the existing session directory and are not uploaded by this lane.

## Network and provider posture

The MCP client continues to use the authenticated loopback Device Gateway. Provider/API use remains
visible in the selected model and existing settings surfaces. Virtual-device providers must satisfy
the same typed gateway and private-network requirements; an unavailable provider is reported as
unavailable, never as a booted phone.
