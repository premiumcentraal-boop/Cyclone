from __future__ import annotations

from pathlib import Path
import time

import pytest
from fastapi.testclient import TestClient

from cyclone_device_gateway.adb.client import ADBDevice, ADBError
from cyclone_device_gateway.config import Settings
from cyclone_device_gateway.cyclone_bridge.client import BridgeDisconnectedError
from cyclone_device_gateway.desktop_runtime.api import DesktopRuntime, create_desktop_app
from cyclone_device_gateway.desktop_runtime.controls import MANUAL_KINDS, ClipboardService, ManualControlService, clipboard_looks_sensitive
from cyclone_device_gateway.desktop_runtime.fleet import DeviceFleetManager
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError, DeviceFleetState, VIDEO_PROFILES, deterministic_device_id
from cyclone_device_gateway.desktop_runtime.pairing import PairingCoordinator
from cyclone_device_gateway.desktop_runtime.video import VideoFleetLimiter, VideoStreamController
from cyclone_device_gateway.cyclone_bridge.protocol import ALLOWED_OPS


class Inventory:
    def __init__(self, devices):
        self.current = list(devices)

    def devices(self):
        return list(self.current)


class FakeDeviceADB:
    def __init__(self, serial, *, fail=False):
        self.serial = serial
        self.fail = fail
        self.forwards = []
        self.removed = []

    def shell(self, *args, timeout=15):
        if self.fail:
            raise ADBError("offline")
        if args[:2] == ("pm", "path"):
            return "package:/data/app/com.cyclone.mobile/base.apk\n"
        if args[:2] == ("dumpsys", "power"):
            return "mWakefulness=Awake\nDisplay Power: state=ON\n"
        if args[:2] == ("wm", "size"):
            return "Physical size: 1080x2400\n"
        return ""

    def ensure_bridge_forward(self, port):
        self.forwards.append(port)
        return True

    def remove_forward(self, port):
        self.removed.append(port)

    def exec_out(self, *args, timeout=15):
        return b"png-not-used-in-sleep-test"

    def start_process(self, args, stdout=None):
        raise RuntimeError("h264 unavailable in unit test")

    def collect_cyclone_crash_diagnostics(self):
        return {
            "pid": "",
            "exit_info": "ApplicationExitInfo reason=REASON_CRASH lastStage=gateway.pair.complete.returning",
            "crash_logcat": "FATAL EXCEPTION: cyclone-test",
            "enabled_accessibility_services": "com.cyclone.mobile/.CycloneAccessibilityService",
            "accessibility_enabled": "1",
        }


class FakeBridge:
    def __init__(self, credentials=None):
        self.credentials = list(credentials or ["A" * 43, "B" * 43, "C" * 43])
        self.challenge = 0
        self.calls = []
        self.qr_approved = False

    def request_unauthenticated(self, op, args=None, request_id=None):
        self.calls.append((op, args or {}))
        if op == "pair.begin":
            self.challenge += 1
            return {"challengeId": f"ch-{self.challenge}", "expiresAtMs": int(time.time() * 1000) + 60_000}
        if op == "pair.complete":
            return {"credential": self.credentials.pop(0), "paired": True}
        if op == "pair.qr.complete":
            if not self.qr_approved:
                return {"paired": False, "pending": True}
            return {"credential": self.credentials.pop(0), "paired": True}
        raise AssertionError(op)

    def request(self, op, args=None, request_id=None):
        self.calls.append((op, args or {}))
        if op == "bridge.status":
            return {"gatewayEnabled": True, "socketListening": True, "accessibilityConnected": True}
        if op == "pair.revoke":
            return {"revoked": True}
        if op == "manual.execute":
            return {"ok": True, "status": "DONE"}
        if op == "clipboard.get":
            return {"mode": "PC_TO_PHONE", "reverseSync": "UNAVAILABLE"}
        if op == "clipboard.set":
            return {"updated": True}
        raise AssertionError(op)


class FlakyBeginBridge(FakeBridge):
    def __init__(self):
        super().__init__()
        self.fail_begin_once = True

    def request_unauthenticated(self, op, args=None, request_id=None):
        if op == "pair.begin" and self.fail_begin_once:
            self.fail_begin_once = False
            self.calls.append((op, args or {}))
            raise BridgeDisconnectedError("simulated transient USB race")
        return super().request_unauthenticated(op, args, request_id)


class DiesAfterPairBridge(FakeBridge):
    def request(self, op, args=None, request_id=None):
        self.calls.append((op, args or {}))
        if op == "bridge.status":
            raise BridgeDisconnectedError("simulated phone process death after pair.complete")
        return super().request(op, args, request_id)


