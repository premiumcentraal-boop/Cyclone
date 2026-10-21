# VMOS prerequisites and install order

This is checklist item 2 only: get Windows + VMOS + Cyclone Mobile to a verified running app process. Pairing/trust starts in checklist item 3.

## Exact validated baseline

### Windows / Cyclone One

- Windows 10 or Windows 11.
- Published **Cyclone One 1.5.0 — Local AI Edition**.
- Installer: `Cyclone-PC-Companion-1.5.0-Setup.exe`
- Installer SHA-256: `191bae8bf09ffd95650cb21f4aa6ae090118e4001baf436f5e216615f2037097`
- Installed per-user under `%LOCALAPPDATA%\Cyclone One`.
- Cyclone One carries `CyclonePCRuntime.exe` (PC Agent/runtime), `CycloneAgentMCP.exe`, and `CycloneLivePhone.exe`. **Do not install a separate PC Agent or the retired Cyclone PC Companion 3.8.x.**

Cyclone One does **not** bundle `adb.exe`. Install Google's current Android SDK Platform-Tools for Windows and either put `adb.exe` on `PATH` or pass its full path to the VMOS setup scripts.

### Cyclone Mobile

- Published **Cyclone Mobile 4.3.6**, Android `versionCode 97`.
- APK: `Cyclone-4.3.6.apk`
- APK SHA-256: `4894dd8c0a69d3445d81b2f33c98ceef86630a0951d273912bd240b87b27dc17`
- Package: `com.cyclone.mobile`
- Launcher: `.MainActivity`
- `minSdk = 33`: Android 13 minimum.

### VMOS

Use hosted **VMOS Cloud Android 15** for the primary path. Android 13/14 are compatibility targets. Android 10 is incompatible with the current APK.

For the normal hosted VMOS path there is **no Edge image ID requirement**: Cyclone uses VMOS only as the Android host and remote-ADB transport at this stage.

VMOS remote ADB must be enabled for the account. In the VMOS Web/PC client, open the cloud phone, then **Local Debugging → ADB**, enable ADB, and follow VMOS's generated SSH connection command / connection key / ADB connection flow. VMOS documents the manual connection as valid for 24 hours. `adb devices` must show the resulting VMOS serial in `device` state.

Optional only: if a future deployment uses VMOS Edge **Android Control API** rather than the normal hosted remote-ADB path, current VMOS documentation requires CBS `1.1.1.10+`; Android 15 requires image `vcloud_android15_edge_20260110` or newer. This is not a prerequisite for the supported hosted VMOS path.

## One install order

1. Install **Cyclone One 1.5.0** using `Cyclone-PC-Companion-1.5.0-Setup.exe`.
2. Install **Google Android SDK Platform-Tools** and confirm `adb version` works.
3. Create/select a hosted **VMOS Cloud Android 15** phone.
4. Have VMOS remote-ADB permission authorized, then open **Local Debugging → ADB** and establish the generated SSH/ADB connection until `adb devices` reports `device`.
5. Download the signed published `Cyclone-4.3.6.apk` and verify its SHA-256.
6. From the Cyclone repository run:

```powershell
.\scripts\vmos\check-prerequisites.ps1 -MobileApk "C:\path\Cyclone-4.3.6.apk" -VmosSerial "<serial-from-adb-devices>"
.\scripts\vmos\install-mobile.ps1 -MobileApk "C:\path\Cyclone-4.3.6.apk" -VmosSerial "<serial-from-adb-devices>"
```

If `adb.exe` is not on `PATH`, pass it explicitly to both scripts:

```powershell
-Adb "C:\Android\platform-tools\adb.exe"
```

The installer verifies:

```text
adb -s <serial> install -r <Cyclone.apk>
adb -s <serial> shell am start -W -n com.cyclone.mobile/.MainActivity
adb -s <serial> shell pm path com.cyclone.mobile
adb -s <serial> shell pidof com.cyclone.mobile
```

Item 2 is successful only when the prerequisite checker has no blockers, the exact APK is installed, `.MainActivity` launches, and `com.cyclone.mobile` has a live PID. Pairing/trust is intentionally not part of this item.
