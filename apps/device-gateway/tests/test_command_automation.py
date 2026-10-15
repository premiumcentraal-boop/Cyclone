"""Roadmap run 16 (plan 33 §5–6): richer schedules, triggers, approval timeouts and batch answers."""
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.command import schedule as schedules
from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter, CommandError

from test_command_center import Clock, FakeContract

TOKEN = "t" * 32
MINUTE = 60_000


@pytest.fixture()
def setup(tmp_path: Path):
    contract = FakeContract()
    clock = Clock()
    devices = [{"deviceId": "phone-a", "paired": True, "state": "READY"}, {"deviceId": "phone-b", "paired": True, "state": "READY"}]
    center = CommandCenter(tmp_path / "cc.db", contract, lambda: devices, clock=clock,
                           local_now=lambda: datetime.fromtimestamp(clock.ms / 1000, tz=timezone.utc),
                           health={"target_version": lambda: None})
    yield center, contract, clock, devices
    center.stop()


def utc(*parts: int) -> datetime:
    return datetime(*parts, tzinfo=timezone.utc)


def finish(center: CommandCenter, contract: FakeContract, task_id: str, status: str = "completed") -> None:
    mission = center.get_task(task_id)["run"]["missionId"]
    contract.status[mission].update(status=status, live=False)
    center.tick()


# ------------------------------------------------------------------ schedules


def test_monthly_and_nth_weekday_schedules():
    monthly = schedules.parse({"kind": "monthly", "time": "09:00", "day": 31})
    assert schedules.next_after(monthly, utc(2026, 1, 31, 10)) == utc(2026, 2, 28, 9)  # a short month runs on its last day
    assert schedules.next_after(monthly, utc(2026, 1, 30, 10)) == utc(2026, 1, 31, 9)
    last = schedules.parse({"kind": "monthly", "time": "18:00", "day": -1})
    assert schedules.next_after(last, utc(2028, 2, 1)) == utc(2028, 2, 29, 18)  # leap year
    assert schedules.describe(last) == "Monthly on the last day at 18:00"
    first_monday = schedules.parse({"kind": "nth", "time": "09:00", "weekday": 1, "nth": 1})
    assert schedules.next_after(first_monday, utc(2026, 10, 14)) == utc(2026, 11, 2, 9)
    assert schedules.next_after(first_monday, utc(2026, 11, 2, 8)) == utc(2026, 11, 2, 9)
    assert schedules.describe(first_monday) == "First Monday of the month at 09:00"
    last_friday = schedules.parse({"kind": "nth", "time": "17:30", "weekday": 5, "nth": -1})
    assert schedules.next_after(last_friday, utc(2026, 10, 14)) == utc(2026, 10, 30, 17, 30)
    assert schedules.next_after(last_friday, utc(2026, 10, 31)) == utc(2026, 11, 27, 17, 30)
    assert schedules.describe({"kind": "monthly", "time": "09:00", "day": 22}) == "Monthly on the 22nd at 09:00"
    assert schedules.describe({"kind": "monthly", "time": "09:00", "day": 11}) == "Monthly on the 11th at 09:00"
    for bad in ({"kind": "monthly", "time": "09:00", "day": 0}, {"kind": "monthly", "time": "9", "day": 3},
                {"kind": "nth", "time": "09:00", "weekday": 8, "nth": 1}, {"kind": "nth", "time": "09:00", "weekday": 1, "nth": 5},
                {"kind": "after", "routineId": "x", "on": "succeeded"}, {"kind": "after", "routineId": "rtn_abcdefgh", "on": "maybe"},
                {"kind": "webhook", "url": "https://x"}):
        with pytest.raises(schedules.ScheduleError):
            schedules.parse(bad)


