# Cyclone 3.5 verification plan

This plan combines the fleet/virtual-device, AI/Teach/MCP, mobile, packaging and real-device gates.
CI and mocks verify contracts; they never substitute for physical or virtual-device evidence.

## Automated fleet gates

- DeviceBackend structural conformance and explicit capability reporting.
- Fleet workspace schema, group/selection persistence, filtering and malformed-target rejection.
- Port pair allocation, collision avoidance, lease/release and loopback bind defaults.
- Virtual registry migration/reconstruction and lifecycle state transitions.
- Provider health and fixed argument vectors; unavailable SDKs fail closed with a bounded error.
- Endpoint registration metadata and physical/virtual source annotation.
- Batch validation, typed operation allow-list, per-device result/verification aggregation and cancellation.
- Authenticated HTTP fleet/virtual routes and unauthenticated rejection.
- PC Companion TypeScript tests and production build.

## Automated AI, Teach and MCP gates

- Agent reliability policy tests for bounded retries, timeouts and convergence/repetition guards.
- Teach quality tests for selector stability, verifier strength, evidence completeness and weak-compile rejection.
- Existing routine/Brain/App Graph regressions and the complete Android unit-test suite.
- Codex phone MCP schema/protocol tests, including explicit targets and forbidden arbitrary commands.
- Cyclone agent MCP connector/security tests, including source-aware SDK discovery.
- Observe → typed action → verification result handling and per-device aggregation.

## Branch evidence before integration

- Agent 2: gateway focused tests **12 passed**, full gateway **117 passed**, PC Companion **38 passed**, production web build passed.
- Agent 3: Codex phone MCP **46 passed**, Cyclone agent MCP **36 passed**, `git diff --check` passed.
- Agent 3 Android tests were not run on its branch because that agent session could not find JDK 17; integration must run them with the repository's available toolchain or report the gate blocked.

## Integration and release gates

- Run both MCP suites and full Device Gateway/PC Companion suites on the final integration SHA.
- Run focused Android reliability/Teach tests, full Android unit tests and one debug APK assembly.
- Verify `release/version.toml`, Android, Python, Node, Cargo and Tauri versions are coherent.
- Build the actual Windows installer and Android APK from the same exact SHA.
- Verify source SHA, provenance, file sizes, SHA-256 checksums and third-party notices.
- Confirm no secrets, unrestricted shell/ADB/Docker/PowerShell tools, public ADB listener or non-loopback default were introduced.

## Physical acceptance

On the connected Pixel 8, use only harmless typed paths and record:

- authorized discovery and stable identity;
- live stream and screenshot;
- Home/Back, click, type and swipe through the canonical action path;
- semantic observe/search and after-action verification;
- reconnect/session recovery;
- one taught routine replay if the final APK can be installed without losing required user state.

Any item not actually exercised remains `UNVERIFIED`.

## Virtual acceptance

At the current host checkpoint, Android Emulator is unavailable because no SDK/system image is installed, ReDroid is unverified because WSL2 lacks binder/binderfs, and Docker is stopped. The provider must report `UNAVAILABLE` rather than fake a boot.

When a compatible provider exists, record create → start → Device Wall registration → observe/action/screenshot → stop/restart → delete. Clone and snapshot/restore are not advertised until independently proven.

## Fleet acceptance

- At least two concurrent devices for a launch-ready fleet claim.
- Mixed physical + virtual only when both are genuinely available.
- Explicit selection/grouping, one safe typed batch, independent per-device results and one offline/reconnect scenario.

If only the Pixel 8 is available, multi-device and mixed-fleet gates remain `UNVERIFIED`.
