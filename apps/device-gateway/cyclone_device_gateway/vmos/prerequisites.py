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
VMOS_REMOTE_ADB_DEFAULT_HOURS: Final = 24
VMOS_REMOTE_ADB_API_MIN_DAYS: Final = 1
VMOS_REMOTE_ADB_API_MAX_DAYS: Final = 7
VMOS_EDGE_CONTROL_API_MIN_CLIENT: Final = "2.0.4"
VMOS_EDGE_CONTROL_API_MIN_CBS: Final = "1.1.1.10.7"
VMOS_EDGE_ANDROID15_REFERENCE_IMAGE: Final = "vcloud_android15_edge_20251227201917"


def _version_tuple(value: str) -> tuple[int, ...]:
    try:
        return tuple(int(part) for part in value.split("."))
    except ValueError:
        return ()


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
        "VMOS Cloud",
        "Create/select one hosted VMOS Cloud phone on Android 15. Android 13/14 remain supported compatibility targets; do not choose Android 10.",
        "Cyclone Mobile minSdk is 33 (Android 13). Hosted VMOS Cloud is the preferred Cyclone path; VMOS Edge remains optional infrastructure.",
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
        "Ensure VMOS ADB permission is authorized for the account, open VMOS Local Debugging → ADB, then run VMOS's generated SSH connection command/key and ADB connect command.",
        f"The UI/manual VMOS path defaults to a {VMOS_REMOTE_ADB_DEFAULT_HOURS}-hour connection. VMOS OpenAPI can request 1–7 day ADB validity; this transport is bootstrap-only.",
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
        "Using the already-authorized VMOS ADB serial: adb -s <serial> install -r <Cyclone.apk>; then adb -s <serial> shell am start -W -n com.cyclone.mobile/.MainActivity.",
        "Verify package presence and a live com.cyclone.mobile PID. Pairing/trust is intentionally the next checklist item.",
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
    vmos_edge_client_version: str | None = None
    vmos_edge_cbs_version: str | None = None


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
            warnings.append(f"Android {snapshot.vmos_android_major} is newer than the validated VMOS 13/14/15 target set.")
    elif snapshot.vmos_android_major != PREFERRED_ANDROID_MAJOR:
        warnings.append(f"Android {PREFERRED_ANDROID_MAJOR} is the preferred VMOS target; Android {snapshot.vmos_android_major} is compatibility mode.")

    if not snapshot.vmos_adb_account_authorized:
        blockers.append("VMOS remote ADB permission is not authorized for this account; request/enable ADB access before setup.")
    if not snapshot.vmos_adb_session_open:
        blockers.append("Open VMOS Local Debugging → ADB and establish VMOS's generated SSH/ADB connection before installing Cyclone Mobile.")

    if snapshot.needs_edge_control_api:
        client = _version_tuple(snapshot.vmos_edge_client_version or "")
        cbs = _version_tuple(snapshot.vmos_edge_cbs_version or "")
        if not client:
            blockers.append(f"VMOS Edge Android Control API requires Edge client {VMOS_EDGE_CONTROL_API_MIN_CLIENT} or newer.")
        elif client < _version_tuple(VMOS_EDGE_CONTROL_API_MIN_CLIENT):
            blockers.append(f"VMOS Edge Android Control API requires Edge client {VMOS_EDGE_CONTROL_API_MIN_CLIENT} or newer.")
        if not cbs:
            blockers.append(f"VMOS Edge Android Control API baseline requires CBS {VMOS_EDGE_CONTROL_API_MIN_CBS} or newer.")
        elif cbs < _version_tuple(VMOS_EDGE_CONTROL_API_MIN_CBS):
            blockers.append(f"VMOS Edge Android Control API baseline requires CBS {VMOS_EDGE_CONTROL_API_MIN_CBS} or newer.")
        if snapshot.vmos_android_major == 15:
            if not snapshot.vmos_image:
                warnings.append(
                    f"For VMOS Edge Android 15, the current documented 2.0 reference image is {VMOS_EDGE_ANDROID15_REFERENCE_IMAGE}; image identity was not supplied."
                )
            elif snapshot.vmos_image != VMOS_EDGE_ANDROID15_REFERENCE_IMAGE:
                warnings.append(
                    f"VMOS Edge Android 15 image differs from the documented 2.0 reference image {VMOS_EDGE_ANDROID15_REFERENCE_IMAGE}; verify it is an official matching image."
                )

    return PrerequisiteResult(ready=not blockers, blockers=tuple(blockers), warnings=tuple(warnings))
