# Cyclone Host Bridge

The Host Bridge is a small Windows-native, **loopback-only** local service. It is not a remote shell and is not mounted into Docker.

## Current implemented capabilities

| Capability | Behavior | Policy |
|---|---|---|
| `filesystem.read` | Reads one file up to 1 MB from the configured Cyclone workspace | Allowlisted read-only |
| `process.list` | Lists process names/IDs | Allowlisted read-only |
| `window.list` | Lists processes with top-level window captions | Allowlisted read-only |

The capability vocabulary also reserves `filesystem.write`, `app.launch`, `powershell.execute`, `git.execute`, `browser.open`, `browser.navigate`, and `screenshot.capture`, but this bridge intentionally **does not implement** them yet. A Core approval alone never turns into an arbitrary shell execution.

## Security boundary

- binds to `127.0.0.1` only;
- requires a non-placeholder `CYCLONE_HOST_BRIDGE_TOKEN` bearer token;
- resolves filesystem paths and restricts them to `CYCLONE_WORKSPACE_HOST_PATH`;
- applies time/capability validation before execution;
- creates JSONL audit entries under `%LOCALAPPDATA%\Cyclone\HostBridge\audit` by default;
- rejects unknown/unimplemented capabilities, including after an approval.

## Local run

```bash
set CYCLONE_HOST_BRIDGE_TOKEN=<strong local secret>
set CYCLONE_WORKSPACE_HOST_PATH=C:\Users\<you>\Documents\CycloneWorkspace
dotnet run --project apps/host-bridge/Cyclone.HostBridge
```

`dotnet` is present on the development host. The project is not claimed as built until `dotnet build` successfully executes.

## Relationship to Cyclone Core

Cyclone Core evaluates whether a proposed host action is read-only, denied, or needs an approval record. The Host Bridge is separately defensive: it rechecks path/capability constraints and exposes no generic command execution endpoint. Telegram-originated operations must go through the same Core policy path.
