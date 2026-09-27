# Multi-device fleet

Cyclone can name, watch, and task more than one paired Android phone from the gateway you already run. One planner sees the fleet. Each phone keeps its own session, queue, and verification. Phone actions still go through the existing ask path and `PhoneToolExecutor`. Nothing here is a second automation engine.

This has **not** been verified on physical Android devices. Unit tests use stand-in ADB. Run [REAL_DEVICE_FLEET_CHECK.md](REAL_DEVICE_FLEET_CHECK.md) on a PC with two authorized phones before treating any of this as proven on hardware.

The browser fleet preview is not this feature. Do not use it as evidence.

Deeper design notes: [FLEET_ORCHESTRATION.md](FLEET_ORCHESTRATION.md).

## What was added

| Piece | What it does |
|---|---|
| Device registry | Remembers each phone by a stable id, a name you choose, trust, online/offline, screen state, and the mission it is in. Names survive a gateway restart in `fleet-registry.json`. |
| Live sync | Copies phones the existing ADB fleet already discovered into that registry. It does not invent devices. Unplug and replug come from ADB events. |
| Planner | Turns one sentence that names two phones into two device-scoped missions. Names come from the registry, not from hardcoded phone models. |
| Parallel runner | Runs those missions at the same time. Each phone has its own lock. There is no lock across the whole fleet. |
| Ask runner | Sends each mission only to that phone: `ask.start` / `ask.status` through the existing V5 contract, gateway, and OpenRouter agent on the phone. It waits up to three minutes. The OpenRouter key stays in the phone's `OpenRouterSecretStore`. |
| Session check | Refuses a mission that uses another phone's session. The error is `DEVICE_CONTEXT_MISMATCH`. The other phone is not touched. |
| Lock handling | If Android reports a secure lock, that phone becomes `WAITING_OWNER`. Cyclone does not type a PIN and does not dismiss the lock. Other phones keep going. After you unlock it yourself, resume re-reads the screen and does not blindly replay an action that may already have happened. |
| Disconnect | The unplugged phone leaves the online set. A mission that was mid-action is not replayed until the phone is seen again. The other phone is not stopped. |
| Per-device controls | Stop one phone, continue it, or take over that phone, plus stop the whole fleet. |

Glass was not redesigned. The existing Devices page reads `/v1/fleet/snapshot` on its normal refresh and shows the registry next to the phones it already lists.

## How to open it

From `cyclone/apps/device-gateway`, with Python 3.11 and `pip install -e .`:

```bash
export CYCLONE_DEVICE_GATEWAY_TOKEN="the-bearer-you-already-use"
export CYCLONE_DESKTOP_PAIRING_BOOTSTRAP=1
export CYCLONE_DEVICE_GATEWAY_RUNTIME="$PWD/.runtime/device-gateway"
export CYCLONE_DEVICE_GATEWAY_URL="http://127.0.0.1:8765"
PYTHONPATH=. python -m cyclone_device_gateway serve
```

If Cyclone One already saved a bearer, `python -m cyclone_device_gateway glass` attaches to that gateway or starts this one.

Then:

1. Plug in the phones and accept USB debugging (`adb devices -l` shows `device`).
2. Open Glass and go to **Devices**.
3. Connect each phone and tap **Allow** on the phone. A phone that is only discovered is not trusted and will not be tasked.
4. Name them. The planner uses those names.

The registry file is `<CYCLONE_DEVICE_GATEWAY_RUNTIME>/fleet-registry.json`. Mission history is `fleet-missions.json` in the same folder. Neither file stores the OpenRouter key.

## How to ask the fleet

Glass and any companion call the same routes. Send `Authorization: Bearer <token>`.

Name both phones in one sentence:

```bash
curl -s -X POST http://127.0.0.1:8765/v1/fleet/command \
  -H "Authorization: Bearer $CYCLONE_DEVICE_GATEWAY_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"text":"On Device A, open Settings and stop. On Device B, open Clock and stop."}'
```

The response includes `fleetMissionId`. Read it back:

```bash
curl -s http://127.0.0.1:8765/v1/fleet/missions/FLEET_MISSION_ID \
  -H "Authorization: Bearer $CYCLONE_DEVICE_GATEWAY_TOKEN"
```

`done` from the phone is what marks a child mission verified. The fleet does not invent a model answer.

### Routes

| Method and path | What you get |
|---|---|
| `GET /v1/fleet/snapshot` | Names, trust, online, screen, current mission |
| `GET /v1/fleet/devices` | Same device list |
| `POST /v1/fleet/devices/{id}/rename` | `{"name":"Device A"}` |
| `POST /v1/fleet/command` | `{"text":"..."}` planned and started |
| `POST /v1/fleet/missions` | An explicit plan, or the same text fields |
| `GET /v1/fleet/missions/{id}` | Status of that fleet mission or one child |
| `POST /v1/fleet/missions/{id}/cancel` | Cancel |
| `POST /v1/fleet/missions/{id}/pause` | Pause |
| `POST /v1/fleet/missions/{id}/resume` | Continue after you unlock, or after reconnect |
| `POST /v1/fleet/devices/{id}/stop` | Stop that phone only |
| `POST /v1/fleet/devices/{id}/continue` | Release a takeover on that phone |
| `POST /v1/fleet/devices/{id}/take-control` | You drive that phone; its mission waits |
| `POST /v1/fleet/stop` | Stop every running mission |
| `GET /v1/fleet/events` | Discovery, start, and completion stamps |

`GET /v1/fleet` is still the older device list (pair, video, bridge health). The new registry is `/v1/fleet/snapshot`.

## What a mission status means

| Status | Meaning |
|---|---|
| `QUEUED` / `RUNNING` | That phone's turn is waiting or in progress |
| `COMPLETED` | The phone reported a verified `done` |
| `WAITING_OWNER` | The screen is locked. Unlock it yourself. No PIN is sent |
| `RECONNECTING` | ADB dropped that phone |
| `PAUSED` | Stopped on purpose, or paused so a possible half-finished action is not replayed |
| `FAILED` | That phone failed. Other phones are unchanged |
| `PARTIAL_FAILURE` | The fleet mission finished with mixed phone results |

## Check it on two real phones

The command and the expected evidence file are in [REAL_DEVICE_FLEET_CHECK.md](REAL_DEVICE_FLEET_CHECK.md). Until that command exits 0 on your PC, this README is a description of the code, not a hardware result.

## Where the code lives

```text
cyclone/apps/device-gateway/cyclone_device_gateway/desktop_runtime/orchestration/
  live.py         ADB events → registry
  registry.py     names, trust, fleet-registry.json
  planner.py      one sentence → per-device missions
  controller.py   queues, locks, stop, resume
  runner.py       ask.start / ask.status for one device
  power.py        lock probe, wake only, no PIN
  fleet_api.py    /v1/fleet routes
cyclone/apps/device-gateway/cyclone_device_gateway/real_device_check.py
cyclone/apps/glass/src/pages/devicesPage.ts    existing Devices page, snapshot poll
```
