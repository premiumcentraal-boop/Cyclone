# Cyclone launchpad — start here

A one-page orientation for an agent picking up work on **Cyclone**. Read this, then
`AGENTS.md`, then the plan index. It is kept current; if it disagrees with `AGENTS.md`,
`AGENTS.md` wins.

## What Cyclone is

Cyclone is an **AI agent that operates a real Android phone** for its owner — it sees the
screen, decides, and acts, to run tasks like signing up for and using apps. Pieces:

- **Android app** (`apps/mobile/**`) — the agent and runtime on the phone. Package
  `com.cyclone.mobile`, launcher `.MainActivity`. `PhoneToolExecutor` is the one engine that
  mutates the phone.
- **Cyclone Glass** (`apps/glass/**`) — the browser/PC dashboard: Home, Profiles, Ask Cyclone,
  Routines, Brain, the Command Center, phones and results.
- **Device gateway + MCP** (`apps/device-gateway/**`, `tools/codex-phone-mcp/**`,
  `tools/cyclone-agent-mcp/**`) — the constrained contract the PC and other agents reach the
  phone through. Everything routes through it; no generic shell/root is exposed to the model.
- **Profiles** — each Cyclone profile is a full secondary Android user; the owner switches
  between them, and settings/skills/memory travel on a switch.
- **Connectors** — approved companion apps talk to Cyclone over Binder (contract
  `cyclone.connector/1`), after the owner approves them by package + signing certificate.

Baseline: V4 **4.0.0** Session OS + Cyclone One **1.0.0** on the 3.9.12 workspace, plus the
Fast Path, Session Kernel, Skill Compiler and One glass stages. Do **not** resurrect retired
V2/V3 plans, the old Core/Desktop control plane, or Teamwork Sniper unless a task is explicitly
historical.

## Where we are now (2026-10-09)

- **Latest release:** `v5.0.0-alpha.122.dev1` (Android version code **276**), Glass
  `1.0.0-alpha.65`. All published and asset-verified.
