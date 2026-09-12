from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Final


CURRENT_ONE_VERSION: Final = "1.1.2"
CURRENT_MOBILE_BASELINE: Final = "4.3.6"
MIN_WINDOWS_MAJOR: Final = 10
MIN_ANDROID_MAJOR: Final = 13
PREFERRED_ANDROID_MAJOR: Final = 15
SUPPORTED_ANDROID_MAJORS: Final = (13, 14, 15)
VMOS_REMOTE_ADB_SESSION_HOURS: Final = 24
EDGE_ANDROID15_CONTROL_API_MIN_IMAGE: Final = "vcloud_android15_edge_20260110"


@dataclass(frozen=True)
class InstallStep:
    order: int
    component: str
    action: str
    reason: str

    def public(self) -> dict[str, object]:
        return {
            "order": self.order,
            "component": self.component,
            "action": self.action,
            "reason": self.reason,
        }


INSTALL_ORDER: Final = (
    InstallStep(
        1,
        "VMOS",
        "Create/select one VMOS cloud phone on Android 13, 14, or 15; Android 15 is preferred.",
        "Cyclone Mobile minSdk is 33 (Android 13), so Android 10 is not a valid Cyclone target.",
    ),
    InstallStep(
        2,
        "Cyclone One",
        f"Install Cyclone One {CURRENT_ONE_VERSION} on Windows 10+ for the current user.",
        "Cyclone One bundles CyclonePCRuntime/PC Agent; do not install a second legacy PC Companion.",
    ),
    InstallStep(
        3,
        "VMOS Remote ADB",
        "Ensure VMOS ADB permission is authorized for the account, then enable ADB in VMOS Local Debugging and obtain the current connection command/key.",
        f"VMOS documents each remote ADB connection as valid for {VMOS_REMOTE_ADB_SESSION_HOURS} hours; this is bootstrap transport only.",
    ),
    InstallStep(
        4,
        "Cyclone Mobile",
        f"Download Cyclone Mobile {CURRENT_MOBILE_BASELINE} or newer APK from the official GitHub release.",
        "The VMOS phone must run package com.cyclone.mobile and launcher .MainActivity.",
    ),
    InstallStep(
        5,
        "Install + launch",
        "Using the already-authorized VMOS ADB serial: adb -s <serial> install -r <Cyclone.apk>; then adb -s <serial> shell am start -n com.cyclone.mobile/.MainActivity.",
        "This installs/starts Mobile without introducing a second agent-control path; pairing/trust is the next checklist item.",
    ),
)


@dataclass(frozen=True)
class PrerequisiteSnapshot:
    windows_major: int
    cyclone_one_version: str | None
    adb_available: bool
    mobile_apk: str | None
    vmos_android_major: int | None
    vmos_adb_account_authorized: bool
    vmos_adb_session_open: bool
    vmos_image: str | None = None
    needs_edge_control_api: bool = False


@dataclass(frozen=True)
class PrerequisiteResult:
    ready: bool
    blockers: tuple[str, ...]
    warnings: tuple[str, ...]

    def public(self) -> dict[str, object]:
        return {"ready": self.ready, "blockers": list(self.blockers), "warnings": list(self.warnings)}


def installation_plan() -> list[dict[str, object]]:
    return [step.public() for step in INSTALL_ORDER]


def validate_prerequisites(snapshot: PrerequisiteSnapshot) -> PrerequisiteResult:
    blockers: list[str] = []
    warnings: list[str] = []

    if snapshot.windows_major < MIN_WINDOWS_MAJOR:
        blockers.append("Windows 10 or later is required for the supported Cyclone One + VMOS operator path.")

    if snapshot.cyclone_one_version is None:
        blockers.append(f"Install Cyclone One {CURRENT_ONE_VERSION}; it bundles the required PC runtime/agent.")
    elif snapshot.cyclone_one_version != CURRENT_ONE_VERSION:
        warnings.append(f"Validated baseline is Cyclone One {CURRENT_ONE_VERSION}; found {snapshot.cyclone_one_version}.")

    if not snapshot.adb_available:
        blockers.append("ADB is unavailable on the Windows runtime.")

    if not snapshot.mobile_apk:
        blockers.append(f"Cyclone Mobile {CURRENT_MOBILE_BASELINE} or newer APK is required.")
    elif Path(snapshot.mobile_apk).suffix.lower() != ".apk":
        blockers.append("Cyclone Mobile install artifact must be an .apk file.")

    if snapshot.vmos_android_major is None:
        blockers.append("VMOS Android version is unknown; select a VMOS instance before continuing.")
    elif snapshot.vmos_android_major not in SUPPORTED_ANDROID_MAJORS:
        if snapshot.vmos_android_major < MIN_ANDROID_MAJOR:
            blockers.append("Cyclone Mobile minSdk 33 requires Android 13 or newer.")
        else:
            warnings.append(f"Android {snapshot.vmos_android_major} is not in the validated VMOS 13/14/15 target set.")
    elif snapshot.vmos_android_major != PREFERRED_ANDROID_MAJOR:
        warnings.append(f"Android {PREFERRED_ANDROID_MAJOR} is the preferred VMOS target; Android {snapshot.vmos_android_major} is compatibility mode.")

    if not snapshot.vmos_adb_account_authorized:
        blockers.append("VMOS remote ADB permission is not authorized for this account; request/enable ADB access before setup.")
    if not snapshot.vmos_adb_session_open:
        blockers.append("Open VMOS Local Debugging → ADB and obtain a current connection command/key before installing Cyclone Mobile.")

    if snapshot.needs_edge_control_api:
        if not snapshot.vmos_image:
            blockers.append("VMOS Edge Android Control API use requires a known image version.")
        elif snapshot.vmos_android_major == 15 and snapshot.vmos_image.startswith("vcloud_android15_edge_"):
            if snapshot.vmos_image < EDGE_ANDROID15_CONTROL_API_MIN_IMAGE:
                blockers.append(
                    f"VMOS Edge Android 15 Control API requires image {EDGE_ANDROID15_CONTROL_API_MIN_IMAGE} or newer."
                )
        else:
            warnings.append("Edge Android Control API image floor was not evaluated for this VMOS image family.")

    return PrerequisiteResult(ready=not blockers, blockers=tuple(blockers), warnings=tuple(warnings))
