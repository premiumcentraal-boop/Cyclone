# Copyright 2026 Cyclone adaptations
# Licensed under the Apache License, Version 2.0

"""Unit tests for CycloneGatewayDriver Mode A contracts (no live gateway)."""

from __future__ import annotations

import asyncio

import pytest

from artemis.drivers.cyclone.gateway_driver import (
    ALLOWED_PHONE_ACT,
    CycloneGatewayDriver,
    CycloneGatewayError,
    FORBIDDEN_PHONE_ACT,
)


def test_allowlist_excludes_swipe_and_launch_intent():
    assert "phone.swipe" in FORBIDDEN_PHONE_ACT
    assert "phone.click" in ALLOWED_PHONE_ACT
    assert "phone.open_app" in ALLOWED_PHONE_ACT
    assert "launch_intent" not in ALLOWED_PHONE_ACT


def test_missing_session_id_refused(monkeypatch):
    monkeypatch.delenv("CYCLONE_SESSION_ID", raising=False)
    with pytest.raises(CycloneGatewayError, match="CYCLONE_SESSION_ID"):
        CycloneGatewayDriver(session_id="")


def test_swipe_forbidden():
    driver = CycloneGatewayDriver(session_id="default-foreground", token="test")

    async def _run():
        with pytest.raises(CycloneGatewayError, match="swipe"):
            await driver.swipe(0, 0, 10, 10)

    asyncio.run(_run())


def test_execute_shell_forbidden():
    driver = CycloneGatewayDriver(session_id="default-foreground", token="test")

    async def _run():
        with pytest.raises(CycloneGatewayError, match="execute_shell"):
            await driver.execute_shell("id")

    asyncio.run(_run())


def test_act_posts_capability_and_clears_observation():
    driver = CycloneGatewayDriver(session_id="default-foreground", token="tok")

    def fake_request(method, path, payload=None):
        if "observe" in path:
            return {
                "witness": {"observation_id": "obs-1"},
                "page": {"package": "com.android.settings"},
            }
        return {"ok": True}

    driver._request = fake_request  # type: ignore[method-assign]

    async def _run():
        await driver.tap(100, 200)

    asyncio.run(_run())
    assert driver._last_observation_id is None
    assert driver.action_history[-1]["capability_id"] == "phone.click"