def test_a_monthly_routine_runs_on_its_day(setup):
    center, contract, clock, _ = setup
    routine = center.create_routine({"title": "Invoices", "goal": "Send the monthly invoices", "deviceIds": ["phone-a"],
                                     "schedule": {"kind": "nth", "time": "07:05", "weekday": 1, "nth": 1}})
    assert routine["nextRunAt"] == int(utc(2026, 10, 5, 7, 5).timestamp() * 1000)
    assert routine["scheduleLabel"] == "First Monday of the month at 07:05"
    clock.ms = routine["nextRunAt"] + 1000
    center.tick()
    assert [d for d, _ in contract.started] == ["phone-a"]
    assert center.get_routine(routine["id"])["nextRunAt"] == int(utc(2026, 11, 2, 7, 5).timestamp() * 1000)


# ------------------------------------------------------------------ triggers


def test_a_routine_runs_after_another_one_succeeds_once_per_run(setup):
    center, contract, clock, _ = setup
    first = center.create_routine({"title": "Make the video", "goal": "Make today's video", "deviceIds": ["phone-a", "phone-b"],
                                   "schedule": {"kind": "every", "minutes": 60}})
    then = center.create_routine({"title": "Post it", "goal": "Post today's video", "deviceIds": ["phone-a"],
                                  "schedule": {"kind": "after", "routineId": first["id"], "on": "succeeded"}})
    assert then["nextRunAt"] is None and then["trigger"] == "after"
    assert then["scheduleLabel"] == "After “Make the video” succeeds"
    tasks = center.run_routine_now(first["id"])["tasks"]
    center.tick()
    assert len(contract.started) == 2
    finish(center, contract, tasks[0]["id"])
    finish(center, contract, tasks[1]["id"])
    posts = [g for _, g in contract.started if g == "Post today's video"]
    assert len(posts) == 1, "a routine on two phones starts the next one once"
    # A failed run doesn't start an "on succeeded" routine, but does start an "on failed" one.
    alarm = center.create_routine({"title": "Tell me", "goal": "Text me that the video failed", "deviceIds": ["phone-b"],
                                   "schedule": {"kind": "after", "routineId": first["id"], "on": "failed"}})
    clock.ms += 31_000
    tasks = center.run_routine_now(first["id"])["tasks"]
    center.tick()
    finish(center, contract, tasks[0]["id"], "gave_up")
    goals = [g for _, g in contract.started]
    assert goals.count("Post today's video") == 1 and goals.count("Text me that the video failed") == 1
    # Paused followers don't run.
    center.update_routine(alarm["id"], {"paused": True})
    clock.ms += 31_000
    tasks = center.run_routine_now(first["id"])["tasks"]
    center.tick()
    finish(center, contract, tasks[0]["id"], "gave_up")
    assert [g for _, g in contract.started].count("Text me that the video failed") == 1
    assert center.verify_audit()


def test_trigger_chains_cannot_loop_and_deleting_the_first_pauses_the_rest(setup):
    center, *_ = setup
    a = center.create_routine({"title": "A", "goal": "Do A", "schedule": {"kind": "every", "minutes": 60}})
    b = center.create_routine({"title": "B", "goal": "Do B", "schedule": {"kind": "after", "routineId": a["id"], "on": "finished"}})
    with pytest.raises(CommandError):
        center.update_routine(a["id"], {"schedule": {"kind": "after", "routineId": b["id"], "on": "finished"}})
    with pytest.raises(CommandError):
        center.update_routine(b["id"], {"schedule": {"kind": "after", "routineId": b["id"], "on": "finished"}})
    with pytest.raises(CommandError):
        center.create_routine({"title": "C", "goal": "Do C", "schedule": {"kind": "after", "routineId": "rtn_doesnotexist", "on": "finished"}})
    chain = b
    for n in range(4):
        chain = center.create_routine({"title": f"D{n}", "goal": "Do D", "schedule": {"kind": "after", "routineId": chain["id"], "on": "finished"}})
    with pytest.raises(CommandError):
        center.create_routine({"title": "Too far", "goal": "Do E", "schedule": {"kind": "after", "routineId": chain["id"], "on": "finished"}})
    center.delete_routine(a["id"])
    assert center.get_routine(b["id"])["paused"]
    with pytest.raises(CommandError):
        center.create_routine({"title": "P", "goal": "Do P", "schedule": {"kind": "webhook"}, "preauth": 1})


