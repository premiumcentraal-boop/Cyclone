from __future__ import annotations

from dataclasses import dataclass
import ipaddress
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

        # Desktop V1 pairing owns per-device Android credentials in memory. The historical
        # single-device bridge token remains optional for legacy /v1/device and Codex routes.
        bridge_token = os.getenv("CYCLONE_ANDROID_BRIDGE_TOKEN", "").strip()
        runtime_dir = Path(
            os.getenv("CYCLONE_DEVICE_GATEWAY_RUNTIME", ".runtime/device-gateway")
        ).expanduser().resolve()

        return cls(
            token=token,
            device_serial=os.getenv("CYCLONE_DEVICE_SERIAL") or None,
            adb_path=os.getenv("ADB_PATH", "adb"),
            runtime_dir=runtime_dir,
            bridge_token=bridge_token,
        )
