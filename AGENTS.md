# Cyclone coding-agent guide

Cyclone's active baseline is the V4 **4.0.0** Session OS + Cyclone One **1.0.0** on the 3.9.12 workspace plus Stage 1 Fast Path, Stage 2 Session Kernel, Stage 3 Skill Compiler and Stage 4 One glass. Do not reconstruct retired V2/V3 plans, the old Core/Desktop control plane, Teamwork Sniper experiments or version-specific handoff documents unless a task explicitly asks for historical research.

## Read first

1. `README.md`
2. `docs/ARCHITECTURE.md`
3. the owning module's README / nearest tests

Load more context only when the task needs it.

## Product invariants

- Android package: `com.cyclone.mobile`
- Launcher: `.MainActivity`
- `PhoneToolExecutor` is the canonical phone mutation engine.
- Prefer learned routes, `phone.open_app` / intent landing, and semantic selectors before coordinates or vision.
- Re-observe after page-changing actions. Ordinary taps use Fast Path fingerprint settle (300ms, then +500/+1000); Unchanged is not a second click.
- Transport success is not task success. One screen-changing mutation per agent decision turn; form fills may batch.
- Keep approval boundaries for pay/send/delete/permission/authentication-sensitive actions.
- Never persist passwords, OTPs, API keys, payment data or raw typed secret values in Brain, learning stores or diagnostics.
- Run diagnostics may contain model-visible context, decisions, tool calls/results, verification and recovery—not hidden provider chain-of-thought.
- PC integrations route through the constrained gateway/MCP contracts; do not expose generic shell/root control to the model.
- Task buttons (stop, take over, I'm done, approve, confirm…) on any surface go through Task Kit (`TaskCommands` in `apps/mobile/.../task/`) to the engine that owns the task; surfaces never call an engine directly (guarded by `scripts/ci/tests/test_mobile_task_kit.py`). See `Cyclone V5 plan/17-structure.md`.

## Ownership

- Android runtime + UX: `apps/mobile/**`
- Device gateway: `apps/device-gateway/**`
- Windows companion: `apps/pc-companion/**`, `packaging/pc-companion/**`
- Cyclone Glass (local browser dashboard, no intelligence): `apps/glass/**`, gateway hosting in `apps/device-gateway/cyclone_device_gateway/glass/**`
- PC agent adapters: `tools/codex-phone-mcp/**`, `tools/cyclone-agent-mcp/**`
- CI/release: `.github/workflows/**`, `scripts/ci/**`, `release/version.toml`

Keep parallel agents on non-overlapping paths whenever possible.

## Versioning

The authoritative product/component metadata is `release/version.toml`. Android `versionName` and `versionCode` live in `apps/mobile/app/build.gradle.kts` and must agree with release metadata. Increment `versionCode` for every distributed Android build.

### Fast release lane (since 5.0.0-alpha.43)

Releases ship **Android and Glass only**. The Windows companion, device gateway and MCP stay frozen at the installed
`1.6.0-alpha.43` / `5.0.0-alpha.43.dev1` build: leave `pc_companion`, `device_gateway`, `mcp` and `python_version`
in `release/version.toml` unchanged, and do not touch `apps/pc-companion/**` versions.

To release: bump `product_version`, `components.mobile`, `android_version_code` (+ `build.gradle.kts`), and
`components.glass` only when `apps/glass` changed; add `docs/RELEASE_<mobile>.md`; push to the dev branch.
`.github/workflows/v5-publish.yml` waits for Mobile CI on that commit, signs the APK with the rotated key, builds the
Glass zip and publishes the release. No RC branch and no per-release publisher. Do not push other commits to the dev
branch until the publish finishes (Mobile CI cancels in-progress runs per branch).

The PC gateway serves Glass from `CYCLONE_GLASS_DIST` first, so a new Glass zip is installed by unzipping it and
pointing that variable at the folder — the companion itself is not rebuilt.

## Validation

For mobile changes:

```bash
cd apps/mobile
./gradlew :app:testDebugUnitTest
```

For PC gateway/MCP changes:

```bash
python -m pip install -e 'apps/device-gateway[test]' -e tools/codex-phone-mcp
python -m pytest apps/device-gateway/tests -q
python -m unittest discover -s tools/codex-phone-mcp/tests -v
```

For Cyclone Glass changes:

```bash
cd apps/glass && npm ci && npm test && npm run build
python scripts/ci/glass_guard.py
python -m pytest apps/device-gateway/tests/test_glass_hosting.py -q
```

Run `python scripts/ci/release_versions.py --check` and `python scripts/ci/mobile_product_guard.py` when product identity or release surfaces change.

## Definition of done

A change is done when behavior is implemented, the relevant tests/guards pass, privacy/security invariants remain intact, version identity is coherent, and physical-device verification is stated honestly when applicable.
