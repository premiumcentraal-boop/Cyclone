"""Fleet orchestration: parallel missions, isolation, recovery, and secret boundaries."""

from __future__ import annotations

import threading
import time

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode, deterministic_device_id
from cyclone_device_gateway.desktop_runtime.orchestration.context import assert_context, bind_context
from cyclone_device_gateway.desktop_runtime.orchestration.controller import FleetController
from cyclone_device_gateway.desktop_runtime.orchestration.fleet_api import create_fleet_orchestrator_router
from cyclone_device_gateway.desktop_runtime.orchestration.planner import FleetPlanner
from cyclone_device_gateway.desktop_runtime.orchestration.power import DevicePowerController, ScreenState
from cyclone_device_gateway.desktop_runtime.orchestration.runner import AskContractRunner, GoalResult

PIXEL = deterministic_device_id("pixel-main-serial")
WORK = deterministic_device_id("samsung-work-serial")
TABLET = deterministic_device_id("tablet-serial")


class ScriptedRunner:
    def __init__(self, delay: float = 0.0, fail: set[str] | None = None):
        self.delay = delay
        self.fail = fail or set()
        self.observe_calls: list[str] = []
        self.run_calls: list[str] = []
        self.lock = threading.Lock()
        self.block: dict[str, threading.Event] = {}
        self.entered: dict[str, threading.Event] = {}

    def observe(self, ctx):
        with self.lock:
            self.observe_calls.append(ctx.device_id)
        return {
            "deviceId": ctx.device_id,
            "sessionId": ctx.session_id,
            "displayId": ctx.display_id,
            "package": "com.example",
            "state": "observed",
        }

    def run(self, ctx, objective, hooks):
        with self.lock:
            self.run_calls.append(ctx.device_id)
        if hooks.cancelled():
            return GoalResult(False, "cancelled", "cancelled")
        gate = self.block.get(ctx.device_id)
        if gate is not None:
            self.entered.setdefault(ctx.device_id, threading.Event()).set()
            gate.wait(timeout=2)
        if self.delay:
            time.sleep(self.delay)
        if hooks.cancelled():
            return GoalResult(False, "cancelled", "cancelled", action_ids=[f"{ctx.mission_id}:act"])
        if not hooks.online():
            return GoalResult(False, "disconnected", "disconnected during action", action_ids=[f"{ctx.mission_id}:act"])
        if not hooks.trusted():
            return GoalResult(False, "revoked", "revoked", action_ids=[f"{ctx.mission_id}:act"])
        if hooks.human():
            return GoalResult(False, "paused", "human", action_ids=[f"{ctx.mission_id}:act"])
        if ctx.device_id in self.fail:
            return GoalResult(False, "failed", "device failed", {"deviceId": ctx.device_id}, [f"{ctx.mission_id}:act"])
        return GoalResult(
            True,
            "completed",
            objective,
            {
                "deviceId": ctx.device_id,
                "sessionId": ctx.session_id,
                "displayId": ctx.display_id,
                "package": "com.android.chrome",
                "state": "done",
                "otp": "837291",
            },
            [f"{ctx.mission_id}:act"],
        )


def controller(tmp_path, runner, workers=4, power=None):
    fleet = FleetController(
        registry_path=tmp_path / "registry.json",
        journal_path=tmp_path / "journal.json",
        runner=runner,
        power=power,
        max_workers=workers,
    )
    fleet.adopt(PIXEL, name="Pixel Main", manufacturer="Google", model="Pixel 8", role="PERSONAL", trust=True, online=True)
    fleet.adopt(WORK, name="Work Phone", manufacturer="Samsung", model="Galaxy S24", role="WORK", trust=True, online=True)
    fleet.adopt(TABLET, name="Tablet", manufacturer="Google", model="Pixel Tablet", role="MEDIA", trust=True, online=True)
    fleet.designate_controller(PIXEL)
    return fleet


def _ids(body):
    return {item["deviceId"] for item in body["missions"]}


