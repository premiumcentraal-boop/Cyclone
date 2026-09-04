# Cyclone

**Cyclone is an Android agent that can observe a phone, decide what to do, act through constrained native tools, verify the result, recover from failures and learn reusable app knowledge.**

This repository is intentionally kept as a current-product launchpad. Historical sprint plans, old control planes, one-off release workflows and retired version folders belong in Git history and GitHub Releases—not in the active tree.

## Current baseline — Cyclone 3.9.2

Cyclone 3.9.2 focuses on adaptive reliability and useful failure evidence:

- **Ask Cyclone** — chat-style task composer with model selection and a single send action.
- **Structured + Free Mode agent** — Cyclone starts with reliable semantic/learned routes, then automatically gives the model more freedom to choose a materially different strategy when verified progress stalls; GATE and policy boundaries remain mandatory.
- **Progress-bounded recovery** — changing Android fingerprints do not count as task progress, repeated failures are bounded, and only verified semantic progress resets no-progress budgets.
- **Strict phone-tool contracts** — app launches require resolvable packages and browser navigation can use the existing allowlisted Android HTTPS intent path as a different recovery strategy.
- **Brain → Recent runs** — durable run history with compact, sanitized `.txt` diagnostics that can be exported while a task is running, suspended, failed or completed.
- **Truthful task states** — running, suspended, completed and failed runs are represented consistently across the result timeline and durable trace.
- **Aurora** — unobtrusive bottom-center persistent activation overlay with a small touch target.
- **Teach + Routines** — reusable app knowledge and repeatable phone workflows.
- **PC integration** — optional Device Gateway, Windows Companion and constrained MCP adapters without creating a second phone-control engine.

Android package: `com.cyclone.mobile`  
Minimum Android: 14 (API 34)  
Current mobile identity: `3.9.2` / versionCode `56`

## Repository

```text
apps/
  mobile/              Android product
  device-gateway/      PC ↔ phone gateway
  pc-companion/        Windows companion

tools/
  codex-phone-mcp/     constrained PC agent tools
  cyclone-agent-mcp/   generic Cyclone MCP adapter

scripts/
  ci/                  product/version guards
  phone-gateway/       gateway setup and acceptance helpers
  pc-companion/        companion tooling

docs/                  current architecture/development/release docs
release/version.toml    product/component version source
.github/workflows/      current CI and candidate verification
```

## Build the Android app

Requirements: JDK 17 and Android SDK 35.

```bash
cd apps/mobile
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Use GitHub Actions for release candidates so APK provenance, checksum and source SHA stay connected.

## Development rules

Read [`AGENTS.md`](AGENTS.md) before substantial work and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) before changing runtime boundaries.

Core invariants:

- one Android package and launcher;
- one canonical phone mutation engine (`PhoneToolExecutor`);
- semantic evidence before coordinate/vision fallback;
- re-observe and verify after page-changing actions;
- explicit approval boundaries for consequential actions;
- no credentials or raw typed secrets in Brain/run diagnostics;
- CI evidence and physical-device evidence are reported separately.

## History

Old Cyclone versions, experiments and retired architecture remain available through Git history, tags, branches and GitHub Releases. They are deliberately not duplicated in the current working tree.

## License

Proprietary. Third-party components remain under their respective licenses; see [`docs/OPEN_SOURCE_COMPONENTS.md`](docs/OPEN_SOURCE_COMPONENTS.md).
