# Cyclone coding-agent guide

Cyclone's active baseline is the 3.9 Android-first product with a V4 Stage 4 Cyclone One glass alpha (`4.0.0-alpha.4`) on the 3.9.12 workspace plus Stage 1 Fast Path, Stage 2 Session Kernel and Stage 3 Skill Compiler. Do not reconstruct retired V2/V3 plans, the old Core/Desktop control plane, Teamwork Sniper experiments or version-specific handoff documents unless a task explicitly asks for historical research.

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

## Ownership

- Android runtime + UX: `apps/mobile/**`
- Device gateway: `apps/device-gateway/**`
- Windows companion: `apps/pc-companion/**`, `packaging/pc-companion/**`
- PC agent adapters: `tools/codex-phone-mcp/**`, `tools/cyclone-agent-mcp/**`
- CI/release: `.github/workflows/**`, `scripts/ci/**`, `release/version.toml`

Keep parallel agents on non-overlapping paths whenever possible.

## Versioning

The authoritative product/component metadata is `release/version.toml`. Android `versionName` and `versionCode` live in `apps/mobile/app/build.gradle.kts` and must agree with release metadata. Increment `versionCode` for every distributed Android build.

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

Run `python scripts/ci/release_versions.py --check` and `python scripts/ci/mobile_product_guard.py` when product identity or release surfaces change.

## Definition of done

A change is done when behavior is implemented, the relevant tests/guards pass, privacy/security invariants remain intact, version identity is coherent, and physical-device verification is stated honestly when applicable.