def test_parallel_missions_overlap(tmp_path):
    runner = ScriptedRunner(delay=0.35)
    fleet = controller(tmp_path, runner)
    started = time.monotonic()
    body = fleet.submit_command(
        "On Pixel Main, open Chrome and search for maps. On Work Phone, open Gmail and summarize unread mail."
    )
    assert body["status"] in {"QUEUED", "RUNNING"}
    assert _ids(body) == {PIXEL, WORK}
    assert fleet.wait_idle(2)
    elapsed = time.monotonic() - started
    assert elapsed < 0.7, elapsed
    finished = fleet.mission(body["fleetMissionId"])
    assert finished["status"] == "COMPLETED"
    assert all(child["verified"] is True for child in finished["missions"])
    fleet.shutdown()


def test_same_device_is_serialized(tmp_path):
    runner = ScriptedRunner(delay=0.2)
    fleet = controller(tmp_path, runner, workers=4)
    started = time.monotonic()
    fleet.submit_plan({
        "goal": "two on one phone",
        "missions": [
            {"target": {"deviceId": PIXEL}, "objective": "open clock"},
            {"target": {"deviceId": PIXEL}, "objective": "open calculator"},
        ],
    })
    assert fleet.wait_idle(2)
    elapsed = time.monotonic() - started
    assert elapsed >= 0.38, elapsed
    assert runner.run_calls == [PIXEL, PIXEL]
    fleet.shutdown()


def test_ten_devices_stay_bounded(tmp_path):
    runner = ScriptedRunner(delay=0.2)
    fleet = FleetController(
        registry_path=tmp_path / "registry.json",
        journal_path=tmp_path / "journal.json",
        runner=runner,
        max_workers=2,
    )
    missions = []
    for index in range(10):
        device_id = deterministic_device_id(f"lab-{index}")
        fleet.adopt(device_id, name=f"Lab {index}", role="TESTING", trust=True, online=True)
        missions.append({"target": {"deviceId": device_id}, "objective": "open clock"})
    started = time.monotonic()
    fleet.submit_plan({"goal": "lab sweep", "missions": missions})
    assert fleet.wait_idle(3)
    elapsed = time.monotonic() - started
    assert elapsed < 1.5, elapsed
    assert elapsed < 1.8
    assert fleet._peak_concurrency <= 2
    assert len(runner.run_calls) == 10
    fleet.shutdown()


def test_partial_failure_does_not_drop_success(tmp_path):
    runner = ScriptedRunner(fail={PIXEL})
    fleet = controller(tmp_path, runner)
    body = fleet.submit_command("On Pixel Main, open Chrome. On Work Phone, open Gmail.")
    assert fleet.wait_idle(2)
    finished = fleet.mission(body["fleetMissionId"])
    assert finished["status"] == "PARTIAL_FAILURE"
    by_device = {item["deviceId"]: item["status"] for item in finished["missions"]}
    assert by_device[PIXEL] == "FAILED"
    assert by_device[WORK] == "COMPLETED"
    fleet.shutdown()


def test_context_mismatch_rejected():
    ctx = bind_context(
        device_id=PIXEL,
        session_id="default-foreground",
        display_id=0,
        mission_id="msn_test",
        fleet_mission_id="flt_test",
    )
    with pytest.raises(DesktopRuntimeError) as mismatch:
        assert_context(ctx, device_id=WORK, session_id="default-foreground", display_id=0)
    assert mismatch.value.code == RuntimeErrorCode.DEVICE_CONTEXT_MISMATCH.value
    named = bind_context(
        device_id=PIXEL,
        session_id="sess-pixel",
        display_id=3,
        mission_id="msn_named",
        fleet_mission_id="flt_test",
    )
    with pytest.raises(DesktopRuntimeError):
        assert_context(named, device_id=PIXEL, session_id="sess-other", display_id=3)
    with pytest.raises(DesktopRuntimeError):
        assert_context(named, device_id=PIXEL, session_id="sess-pixel", display_id=9)


def test_session_cannot_cross_devices(tmp_path):
    fleet = controller(tmp_path, ScriptedRunner())
    fleet.submit_plan({
        "goal": "bind",
        "missions": [{"target": {"deviceId": PIXEL, "sessionId": "sess-pixel", "displayId": 3}, "objective": "look"}],
    })
    with pytest.raises(DesktopRuntimeError) as rejected:
        fleet.submit_plan({
            "goal": "steal",
            "missions": [{"target": {"deviceId": WORK, "sessionId": "sess-pixel", "displayId": 3}, "objective": "look"}],
        })
    assert rejected.value.code == RuntimeErrorCode.DEVICE_CONTEXT_MISMATCH.value
    fleet.shutdown()


