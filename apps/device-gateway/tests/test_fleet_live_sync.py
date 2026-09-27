"""Live fleet sync uses ADB events and the ask contract. Fakes here are test-only."""

from __future__ import annotations

import threading
import time

import pytest

from cyclone_device_gateway.adb.client import ADBDevice
from cyclone_device_gateway.config import Settings
from cyclone_device_gateway.desktop_runtime.api import DesktopRuntime
from cyclone_device_gateway.desktop_runtime.fleet import DeviceFleetManager
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, RuntimeErrorCode, deterministic_device_id
from cyclone_device_gateway.desktop_runtime.orchestration.controller import FleetController
from cyclone_device_gateway.desktop_runtime.orchestration.live import FleetLiveSync
from cyclone_device_gateway.desktop_runtime.orchestration.power import DevicePowerController, ScreenState
from cyclone_device_gateway.desktop_runtime.orchestration.runner import AskContractRunner


class Inventory:
    def __init__(self, devices):
        self.current = list(devices)

    def devices(self):
        return list(self.current)


class ShellAdb:
    def __init__(self, serial: str, *, locked: bool = False, asleep: bool = False):
        self.serial = serial
        self.locked = locked
        self.asleep = asleep
        self.calls: list[tuple] = []

    def shell(self, *args, timeout=15):
        self.calls.append(args)
        if args[:3] == ("input", "keyevent", "KEYCODE_WAKEUP"):
            self.asleep = False
            return ""
        if args[:2] == ("dumpsys", "window"):
            flag = "true" if self.locked else "false"
            return f"mDreamingLockscreen={flag}\nisStatusBarKeyguard={flag}\n"
        if args[:2] == ("dumpsys", "power"):
            if self.asleep:
                return "mWakefulness=Asleep\nDisplay Power: state=OFF\n"
            return "mWakefulness=Awake\nDisplay Power: state=ON\n"
        if args[:2] == ("dumpsys", "battery"):
            return "  level: 81\n"
        if args[:3] == ("dumpsys", "activity", "activities"):
            return "mResumedActivity: ActivityRecord{u0 com.google.android.gm/.ConversationListActivity t12}\n"
        if args == ("getprop", "ro.product.manufacturer"):
            return "Google\n"
        if args == ("getprop", "ro.product.model"):
            return "Pixel 8\n"
        if args == ("cat", "/proc/sys/kernel/random/boot_id"):
            return "01234567-89ab-cdef-0123-456789abcdef\n"
        if args[:2] == ("pm", "path"):
            return "package:/data/app/com.cyclone.mobile/base.apk\n"
        if args[:2] == ("wm", "size"):
            return "Physical size: 1080x2400\n"
        return ""

    def ensure_bridge_forward(self, port):
        return True

    def remove_forward(self, port):
        return None


class RecordingContract:
    def __init__(self, *, fail_locked: set[str] | None = None, echo_other: dict[str, str] | None = None, delay: float = 0.0):
        self.fail_locked = fail_locked or set()
        self.echo_other = echo_other or {}
        self.delay = delay
        self.calls: list[tuple[str, str, float]] = []
        self.lock = threading.Lock()

    def forward(self, device_id, op, payload):
        with self.lock:
            self.calls.append((device_id, op, time.monotonic()))
        if device_id in self.fail_locked and op == "ask.start":
            raise DesktopRuntimeError(RuntimeErrorCode.PHONE_LOCKED, "Keyguard is showing.")
        if self.delay and op == "ask.start":
            time.sleep(self.delay)
        reported = self.echo_other.get(device_id, device_id)
        body = {
            "accepted": True,
            "deviceId": reported,
            "sessionId": payload.get("sessionId"),
            "displayId": payload.get("displayId"),
            "state": "done",
            "outcomeCopy": f"done on {device_id}",
            "app": "com.example",
            "title": "done",
        }
        return body


class EmptyFleet:
    def set_source_resolver(self, resolver):
        return None

    def set_video_factory(self, factory):
        return None

    def list_public(self):
        return []

    def diagnostics(self):
        return {}

    def start(self):
        return None

    def stop(self):
        return None


