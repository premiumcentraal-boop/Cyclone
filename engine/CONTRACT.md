# EngineApi Contract — Reference Implementation (Phase 0 lock)

**Owner:** CC-Config Bridge · **Status:** LOCKED 2026-09-05 · **Version:** 1.0
**Branch:** `worktree/cyclone-camera` · This is the single source of truth.
Disputes route to CC-Config Bridge; nobody else edits `engine/engine-api/` without a contract changelog entry.

## What is locked

The UI side of this contract already exists (verified) in `apps/cyclone-camera/app/src/main/java/com/cyclone/camera/engine/EngineApi.kt`:

- `EngineState`: `OFF | ARMED | INJECTING | ERROR(reason)` (sealed interface)
- `CameraMode`: `OFF | FRONT | BACK`
- `SourceType`: `FILE | STREAM`
- `VideoSource(type, uri, url, label, resolution, fps)`
- `IntegrityTier`: `BASIC | DEVICE | STRONG`; `IntegrityResult(tier, passed)`
- `LogEntry(timestamp, level, message, hint?)`; `LogLevel: INFO | WARN | ERROR`
- `EngineSettings(resolutionOverride?, autoDisarmOnLock, hideAppIcon, sensorLock, jitterInjection)`
- `EngineApi` interface: `getStatus, setMode, setSource, setLoop, arm, refreshIntegrity, runSetup, getLogs`

## What this lock adds (engine-side, non-UI)

### 1. UI sanitize gate

`EngineLogEntry` strings crossing the UI boundary are pre-sanitized. The engine keeps raw
technical logs internally. UI-visible vocabulary: "system component", "compatibility layer",
"one-time setup". Forbidden in any UI-facing string: root, Magisk, Zygisk, Shamiko, hook,
detect, stealth, integrity (use "system check" / pill labels BASIC/DEVICE/STRONG only).

### 2. State machine edge cases (binding)

- `arm()` with invalid/unreadable source → `ERROR(no source selected)`; arm not attempted (spec-mandated).
- `arm(false)` is always valid from every state, including ERROR and mid-setup (panic triple-tap).
- `runSetup()` is idempotent: on a verified stack it's a no-op success. Progress reports via callback; reboot required step is UI-presented as "Reboot now".
- Reboot during INJECTING → fresh boot starts at OFF; INJECTING is never persisted.
- `refreshIntegrity()` never throws; per-tier failure is a result, not an error state. STRONG unavailability is a pass/fail value, not a crash path.
- `setSource` while INJECTING: allowed; engine performs graceful source swap (stop → swap → resume) and logs it.

### 3. Threading & lifecycle

- All `EngineApi` calls are safe from the main thread; implementations must post work off-thread.
- `getStatus()` returns current state synchronously; UI also observes via ViewModel polling/refresh (v1 contract is poll-based; reactive flows come with the real engine in Phase 2 and must be additive, not breaking).
- Settings are read/written through standard DataStore by the UI; the engine consumes the same DataStore file and must not write UI-owned keys.

### 4. Module ownership (paths)

| Path | Owner |
|---|---|
| `apps/cyclone-camera/**` (UI, package `com.cyclone.camera`) | ChatGPT loop / orchestrator |
| `engine/engine-api/**` | CC-Config Bridge (this lock) |
| `engine/engine-core/**`, `engine/engine-service/**` | CC-Engine Core |
| `module/**` | CC-Anti-Detect + CC-Module Installer |
| `.github/workflows/mobile-ci.yml` | CC-CI Reliability only |

### 5. Change procedure

Any contract change: proposal → CC-Config Bridge review → bump this file's version + changelog
entry below → UI re-sanitization check → CI green. The ChatGPT UI loop always codes against the
latest locked version; one direction only.

## Changelog

- 1.0 (2026-09-05): Initial lock. Contract mirrors verified `apps/cyclone-camera` EngineApi.kt.
  Adds sanitize gate, state machine edge cases, threading rules, ownership table.