def test_dispatch_guard_rejects_other_device(tmp_path):
    fleet = controller(tmp_path, ScriptedRunner())
    body = fleet.submit_plan({
        "goal": "one",
        "missions": [{"target": {"deviceId": PIXEL}, "objective": "open clock"}],
    })
    child = body["missions"][0]["missionId"]
    with pytest.raises(DesktopRuntimeError) as rejected:
        fleet.dispatch_guard(child, device_id=WORK, session_id="default-foreground", display_id=0)
    assert "DEVICE_CONTEXT_MISMATCH" in rejected.value.code
    fleet.wait_idle(2)
    fleet.shutdown()


def test_revoke_stops_that_device_only(tmp_path):
    runner = ScriptedRunner()
    runner.block[PIXEL] = threading.Event()
    fleet = controller(tmp_path, runner)
    body = fleet.submit_command("On Pixel Main, open Chrome. On Work Phone, open Gmail.")
    assert runner.entered.setdefault(PIXEL, threading.Event()).wait(1)
    fleet.revoke(PIXEL)
    runner.block[PIXEL].set()
    assert fleet.wait_idle(2)
    finished = fleet.mission(body["fleetMissionId"])
    by_device = {item["deviceId"]: item["status"] for item in finished["missions"]}
    assert by_device[PIXEL] == "STOPPED"
    assert by_device[WORK] == "COMPLETED"
    assert finished["status"] == "PARTIAL_FAILURE"
    fleet.shutdown()


def test_disconnect_does_not_replay(tmp_path):
    runner = ScriptedRunner()
    runner.block[PIXEL] = threading.Event()
    fleet = controller(tmp_path, runner)
    body = fleet.submit_plan({"goal": "mail", "missions": [{"target": {"deviceId": PIXEL}, "objective": "open gmail"}]})
    assert runner.entered.setdefault(PIXEL, threading.Event()).wait(1)
    fleet.note_disconnect(PIXEL)
    runner.block[PIXEL].set()
    assert fleet.wait_idle(2)
    child = body["missions"][0]
    status = fleet.mission(child["missionId"])
    assert status["status"] == "RECONNECTING"
    assert status["failureCode"] == "NO_BLIND_REPLAY"
    runs_before = len(runner.run_calls)
    fleet.note_reconnect(PIXEL)
    fleet.resume(child["missionId"])
    assert fleet.wait_idle(2)
    assert len(runner.run_calls) == runs_before
    assert runner.observe_calls
    resumed = fleet.mission(child["missionId"])
    assert resumed["failureCode"] == "REOBSERVE_AFTER_RECONNECT"
    fleet.shutdown()


def test_restart_does_not_blindly_continue(tmp_path):
    runner = ScriptedRunner()
    runner.block[PIXEL] = threading.Event()
    fleet = controller(tmp_path, runner)
    fleet.submit_plan({"goal": "mail", "missions": [{"target": {"deviceId": PIXEL}, "objective": "open gmail"}]})
    assert runner.entered.setdefault(PIXEL, threading.Event()).wait(1)
    time.sleep(0.05)
    journal = tmp_path / "journal.json"
    assert journal.is_file()
    revived_runner = ScriptedRunner()
    revived = FleetController(
        registry_path=tmp_path / "registry.json",
        journal_path=journal,
        runner=revived_runner,
        max_workers=2,
    )
    time.sleep(0.05)
    assert revived_runner.run_calls == []
    paused = revived.missions()[0]["missions"]
    assert paused and paused[0]["status"] == "PAUSED"
    assert paused[0]["failureCode"] == "REOBSERVE_AFTER_RESTART"
    revived.note_reconnect(PIXEL)
    revived.resume(paused[0]["missionId"])
    assert revived.wait_idle(2)
    assert revived_runner.run_calls == []
    assert revived_runner.observe_calls
    runner.block[PIXEL].set()
    fleet.shutdown()
    revived.shutdown()


