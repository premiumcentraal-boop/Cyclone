# VMOS prerequisites and install order

This is the supported operator path for Cyclone on VMOS. Pairing/trust is deliberately excluded; that begins only after this page reports a running Cyclone Mobile process.

## Validated baseline

- Windows 10 or later.
- Cyclone One 1.1.2 installed for the current user. Its installer carries the packaged PC runtime/agent sidecars; do not install the retired Cyclone PC Companion beside it.
- Cyclone Mobile 4.3.6 or newer official APK.
- VMOS Android 13, 14, or 15. Android 15 is preferred. Cyclone Mobile has `minSdk = 33`, so Android 10 cannot install the current app.
- VMOS remote ADB access authorized for the account and a current ADB session opened in **Local Debugging → ADB**. VMOS documents these connections as valid for 24 hours.

VMOS Edge Android Control API is not required for Cyclone's baseline path. If an operator explicitly enables future Edge Control API features, use the image minimum documented by VMOS for that API; Android 15 requires `vcloud_android15_edge_20260110` or newer.

## One install path

1. Create/select a VMOS Android 15 phone (13/14 are supported compatibility targets).
2. Install Cyclone One 1.1.2 on the Windows PC.
3. In VMOS, ensure remote-ADB permission is authorized, open **Local Debugging → ADB**, then execute VMOS's current connection command/key until the device appears as `device` in `adb devices`.
4. Download the official `Cyclone-4.3.6.apk` or a newer compatible release.
5. Run the prerequisite checker, then the installer:

```powershell
.\scripts\vmos\check-prerequisites.ps1 -MobileApk "C:\path\Cyclone-4.3.6.apk" -VmosSerial "<serial-from-adb-devices>"
.\scripts\vmos\install-mobile.ps1 -MobileApk "C:\path\Cyclone-4.3.6.apk" -VmosSerial "<serial-from-adb-devices>"
```

The installer executes and verifies the canonical operations:

```text
adb -s <serial> install -r <Cyclone.apk>
adb -s <serial> shell am start -W -n com.cyclone.mobile/.MainActivity
adb -s <serial> shell pm path com.cyclone.mobile
adb -s <serial> shell pidof com.cyclone.mobile
```

Success means the APK is installed, `.MainActivity` launched, and the `com.cyclone.mobile` process is alive. It does **not** mean Cyclone One is paired yet; pairing/trust is the next setup stage.