def _pair(tmp_path, serials: dict[str, ShellAdb], trust: set[str] | None = None):
    inventory = Inventory([ADBDevice(serial, "device", model="Pixel 8") for serial in serials])
    manager = DeviceFleetManager(
        inventory_adb=inventory,
        adb_factory=lambda serial: serials[serial],
        poll_seconds=20,
    )
    contract = RecordingContract()
    controller = FleetController(
        registry_path=tmp_path / "fleet-registry.json",
        journal_path=tmp_path / "fleet-missions.json",
        runner=AskContractRunner(contract, poll_interval=0.01, max_polls=5),
        max_workers=4,
    )
    trusted = trust or set()

    def trust_state(device_id: str) -> str:
        return "TRUSTED" if device_id in trusted else "UNPAIRED"

    sync = FleetLiveSync(manager, controller, trust_state=trust_state)
    controller.power = DevicePowerController(
        sync.probe_screen,
        wake=sync.wake_display,
        record_lookup=controller._record_or_none,
    )
    return inventory, manager, controller, sync, contract


def test_adb_serial_is_the_identity_and_survives_rename_and_restart(tmp_path):
    serial = "ABC123XYZ"
    adb = ShellAdb(serial)
    inventory, manager, controller, sync, _contract = _pair(tmp_path, {serial: adb})
    manager.refresh_once()
    assert sync.drain() >= 1
    device_id = deterministic_device_id(serial)
    record = controller.registry.get(device_id)
    assert record.online is True
    assert record.trust.value == "DISCOVERED"
    assert record.display_name != "Pixel Main"
    assert record.battery == 81
    assert record.current_app == "com.google.android.gm"
    assert record.serial_suffix == "3XYZ"
    assert "ABC123XYZ" not in controller.registry.path.read_text(encoding="utf-8")

    controller.rename(device_id, "Pixel Main", ["personal"])
    revived = FleetController(registry_path=controller.registry.path, journal_path=tmp_path / "journal-2.json")
    again = revived.registry.get(device_id)
    assert again.display_name == "Pixel Main"
    assert again.aliases == ["personal"]
    revived.shutdown()
    controller.rename(device_id, "Personal Phone")
    third = FleetController(registry_path=controller.registry.path, journal_path=tmp_path / "journal-3.json")
    assert third.registry.get(device_id).display_name == "Personal Phone"
    third.shutdown()

    inventory.current = []
    manager.refresh_once()
    sync.drain()
    assert controller.registry.get(device_id).online is False
    assert any(event["type"] == "DEVICE_DISCONNECTED" and event["deviceId"] == device_id for event in controller.events())

    inventory.current = [ADBDevice(serial, "device", model="Pixel 8")]
    manager.refresh_once()
    sync.drain()
    restored = controller.registry.get(device_id)
    assert restored.online is True
    assert restored.display_name == "Personal Phone"
    controller.shutdown()


def test_locked_device_waits_and_other_device_runs_without_bypass(tmp_path):
    locked_serial = "LOCK-A"
    free_serial = "FREE-B"
    locked = ShellAdb(locked_serial, locked=True)
    free = ShellAdb(free_serial)
    inventory, manager, controller, sync, contract = _pair(
        tmp_path,
        {locked_serial: locked, free_serial: free},
        trust={deterministic_device_id(locked_serial), deterministic_device_id(free_serial)},
    )
    manager.refresh_once()
    sync.drain()
    locked_id = deterministic_device_id(locked_serial)
    free_id = deterministic_device_id(free_serial)
    assert sync.probe_screen(locked_id) == ScreenState.LOCKED
    assert sync.probe_screen(free_id) == ScreenState.AWAKE
    controller.rename(locked_id, "Device X")
    controller.rename(free_id, "Device Y")
    body = controller.submit_command("On Device X, open Chrome. On Device Y, open Gmail.")
    assert controller.wait_idle(2)
    finished = controller.mission(body["fleetMissionId"])
    by_device = {item["deviceId"]: item for item in finished["missions"]}
    assert by_device[locked_id]["status"] == "WAITING_OWNER"
    assert by_device[free_id]["status"] == "COMPLETED"
    assert all(call[0] != "input" for call in locked.calls)
    forwarded = {device for device, _op, _at in contract.calls}
    assert locked_id not in forwarded
    assert free_id in forwarded
    controller.shutdown()


