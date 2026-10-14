"""Roadmap run 14 (plan 33 §10–11): fleet health, quarantine, alerts, alert delivery and metrics."""
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError
from cyclone_device_gateway.command.health import facts_of
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode
from cyclone_device_gateway.desktop_runtime.v5_contract import validate_android_response

from test_command_center import Clock, FakeContract

TOKEN = "t" * 32
MINUTE = 60_000


class AlertContract(FakeContract):
    def __init__(self) -> None:
        super().__init__()
        self.alerts: list[tuple[str, dict[str, Any]]] = []
        self.lost: set[str] = set()
        self.alert_down = False

    def cc_status(self, device_id: str, mission_id: str) -> dict[str, Any]:
        if mission_id in self.lost:
            raise DesktopRuntimeError(RuntimeErrorCode.RUN_NOT_FOUND, "gone")
        return super().cc_status(device_id, mission_id)

    def cc_alert(self, device_id: str, **alert: Any) -> dict[str, Any]:
        if self.alert_down:
            raise DesktopRuntimeError(RuntimeErrorCode.DEVICE_DISCONNECTED, "down")
        self.alerts.append((device_id, alert))
        return {"shown": True}


def phone(device_id: str, **health: Any) -> dict[str, Any]:
    return {"deviceId": device_id, "name": f"Phone {device_id[-1].upper()}", "paired": True, "state": "READY",
            "mobileVersion": "5.0.0-alpha.130.dev1",
            "health": {"batteryPercent": 80, "charging": True, "network": "wifi", "freeStorageMb": 20_000,
                       "temperatureC": 31.5, "thermal": "none", "permissions": {"notifications": True}, **health}}


@pytest.fixture()
def setup(tmp_path: Path):
    contract = AlertContract()
    clock = Clock()
    devices = [phone("phone-a"), phone("phone-b"), {"deviceId": "phone-c", "paired": False, "state": "UNPAIRED"}]
    center = CommandCenter(tmp_path / "cc.db", contract, lambda: devices, clock=clock,
                           local_now=lambda: datetime.fromtimestamp(clock.ms / 1000, tz=timezone.utc),
                           health={"target_version": lambda: "5.0.0-alpha.130.dev1"})
    yield center, contract, clock, devices
    center.stop()


def alerts(center: CommandCenter, kind: str | None = None) -> list[dict[str, Any]]:
    return [a for a in center.health.public()["alerts"] if kind is None or a["kind"] == kind]


def lose_run(center: CommandCenter, contract: AlertContract, device: str) -> str:
    task = center.create_task({"title": "Check", "goal": "Check the inbox", "deviceId": device})
    center.tick()
    mission = center.get_task(task["id"])["run"]["missionId"]
    contract.lost.add(mission)
    center.tick()
    assert center.get_task(task["id"])["status"] == "failed"
    return task["id"]


def test_paired_phones_are_remembered_with_bounded_facts(setup):
    center, _, _, devices = setup
    center.tick()
    phones = {p["deviceId"]: p for p in center.health.public()["phones"]}
    assert set(phones) == {"phone-a", "phone-b"}  # a phone never paired is not part of the fleet
    facts = phones["phone-a"]["facts"]
    assert facts["batteryPercent"] == 80 and facts["temperatureC"] == 31.5 and facts["thermal"] == "none"
    assert facts["notifications"] is True and phones["phone-a"]["online"] and not phones["phone-a"]["behind"]
    junk = facts_of({"health": {"batteryPercent": 400, "temperatureC": "hot", "thermal": "lava", "network": "x" * 99,
                                "freeStorageMb": True}, "mobileVersion": 5})
    assert junk["batteryPercent"] is None and junk["temperatureC"] is None and junk["thermal"] is None
    assert junk["network"] is None and junk["freeStorageMb"] is None and junk["mobileVersion"] is None


