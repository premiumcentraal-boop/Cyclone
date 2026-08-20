# Current Cyclone State

This document describes the current source architecture around the Cyclone Mobile 2.9.5 line. Verify exact release/build status from current code and `releases/<version>/BUILD_VERIFIED.json`.

## Mobile product identity

- Android package: `com.cyclone.mobile`.
- One launcher: `.MainActivity`.
- Minimum Android API: 34; target/compile SDK currently 35.
- Core product surfaces remain Home, Teach, AI, Automations, Brain and Settings.
- Settings is reached from the profile/avatar.
- Full PC + Codex Gateway is represented inside the AI experience rather than as a second launcher.
- User-visible release text is derived from `BuildConfig.VERSION_NAME` through `CycloneRelease`.

## Device control/perception

The app contains `CycloneAccessibilityService` and a canonical `PhoneToolExecutor` path. This is the base capability layer for phone observation and typed actions.

Do not create a second engine for AI, automation, teaching or the PC gateway.

## Page Awareness

The 2.9.3-era Page Awareness implementation remains an important internal foundation. Internal V293 names are compatibility identifiers, not the current marketing version.

The diagnostic funnel distinguishes roughly:

- broad raw Accessibility collection;
- semantic scan;
- stored semantic controls;
- compact production-agent controls.

The existing diagnostic documentation records the 2500 → 450 → 80 → 36 funnel. Treat these as current implementation limits to measure/optimize, not permanent product requirements.

## Learning

App Learner / App Graph and Adaptive Brain are present. The intended behavior is to convert safe, useful navigation into structured reusable knowledge and lower confidence when evidence starts failing.

Follow Me and Routine Teaching are canonical teaching paths. The gateway must call into them instead of creating a separate teaching store.

## Android gateway

The Android gateway uses `LocalServerSocket("cyclone_gateway")` / localabstract transport. It is off by default and uses a random session token. It does not create a normal TCP/LAN listener on the phone.

The bridge exposes constrained operations around status, semantic/PageDebug observation, UI search/element inspection, App Graph/Brain retrieval, typed action execution, teaching and debug snapshots.

Sensitive text is redacted again at the gateway boundary.

## PC Device Gateway

`apps/device-gateway/**` provides the Windows/PC-side gateway. Its responsibilities include:

- deterministic ADB device selection;
- forwarding to Android localabstract gateway;
- loopback bearer-authenticated HTTP API;
- semantic/raw/independent observation retrieval;
- screenshots and content-addressed evidence;
- durable observations/actions/transitions;
- bounded root telemetry where explicitly allowed;
- debug bundles and stabilization/verification.

The Android bridge session token and PC HTTP bearer token are separate concepts.

## Codex MCP

`tools/codex-phone-mcp/**` provides a constrained local STDIO MCP client for Codex. The documented tool surface includes status, observation, UI search, element inspection, screenshot, current page/history, typed action, debug bundle and teaching lifecycle tools.

The model does not get generic shell/root tools.

Infrastructure V3 is now present as compiled Cyclone-native services: capability registry, policy
governor, memory service and tiered provider, Module Supervisor and offline catalog, Context Ledger,
temporal App Graph V2, routine capsules/durable runs, vision routing, signed-data runtime staging,
Recovery/Safe Mode and development agent teams. Shared integration preserves one policy authority,
one module lifecycle authority, one memory write seam and the existing `PhoneToolExecutor`.

Runtime staging hands candidates to Recovery; only Recovery promotes or rolls back. Recovery asks
the public Module Supervisor seam to quarantine an optional module. Sensitive Context Ledger text
is omitted rather than stored as a guessable unkeyed digest. These services are contract-composed,
but no new product UI or second navigation shell was added merely to expose them.

## Release state / limitations

- Builds are currently beta/debug-signed unless a later release explicitly changes signing.
- Stable protected signing is still needed for painless long-term Android upgrades.
- A physical rooted Pixel 8 acceptance path has been the target hardware gate for the full gateway. Do not convert mocked CI evidence into a physical verification claim.
- The broader repository still includes Cyclone Desktop/Core/Hermes/n8n/Host Bridge. Those remain useful as an external control plane and agent environment, but mobile autonomy should not depend on them for basic local phone learning/execution.

## Known organizational debt

- Historical version documents are numerous and can confuse new agents.
- Some mobile files/classes retain V292/V293 names because they originated in earlier releases.
- Some large Compose/runtime files are expensive to edit safely.
- Normal Android push/PR builds are consolidated under `mobile-ci.yml` and `_mobile-build.yml`;
  historical workflows are manual compatibility entry points.
- Repo-wide product version synchronization across Android + Python packages should become generated from one release metadata source.

These are organization/refactoring targets, not reasons to rewrite working subsystems wholesale.
