# Working git line

Read this before creating a branch or opening a PR.

## Current product line

| Fact | Value |
|---|---|
| Shipped product | Cyclone **3.6.0** (`versionCode` 43) |
| Integration branch | `release/beta/cyclone-3.6.0` |
| Release tag | `v3.6.0` |
| Publication flag | `release/version.toml` → `publication_authorized = true` |
| Pixel UI acceptance | UNVERIFIED |
| Next step | V3.7 sprint toward V4 slices (overlay, MCP skill loop, GATE) |

**Start new work from `release/beta/cyclone-3.6.0`, not from `main`, unless the task is explicitly to fast-forward `main`.**

## `main` is behind

As of 2026-08-31, default branch `main` is still **3.5.1** (`9957eea`). Agents that clone the default branch will see stale `CURRENT_STATE`, stale `MOBILE_DOWNLOADS`, and a README that describes the August 2026 Desktop/Hermes stack.

Do not “fix 3.5.1 on main” and call it current Cyclone. Fast-forward or merge the 3.6 line into `main` as a dedicated integration task.

## Frozen / historical branches

These prefixes are **history**, not starting points:

- `release/cyclone-mobile-v2*`
- `release/cyclone-mobile-v3*` except the current 3.6 line
- `release/beta/mobile-3.1.*`
- `feature/v293-*`, `feature/mobile-*` from the V2/V3.1 era
- `agent/v31-*`, `agent/352-*`, `agent/353-*`
- `integration/cyclone-mobile-v3*`
- `docs/v4-super-app-bible` — V4 markdown source; content is copied onto the 3.6 line by the agent-focus cleanup

Do not open PRs that target `release/cyclone-mobile-v2.9*` or `release/cyclone-mobile-v3-beta`.

## What to open PRs against

| Change | Base |
|---|---|
| Phone / gateway / MCP / companion / V4 slices | `release/beta/cyclone-3.6.0` |
| Docs-only agent clarity | `release/beta/cyclone-3.6.0` (this cleanup) or that branch after merge |
| Fast-forward default branch | `main` ← `release/beta/cyclone-3.6.0` as its own PR |
| Legacy Desktop/Hermes only | say so in the PR; still branch from 3.6 so you do not regress mobile |