def make_fleet(devices, failures=()):
    inventory = Inventory(devices)
    adbs = {}

    def factory(serial):
        adbs.setdefault(serial, FakeDeviceADB(serial, fail=serial in failures))
        return adbs[serial]

    fleet = DeviceFleetManager(
        inventory_adb=inventory,
        adb_factory=factory,
        poll_seconds=.2,
        max_devices=16,
        max_workers=8,
    )
    return fleet, inventory, adbs


def install_fake_bridges(fleet, bridge_by_id=None):
    bridge_by_id = bridge_by_id or {}
    for item in fleet.list_public():
        session = fleet.get(item["deviceId"])
        bridge = bridge_by_id.setdefault(item["deviceId"], FakeBridge())
        session.bridge = lambda token=None, auto_forward=False, b=bridge: b
    return bridge_by_id


def paired_session_for_services():
    fleet, _, _ = make_fleet([ADBDevice("A", "device")])
    fleet.refresh_once()
    session = fleet.get(deterministic_device_id("A"))
    session.credential = "Z" * 43
    bridge = FakeBridge()
    session.bridge = lambda token=None, auto_forward=False: bridge
    return fleet, session, bridge


def test_multi_device_discovery_authorized_unauthorized_and_isolated_forwards():
    devices = [
        ADBDevice("SERIAL-A-1234", "device", "Pixel_8"),
        ADBDevice("SERIAL-B-5678", "unauthorized", "Pixel_7"),
        ADBDevice("SERIAL-C-9012", "device", "Pixel_6"),
    ]
    fleet, _, adbs = make_fleet(devices)
    fleet.refresh_once()
    public = fleet.list_public()
    assert len(public) == 3
    assert {p["state"] for p in public} == {"UNPAIRED", "UNAUTHORIZED"}
    a = fleet.get(deterministic_device_id("SERIAL-A-1234"))
    c = fleet.get(deterministic_device_id("SERIAL-C-9012"))
    assert a.local_port != c.local_port
    assert adbs["SERIAL-A-1234"].forwards == [a.local_port]
    assert adbs["SERIAL-C-9012"].forwards == [c.local_port]
    assert "SERIAL-A-1234" not in str(a.public())


def test_reconnect_reuses_device_id_and_credential_and_cleans_only_removed_device():
    fleet, inventory, adbs = make_fleet([ADBDevice("A", "device"), ADBDevice("B", "device")])
    fleet.refresh_once()
    install_fake_bridges(fleet)
    a = fleet.get(deterministic_device_id("A"))
    b = fleet.get(deterministic_device_id("B"))
    fleet.remember_credential(a, "X" * 43)
    a_port, b_port = a.local_port, b.local_port
    inventory.current = [ADBDevice("B", "device")]
    fleet.refresh_once()
    assert adbs["A"].removed == [a_port]
    assert b_port not in adbs["A"].removed
    inventory.current = [ADBDevice("A", "device"), ADBDevice("B", "device")]
    fleet.refresh_once()
    install_fake_bridges(fleet)
    a2 = fleet.get(deterministic_device_id("A"))
    assert a2.device_id == a.device_id
    assert a2.local_port == a_port
    assert a2.credential == "X" * 43


def test_one_device_failure_isolation():
    fleet, _, _ = make_fleet(
        [ADBDevice("GOOD", "device"), ADBDevice("BAD", "device")],
        failures={"BAD"},
    )
    fleet.refresh_once()
    assert fleet.get(deterministic_device_id("BAD")).state == DeviceFleetState.ATTENTION
    assert fleet.get(deterministic_device_id("GOOD")).state == DeviceFleetState.UNPAIRED


def test_pairing_timeout_replay_attempt_limit_and_token_rotation():
    fleet, session, bridge = paired_session_for_services()
    session.credential = None
    pairing = PairingCoordinator(fleet)
    pairing.POST_PAIR_HEALTH_DELAY_SECONDS = 0
    begin = pairing.begin(session.device_id)
    assert begin["pairing"] is True and "credential" not in begin
    session.pending_pairing.expires_at_ms = int(time.time() * 1000) - 1
    with pytest.raises(DesktopRuntimeError) as err:
        pairing.complete(session.device_id, begin["pairingId"], "NOVA")
    assert err.value.code == "PAIRING_EXPIRED"

    begin = pairing.begin(session.device_id)
    for _ in range(4):
        with pytest.raises(DesktopRuntimeError) as err:
            pairing.complete(session.device_id, begin["pairingId"], "bad")
        assert err.value.code == "PAIRING_CODE_REJECTED"
    with pytest.raises(DesktopRuntimeError) as err:
        pairing.complete(session.device_id, begin["pairingId"], "bad")
    assert err.value.code == "PAIRING_ATTEMPTS_EXCEEDED"

    begin = pairing.begin(session.device_id)
    first = pairing.complete(session.device_id, begin["pairingId"], "NOVA")
    first_token = session.credential
    assert first["paired"] is True and first["gatewayHealthy"] is True and "credential" not in first
    assert [op for op, _ in bridge.calls].count("bridge.status") >= 2
    with pytest.raises(DesktopRuntimeError) as err:
        pairing.complete(session.device_id, begin["pairingId"], "NOVA")
    assert err.value.code == "PAIRING_REPLAY"
    begin = pairing.begin(session.device_id)
    pairing.complete(session.device_id, begin["pairingId"], "NOVA")
    assert session.credential != first_token


