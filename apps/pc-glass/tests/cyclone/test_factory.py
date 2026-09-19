# Copyright 2026 Cyclone adaptations
# Licensed under the Apache License, Version 2.0

"""Factory must select CycloneGatewayDriver when Cyclone-connected."""

from __future__ import annotations

from types import SimpleNamespace

from artemis.drivers.cyclone.gateway_driver import CycloneGatewayDriver
from artemis.drivers.factory import create_driver, cyclone_connected


def test_cyclone_connected_truthy(monkeypatch):
    monkeypatch.delenv("CYCLONE_DEVICE_GATEWAY_URL", raising=False)
    monkeypatch.delenv("CYCLONE_SESSION_ID", raising=False)
    monkeypatch.delenv("ARTEMIS_CYCLONE_GATEWAY", raising=False)
    monkeypatch.setenv("CYCLONE_CONNECTED", "true")
    assert cyclone_connected() is True


def test_create_driver_picks_cyclone(monkeypatch):
    monkeypatch.setenv("CYCLONE_CONNECTED", "1")
    monkeypatch.setenv("CYCLONE_SESSION_ID", "default-foreground")
    ctx = SimpleNamespace(
        device=SimpleNamespace(device_id="dev", device_width=1080, device_height=2400),
        adb_client=None,
        ui_adb_client=None,
    )
    driver = create_driver(ctx)
    assert isinstance(driver, CycloneGatewayDriver)