def test_secure_lock_asks_owner_and_other_device_continues(tmp_path):
    states = {PIXEL: ScreenState.LOCKED, WORK: ScreenState.UNLOCKED, TABLET: ScreenState.UNLOCKED}

    def probe(device_id):
        return states[device_id]

    holder = {}
    power = DevicePowerController(probe, record_lookup=lambda device_id: holder["fleet"].registry.get(device_id))
    runner = ScriptedRunner(delay=0.05)
    fleet = controller(tmp_path, runner, power=power)
    holder["fleet"] = fleet
    fleet.set_screen_state(PIXEL, "LOCKED")
    body = fleet.submit_command("On Pixel Main, open Chrome. On Work Phone, open Gmail.")
    assert fleet.wait_idle(2)
    finished = fleet.mission(body["fleetMissionId"])
    by_device = {item["deviceId"]: item for item in finished["missions"]}
    assert by_device[PIXEL]["status"] == "WAITING_OWNER"
    assert by_device[PIXEL]["failureCode"] == "OWNER_REQUIRED"
    assert "unlock" in (by_device[PIXEL]["result"] or "").casefold()
    assert by_device[WORK]["status"] == "COMPLETED"
    assert power.dismiss_calls == []
    states[PIXEL] = ScreenState.UNLOCKED
    fleet.resume(by_device[PIXEL]["missionId"])
    assert fleet.wait_idle(2)
    assert fleet.mission(by_device[PIXEL]["missionId"])["status"] == "COMPLETED"
    fleet.shutdown()


def test_insecure_lock_can_be_dismissed_without_a_pin(tmp_path):
    states = {PIXEL: ScreenState.LOCKED}
    pins: list[str] = []

    def probe(device_id):
        return states.get(device_id, ScreenState.UNLOCKED)

    def dismiss(device_id):
        pins.append("dismissed-without-pin")
        states[device_id] = ScreenState.UNLOCKED

    holder = {}
    power = DevicePowerController(
        probe,
        dismiss_insecure=dismiss,
        record_lookup=lambda device_id: holder["fleet"].registry.get(device_id),
    )
    fleet = controller(tmp_path, ScriptedRunner(), power=power)
    holder["fleet"] = fleet
    fleet.registry.set_profile(PIXEL, secure_lock=False, insecure_lock_dismiss=True)
    fleet.submit_plan({"goal": "wake", "missions": [{"target": {"deviceId": PIXEL}, "objective": "open clock"}]})
    assert fleet.wait_idle(2)
    assert pins == ["dismissed-without-pin"]
    assert fleet.missions()[0]["status"] == "COMPLETED"
    fleet.shutdown()


def test_takeover_is_per_device(tmp_path):
    runner = ScriptedRunner(delay=0.05)
    fleet = controller(tmp_path, runner)
    fleet.take_control(PIXEL)
    body = fleet.submit_command("On Pixel Main, open Chrome. On Tablet, open YouTube.")
    assert fleet.wait_idle(2)
    by_device = {item["deviceId"]: item["status"] for item in fleet.mission(body["fleetMissionId"])["missions"]}
    assert by_device[PIXEL] == "PAUSED"
    assert by_device[TABLET] == "COMPLETED"
    fleet.release_control(PIXEL)
    assert fleet.wait_idle(2)
    assert fleet.mission(body["missions"][0]["missionId"] if body["missions"][0]["deviceId"] == PIXEL else body["missions"][1]["missionId"])["status"] in {
        "COMPLETED", "PAUSED",
    }
    pixel_mission = next(item for item in fleet.mission(body["fleetMissionId"])["missions"] if item["deviceId"] == PIXEL)
    assert pixel_mission["status"] == "COMPLETED"
    fleet.shutdown()


def test_stop_one_device_leaves_the_other(tmp_path):
    runner = ScriptedRunner()
    runner.block[PIXEL] = threading.Event()
    runner.block[WORK] = threading.Event()
    fleet = controller(tmp_path, runner)
    body = fleet.submit_command("On Pixel Main, open Chrome. On Work Phone, open Gmail.")
    assert runner.entered.setdefault(PIXEL, threading.Event()).wait(1)
    assert runner.entered.setdefault(WORK, threading.Event()).wait(1)
    fleet.stop_device(PIXEL)
    runner.block[PIXEL].set()
    runner.block[WORK].set()
    assert fleet.wait_idle(2)
    by_device = {item["deviceId"]: item["status"] for item in fleet.mission(body["fleetMissionId"])["missions"]}
    assert by_device[PIXEL] == "CANCELLED"
    assert by_device[WORK] == "COMPLETED"
    fleet.shutdown()


