# VMOS application lab

## Environment and method

- Host: Windows Cyclone research workstation, disposable lab `C:\Users\Agent\AppData\Local\CycloneResearch\VMOS`.
- Static inspection: Git metadata, package manifests/READMEs, source file inventory, API references, hashes and license text.
- Dynamic execution: **NOT VERIFIED**. Docker daemon was stopped; WSL2 has no binder devices; no Android Emulator/AVD image or VMOS host hardware was available. No VMOS APK/installer was installed. This prevents claims about proprietary UI behavior, network listeners, signatures or runtime process trees.
- No binaries, ROMs, installers, screenshots, user data, credentials or proprietary assets were added to this repository.

## Sources inspected

| Artifact | Static evidence | Dynamic status / limitation |
| --- | --- | --- |
| VMOS Edge Desktop 2.2.5 | `sources/vmos-edge-desktop/package.json`, `README.md`, `LICENSE`, `src/main/**`, `src/shared/**`, `resources/**`; Electron IPC, SQLite DAOs, host/device/image/group/workflow managers, AgentWorkerManager and bundled scrcpy/MediaMTX/FRP names are present. | NOT RUN. GPL desktop must remain research-only. |
| VMOS Edge Skills | Every skill entry point plus Control/Container API references, workflow compilation/recording references, CLI command/error/recovery references and FlowSmith syntax guidance. | API calls NOT RUN; docs are public contract evidence only. |
| VMOS Pro AOSP guest | `README.md`, build/pack scripts, Android framework/device/system trees, authorization notice. | NOT BUILT/IMPORTED. Commercial restriction is a shipping blocker. |
| VMOS Edge Qt client | `sources/vmos-edge` QtScrcpyCore headers/sources, CMake/QML, MIT license. `DeviceManage` allocates per-device local ports from 27183 and supports ADB reverse with forward fallback; `DeviceParams` documents video/audio/control TCP ports 9999/9998/9997. | NOT BUILT/RUN. Bundled executables are evidence only. |
| npm packages | Exact tarball hashes and extracted `package.json`/README; workflow SDK documents planning/retries/timeouts/convergence/post-action verification/events/script generation; web SDK documents WebCodecs/WebGL and channel events; CLI documents JSON envelopes, batch and YAML modes. | No package executed; no phones-home conclusion is possible. |

## Official applications

VMOS Cast, VMOS Pro APK and VMOS Edge installer were **not obtained or installed** in this run. The lab `downloads/` directory contains no official application artifact. Therefore APK permissions/signature/native library inventory, Cast reconnect behavior and proprietary VMOS Pro instance lifecycle are explicitly `NOT VERIFIED`.

## Reproducible static observations

1. Desktop is a three-boundary Electron application: main process managers/DAOs/workers, preload IPC channels, and Vue renderer. `src/shared/ipc/*.types.ts` exposes device, image, group, batch task, workflow, media, proxy and agent seams.
2. Device state is durable: `DeviceDao`, `GroupDao`, `ImageDao`, `WorkflowDao`, `BatchTaskDao` and `DeviceStateCache` are separate modules, indicating reconnectable inventory and queued work rather than a view-only wall.
3. QtScrcpyCore keeps a serial→local-port allocation map, cleans stale entries, and emits connected/disconnected events; this is a useful clean-room reconnection pattern.
4. Skills separate host/container lifecycle (`:18182`) from guest Android control (`:18185` direct cloud IP or host `/android_api/v2/{db_id}`), and require capability discovery before actions.
5. Workflow authoring is evidence-rich while replay is blind: walks capture dumps/diffs, compiler emits selectors/verifiers, quality gate defaults to 90, runtime submits `workflow/execute` and polls `workflow/execution_get`.

## Evidence paths

- `evidence/github-org-repos.json`
- `evidence/desktop-source-files.txt`
- `sources/vmos-edge-desktop/**`
- `sources/vmos-edge-skills/**`
- `sources/open-vmos-aosp_5.1/**`
- `sources/vmos-edge/QtScrcpyCore/**`
- `packages/_*-view.json` and `packages/*-extracted/package/{package.json,README.md}`
