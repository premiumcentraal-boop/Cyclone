# Fleet orchestration

Cyclone can coordinate more than one trusted Android device without creating a second phone-mutation engine.

```text
User / Glass / companion
        │
        ▼
FleetController          apps/device-gateway/.../orchestration/
  registry, resolver, planner, per-device queues
        │
        ▼
AskContractRunner  ── ask.start / ask.status on that device only
        │
        ▼
V5ContractService.forward → DeviceSession.bridge
        │
        ▼
GatewayV5AskAdapter → OverlayChromeRuntime.submitRequest
        │
        ├── OpenRouterAdaptiveAgent (phone OpenRouterSecretStore)
        └── WorkspaceTasks (existing background workspace)
        │
        ▼
PhoneToolExecutor
```

`FleetLiveSync` listens to the existing ADB fleet event broker. It does not scan
for devices itself. A connected serial becomes `deterministic_device_id(serial)`.
Names and aliases stay in `fleet-registry.json`. A device is not trusted until
the existing PC trust record says so, or the phone session credential is present.
Revocation uses the existing trust coordinator state. Disconnect is
`DEVICE_REMOVED` / `DISCONNECTED` from `DeviceFleetManager`, not a timer.

Lock state is `dumpsys window` / `dumpsys power` on that device. A keyguard match
becomes `WAITING_OWNER`. The production power hook may send `KEYCODE_WAKEUP` for
a sleeping screen and does not attach a lock-dismiss or PIN entry.

`ask.start` is only accepted on `default-foreground` / display 0. The phone, not
the fleet, decides whether the goal text runs on the main screen or in the
existing background workspace. Two devices run in the fleet thread pool with
one lock per device. There is no fleet-wide execution mutex.

## Identity

Stable ids stay `dev_<sha256 prefix>` from `deterministic_device_id`. Display names and aliases live in `fleet-registry.json`. Trust is explicit: discovered devices are not tasked until `trust`. Revoked devices stop. The controller device is a trusted primary, not an Android-root concept and not a second automation engine.

## Isolation

Every child mission binds `deviceId + sessionId + displayId`. `assert_context` rejects a mismatched device, session, or display with `DEVICE_CONTEXT_MISMATCH`. Named sessions cannot be reused on another device. `default-foreground` stays display 0, per device.

## Parallelism

Each device has its own queue and lock. A fleet thread pool (max 8) runs different devices together. One device still serializes its own missions. A failure, owner wait, disconnect, or takeover on one device does not cancel the others. Mixed success is `PARTIAL_FAILURE`.

## Phone work

`AskContractRunner` forwards `ask.start` and polls `ask.status` on the bound plane. Acceptance is not completion. `done` is the phone's existing verified outcome. Shell is refused. Secure locks become `DEVICE_NEEDS_OWNER` and never receive a PIN. An insecure lock is dismissed only when that device profile allows it, through an injected transport hook — not scattered ADB keys.

Disconnect and process restart set `reobserveRequired` and will not replay an unverified action.

## Secrets

Journals and events drop key material, OTP fields, screenshots, and typed secrets. Cross-device codes are owner handoffs: the fleet stores the dependency, not the secret. OpenRouter keys stay in the phone's existing `OpenRouterSecretStore`. The planner snapshot has no key field. An optional model hook may propose a plan; the planner still validates device ids, trust, and capabilities before anything runs.

## HTTP

Authenticated routes live under `/v1/fleet/` (`snapshot`, `devices`, `missions`, `command`, `stop`, per-device trust / rename / revoke / take-control / continue). They sit beside the existing fleet discovery routes.
