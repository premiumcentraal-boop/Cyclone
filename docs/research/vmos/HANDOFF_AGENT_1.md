# Agent 1 handoff — VMOS archaeology

## Source and branch

- Base SHA: `e0149ab0638c77fa3d99d9d383f1d912fcbca25e`
- Branch: `research/v35-vmos-archaeology`
- Head SHA: filled after commit (see final report)
- Owned paths: `docs/research/vmos/**` (no production runtime changes)

## Files changed

`SOURCE_CATALOG.md`, `APPLICATION_LAB.md`, `ARCHITECTURE_MAP.md`, `API_PROTOCOL_MAP.md`, `FEATURE_MATRIX.md`, `LICENSE_LEDGER.md`, `CYCLONE_GAP_MAP.md`, `EXTRACTION_BOARD.md`, this handoff, and optional evidence validator under `tools/research/vmos/` if added.

## Lab and dynamic status

Disposable lab: `C:\Users\Agent\AppData\Local\CycloneResearch\VMOS`. Official VMOS Cast/Pro/Edge installers were not present in `downloads/` and were not installed. Static source/package evidence is complete enough for contracts; dynamic app behavior, signatures, process trees, listeners, and virtual boot remain **NOT VERIFIED**. Host constraints observed by coordinator: Docker stopped, WSL2 binder unavailable, no AVD image/tooling.

## Strongest findings for Agent 2

1. Keep host/container lifecycle separate from guest `DeviceBackend`; resolve `db_id` before guest control.
2. Use capability discovery and explicit unavailable states. Do not infer support from version names.
3. Durable serial/device identity, group selection, status polling, stale-entry cleanup and deterministic per-device media ports are high-ROI clean-room patterns.
4. Preserve Cyclone's pinned scrcpy media plane and loopback trust; VMOS evidence does not justify a second public listener.
5. Virtual providers should expose lifecycle and endpoint registration only after an authorized ADB/control endpoint is healthy.

## Strongest findings for Agent 3

1. Workflow authoring captures walks, dumps, diffs and verification; replay is blind and must carry robust selectors/verifiers.
2. Reliability defaults worth reproducing: observation retry 3, action retry 2, tool timeout 12s, max iterations 120, repeated-failure pause, post-action verification, structured events and resumable sessions.
3. CLI/batch/YAML patterns are useful only as Cyclone-native typed routines; never expose generic shell.
4. Keep one Brain/App Graph and one `phone.*` vocabulary; store sanitized evidence and provenance.

## Licensing blockers

VMOS Edge Desktop is GPL-3.0; VMOS Pro AOSP README forbids commercial use without authorization; `@vmosedge/cli` has no declared package license. These are research-only/blocked. MIT/ISC package reuse still requires exact-version notice and dependency audit; clean-room implementation is the default.

## Tests/commands

- Verified all five source git SHAs and remotes from disposable lab.
- Verified npm package versions/licenses, registry integrity/signature metadata and tarball SHA-256.
- Inspected skill API references and desktop IPC/resource/source inventory.
- `git diff --check` and required-file/link heading checks run before commit (report exact result in final coordinator message).

## Unknowns

No proprietary VMOS application runtime traces, no virtual provider boot, and no network protocol capture were possible. These remain explicit release limitations, not inferred successes.
