# Development

## Baseline

New product work should start from the latest accepted Cyclone baseline rather than an old version branch. Keep each change in a focused feature/chore branch and preserve the runtime invariants in `AGENTS.md`.

## Android

Requirements: JDK 17 and Android SDK 35.

```bash
cd apps/mobile
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

For changes to agent execution, also run the closest unit tests for AI, phone tools, verification, recovery, Brain diagnostics and overlay behavior.

## PC gateway / MCP

```bash
python -m pip install -e 'apps/device-gateway[test]' -e tools/codex-phone-mcp
python -m pytest apps/device-gateway/tests -q
python -m unittest discover -s tools/codex-phone-mcp/tests -v
```

## CI guards

Repository CI also validates product identity, the single launcher, the canonical phone executor, version coherence and critical 3.9 UI surfaces.

## Device verification

CI is not physical-device evidence. Changes involving Accessibility behavior, overlays, permissions, PackageInstaller, real app navigation or OEM-specific behavior should explicitly state whether they were tested on a physical Android device.
