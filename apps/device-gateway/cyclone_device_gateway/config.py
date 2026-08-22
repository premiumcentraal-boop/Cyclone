from __future__ import annotations

from dataclasses import dataclass
import ipaddress
from pathlib import Path
import os
import shutil


def resolve_adb_path() -> str:
    """Resolve adb without requiring the desktop app to inherit a freshly edited PATH."""
    configured = os.getenv("ADB_PATH", "").strip()
    if configured:
        return configured

    discovered = shutil.which("adb")
    if discovered:
        return discovered

    candidates: list[Path] = []
    for env_name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        root = os.getenv(env_name, "").strip()
        if root:
            candidates.append(Path(root) / "platform-tools" / "adb.exe")

    local_app_data = os.getenv("LOCALAPPDATA", "").strip()
    if local_app_data:
        local = Path(local_app_data)
        candidates.extend(
            [
                local / "Android" / "Sdk" / "platform-tools" / "adb.exe",
                # This is also where Cyclone's earlier standalone Platform Tools setup installs it.
                local / "Android" / "platform-tools" / "adb.exe",
            ]
        )

    user_profile = os.getenv("USERPROFILE", "").strip()
    if user_profile:
        candidates.append(Path(user_profile) / "AppData" / "Local" / "Android" / "Sdk" / "platform-tools" / "adb.exe")

    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)

    # Keep the conventional command name as the final fallback so diagnostics can return the
    # existing friendly "ADB executable was not found" error instead of failing configuration.
    return "adb"


@dataclass(frozen=True)
class Settings:
    token: str
    device_serial: str | None
    adb_path: str
    runtime_dir: Path
    host: str = "127.0.0.1"
    port: int = 8765
    bridge_host: str = "127.0.0.1"
    bridge_port: int = 8766
    bridge_token: str = ""

    def __post_init__(self) -> None:
        for field_name, value in (("host", self.host), ("bridge_host", self.bridge_host)):
            if value == "localhost":
                continue
            try:
                is_loopback = ipaddress.ip_address(value).is_loopback
            except ValueError as exc:
                raise ValueError(f"{field_name} must be a loopback address") from exc
            if not is_loopback:
                raise ValueError(f"{field_name} must be a loopback address")

    @classmethod
    def from_env(cls) -> "Settings":
        token = os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip()
        if not token:
            raise RuntimeError("CYCLONE_DEVICE_GATEWAY_TOKEN is required")

        bridge_token = os.getenv("CYCLONE_ANDROID_BRIDGE_TOKEN", "").strip()
        pairing_bootstrap = os.getenv("CYCLONE_DESKTOP_PAIRING_BOOTSTRAP", "").strip().lower() in {
            "1",
            "true",
            "yes",
        }
        # Legacy single-device routes still require an independent Android bridge token.
        # The packaged Desktop V1 Companion explicitly opts into the zero-authority USB
        # pairing bootstrap; per-device credentials are then exchanged and kept in memory.
        if not bridge_token and not pairing_bootstrap:
            raise RuntimeError("CYCLONE_ANDROID_BRIDGE_TOKEN is required")

        runtime_dir = Path(
            os.getenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", ".runtime/device-gateway")
        ).expanduser().resolve()

        return cls(
            token=token,
            device_serial=os.getenv("CYCLONE_DEVICE_SERIAL") or None,
            adb_path=resolve_adb_path(),
            runtime_dir=runtime_dir,
            bridge_token=bridge_token,
        )
