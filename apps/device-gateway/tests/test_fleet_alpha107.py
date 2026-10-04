"""Alpha 107: the fleet works with real phones, has no phone limit, and the review's defects stay fixed.

The Command Center and SQLite are real; the phone RPC is the harness from test_fleet_orchestrator.
"""
import tempfile
import unittest
from pathlib import Path

from cyclone_device_gateway.command.center import CommandCenter
from cyclone_device_gateway.desktop_runtime.fleet_command import KnownDevice, split_command
from cyclone_device_gateway.desktop_runtime.fleet_orchestrator import FleetError, FleetOrchestrator, _request_id
from cyclone_device_gateway.desktop_runtime.fleet_store import FleetStore
from cyclone_device_gateway.desktop_runtime.workspace import FleetWorkspaceStore
from tests.test_fleet_orchestrator import Clock, FakePhones, paired


class Base(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = Path(tempfile.mkdtemp())
        self.phones = FakePhones()
        self.clock = Clock()
        self.devices = [paired("dev_a", "CPH2717"), paired("dev_b", "SM-X710")]
        self.nicknames = {"dev_a": "Work Phone", "dev_b": "Tablet"}
        self.colors: dict[str, str] = {}
        self.center = CommandCenter(self.dir / "cc" / "command.db", self.phones, lambda: list(self.devices), clock=self.clock)
        self.center.request_tick = self.center.tick
        self.fleet = FleetOrchestrator(self.center, lambda: list(self.devices), lambda: dict(self.nicknames),
                                       self.dir / "missions.json", clock=self.clock, colors=lambda: dict(self.colors))

    def tearDown(self) -> None:
        self.fleet.close()
        self.center.stop()


class RealPhoneStateTests(Base):
    def test_the_command_center_starts_work_on_a_phone_the_gateway_calls_READY(self):
        # DeviceSession.public() says "READY". Before alpha.107 the Command Center only matched "ready", so a real
        # phone never got its task.
        self.devices = [paired("dev_a", "CPH2717", state="READY")]
        task = self.center.create_task({"goal": "open the camera", "deviceId": "dev_a"})
        self.center.tick()
        self.assertEqual(self.center.get_task(task["id"])["status"], "running")
        self.assertEqual(self.phones.started, [("dev_a", "open the camera")])

    def test_a_ready_phone_is_counted_ready_and_never_shown_as_waiting(self):
        overview = self.fleet.overview()
        self.assertEqual(overview["counts"]["ready"], 2)
        self.assertEqual({r["presence"] for r in overview["phones"]}, {"ready"})
        mission = self.fleet.run_command("open Maps on Tablet")["mission"]
        self.assertEqual(mission["phones"][0]["state"], "RUNNING")
        self.assertEqual(mission["phones"][0]["hint"], "")


class NoPhoneLimitTests(Base):
    def test_one_mission_reaches_every_phone_it_names(self):
        self.devices = [paired(f"dev_{i:03d}", f"Phone {i}") for i in range(60)]
        self.nicknames = {}
        mission = self.fleet.dispatch([{"deviceId": d["deviceId"], "label": d["name"], "goal": "open mail"} for d in self.devices])
        self.assertEqual(len(mission["phones"]), 60)
        self.assertEqual(len(self.phones.started), 60)
        self.assertIsNone(self.fleet.health()["phoneLimit"])
        self.assertIsNone(self.fleet.overview()["phoneLimit"])

    def test_a_page_of_missions_reads_tasks_in_one_batch(self):
        self.devices = [paired(f"dev_{i:03d}", f"Phone {i}") for i in range(20)]
        for _ in range(5):
            self.fleet.dispatch([{"deviceId": d["deviceId"], "label": d["name"], "goal": "open mail"} for d in self.devices])
        calls = {"get": 0, "batch": 0}
        get_task, task_states = self.center.get_task, self.center.task_states

        def counted_get(task_id):
            calls["get"] += 1
            return get_task(task_id)

        def counted_batch(ids):
            calls["batch"] += 1
            return task_states(ids)

        self.center.get_task, self.center.task_states = counted_get, counted_batch
        page = self.fleet.missions_page(20)
        self.assertEqual(len(page["missions"]), 5)
        self.assertEqual(calls, {"get": 0, "batch": 1})

    def test_the_batch_read_agrees_with_one_task_at_a_time(self):
        mission = self.fleet.run_command("open Gmail on Work Phone, open Maps on Tablet")["mission"]
        self.phones.finish(self.phones.mission_for("dev_a", "Open Gmail"), summary="3 unread")
        self.center.tick()
        ids = [p["taskId"] for p in mission["phones"]]
        batch = self.center.task_states(ids + ["no-such-task"])
        self.assertNotIn("no-such-task", batch)
        for task_id in ids:
            single = self.center.get_task(task_id)
            self.assertEqual(batch[task_id]["status"], single["status"])
            self.assertEqual(batch[task_id]["run"]["summary"], single["run"]["summary"])


class RolloutTests(Base):
    def test_a_rollout_checks_every_phone_before_starting_the_canary(self):
        with self.assertRaises(FleetError) as dup:
            self.fleet.rollout([
                {"deviceId": "dev_a", "label": "Work Phone", "goal": "open mail"},
                {"deviceId": "dev_b", "label": "Tablet", "goal": "open mail"},
                {"deviceId": "dev_b", "label": "Tablet", "goal": "open camera"},
            ], canary=1)
        self.assertEqual(dup.exception.code, "INVALID_REQUEST")
        self.assertEqual(self.phones.started, [])
        self.assertEqual(self.center.list_tasks(status="open"), [])

    def test_a_stopped_canary_does_not_release_the_rest(self):
        rolled = self.fleet.rollout([
            {"deviceId": "dev_a", "label": "Work Phone", "goal": "open mail"},
            {"deviceId": "dev_b", "label": "Tablet", "goal": "open mail"},
        ], canary=1)
        self.fleet.cancel_mission(rolled["mission"]["missionId"])
        with self.assertRaises(FleetError) as failed:
            self.fleet.continue_rollout(rolled["mission"]["missionId"])
        self.assertEqual(failed.exception.code, "CANARY_FAILED")


class DispatchSafetyTests(Base):
    def test_one_phone_crashing_does_not_sink_the_others_or_leave_a_hidden_task(self):
        create = self.center.create_task

        def flaky(body, **kwargs):
            if body["deviceId"] == "dev_b":
                raise RuntimeError("disk hiccup")
            return create(body, **kwargs)

        self.center.create_task = flaky
        mission = self.fleet.run_command("open Gmail on Work Phone, open Maps on Tablet")["mission"]
        by_label = {p["label"]: p for p in mission["phones"]}
        self.assertEqual(by_label["Work Phone"]["state"], "RUNNING")
        self.assertEqual(by_label["Tablet"]["state"], "FAILED")
        self.assertIn("couldn't start", by_label["Tablet"]["cause"])
        open_ids = {t["id"] for t in self.center.list_tasks(status="open")}
        self.assertEqual(open_ids, {by_label["Work Phone"]["taskId"]})       # every open task is in a mission

    def test_if_the_mission_cannot_be_saved_its_tasks_are_stopped(self):
        def broken_save(_mission):
            raise RuntimeError("disk full")

        self.fleet._store.save = broken_save
        with self.assertRaises(RuntimeError):
            self.fleet.dispatch([{"deviceId": "dev_a", "label": "Work Phone", "goal": "open mail"}])
        self.assertEqual(self.center.list_tasks(status="open"), [])

    def test_long_ids_still_make_valid_request_ids(self):
        key = _request_id("flt_" + "x" * 40, "rollout-" + "y" * 60)
        self.assertRegex(key, r"^[A-Za-z0-9_-]{8,80}$")
        self.assertEqual(key, _request_id("flt_" + "x" * 40, "rollout-" + "y" * 60))


class RetryAndSpendTests(Base):
    def test_retry_leaves_a_phone_the_owner_stopped_alone(self):
        mission = self.fleet.run_command("open Gmail on Work Phone, open Maps on Tablet")["mission"]
        self.phones.finish(self.phones.mission_for("dev_a", "Open Gmail"), status="failed", summary="no network")
        self.center.tick()
        self.center.cancel_task(mission["phones"][1]["taskId"])
        result = self.fleet.retry_failed(mission["missionId"])
        self.assertEqual(result["retried"], 1)
        self.assertEqual(self.phones.started.count(("dev_b", "Open Maps")), 1)

    def test_the_spend_cap_counts_every_attempt(self):
        mission = self.fleet.run_command("open Gmail on Work Phone")["mission"]
        self.phones.finish(self.phones.mission_for("dev_a", "Open Gmail"), status="failed")
        self.center.tick()
        self.fleet.retry_failed(mission["missionId"])
        self.center.tick()                                   # the retry's run reports its own cost
        stored = self.fleet._lookup(mission["missionId"])
        self.assertAlmostEqual(stored["assignments"][0]["priorCostUsd"], 0.01)
        export = self.fleet.export_mission(mission["missionId"])
        self.assertAlmostEqual(export["costUsd"], 0.02)


class OverviewTests(Base):
    def test_each_phone_carries_its_name_colour_model_root_and_health(self):
        self.colors = {"dev_a": "teal"}
        self.devices[0] = paired("dev_a", "CPH2717", health={
            "version": 1, "batteryPercent": 81, "charging": True, "network": "wifi", "os": "Android 15",
            "model": "Pixel 8 Pro", "manufacturer": "Google", "root": {"rooted": True, "verified": False, "signals": ["magisk"]},
        })
        rows = {r["deviceId"]: r for r in self.fleet.overview()["phones"]}
        a, b = rows["dev_a"], rows["dev_b"]
        self.assertEqual((a["label"], a["color"], a["model"], a["manufacturer"], a["root"]),
                         ("Work Phone", "teal", "Pixel 8 Pro", "Google", "ROOTED"))
        self.assertEqual(a["health"], {"batteryPercent": 81, "charging": True, "network": "wifi", "freeStorageMb": None})
        self.assertEqual((b["color"], b["root"], b["health"]), (None, "UNKNOWN", None))     # an older phone says nothing
        self.assertEqual(self.fleet.overview()["counts"]["rooted"], 1)

    def test_presence_reads_the_gateways_words(self):
        self.devices = [
            paired("p1", "A"), paired("p2", "B", state="SLEEPING"), paired("p3", "C", state="DISCONNECTED"),
            paired("p4", "D", state="ATTENTION"), {**paired("p5", "E"), "paired": False, "state": "UNPAIRED"},
        ]
        self.nicknames = {}
        got = {r["deviceId"]: r["presence"] for r in self.fleet.overview()["phones"]}
        self.assertEqual(got, {"p1": "ready", "p2": "asleep", "p3": "offline", "p4": "attention", "p5": "unpaired"})


class ParserTests(unittest.TestCase):
    def test_then_is_never_run_without_the_owner_seeing_it_runs_at_once(self):
        phones = [KnownDevice("dev_a", "Work Phone", ("work phone",), True), KnownDevice("dev_b", "Tablet", ("tablet",), True)]
        plan = split_command("open maps on Work Phone then check the weather on Tablet", phones)
        self.assertTrue(plan.ok)
        self.assertTrue(plan.needs_confirmation)
        self.assertTrue(any("then" in note for note in plan.notes))


class StoreAndWorkspaceTests(unittest.TestCase):
    def test_a_corrupt_database_takes_its_journal_files_with_it(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "fleet.db"
            path.write_text("this is not sqlite", encoding="utf-8")
            Path(f"{path}-wal").write_bytes(b"stale")
            Path(f"{path}-shm").write_bytes(b"stale")
            store = FleetStore(path, clock=lambda: 1)
            store.save({"missionId": "flt_new", "command": "ok", "createdAt": 1, "notes": [], "assignments": [{"deviceId": "a"}]})
            self.assertIsNotNone(store.get("flt_new"))
            store.close()
            # The fresh database never inherits the broken one's journal.
            for suffix in ("-wal", "-shm"):
                journal = Path(f"{path}{suffix}")
                self.assertFalse(journal.is_file() and journal.read_bytes() == b"stale")

    def test_a_phone_colour_is_one_of_the_palette_and_survives_a_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "workspace.json"
            store = FleetWorkspaceStore(path)
            self.assertEqual(store.set_color("dev_a", "Teal")["color"], "teal")
            with self.assertRaises(ValueError):
                store.set_color("dev_a", "#ff0000")
            self.assertEqual(FleetWorkspaceStore(path).color_map(), {"dev_a": "teal"})
            store.set_color("dev_a", None)
            self.assertEqual(FleetWorkspaceStore(path).color_map(), {})


class RealSessionTests(unittest.TestCase):
    """The gateway's own DeviceSession objects, not hand-written dicts."""

    def session(self, **extra):
        from cyclone_device_gateway.adb.client import ADBDevice
        from cyclone_device_gateway.desktop_runtime.fleet import DeviceSession
        from cyclone_device_gateway.desktop_runtime.models import DeviceFleetState

        device = ADBDevice(serial="ABC123", state="device", model="Pixel_8", device="shiba", product="shiba", transport_id="1")
        return DeviceSession(device_id="dev1", serial="ABC123", adb_device=device, adb=None, local_port=18001,
                             usb_session_id="u", state=DeviceFleetState.READY, credential="tok", accessibility_connected=True, **extra)

    def test_a_real_ready_phone_gets_its_task(self):
        live = self.session()
        phones = FakePhones()
        center = CommandCenter(Path(tempfile.mkdtemp()) / "cc.db", phones, lambda: [live.public()], clock=Clock())
        try:
            task = center.create_task({"goal": "open the camera", "deviceId": "dev1"})
            center.tick()
            self.assertEqual(center.get_task(task["id"])["status"], "running")
        finally:
            center.stop()

    def test_the_phone_root_report_is_kept_to_known_words(self):
        import threading

        from cyclone_device_gateway.desktop_runtime.fleet import DeviceFleetManager

        live = self.session()
        manager = DeviceFleetManager.__new__(DeviceFleetManager)
        manager._lock = threading.RLock()
        manager.record_bridge_status(live, {"accessibilityConnected": True, "fleetHealth": {
            "model": "Pixel 8", "manufacturer": "Google",
            "root": {"rooted": True, "verified": False, "signals": ["magisk", "<script>", 7]},
        }})
        health = live.public()["health"]
        self.assertEqual(health["manufacturer"], "Google")
        self.assertEqual(health["root"], {"rooted": True, "verified": False, "signals": ["magisk"]})
        manager.record_bridge_status(live, {"fleetHealth": {"model": "Pixel 8", "root": "yes"}})
        self.assertIsNone(live.public()["health"]["root"])          # not a report: unknown, never a guess


if __name__ == "__main__":
    unittest.main()
