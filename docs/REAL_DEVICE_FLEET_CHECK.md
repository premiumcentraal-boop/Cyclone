# Real-device fleet check

The checker does not start a fleet of its own. It calls the Cyclone gateway you already started, on `/v1/fleet/*`. It does not use `FakeDeviceADB`, `ScriptedRunner`, `RecordingContract`, or the browser preview.

No physical-device run has passed. This sandbox has no `adb` and no phones.

## 1. Directory

```bash
cd cyclone/apps/device-gateway
```

Use this source tree. An older installed `cyclone` binary will not have `/v1/fleet/command`.

## 2. Python

Python 3.11 or newer. The package declares `requires-python = ">=3.11"`.

## 3. Dependencies

```bash
python3.11 -m venv .venv
source .venv/bin/activate
pip install -e .
```

That installs FastAPI, uvicorn, websockets, pydantic, and cryptography. Test-only packages are not required.

## 4. ADB

```bash
adb version
```

You want a version line from Android platform-tools. `command not found` means it is not installed.

## 5. Both phones authorized

```bash
adb devices -l
```

Two lines must say `device`. `unauthorized` and `offline` do not count. Accept the USB debugging prompt on each phone. If more than two phones are connected, set `CYCLONE_DEVICE_A_SERIAL` and `CYCLONE_DEVICE_B_SERIAL`.

## 6. Cyclone accessibility

On each phone: Settings → Accessibility → Cyclone, on.

The checker also reads `connectionHealth.accessibilityConnected` from the running gateway. That becomes true only after the phone is paired and a bridge heartbeat has succeeded. `false` means do not run `--commands`.

Confirm the app is installed:

```bash
adb -s SERIAL shell pm path com.cyclone.mobile
```

The line must start with `package:`.

## 7. OpenRouter key, without showing it

On each phone open Cyclone's API key screen. It must say **API key secured**. That is `OpenRouterSecretStore.hasKey`. Do not print the key, and do not `adb shell` the preferences file.

## 8. First command

Start the gateway from this directory and pair both phones in Glass (Connect, then Allow on the phone). Keep that process running.

In a second shell, with the same `CYCLONE_DEVICE_GATEWAY_TOKEN` and `CYCLONE_DEVICE_GATEWAY_URL` if you exported them (a saved Cyclone One bearer is picked up automatically):

```bash
cd cyclone/apps/device-gateway
source .venv/bin/activate
export CYCLONE_REAL_DEVICES=1
export PYTHONPATH=.
python -m cyclone_device_gateway.real_device_check
```

Do not start a second gateway.

## 9. Successful first output

Exit 3. Stdout is JSON with `"result": "PARTIAL_NOT_RUN"`, `"attachedToRunningGateway": true`, and `"phases.discovery": "PASS"`. Command, isolation, OpenRouter, disconnect, and lock stay `NOT_RUN`.

After pairing, accessibility, and the key screen:

```bash
python -m cyclone_device_gateway.real_device_check --commands
python -m cyclone_device_gateway.real_device_check --commands --operator
```

`--operator` tells you when to unplug, replug, lock, and unlock. It never asks for a PIN.

## 10. Evidence

`cyclone/apps/device-gateway/.runtime/real-device-check/real-device-evidence.json`

A full pass has `physicalDevicesUsed: true`, `fakeDoublesUsed: false`, both serials, `deviceId` equal to `dev_` plus the first 20 hex chars of SHA-256(`cyclone-desktop-v1\0` + serial), overlapping `timeline` stamps, Settings in device A's `resumedAfter`, Clock in device B's, `isolation.code` of `DEVICE_CONTEXT_MISMATCH`, and both missions `verified: true`. The gateway token is not written.

The gateway's own `fleet-registry.json` is in its runtime directory (`CYCLONE_DEVICE_GATEWAY_RUNTIME`, or the Cyclone One locator). That file is the persisted names.

## 11. Success

Exit **0**. Every phase that the command was asked to run passed on the phones. Exit 3 is only a partial checkpoint.

## 12. Other exit codes

| Code | Meaning |
|---|---|
| 0 | Hardware phases that ran all passed |
| 1 | A connected phone or the running gateway failed a check |
| 2 | NOT_RUN. Safety flag off, adb missing, fewer than two authorized phones, or no gateway from this source tree |
| 3 | Phones were discovered on the running gateway; command and/or operator phases were not run |
