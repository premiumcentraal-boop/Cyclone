from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, Final


CURRENT_ONE_VERSION: Final = "1.1.2"
CURRENT_MOBILE_BASELINE: Final = "4.3.6"
MIN_WINDOWS_MAJOR: Final = 10
MIN_ANDROID_MAJOR: Final = 13
PREFERRED_ANDROID_MAJOR: Final = 15
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
    InstallStep(1, "VMOS", "Create or select one Android cloud phone; prefer Android 15 and require Android 13+.", "Cyclone needs a reachable Android host before transport/bootstrap can be verified."),
    InstallStep(2, "Cyclone One", f"Install Cyclone One {CURRENT_ONE_VERSION} for the current Windows user.", "CyclonePCRuntime/PC Agent is bundled with One; do not install a second legacy PC Companion."),
    InstallStep(3, "ADB", "Verify the bundled/system adb client is callable from the PC runtime.", "VMOS bootstrap uses remote ADB, while agent mutations remain inside Cyclone Mobile."),
    InstallStep(4, "Cyclone Mobile", f"Obtain Cyclone Mobile {CURRENT_MOBILE_BASELINE} or newer APK from the official GitHub release.", "The VMOS phone must run com.cyclone.mobile; VMOS-native touch is not the Cyclone executor."),
    InstallStep(5, "VMOS + Mobile", "Enable temporary VMOS remote ADB, install the APK, then launch com.cyclone.mobile/.MainActivity.", "This prepares the phone for the separate pairing/trust stage."),
)


@dataclass(frozen=True)
class PrerequisiteSnapshot:
    windows_major: int
    cyclone_one_version: str | None
    adb_available: bool
    mobile_apk: str | None
    vmos_android_major: int | None
    vmos_image: str | None = None


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
        blockers.append("Cyclone One requires Windows 10 or later for this VMOS setup path.")
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
    elif snapshot.vmos_android_major < MIN_ANDROID_MAJOR:
        blockers.append(f"VMOS Android {MIN_ANDROID_MAJOR}+ is required; Android {PREFERRED_ANDROID_MAJOR} is preferred.")
    elif snapshot.vmos_android_major != PREFERRED_ANDROID_MAJOR:
        warnings.append(f"Android {PREFERRED_ANDROID_MAJOR} is the preferred VMOS target; Android {snapshot.vmos_android_major} is compatibility mode.")

    if snapshot.vmos_image and snapshot.vmos_android_major == 15 and snapshot.vmos_image.startswith("vcloud_android15_edge_"):
        if snapshot.vmos_image < EDGE_ANDROID15_CONTROL_API_MIN_IMAGE:
            blockers.append(f"VMOS Edge Android 15 image must be {EDGE_ANDROID15_CONTROL_API_MIN_IMAGE} or newer for Android Control API compatibility.")

    return PrerequisiteResult(ready=not blockers, blockers=tuple(blockers), warnings=tuple(warnings))
