# Part B — transport onboarding (USB / Wireless / VMOS ADB)

This runbook covers transport only. Cyclone trust pairing remains a separate step after ADB transport is ready.

## Cyclone One UI

1. Open Cyclone One and click **Connect phone**.
2. USB: choose **USB**, connect the cable, enable **Developer options → USB debugging**, approve this PC on the phone, then click **Check USB**.
3. Wireless: choose **Wireless**, on Android open **Developer options → Wireless debugging → Pair device with pairing code**. Enter the **pairing address**, the separate **device address**, and the **6-digit pairing code**, then click **Pair & connect**.
4. VMOS: choose **VMOS**, enable the provider's ADB/Local Debugging tunnel, copy its `host:port` endpoint, paste it into Cyclone One, then click **Connect VMOS phone**.
5. When the panel reports the transport ready, Cyclone One rescans the fleet. The device then appears as an unpaired Cyclone phone until normal Cyclone trust pairing is completed.

Android Wireless Debugging uses two addresses: the temporary pairing address and the regular device connection address. Cyclone rejects using the same endpoint for both.

## Allowlisted CLI fallback

The CLI accepts no arbitrary ADB command and never accepts the wireless pairing code in argv.

```text
cyclone-device-gateway transport usb
cyclone-device-gateway transport wifi --pair 192.168.1.25:37123 --connect 192.168.1.25:42891
cyclone-device-gateway transport vmos --endpoint vmos.example:5555
```

The Wi-Fi command prompts privately for the 6-digit pairing code. `--json` is available for transport status output; pairing secrets are not returned.

## Verification contract

- `apps/device-gateway/cyclone_device_gateway/adb/onboarding.py` is the only ADB transport executor for this flow.
- `apps/device-gateway/cyclone_device_gateway/desktop_runtime/transport_api.py` exposes only USB inventory, Wireless pair/connect, VMOS connect, and endpoint disconnect through authenticated loopback routes.
- `apps/pc-companion/src/services/transportOnboardingClient.ts` calls only those typed routes using Cyclone One's protected `gateway_session`.
- `apps/pc-companion/src/ui/transportOnboarding.ts` is the operator UI and clears the pairing code input immediately on submit.
- `apps/device-gateway/tests/test_transport_onboarding.py`, `test_transport_cli.py`, and `apps/pc-companion/tests/transport-onboarding-ui.test.mjs` are the Part B adversarial contract tests.
