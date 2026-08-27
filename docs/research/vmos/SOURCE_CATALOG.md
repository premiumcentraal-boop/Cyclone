# VMOS 3.5 research source catalog

Research was performed from the disposable lab at `C:\Users\Agent\AppData\Local\CycloneResearch\VMOS` on 2026-08-27/28. Repository SHAs are immutable evidence; no source tree is copied into Cyclone production.

| Source | Revision/version | License evidence | Relevance and disposition |
| --- | --- | --- | --- |
| [vmos-edge-desktop](https://github.com/vmos-dev/vmos-edge-desktop) | `08f278426c049cd7091dac7fb55461a1b999c3cf` (2026-08-18) / package 2.2.5 | repository `LICENSE` is GPL-3.0; `README.md` confirms | Electron/Vue desktop orchestration, device/group/image/workflow managers, bundled scrcpy/MediaMTX/FRP. Research only; do not link or copy GPL implementation. |
| [vmos-edge-skills](https://github.com/vmos-dev/vmos-edge-skills) | `d5b12deb19f3a2bc5d4cd2ee703c19d468709ca8` (2026-08-12) | README says MIT; individual skill files carry no stronger grant | Public Control/Container API and workflow/CLI contracts. Use as behavioral evidence; direct reuse only after legal review of each file. |
| [open-vmos-aosp_5.1](https://github.com/vmos-dev/open-vmos-aosp_5.1) | `65d119a7f927cbd85179328ce8acf080f4528bd0` (2022-11-01) | README authorization explicitly forbids commercial use without purchase; no permissive SPDX grant | Guest AOSP/virtual-HAL architecture. CLEAN-ROOM architectural study only; never ship source, ROM or binaries. |
| [vmos-edge](https://github.com/vmos-dev/vmos-edge) | `005cb9548c048f0f2a63af23d3686feb79200439` (2025-12-29) | repository MIT `LICENSE` | Qt desktop/scrcpy reference. Candidate for independent behavior comparison; avoid importing bundled binaries/assets. |
| [ai-battle-mcp](https://github.com/vmos-dev/ai-battle-mcp) | `d0e320c6831cc799e75f126779f200d735b69b7f` (2026-03-19) | GitHub API reports no SPDX license | MCP room/server patterns only; DO-NOT-COPY until licensing is clarified. |
| [scrcpy](https://github.com/Genymobile/scrcpy) | public release checkpoint 4.0 (lab did not build it) | Apache-2.0 | Proven USB/TCP media/control building block; Agent 2 may use its own pinned dependency with notices. |
| [redroid](https://github.com/remote-android/redroid) | current docs reviewed, no local runtime proof | Apache-2.0 (verify exact selected tag) | Linux container Android provider candidate; experimental until binder/GPU/ADB isolation passes. |
| Android Emulator/AVD | Android SDK docs, no SDK/image installed on host | Android SDK licenses | Alternative virtual provider; not launch-proven on this Windows host. |
| Cuttlefish | Android Open Source Project docs, no host deployment | Apache-2.0 and component licenses | Linux/KVM-oriented provider; experimental on Windows/WSL. |
| Waydroid | public docs, no host deployment | GPL-3.0 userspace plus component licenses | Linux container option; not suitable as a Windows hard-launch dependency. |

## Package evidence

The npm registry pack/view JSON and tarballs are retained outside Git under `packages/`:

| Package | Exact version | Declared license | SHA-256 (lab tarball) | Cyclone classification |
| --- | ---: | --- | --- | --- |
| `@vmosedge/workflow-agent-sdk` | 1.0.5 | MIT | `51D976E90A569E9BD3FB503B6142B44AF9DA3A4054CC9F8F22BC93F738BA7D89` | CLEAN-ROOM/ADAPT reliability contracts; adapter required |
| `@vmosedge/proxy-sdk` | 1.2.5 | MIT | `B20C453B00E64C3B7BDF8F00BDD3D349459CCB2836184395F4EFDB09AC69A947` | DO-NOT-COPY bundled proxy binaries; evaluate protocol ideas only |
| `@vmosedge/sensor-simulator` | 1.0.1 | ISC | `2F352F4D4784C2B7D6DB6A3BE40337FEF3CF7CB7B3C8C5A359B706E5F39AD1B3` | DIRECT-REUSE possible with ISC notice; not needed for core launch |
| `@vmosedge/web-sdk` | 1.2.7 | ISC | `60E985A8DE1D24C0E12F7F024217B482490CBC7FB3AF32A6C5A1EADA209E16F2` | CLEAN-ROOM WebCodecs/channel behavior; do not import VMOS branding |
| `@vmosedge/cli` | 1.4.2 | package metadata blank | `DAC10341885EBC714AB6EC7167C35FAC94948C27F1807C9DFB421E8E35609D7D` | CLEAN-ROOM JSON CLI shape; no reuse until license clarified |

Registry evidence includes npm integrity/signature metadata in `_*-view.json`; package contents remain disposable and untracked.
