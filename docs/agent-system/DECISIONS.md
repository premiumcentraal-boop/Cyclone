# Decisions agents should not casually reverse

Add a short entry here when a change would otherwise look optional to a future agent.

## V4 is a layer, not a rewrite (2026-08-30)

Infrastructure V3 stays. V4 adds page-card compact, after-state MCP acts, overlay chrome, and skill compile into the existing AutomationStore / Brain memory seam.

Do not add a second executor, Brain, Companion, or MCP server on the phone. Do not bump `versionName` to 4.0.0 until `V4_ROADMAP.md` slices 1–4 are green on a physical Pixel.

Overlay buttons are Cyclone-state only. Host taps still go through `PhoneToolExecutor`.

## Device identity and fleet targeting (2026-08-29)

Durable `device_id` plus explicit selection is the control-plane identity. Android serials, ADB names and emulator ports are discovery hints, not the operator contract. Empty, wildcard and duplicate target sets fail closed. Command-shaped host parameters fail closed.

## Virtual devices are lifecycle-only (2026-08-29)

The official virtual backend is an Android Emulator/AVD provider bound to loopback. Clone and snapshot/restore are research, not advertised product. ReDroid stays experimental until a host with binder support proves it. Virtual and mixed-fleet claims need a genuinely booted provider.

## Teach quality before durable knowledge (2026-08-29)

Selector stability, verifier strength and evidence completeness score a captured workflow before it becomes durable knowledge. Weak evidence stays visible and does not get promoted by default.

## VMOS and foreign virtualization stay out of production (2026-08-29)

Clean-room VMOS research may inform a future legal provider. GPL, noncommercial and proprietary foreign binaries stay out of Cyclone production code and releases.

## Infrastructure V3 composition (2026-08-28)

Capability inventory, policy authorization, module lifecycle, memory writes, context persistence, runtime staging and recovery decisions are separate authorities. Shared mobile/PC integration composes those public contracts onto the existing executor, gateway and Companion. It must not create a second policy engine, module supervisor, memory store or mutation path.

See `ADR_INFRASTRUCTURE_V3.md` and `infrastructure-v3/OWNERSHIP.md`.

## Recovery does not wipe user data (2026-08-28)

Recovery may roll back a staged runtime or ask the Module Supervisor to quarantine an optional module. It must not automatically erase user-learned knowledge, routines or other user data as a health action.

## Context Ledger does not store guessable restricted digests (2026-08-28)

Restricted raw text is omitted from durable Context Ledger rows. Unkeyed hashes of that text are not an acceptable substitute because they remain guessable.