def test_ambiguous_name_does_not_guess(tmp_path):
    fleet = controller(tmp_path, ScriptedRunner())
    fleet.adopt(deterministic_device_id("pixel-2"), name="Pixel Travel", manufacturer="Google", model="Pixel 7", trust=True)
    answer = fleet.submit_command("On the Pixel, open Maps.")
    assert answer["kind"] == "clarification"
    assert "Pixel Main" in answer["message"] and "Pixel Travel" in answer["message"]
    assert fleet.missions() == []
    fleet.shutdown()


def test_secret_handoff_is_owner_gated_and_not_stored(tmp_path):
    runner = ScriptedRunner(delay=0.05)
    fleet = controller(tmp_path, runner)
    body = fleet.submit_command("Find the OTP on my tablet and use it on my phone.")
    assert fleet.wait_idle(2)
    finished = fleet.mission(body["fleetMissionId"])
    assert finished["status"] == "COMPLETED"
    blob = (tmp_path / "journal.json").read_text(encoding="utf-8")
    assert "837291" not in blob
    assert "otp" not in blob.casefold() or "[redacted]" in blob
    objectives = " ".join(child["objective"] for child in finished["missions"])
    assert "837291" not in objectives
    assert any(child["handoff"] == "OWNER_REQUIRED" for child in finished["missions"])
    fleet.shutdown()


def test_openrouter_material_never_lands_in_fleet_state(tmp_path):
    seen = {}

    class Model:
        def propose(self, snapshot, text):
            seen["snapshot"] = snapshot
            seen["text"] = text
            return None

    runner = ScriptedRunner()
    fleet = controller(tmp_path, runner)
    fleet.planner = FleetPlanner(fleet.registry, model=Model())
    key = "sk-or-v1-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    fleet.submit_command(f"On Pixel Main, open Chrome using {key}")
    assert fleet.wait_idle(2)
    assert key not in str(seen["snapshot"])
    assert key not in seen["text"]
    blob = (tmp_path / "journal.json").read_text(encoding="utf-8") + str(fleet.events())
    assert "sk-or-v1" not in blob
    assert "ABCDEFGHIJKLMNOP" not in blob
    fleet.shutdown()


def test_broadcast_consequential_waits_for_confirmation(tmp_path):
    fleet = controller(tmp_path, ScriptedRunner())
    fleet.registry.put_group("testing", "Testing", [PIXEL, TABLET])
    pending = fleet.submit_command("Run delete the account on all testing devices")
    assert pending["kind"] == "confirmation"
    assert fleet.missions() == []
    confirmed = fleet.submit_command("yes", confirmed=True)
    assert confirmed["status"] in {"QUEUED", "RUNNING", "COMPLETED"}
    assert fleet.wait_idle(2)
    assert len(fleet.missions()[0]["missions"]) == 2
    fleet.shutdown()


def test_group_broadcast_runs_together(tmp_path):
    runner = ScriptedRunner(delay=0.2)
    fleet = controller(tmp_path, runner, workers=4)
    fleet.registry.put_group("testing", "Testing", [PIXEL, WORK, TABLET])
    started = time.monotonic()
    fleet.submit_command("Run open Clock on all testing devices")
    assert fleet.wait_idle(2)
    assert time.monotonic() - started < 0.6
    assert set(runner.run_calls) == {PIXEL, WORK, TABLET}
    fleet.shutdown()