- **Active work branch:** `claude/cyclone-v5-handoff-review-9qrs40` (checkout `/home/user/cyc-v5`).
- **Just shipped — plan 57 "Hardened profiles" (alpha.118–122), complete:**
  - alpha.118: truthful profile errors, the "what's using the slots" screen, "Allow more
    profiles" on rooted phones (`fw.max_users`), a redacted debug file.
  - alpha.119: staged profile switch with a root-side **dead-man return** (switches back if the
    target never checks in), root proven from inside each profile.
  - alpha.120: cornerstone apps per profile, every settings file classified as carry/per-profile/
    never, routines/Market/skills/manuals carried, a "Profile C has" report.
  - alpha.121: connector health in the Rooted pill, `root.status.v1` (contract minor 2), the
    profile debug file + health in Glass, a switch-matrix test suite.
  - alpha.122: a connector can **ask** the owner to open a profile (contract minor 3,
    owner-confirmed on Cyclone's own screen); PC/Glass switches got the dead-man return + journal.
- **Next up — plan 58 "Luna Decision Box", three releases = alpha.123–125:** alpha.123 Foundation (the decisions
  wire, Luna in shadow, Triage in shadow, golden set and Lab scorer) → about a week of shadow use → alpha.124 See and
  act (context on demand, Bind, Verify, capability registry, destination index) → about a week of use → alpha.125
  Chains and switch (Instant chains, Flash steps on Luna, calibration; Luna becomes the provider as a setting flip).
  First session: plan 58 §11.5.
- **Then plan 56 "VMOS fleet" (cloud phones), runs V1–V4.** VMOS phone power/backup/rent landed earlier in alpha.117.
- **Physical device: UNVERIFIED.** Nothing in alpha.118–122 has been run on a real phone; the
  owner's checklist is `docs/PROFILES_DEVICE_MATRIX.md`.

## How we work (release rhythm)

1. **Build on a feature branch**, never commit to the default branch directly.
2. **Code first, then bump.** Push the code change with **no version bump** and let Mobile CI
   compile it (Compose UI and the service only compile in CI, not locally). Keep the release/
   version bump commit local until that CI is green.
3. **A push that changes `release/version.toml` triggers the publish workflow**, which waits for
   Mobile CI on the same commit, then builds and publishes the GitHub release. So: get CI green
   on the code, *then* push the bump.
4. **Bump these together:** `release/version.toml`, `apps/mobile/app/build.gradle.kts`
   (`versionCode` **and** `versionName`), the three `pyproject.toml` files (gateway + both MCP
   tools), the matching `docs/RELEASE_*.md`; Glass `package.json` + `package-lock.json` only if
   Glass changed. `versionCode` increments on every distributed Android build.
5. **Verify every publish:** download the release assets and confirm the manifest `source_sha`
   matches the commit, the `.apk.sha256` matches the APK, and the v3 signer is
   `e78c6e0b32d66da05839243c65e9987ba54722d402543f00408b57b1585dbf60`
   (`scratchpad/apksig/apkcert.py`). Fetch assets with `curl -sSfL` from the release download
   URL (`gh api` refuses the redirect).
6. **CI can flake on infra, not tests.** The Windows installer step pulls NSIS from Chocolatey,
   which sometimes 504s; the build now retries it. Re-run a failed *publish* job once rather
   than re-cutting a release.

## Validation (run before pushing)

```bash
# mobile
cd apps/mobile && ./gradlew :app:testDebugUnitTest
# gateway / MCP
python -m pip install -e 'apps/device-gateway[test]' -e tools/codex-phone-mcp
python -m pytest apps/device-gateway/tests -q
python -m unittest discover -s tools/codex-phone-mcp/tests -v
# when product identity / release surfaces change
python scripts/ci/release_versions.py --check
python scripts/ci/mobile_product_guard.py
# the CI guard suite (fast, catches most regressions)
python -m pytest scripts/ci/tests -q
```

Glass: `cd apps/glass && npm test && npx tsc -p tsconfig.json --noEmit`. Compose UI and Android
lint only run in CI — expect a round-trip there.

## Invariants (do not break)

- `PhoneToolExecutor` is the only phone mutation engine.
- Prefer learned routes / `phone.open_app` / semantic selectors before coordinates or vision.
- One screen-changing mutation per agent decision turn (form fills may batch). Re-observe after
  page-changing actions; transport success ≠ task success.
- Keep approval boundaries for pay/send/delete/permission/auth-sensitive actions.
- **Never** persist passwords, OTPs, API keys, payment data or raw secrets in Brain, learning
  stores or diagnostics; debug files are redacted and tested for it.
- PC/MCP integrations stay inside the constrained gateway contract; no generic shell/root to the
  model.
- Money moves only with an owner-confirmed exact price.
- Out of scope: bulk sign-ups, SMS farms, CAPTCHA solving, fingerprint spoofing, engagement
  manipulation.

## Map

- `AGENTS.md` — the coding-agent guide (ownership, validation, definition of done). Read it.
- `Cyclone V5 plan/README.md` — the plan index; every feature's plan and build status.
- `docs/RELEASE_5.0.0-alpha.*.md` — per-release notes (what changed, limits, UNVERIFIED items).
- `docs/PROFILES_DEVICE_MATRIX.md` — the on-phone checklist the owner still has to run.
- Ownership by path: Android `apps/mobile/**`; gateway `apps/device-gateway/**`; PC companion
  `apps/pc-companion/**`; agent adapters `tools/*-mcp/**`; CI/release `.github/workflows/**`,
  `scripts/ci/**`, `release/version.toml`.

## Definition of done

Behavior implemented, the relevant tests/guards pass, privacy/security invariants intact,
version identity coherent, and physical-device verification stated honestly (UNVERIFIED until a
real phone confirms it).