def test_a_phone_offline_for_ten_minutes_raises_one_alert_and_it_resolves_itself(setup):
    center, _, clock, devices = setup
    center.tick()
    devices[1] = {**devices[1], "state": "DISCONNECTED"}
    center.tick()
    clock.ms += 9 * MINUTE
    center.tick()
    assert alerts(center, "offline") == []
    clock.ms += 2 * MINUTE
    center.tick()
    center.tick()
    offline = alerts(center, "offline")
    assert len(offline) == 1 and offline[0]["level"] == "critical" and offline[0]["deviceId"] == "phone-b"
    assert "11 minutes" in offline[0]["detail"]
    # A phone that drops out of the list entirely (unplugged) counts the same way.
    gone = devices.pop(0)
    center.tick()
    clock.ms += 11 * MINUTE
    center.tick()
    assert {a["deviceId"] for a in alerts(center, "offline")} == {"phone-a", "phone-b"}
    devices.insert(0, gone)
    devices[1] = phone("phone-b")
    center.tick()
    assert alerts(center, "offline") == []
    assert {a["kind"] for a in center.health.public()["resolved"]} == {"offline"}
    assert center.verify_audit()


def test_three_infrastructure_failures_in_a_row_quarantine_a_phone_until_released(setup):
    center, contract, clock, _ = setup
    for _ in range(2):
        lose_run(center, contract, "phone-a")
    assert not center.health.public()["phones"][0]["quarantined"]
    lose_run(center, contract, "phone-a")
    a = next(p for p in center.health.public()["phones"] if p["deviceId"] == "phone-a")
    assert a["quarantined"] and a["infraStreak"] == 3 and "no longer knows this mission" in a["quarantineReason"]
    assert [x["deviceId"] for x in alerts(center, "quarantined")] == ["phone-a"]
    assert center.overview()["quarantined"] == 1
    # No new task goes to it: one for any phone goes to phone-b, one for phone-a waits and says why.
    started = len(contract.started)
    anyone = center.create_task({"title": "Any", "goal": "Open the camera"})
    pinned = center.create_task({"title": "Pinned", "goal": "Open the camera", "deviceId": "phone-a"})
    center.tick()
    assert [d for d, _ in contract.started[started:]] == ["phone-b"]
    assert center.get_task(anyone["id"])["status"] == "running"
    waiting = center.get_task(pinned["id"])
    assert waiting["status"] == "waiting_device" and "quarantine" in waiting["cause"]
    released = center.health.release("phone-a")
    assert not released["quarantined"] and released["infraStreak"] == 0
    assert alerts(center, "quarantined") == []
    clock.ms += 31_000
    center.tick()
    assert center.get_task(pinned["id"])["status"] == "running"
    with pytest.raises(CommandError):
        center.health.release("phone-a")
    assert center.verify_audit()


def test_a_task_failure_breaks_the_row(setup):
    center, contract, _, _ = setup
    lose_run(center, contract, "phone-a")
    lose_run(center, contract, "phone-a")
    task = center.create_task({"title": "Post", "goal": "Post the video", "deviceId": "phone-a"})
    center.tick()
    mission = center.get_task(task["id"])["run"]["missionId"]
    contract.status[mission].update(status="gave_up", live=False)
    center.tick()
    lose_run(center, contract, "phone-a")
    a = next(p for p in center.health.public()["phones"] if p["deviceId"] == "phone-a")
    assert a["infraStreak"] == 1 and not a["quarantined"]


def test_a_task_refusal_counts_only_when_it_is_the_phone_s_fault(setup):
    center, contract, _, _ = setup
    contract.refuse["phone-a"] = "CAPABILITY_UNAVAILABLE"
    for _ in range(3):
        center.create_task({"title": "x", "goal": "Open the app", "deviceId": "phone-a"})
        center.tick()
    assert next(p for p in center.health.public()["phones"] if p["deviceId"] == "phone-a")["infraStreak"] == 0
    contract.refuse["phone-a"] = "PROTOCOL_MISMATCH"
    for _ in range(3):
        center.create_task({"title": "x", "goal": "Open the app", "deviceId": "phone-a"})
        center.tick()
    assert next(p for p in center.health.public()["phones"] if p["deviceId"] == "phone-a")["quarantined"]