def test_model_requests_can_overlap(tmp_path):
    gate = threading.Barrier(2)
    calls = []

    class Model:
        def propose(self, snapshot, text):
            calls.append(text)
            gate.wait(timeout=1)
            return None

    fleet = controller(tmp_path, ScriptedRunner())
    fleet.planner = FleetPlanner(fleet.registry, model=Model())
    errors = []

    def ask(text):
        try:
            fleet.submit_command(text)
        except Exception as exc:  # noqa: BLE001 - the test records it
            errors.append(exc)

    threads = [
        threading.Thread(target=ask, args=("On Pixel Main, open Chrome.",)),
        threading.Thread(target=ask, args=("On Work Phone, open Gmail.",)),
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(2)
    assert errors == []
    assert len(calls) == 2
    fleet.shutdown()


def test_capability_mismatch_does_not_run(tmp_path):
    fleet = controller(tmp_path, ScriptedRunner())
    fleet.registry.set_profile(PIXEL, capabilities={"camera": False})
    answer = fleet.submit_command("On Pixel Main, take a photo of the receipt.")
    assert answer["kind"] == "clarification"
    assert fleet.missions() == []
    fleet.shutdown()


def test_shell_objective_is_denied(tmp_path):
    runner = ScriptedRunner()
    fleet = controller(tmp_path, runner)
    fleet.submit_plan({"goal": "nope", "missions": [{"target": {"deviceId": PIXEL}, "objective": "adb shell input tap 1 1"}]})
    assert fleet.wait_idle(2)
    assert runner.run_calls == []
    assert fleet.missions()[0]["missions"][0]["failureCode"] == "POLICY_DENIED"
    fleet.shutdown()


def test_rename_persists_and_resolves(tmp_path):
    fleet = controller(tmp_path, ScriptedRunner())
    fleet.rename(WORK, "Studio Phone", ["studio"])
    revived = FleetController(registry_path=tmp_path / "registry.json", journal_path=tmp_path / "journal.json", runner=ScriptedRunner())
    body = revived.submit_command("On studio, open Gmail and summarize unread mail.")
    assert body["missions"][0]["deviceId"] == WORK
    revived.wait_idle(2)
    fleet.shutdown()
    revived.shutdown()


def test_ask_runner_waits_for_phone_verification():
    class Contract:
        def __init__(self):
            self.calls = []
            self.state = "working"

        def forward(self, device_id, op, args):
            self.calls.append((device_id, op, dict(args)))
            if op == "ask.start":
                return {"accepted": True, "sessionId": args["sessionId"], "displayId": args["displayId"]}
            if self.calls.count((device_id, "ask.status", self.calls[-1][2] if False else args)) > 1:
                self.state = "done"
            if len([item for item in self.calls if item[1] == "ask.status"]) >= 2:
                self.state = "done"
            return {
                "taskId": "task-1",
                "state": self.state,
                "title": "Open clock",
                "app": "Clock",
                "currentMilestone": "Opening",
                "milestones": [],
                "supportingCopy": "",
                "outcomeCopy": "Clock is open." if self.state == "done" else "",
                "sessionId": args["sessionId"],
                "displayId": args["displayId"],
            }

    contract = Contract()
    runner = AskContractRunner(contract, poll_interval=0.01, max_polls=5)
    ctx = bind_context(device_id=PIXEL, session_id="default-foreground", display_id=0, mission_id="msn_ask", fleet_mission_id="flt_ask")

    class Hooks:
        def cancelled(self): return False
        def trusted(self): return True
        def online(self): return True
        def human(self): return False
        def reobserve_required(self): return False

    result = runner.run(ctx, "open clock", Hooks())
    assert result.verified is True
    assert contract.calls[0][1] == "ask.start"
    assert any(item[1] == "ask.status" for item in contract.calls)
    assert contract.calls[0][2]["goal"] == "open clock"


def test_fleet_http_routes(tmp_path):
    runner = ScriptedRunner()
    fleet = controller(tmp_path, runner)
    runtime = type("Runtime", (), {"orchestration": fleet})()
    app = FastAPI()
    app.include_router(create_fleet_orchestrator_router(runtime, "secret-token"))
    client = TestClient(app)
    denied = client.get("/v1/fleet/snapshot")
    assert denied.status_code == 401
    headers = {"Authorization": "Bearer secret-token"}
    snapshot = client.get("/v1/fleet/snapshot", headers=headers)
    assert snapshot.status_code == 200
    assert snapshot.json()["counts"]["devices"] == 3
    created = client.post(
        "/v1/fleet/command",
        headers=headers,
        json={"text": "On Pixel Main, open Chrome. On Work Phone, open Gmail."},
    )
    assert created.status_code == 200
    fleet.wait_idle(2)
    listing = client.get("/v1/fleet/missions", headers=headers)
    assert listing.json()["missions"][0]["status"] == "COMPLETED"
    renamed = client.post("/v1/fleet/devices/" + WORK + "/rename", headers=headers, json={"name": "Desk Phone"})
    assert renamed.status_code == 200
    assert renamed.json()["name"] == "Desk Phone"
    fleet.shutdown()