def test_pair_begin_repairs_forward_and_recovers_one_transient_transport_drop():
    fleet, session, _ = paired_session_for_services()
    session.credential = None
    bridge = FlakyBeginBridge()
    session.bridge = lambda token=None, auto_forward=False: bridge
    pairing = PairingCoordinator(fleet)
    pairing.BEGIN_RETRY_DELAY_SECONDS = 0

    result = pairing.begin(session.device_id)

    assert result["pairing"] is True
    assert [op for op, _ in bridge.calls].count("pair.begin") == 2
    assert session.adb.forwards.count(session.local_port) >= 2


def test_pair_complete_never_marks_ready_until_phone_survives_health_probe_and_writes_diagnostics(tmp_path):
    fleet, session, _ = paired_session_for_services()
    session.credential = None
    bridge = DiesAfterPairBridge()
    session.bridge = lambda token=None, auto_forward=False: bridge
    pairing = PairingCoordinator(fleet)
    pairing.POST_PAIR_HEALTH_DELAY_SECONDS = 0
    pairing.diagnostics_dir = tmp_path
    begin = pairing.begin(session.device_id)

    with pytest.raises(DesktopRuntimeError) as err:
        pairing.complete(session.device_id, begin["pairingId"], "NOVA")

    assert err.value.code == "DEVICE_DISCONNECTED"
    assert session.credential is None
    assert session.state == DeviceFleetState.UNPAIRED
    logs = list(tmp_path.glob("pairing-*.json"))
    assert len(logs) == 1
    text = logs[0].read_text(encoding="utf-8")
    assert "pair.complete.post_health" in text
    assert "FATAL EXCEPTION: cyclone-test" in text
    assert "Diagnostic file:" in err.value.safe_message


def test_pairing_confirmation_is_bound_to_latest_challenge_without_consuming_it():
    fleet, session, _ = paired_session_for_services()
    session.credential = None
    pairing = PairingCoordinator(fleet)
    pairing.POST_PAIR_HEALTH_DELAY_SECONDS = 0
    first = pairing.begin(session.device_id)
    latest = pairing.begin(session.device_id)

    with pytest.raises(DesktopRuntimeError) as err:
        pairing.complete(session.device_id, first["pairingId"], "NOVA")
    assert err.value.code == "PAIRING_REPLAY"
    assert session.pending_pairing.challenge_id == latest["pairingId"]
    assert session.pending_pairing.attempts == 0

    assert pairing.complete(session.device_id, latest["pairingId"], "NOVA")["paired"] is True


def test_qr_pairing_is_one_time_pending_until_phone_scan_approval():
    fleet, session, bridge = paired_session_for_services()
    session.credential = None
    pairing = PairingCoordinator(fleet)
    pairing.POST_PAIR_HEALTH_DELAY_SECONDS = 0
    begin = pairing.begin(session.device_id)

    assert begin["qrAvailable"] is True
    assert begin["qrPayload"].startswith("cyclone://pair?challenge=")
    assert "credential" not in begin["qrPayload"]
    assert pairing.complete_qr(session.device_id, begin["pairingId"]) == {
        "deviceId": session.device_id,
        "paired": False,
        "pending": True,
    }
    assert session.credential is None

    bridge.qr_approved = True
    completed = pairing.complete_qr(session.device_id, begin["pairingId"])
    assert completed["paired"] is True
    assert session.credential is not None
    with pytest.raises(DesktopRuntimeError) as err:
        pairing.complete_qr(session.device_id, begin["pairingId"])
    assert err.value.code == "PAIRING_REPLAY"


def test_manual_control_routes_explicit_device_and_never_echoes_keyboard_text():
    fleet, session, bridge = paired_session_for_services()
    service = ManualControlService(fleet)
    assert service.execute(session.device_id, {"kind": "tap", "x": .25, "y": .75})["kind"] == "tap"
    result = service.execute(session.device_id, {"kind": "text", "text": "ordinary words"})
    assert "ordinary words" not in str(result)
    assert bridge.calls[-1][0] == "manual.execute"
    assert bridge.calls[-1][1]["text"] == "ordinary words"
    with pytest.raises(DesktopRuntimeError):
        service.execute(session.device_id, {"kind": "shell"})