def test_hot_low_and_full_phones_and_phones_behind_raise_their_alerts(setup):
    center, _, _, devices = setup
    devices[0] = phone("phone-a", thermal="severe", batteryPercent=9, charging=False, freeStorageMb=300)
    devices[1] = {**phone("phone-b"), "mobileVersion": "5.0.0-alpha.120.dev1"}
    center.tick()
    kinds = {(a["kind"], a["deviceId"], a["level"]) for a in alerts(center)}
    assert kinds == {("hot", "phone-a", "warning"), ("battery", "phone-a", "warning"), ("storage", "phone-a", "warning"),
                     ("behind", "phone-b", "info")}
    assert next(p for p in center.health.public()["phones"] if p["deviceId"] == "phone-b")["behind"]
    # Info alerts do not count as needing the owner.
    assert center.overview()["alerts"] == 3
    devices[0] = phone("phone-a", charging=False, batteryPercent=9)
    center.tick()
    assert {a["kind"] for a in alerts(center) if a["deviceId"] == "phone-a"} == {"battery"}


def test_a_routine_under_80_percent_over_a_week_raises_a_warning(setup):
    center, contract, clock, _ = setup
    routine = center.create_routine({"title": "Daily post", "goal": "Post the video", "schedule": {"kind": "every", "minutes": 60}})
    outcomes = ["completed", "gave_up", "completed", "gave_up", "gave_up"]
    for status in outcomes:
        clock.ms += 31_000
        task = center.create_task({"title": "Daily post", "goal": "Post the video"})
        center._db.execute("UPDATE task SET routine_id = ? WHERE id = ?", (routine["id"], task["id"]))
        center.tick()
        mission = center.get_task(task["id"])["run"]["missionId"]
        contract.status[mission].update(status=status, live=False)
        center.tick()
    clock.ms += 31_000
    center.tick()
    low = alerts(center, "success_low")
    assert len(low) == 1 and "2 of 5" in low[0]["detail"] and low[0]["title"].startswith("Daily post")
    metrics = center.health.public()["metrics"]
    assert metrics["byRecipe"][0]["runs"] == 5 and metrics["byRecipe"][0]["succeeded"] == 2
    assert metrics["pickUpMedianMs"] is not None


def test_alerts_go_to_the_owner_s_phone_rate_limited_and_never_to_an_offline_phone(setup):
    center, contract, clock, devices = setup
    center.tick()
    with pytest.raises(CommandError):
        center.health.test_notify()
    center.health.set_settings({"notifyDevice": "phone-a"})
    assert center.health.test_notify()["sent"]
    contract.alerts.clear()
    devices[1] = phone("phone-b", thermal="critical")
    center.tick()
    assert [(d, a["title"]) for d, a in contract.alerts] == [("phone-a", "Phone B is hot")]
    center.tick()
    assert len(contract.alerts) == 1  # sent once
    # The owner's phone goes offline: alerts wait for it (the one about it included) and are not lost.
    devices[0] = {**devices[0], "state": "DISCONNECTED"}
    center.tick()
    clock.ms += 11 * MINUTE
    center.tick()
    assert len(contract.alerts) == 1
    devices[0] = phone("phone-a")
    center.tick()
    assert len(contract.alerts) == 1  # its offline alert resolved before it could be told; nothing stale is sent
    # Delivery failures are retried, then given up after an hour.
    contract.alert_down = True
    devices[1] = phone("phone-b", batteryPercent=5, charging=False, thermal="critical")
    center.tick()
    assert next(a for a in alerts(center, "battery"))["notified"] == "pending"
    clock.ms += 61 * MINUTE
    center.tick()
    assert next(a for a in alerts(center, "battery"))["notified"] == "gave_up"


