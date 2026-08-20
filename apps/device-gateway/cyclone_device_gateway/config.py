from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import os


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

    @classmethod
    def from_env(cls) -> "Settings":
        token = os.getenv("CYCLONE_DEVICE_GATEWAY_TOKEN", "").strip()
        if not token:
            raise RuntimeError("CYCLONE_DEVICE_GATEWAY_TOKEN is required")
        return cls(
            token=token,
            device_serial=os.getenv("CYCLONE_DEVICE_SERIAL") or None,
            adb_path=os.getenv("ADB_PATH", "adb"),
            runtime_dir=Path(os.getenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", ".runtime/device-gateway")),
            bridge_token=os.getenv("CYCLONE_ANDROID_BRIDGE_TOKEN", token),
        )