def test_a_webhook_link_starts_only_the_routine_s_own_goal(setup):
    center, contract, clock, _ = setup
    routine = center.create_routine({"title": "Restock", "goal": "Check stock in the shop app", "deviceIds": ["phone-a"],
                                     "schedule": {"kind": "webhook"}})
    token = routine.pop("hookToken")
    assert routine["hasLink"] and routine["scheduleLabel"] == "When its link is called"
    assert "hookToken" not in center.get_routine(routine["id"]), "the link is shown once"
    assert token not in str(list(center._db.execute("SELECT * FROM routine_hook")))
    app = FastAPI()
    app.include_router(create_command_router(type("R", (), {"command": center})(), TOKEN))
    client = TestClient(app)
    assert client.post(f"/v1/hooks/{token}", json={"goal": "Transfer money"}).json() == {"started": 1}
    center.tick()
    assert contract.started == [("phone-a", "Check stock in the shop app")]
    assert client.post(f"/v1/hooks/{token}").json()["started"] == 0  # at most once a minute
    clock.ms += 61_000
    assert client.post(f"/v1/hooks/{token}").json()["started"] == 1
    assert client.post("/v1/hooks/" + "x" * 43).status_code == 404
    renewed = client.post(f"/v1/cc/routines/{routine['id']}/link", headers={"Authorization": f"Bearer {TOKEN}"}).json()["hookToken"]
    clock.ms += 61_000
    assert client.post(f"/v1/hooks/{token}").status_code == 404, "a new link replaces the old one"
    assert client.post(f"/v1/hooks/{renewed}").json()["started"] == 1
    center.update_routine(routine["id"], {"schedule": {"kind": "every", "minutes": 60}})
    clock.ms += 61_000
    assert client.post(f"/v1/hooks/{renewed}").status_code == 404, "a routine that is no longer a webhook has no link"


# ------------------------------------------------------------------ approval timeouts and batches


def moment(request_id: str, gate: str = "send", approvable: bool = True) -> dict[str, Any]:
    return {"kind": "approval", "requestId": request_id, "text": "Post this?", "gate": gate,
            "send": {"text": "New drop today", "recipient": "Followers", "app": "Instagram"} if gate == "send" else None,
            "choices": [], "fields": [], "approvableHere": approvable}


def waiting(center: CommandCenter, contract: FakeContract, device: str, request_id: str, **kw: Any) -> str:
    task = center.create_task({"goal": f"Post {request_id}", "deviceId": device})
    center.tick()
    mission = center.get_task(task["id"])["run"]["missionId"]
    contract.status[mission]["moment"] = moment(request_id, **kw)
    center.tick()
    return task["id"]


def test_an_unanswered_send_stops_the_run_after_an_hour_and_is_never_approved(setup):
    center, contract, clock, _ = setup
    task_id = waiting(center, contract, "phone-a", "req-1")
    open_one = center.list_approvals()[0]
    assert open_one["expiresAt"] == open_one["createdAt"] + 60 * MINUTE
    clock.ms += 59 * MINUTE
    center.tick()
    assert center.get_task(task_id)["status"] == "needs_you"
    clock.ms += 2 * MINUTE
    center.tick()
    task = center.get_task(task_id)
    assert task["status"] == "cancelled" and "stopped without approving" in task["cause"]
    assert [a[2] for a in contract.answers] == ["stop"], "the only word sent to the phone is stop"
    assert center.list_approvals() == [] and center.list_approvals(state="expired")[0]["id"] == open_one["id"]
    # Owners may set their own limits; 0 means no limit.
    assert center.automation.set_timeouts({"send": 0})["send"] == 0
    task_id = waiting(center, contract, "phone-b", "req-2")
    clock.ms += 3 * 24 * 60 * MINUTE
    center.tick()
    assert center.get_task(task_id)["status"] == "needs_you"
    with pytest.raises(CommandError):
        center.automation.set_timeouts({"send": -1})
    with pytest.raises(CommandError):
        center.automation.set_timeouts({"everything": 5})


