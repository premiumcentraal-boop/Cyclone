# VMOS prerequisites and install order

This is the supported operator path for Cyclone on VMOS. Pairing/trust is deliberately excluded; that begins only after this page reports a running Cyclone Mobile process.

## Validated baseline

- Windows 10 or later.
- Cyclone One 1.1.2 installed for the current user. Its installer carries the packaged PC runtime/agent sidecars; do not install the retired Cyclone PC Companion beside it.
- Cyclone Mobile 4.3.6 or newer official APK.
- Hosted VMOS Cloud Android 15 is the preferred target. Android 13/14 are supported compatibility targets. Cyclone Mobile has `minSdk = 33`, so Android 10 cannot install the current app. Android 16 is available from VMOS but is not yet in Cyclone's validated VMOS target set.
- VMOS remote ADB permission authorized for the account and a live ADB session established from **Local Debugging → ADB**. VMOS's UI/manual flow documents a 24-hour connection. Its OpenAPI can request an ADB validity period from 1 to 7 days.

## VMOS Cloud vs VMOS Edge

Use hosted **VMOS Cloud** for the normal Cyclone cloud-phone path. It requires no local VMOS image management: choose Android 15 in the hosted service.

VMOS Edge is optional infrastructure, not a prerequisite for hosted VMOS Cloud. If Edge is used, keep the client/image generation matched. VMOS's current release history documents Edge client **2.0.4** and CBS **1.1.1.10.7** as the release that introduced Android Control API support. The documented VMOS Edge 2.0 Android-15 reference image is `vcloud_android15_edge_20251227201917`. Do not invent or infer a newer image minimum from the date of the client release.

## One install path

1. Create/select a hosted VMOS Cloud **Android 15** phone. Use Android 13/14 only when testing compatibility.
2. Install Cyclone One 1.1.2 on the Windows PC.
3. In VMOS, ensure remote-ADB permission is authorized. Open **Local Debugging → ADB** and run VMOS's generated SSH connection command, connection key, and `adb connect ...` command until the resulting serial appears as `device` in `adb devices`.
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

Success means the APK is installed, `.MainActivity` launched, and the `com.cyclone.mobile` process is alive. It does **not** mean Cyclone One is paired yet; pairing/trust is the next checklist item.
