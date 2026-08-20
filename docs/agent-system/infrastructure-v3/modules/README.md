# Cyclone Module Supervisor

The Module Supervisor is the single mutable lifecycle authority for optional Cyclone runtime
modules. It consumes the frozen Phase 0 module descriptor and service lifecycle vocabulary from
Foundation SHA `1ef5d5ba9c4273fb190087ebbf7bb51d129c7df8`.

This implementation is deliberately a supervisor for trusted declarations, not a plugin loader.
Every managed runtime is already compiled into Cyclone or explicitly assembled by trusted app
code. There is no Dex loading, JavaScript evaluation, shell installation, root execution, or
downloaded executable-code path.

The MIT-licensed `LX2000WASD/dsh-web-plugin-manager` was inspected as an architecture reference for
guarded lifecycle, dependency/conflict analysis, health diagnostics, and rollback. This is a
clean-room Kotlin implementation behind Cyclone's frozen contracts; no source or runtime from that
project was copied or vendored.

## Agent map

| Need | API |
|---|---|
| Deterministic discovery | `ModuleSupervisor.fromDeclared(...)` |
| Read-only inventory for Catalog | `snapshot()` / `status(moduleId)` |
| Dependency-ordered startup | `startAll(nowEpochMillis)` |
| Individual lifecycle | `start`, `stop`, `enable`, `disable` |
| Health supervision | `refreshHealth(nowEpochMillis)` |
| Bounded recovery | `restartDue(nowEpochMillis)` / `clearQuarantine` |
| Migration declaration | `migrationPlan(moduleId)` |
| Update metadata preflight | `preflightUpdate` / `prepareUpdate` |
| Trusted rollback seam | `rollback(moduleId, targetVersion)` |

All lifecycle mutations go through `ModuleSupervisor`. The returned `ModuleStatus` and
`ModuleSupervisorSnapshot` values are immutable views suitable for diagnostics and the future
Module Catalog. Catalog code must never call a module runtime hook itself.

## Deterministic discovery and validation

`fromDeclared` performs one complete discovery pass and sorts by `ModuleId`. A duplicate module ID
rejects every declaration for that ID, so input iteration order never selects a winner. Before any
runtime hook can execute, the supervisor validates:

- Cyclone API compatibility;
- required module presence and version ranges;
- optional dependency version ranges (warning only);
- required dependency cycles;
- conflicting capability providers;
- migration readiness.

Errors cascade only through required dependency edges. A broken optional module does not prevent
an unrelated module from starting. The stable topological order is exposed as
`deterministicStartOrder`.

## State model

```text
INSTALLED -> STARTING -> READY <-> DEGRADED
    |            |         |          |
    |            +---------+----------+-> FAILED --(explicit time)--> STARTING
    |                                           |
    |                                           +-> QUARANTINED
    +-> DISABLED
    +-> UPDATE_PENDING
```

The status also maps onto the frozen `ServiceLifecycleState` vocabulary. `UPDATE_PENDING` maps to
`STOPPED`, while `QUARANTINED` maps to `FAILED`. Critical built-in modules cannot be disabled.
Disabling a dependency is rejected while a running module requires it.

## Restart and quarantine policy

`RestartPolicy.maxStartAttempts` includes the initial attempt. Failed start hooks and failed health
checks share one failure streak. Backoff is exponential and capped. The supervisor records the
next eligible epoch millisecond; it never sleeps or creates an internal timer. A caller invokes
`restartDue(nowEpochMillis)` using its trusted scheduler.

After the budget is exhausted, the module becomes `QUARANTINED` and unrelated modules remain
available. Clearing quarantine is an explicit supervisor operation and resets only transient
failure diagnostics. Integration should place policy/user authority around that operation when it
is wired into product UI.

## Updates, migrations, and rollback

Update APIs inspect metadata only. `ModuleUpdateCandidate` contains a frozen descriptor and a
SHA-256 manifest digest; it contains no executable payload. The default preflight requires the same
module ID, a newer module version, compatible Cyclone API, and satisfiable required dependencies.
It reports a schema migration when the candidate migration version increases.

`prepareUpdate` marks a stopped module `UPDATE_PENDING`; it does not download, install, activate, or
execute anything. The Runtime Updater agent owns verified artifact staging. A rollback runs only an
explicit `ModuleRollbackHook` supplied by the trusted declaration. No generic filesystem or command
hook exists.

## Failure and privacy behavior

- Exceptions in start, stop, health, migration, update, or rollback hooks are caught at the module
  boundary.
- Diagnostics record the exception type, not the exception message, to avoid leaking provider or
  user data.
- Runtime-reported reasons must already be safe for diagnostics; implementations must never place
  credentials, typed values, tokens, OTPs, or payment data in them.
- No module state or event grants phone-action authority. Policy approval and the canonical
  `PhoneToolExecutor` path remain mandatory after a module is healthy.

## Integration notes

Agent 15 should assemble trusted declarations in shared composition code and call
`ModuleSupervisor.fromDeclared` once. Do not add discovery by classpath scanning or network catalog.
The supervisor is intentionally not wired into `MainActivity`, the manifest, or app startup on this
branch.

Agent 3 can build the Catalog entirely from `ModuleSupervisorSnapshot`. Enable, disable, update
preflight, quarantine clearing, and rollback actions must delegate back to the supervisor. The
Catalog has no independent mutation authority.

Focused JVM tests cover dependency order, cycles, missing/incompatible dependencies, duplicate
modules/providers, critical modules, crash isolation, deterministic retry/backoff, quarantine,
migration planning, update preflight, rollback, and health degradation/recovery.
