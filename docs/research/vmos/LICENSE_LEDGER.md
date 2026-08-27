# VMOS research license ledger (shipping gate)

This ledger governs what may enter a Cyclone distributable. Research evidence remains outside the production tree.

| Component | Exact evidence | Status | Shipping rule |
| --- | --- | --- | --- |
| VMOS Edge Desktop | `sources/vmos-edge-desktop/LICENSE`, GPL-3.0; package 2.2.5 | BLOCKED | Do not copy/link/redistribute in Cyclone unless Cyclone distribution is deliberately GPL-3.0 and legal review approves. VMOS branding/assets also excluded. |
| VMOS Pro AOSP guest | `open-vmos-aosp_5.1/README.md` authorization notice | BLOCKED | Explicit noncommercial restriction; no source, ROM, binary, patch or derived production implementation. |
| VMOS Edge Skills | README MIT claim; no per-file SPDX headers | REVIEW | Public behavior may inform CLEAN-ROOM work. Direct reuse requires attribution and confirmation that all referenced materials are MIT-compatible. |
| `@vmosedge/workflow-agent-sdk@1.0.5` | npm view/package metadata says MIT; tarball hash recorded in SOURCE_CATALOG | REVIEW | Prefer independent Cyclone implementation. If adapter reuse is chosen, vendor exact LICENSE/notice and isolate package; no secrets or VMOS endpoint assumptions. |
| `@vmosedge/proxy-sdk@1.2.5` | package LICENSE + metadata MIT; tarball bundles platform binaries | REJECT | Do not ship bundled proxy executables or use as a hidden network path. |
| `@vmosedge/sensor-simulator@1.0.1` | metadata ISC | POSSIBLE | Direct reuse only with ISC notice and dependency audit; not required for 3.5 core. |
| `@vmosedge/web-sdk@1.2.7` | metadata ISC | POSSIBLE | Do not copy code/branding; independently implement channel behavior or perform full notice review. |
| `@vmosedge/cli@1.4.2` | metadata has blank license | BLOCKED | Clean-room JSON/batch semantics only until license is clarified. |
| `vmos-edge` Qt client | repository MIT `LICENSE`; includes third-party QtScrcpy/FFmpeg/MediaMTX/ADB artifacts | REVIEW | MIT source may be studied; each bundled dependency needs separate notices and provenance. Cyclone already has its own pinned scrcpy path. |
| scrcpy | upstream Apache-2.0 | ALLOWED WITH NOTICE | Agent 2 may use approved upstream release and retain Apache notice. |
| ReDroid/AVD/Cuttlefish/Waydroid | component-specific licenses | EXPERIMENTAL | Provider integrations must pin versions, emit notices and never bundle unreviewed images/binaries. |

## Prohibited material

No VMOS APK/EXE/ROM, proprietary assets, private keys, server credentials, paid content, copied UI artwork or VMOS trademarks may be committed. The lab contains disposable tarballs and source checkouts only; production references must be links/contracts, not binaries.

## Release checks

Before a 3.5 artifact is published, verify dependency lockfiles, third-party notices, source SHA and artifact hashes. Any unresolved `REVIEW`/`BLOCKED` item fails the gate if the corresponding material is distributed.