def test_ask_runner_overlaps_and_rejects_the_other_device(tmp_path):
    first = deterministic_device_id("serial-a")
    second = deterministic_device_id("serial-b")
    contract = RecordingContract(delay=0.25)
    controller = FleetController(
        registry_path=tmp_path / "registry.json",
        journal_path=tmp_path / "journal.json",
        runner=AskContractRunner(contract, poll_interval=0.01, max_polls=4),
        max_workers=4,
    )
    controller.adopt(first, name="Device X", trust=True, online=True)
    controller.adopt(second, name="Device Y", trust=True, online=True)
    started = time.monotonic()
    body = controller.submit_command("On Device X, open Clock. On Device Y, open Calculator.")
    assert controller.wait_idle(2)
    elapsed = time.monotonic() - started
    assert elapsed < 0.55, elapsed
    starts = {}
    finishes = {}
    for device_id, op, at in contract.calls:
        if op == "ask.start":
            starts[device_id] = at
        if op == "ask.status":
            finishes[device_id] = at
    assert set(starts) == {first, second}
    assert starts[first] < finishes[second]
    assert starts[second] < finishes[first]
    assert controller.mission(body["fleetMissionId"])["status"] == "COMPLETED"
    for device_id, op, _at in contract.calls:
        assert device_id in {first, second}

    controller._claim_session(second, "sess-b", 4)
    with pytest.raises(DesktopRuntimeError) as rejected:
        controller.submit_plan({
            "goal": "wrong phone",
            "missions": [{
                "target": {"deviceId": first, "sessionId": "sess-b", "displayId": 4},
                "objective": "open maps",
            }],
        })
    assert rejected.value.code == RuntimeErrorCode.DEVICE_CONTEXT_MISMATCH.value
    controller.shutdown()


def test_phone_locked_error_and_cross_device_response_are_not_success(tmp_path):
    first = deterministic_device_id("serial-a")
    second = deterministic_device_id("serial-b")
    contract = RecordingContract(fail_locked={first}, echo_other={second: first})
    controller = FleetController(
        registry_path=tmp_path / "registry.json",
        journal_path=tmp_path / "journal.json",
        runner=AskContractRunner(contract, poll_interval=0.01, max_polls=3),
    )
    controller.adopt(first, name="Device X", trust=True, online=True)
    controller.adopt(second, name="Device Y", trust=True, online=True)
    body = controller.submit_command("On Device X, open Clock. On Device Y, open Calculator.")
    assert controller.wait_idle(2)
    by_device = {item["deviceId"]: item for item in controller.mission(body["fleetMissionId"])["missions"]}
    assert by_device[first]["status"] == "WAITING_OWNER"
    assert by_device[first]["failureCode"] == "OWNER_REQUIRED"
    assert by_device[second]["status"] == "FAILED"
    assert by_device[second]["failureCode"] == RuntimeErrorCode.DEVICE_CONTEXT_MISMATCH.value
    text = (tmp_path / "journal.json").read_text(encoding="utf-8")
    assert "sk-or-" not in text
    assert "apiKey" not in text
    controller.shutdown()


def test_production_runtime_uses_ask_runner_and_does_not_dismiss_locks(tmp_path):
    runtime = DesktopRuntime(Settings("pc-secret", None, "adb", tmp_path), fleet=EmptyFleet())
    assert runtime.orchestration.runner.__class__.__name__ == "AskContractRunner"
    assert runtime.orchestration.runner.max_polls == 360
    assert runtime.orchestration.runner.poll_interval == 0.5
    assert runtime.orchestration.power._dismiss_insecure is None
    assert runtime.orchestration.power._wake == runtime.live_fleet.wake_display
    assert runtime.live_fleet.fleet is runtime.fleet
    runtime.stop()
