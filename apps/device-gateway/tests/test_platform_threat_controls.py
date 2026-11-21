"""Plan 76, stage 0: tests for controls the threat model relies on that had no test of their own."""
from __future__ import annotations

from pathlib import Path

from cyclone_device_gateway.config import Settings


def test_the_gateway_binds_to_loopback_by_default() -> None:
    """Threat 1: a remote attacker must not reach the API. The default bind is the loopback address."""
    settings = Settings(token="t", device_serial=None, adb_path="adb", runtime_dir=Path("."))
    assert settings.host == "127.0.0.1" and settings.bridge_host == "127.0.0.1"
