# Cyclone One — first run

Cyclone One 1.5.2 is self-contained for Android transport onboarding and ChatGPT Attach.

1. Open Cyclone One and click **Connect phone**.
2. No Android Studio or separate ADB install is required. Cyclone One ships bundled Android Platform-Tools 37.0.1.
3. Choose one transport:
   - **USB:** enable Android Developer options → USB debugging, connect the cable, unlock the phone, approve this PC, then click **Check USB**.
   - **Wireless:** Android Settings → Developer options → Wireless debugging → Pair device with pairing code. Enter the pairing address, the separate device address, and the 6-digit code, then click **Pair & connect**.
   - **VMOS:** use Android 15 when available. Android 13/14 are compatible; Android 12 and older are unsupported for this Cyclone setup. Install Cyclone Mobile 4.3.6 inside VMOS, enable the provider ADB/Local Debugging tunnel, copy its host:port endpoint, then click **Connect VMOS phone**.
4. Cyclone One starts its private One gateway automatically on loopback. A successful transport makes the phone discoverable and immediately refreshes the Fleet.
5. Transport is not Cyclone trust. After ADB is ready, open **Cyclone Mobile → PC Gateway & QR pairing** and finish the normal QR/manual trust-pairing flow in Cyclone One.
6. **ChatGPT Attach** syncs VMOS Cloud pads and copies a one-file Custom GPT handoff. SSH Connect Keys and VMOS AccessKeys stay on this PC.

The One gateway remains loopback-only; do not expose its bearer token or ADB endpoint publicly.
