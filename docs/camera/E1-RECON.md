# E1 — VCAM Fork Strip: Reconnaissance Findings (2026-09-05)

Source cloned to `.engine-work/vcam-upstream` @ `d1faecb` (Yaahua/android_virtual_cam, GPL-3.0, v4.4).

## Upstream inventory

| File | LOC | Role | Keep/Strip |
|---|---|---|---|
| `HookMain.java` | 1230 | Xposed hook entry (IXposedHookLoadPackage): Camera1 + Camera2 hooks, MediaPlayer source playback, surface management | **KEEP core** — the heart of E1 |
| `VideoToFrames.java` | 360 | MediaCodec hardware decode loop (H.264/H.265) | **KEEP** — becomes decode engine |
| `ConfigManager.java` | 263 | JSON config via `/sdcard/DCIM/Camera1/cs_config.json`, auto-migrates marker files | **KEEP path mechanism temporarily**, replace transport in E6 |
| `MainActivity.java` | 257 | Upstream UI (manual file-drop workflow) | **STRIP** — replaced by apps/cyclone-camera |
| `IpcContract.java` | 22 | IPC key constants | **KEEP** |
| `res/` + launcher drawables | 104K | Upstream UI resources | **STRIP** |
| `app/libs/api-82.jar` | — | Xposed API (compileOnly) | **KEEP** |

## Key upstream facts

- Hook entry: `assets/xposed_init` → `com.example.vcam.HookMain`.
- Config transport is a **file-drop**: reads `virtual.mp4` + `cs_config.json` + marker files
  (`disable.jpg`, `no_toast.jpg`) from `/storage/emulated/0/DCIM/Camera1/`. This is what E6
  replaces with the EngineApi-driven service (CONTRACT.md §2).
- HookMain has ~91 Toast references (upstream UX toasts) — strip or gate behind a debug flag.
- Camera1 refs: 63 · Camera2 refs: 32 — both hook paths live in the single HookMain file
  (1230 lines). Strip plan: split into Camera1Hook / Camera2Hook behind one FrameProvider.
- Upstream decodes via `VideoToFrames` (MediaCodec) and feeds either byte[] (Camera1 preview
  callbacks) or Surface/SurfaceTexture (Camera2). Both paths confirmed present.
- Upstream targets compileSdk 31 / minSdk 21 — rebase to our minSdk 33 / target 34 / compile 35.
- Stream source (RTMP/RTSP) does NOT exist upstream — MediaPlayer is file/uri based; E4 adds
  stream ingest (ExoPlayer or ffmpeg) as new code, not a port.

## E1 strip plan (fork → engine module)

1. Copy `HookMain.java`, `VideoToFrames.java`, `ConfigManager.java`, `IpcContract.java` into
   `engine/engine-core/` under package `com.cyclone.camera.engine.hook` (keep GPL headers).
2. Remove: MainActivity, all upstream res/, launcher icons, upstream app UI Gradle.
3. New Gradle: `engine/engine-api` (contract, Kotlin), `engine/engine-core` (hook, Java→Kotlin
   OK incrementally), `engine/engine-service` (Phase 2 E6).
4. Rebase build config: compileSdk 35, minSdk 33, targetSdk 34.
5. First gate: hook module builds as APK-as-module and loads via Zygisk/LSPosed on Pixel 8 test
   image with a raw file — camera app sees injected frames.

## Day-6 gate data (fork vs native)

Strip removes ~35% of upstream code (MainActivity 257 + res + test scaffolding ≈ 700 of ~2170
LOC) but keeps 100% of hook value. License headers are clean GPL-3.0. **Provisional: stay on
fork** unless module-load gate fails; Zygisk-native rewrite cost (3–6 mo) not justified by code
quality alone — HookMain is monolithic but functional and battle-tested across devices.