def test_clipboard_is_pc_to_phone_and_sensitive_values_are_rejected_without_echo():
    fleet, session, _ = paired_session_for_services()
    service = ClipboardService(fleet)
    assert service.capability(session.device_id)["reverseSync"] == "UNAVAILABLE"
    assert service.set(session.device_id, "hello desktop")["updated"] is True
    assert clipboard_looks_sensitive("OTP: 123456") is True
    assert clipboard_looks_sensitive("eyJabcde123.eyJdefgh456.signature789") is True
    with pytest.raises(DesktopRuntimeError) as err:
        service.set(session.device_id, "OTP: 123456")
    assert "123456" not in str(err.value)


def test_video_profiles_are_bounded_thumbnail_cheaper_and_sleeping_stream_pauses():
    assert VIDEO_PROFILES["thumbnail"].max_long_edge <= 540
    assert VIDEO_PROFILES["thumbnail"].target_fps <= 4
    assert VIDEO_PROFILES["thumbnail"].cpu_weight < VIDEO_PROFILES["focus"].cpu_weight
    assert VIDEO_PROFILES["focus"].max_long_edge <= 1080
    assert VIDEO_PROFILES["focus"].target_fps == 30
    fleet, session, _ = paired_session_for_services()
    session.screen_awake = False
    limiter = VideoFleetLimiter(max_sources=12, max_focus=2)
    controller = VideoStreamController(session, limiter)
    assert controller.subscriber_count() == 0
    assert limiter.snapshot()["sources"] == 0
    q = controller.subscribe("thumbnail")
    init = q.get(timeout=2)
    sleeping = q.get(timeout=2)
    assert "stream.init" in init.data
    assert "SLEEPING" in sleeping.data
    controller.unsubscribe("thumbnail", q)
    time.sleep(.1)
    controller.stop_all()


def test_no_generic_shell_or_arbitrary_adb_surface():
    forbidden = ("shell", "powershell", "root", "su", "command", "script", "adb")
    assert MANUAL_KINDS == {"tap", "back", "home", "scroll_up", "scroll_down", "text", "wake"}
    assert all(not any(word in op.lower() for word in forbidden) for op in ALLOWED_OPS)


@pytest.mark.parametrize("count", [1, 2, 4, 8, 12])
def test_fleet_performance_shape_is_bounded(count):
    devices = [ADBDevice(f"SERIAL-{i}", "device") for i in range(count)]
    fleet, _, adbs = make_fleet(devices)
    started = time.perf_counter()
    fleet.refresh_once()
    elapsed = time.perf_counter() - started
    sessions = [fleet.get(deterministic_device_id(d.serial)) for d in devices]
    assert len({s.local_port for s in sessions}) == count
    assert fleet.max_workers <= 8
    assert all(len(adbs[d.serial].forwards) == 1 for d in devices)
    assert elapsed < 2.0


def test_frozen_http_and_websocket_routes_are_authenticated(tmp_path):
    fleet, _, _ = make_fleet([ADBDevice("SERIAL-API-1234", "device")])
    fleet.refresh_once()
    install_fake_bridges(fleet)
    settings = Settings("pc-secret", None, "adb", tmp_path)
    runtime = DesktopRuntime(settings, fleet=fleet)
    app = create_desktop_app(settings, runtime)
    with TestClient(app) as client:
        assert client.get("/v1/fleet").status_code == 401
        headers = {"Authorization": "Bearer pc-secret"}
        response = client.get("/v1/fleet", headers=headers)
        assert response.status_code == 200
        assert len(response.json()["devices"]) == 1
        device_id = response.json()["devices"][0]["deviceId"]
        begin = client.post(f"/v1/devices/{device_id}/pair/begin", headers=headers)
        assert begin.status_code == 200
        qr_pending = client.post(
            f"/v1/devices/{device_id}/pair/qr/complete",
            headers=headers,
            json={"pairing_id": begin.json()["pairingId"]},
        )
        assert qr_pending.status_code == 200
        assert qr_pending.json()["pending"] is True
        assert client.post(
            f"/v1/devices/{device_id}/pair/complete",
            headers=headers,
            json={"code": "NOVA"},
        ).status_code == 422
        complete = client.post(
            f"/v1/devices/{device_id}/pair/complete",
            headers=headers,
            json={"pairing_id": begin.json()["pairingId"], "code": "nova"},
        )
        assert complete.status_code == 200
        assert complete.json()["paired"] is True
        assert client.post(
            f"/v1/devices/{device_id}/control",
            headers=headers,
            json={"kind": "shell"},
        ).status_code == 422
        with client.websocket_connect("/v1/fleet/events", headers=headers) as websocket:
            assert websocket.receive_json()["event"] == "FLEET_SNAPSHOT"