def test_secret_shaped_or_unknown_settings_are_refused(setup):
    center, *_ = setup
    center.tick()
    with pytest.raises(CommandError):
        center.health.set_settings({"notifyDevice": "phone-z"})
    with pytest.raises(CommandError):
        center.health.set_settings({"smtpPassword": "x"})
    with pytest.raises(CommandError):
        center.health.seen("alr_nope")


def test_forgetting_a_phone_resolves_its_alerts(setup):
    center, _, clock, devices = setup
    center.tick()
    center.health.set_settings({"notifyDevice": "phone-b"})
    devices.pop(1)
    center.tick()
    clock.ms += 11 * MINUTE
    center.tick()
    assert alerts(center, "offline")
    center.health.forget("phone-b")
    assert alerts(center) == [] and center.health.settings()["notifyDevice"] is None
    assert [p["deviceId"] for p in center.health.public()["phones"]] == ["phone-a"]


def test_routes(setup):
    center, contract, _, _ = setup
    app = FastAPI()
    app.include_router(create_command_router(type("R", (), {"command": center})(), TOKEN))
    client = TestClient(app)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    assert client.get("/v1/cc/health").status_code == 401
    center.tick()
    body = client.get("/v1/cc/health", headers=headers).json()
    assert {p["deviceId"] for p in body["phones"]} == {"phone-a", "phone-b"} and body["rules"]["quarantineAfter"] == 3
    assert client.post("/v1/cc/health/phones/phone-a/quarantine", headers=headers).json()["quarantined"]
    assert client.post("/v1/cc/health/phones/phone-a/release", headers=headers).json()["quarantined"] is False
    assert client.post("/v1/cc/health/phones/phone-a/release", headers=headers).status_code == 400
    alert = client.get("/v1/cc/health", headers=headers).json()["resolved"][0]
    assert client.post(f"/v1/cc/health/alerts/{alert['id']}/seen", headers=headers).json()["seen"]
    assert client.put("/v1/cc/health/settings", headers=headers, json={"notifyDevice": "phone-b"}).json()["notifyDevice"] == "phone-b"
    assert client.post("/v1/cc/health/test-alert", headers=headers).json() == {"sent": True, "deviceId": "phone-b"}
    assert client.post("/v1/cc/health/phones/phone-b/forget", headers=headers).json() == {"forgotten": "phone-b"}


def test_the_phone_s_alert_answer_is_checked():
    assert validate_android_response("cc.alert", {"shown": True}, {})
    with pytest.raises(DesktopRuntimeError):
        validate_android_response("cc.alert", {"shown": True, "extra": 1}, {})
    with pytest.raises(DesktopRuntimeError):
        validate_android_response("cc.alert", {"shown": "yes"}, {})


def test_a_connection_s_daily_cap_raises_a_warning_for_that_day_only(setup):
    center, _, clock, _ = setup
    from cyclone_device_gateway.command.connections import _today
    db = center._db
    now = clock.ms
    db.execute("INSERT INTO connection(id, name, url, auth, sign_in, tools, allowed, daily_cap, approval, status, detail, created_at, updated_at)"
               " VALUES ('con_higgsfield1', 'Higgsfield', 'https://mcp.example/mcp', '{}', NULL, '[]', '[]', 3, 'cap', 'ready', '', ?, ?)", (now, now))
    db.execute("INSERT INTO tool_call(id, connection_id, tool, task_id, actor, arguments, poll_tool, state, day, approval_id, summary, artifacts,"
               " created_at, started_at, finished_at, step) VALUES ('cal_x1', 'con_higgsfield1', 'generate', NULL, 'engine', '{}', NULL, 'refused', ?,"
               " NULL, 'The daily cap of 3 call(s) is reached.', '[]', ?, NULL, ?, 0)", (_today(now), now, now))
    center.tick()
    capped = alerts(center, "cap_reached")
    assert len(capped) == 1 and capped[0]["title"] == "Higgsfield reached its daily cap" and capped[0]["level"] == "warning"
    clock.ms += 24 * 60 * MINUTE
    center.tick()
    assert alerts(center, "cap_reached") == []