def test_identical_approvals_can_be_answered_together_each_for_its_own_request(setup):
    center, contract, *_ = setup
    waiting(center, contract, "phone-a", "req-a")
    waiting(center, contract, "phone-b", "req-b")
    ids = [a["id"] for a in center.list_approvals()]
    out = center.automation.answer_batch({"ids": ids, "action": "approve"})
    assert all(r["handled"] for r in out["answered"])
    assert sorted((a[0], a[3]) for a in contract.answers) == [("phone-a", "req-a"), ("phone-b", "req-b")]
    with pytest.raises(CommandError):
        center.automation.answer_batch({"ids": ids, "action": "approve"})  # already answered


def test_a_batch_refuses_mixed_hidden_or_lone_approvals(setup):
    center, contract, *_ = setup
    waiting(center, contract, "phone-a", "req-a", gate="send")
    waiting(center, contract, "phone-b", "req-b", gate="pay")
    ids = [a["id"] for a in center.list_approvals()]
    with pytest.raises(CommandError):
        center.automation.answer_batch({"ids": ids, "action": "approve"})
    with pytest.raises(CommandError):
        center.automation.answer_batch({"ids": ids[:1], "action": "approve"})
    with pytest.raises(CommandError):
        center.automation.answer_batch({"ids": ids, "action": "reply"})
    assert contract.answers == []


def test_a_batch_refuses_approvals_that_must_be_answered_on_the_phone(setup):
    center, contract, *_ = setup
    waiting(center, contract, "phone-a", "req-a", approvable=False)
    waiting(center, contract, "phone-b", "req-b", approvable=False)
    with pytest.raises(CommandError):
        center.automation.answer_batch({"ids": [a["id"] for a in center.list_approvals()], "action": "decline"})
    assert contract.answers == []


def test_a_login_with_an_account_bound_elsewhere_is_never_batched(setup):
    """Plan 59 run 2c: the owner answers each cross-profile login on its own, every time."""
    center, contract, *_ = setup
    waiting(center, contract, "phone-a", "req-a", gate="account")
    waiting(center, contract, "phone-b", "req-b", gate="account")
    approvals = center.list_approvals()
    assert all(a["gate"] == "account" for a in approvals)
    with pytest.raises(CommandError):
        center.automation.answer_batch({"ids": [a["id"] for a in approvals], "action": "approve"})
    assert contract.answers == []
    out = center.answer(approvals[0]["id"], {"action": "approve"})
    assert out["handled"] and [a[3] for a in contract.answers] == ["req-a"], "one at a time still works"


def test_routes(setup):
    center, contract, *_ = setup
    app = FastAPI()
    app.include_router(create_command_router(type("R", (), {"command": center})(), TOKEN))
    client = TestClient(app)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    assert client.get("/v1/cc/approvals/timeouts").status_code == 401
    assert client.get("/v1/cc/approvals/timeouts", headers=headers).json()["timeouts"]["send"] == 60
    assert client.put("/v1/cc/approvals/timeouts", headers=headers, json={"question": 30}).json()["timeouts"]["question"] == 30
    assert client.post("/v1/cc/approvals/batch", json={"ids": [], "action": "approve"}).status_code == 401
    assert client.post("/v1/cc/approvals/batch", headers=headers, json={"ids": [], "action": "approve"}).status_code == 400
    assert client.get("/v1/cc/approvals?state=expired", headers=headers).json() == {"approvals": []}
