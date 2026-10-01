"""Plan 48 run 1: the Port Hub, end to end against the kit's real example plugins over real HTTP."""
from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from cyclone_device_gateway.ports import kit
from cyclone_device_gateway.ports.api import create_ports_router
from cyclone_device_gateway.ports.hub import KEY_HINT, PortHub, PortsError, normalize_endpoint

KIT = Path(__file__).resolve().parents[3] / "tools" / "cyclone-ports-sdk"
TOKEN = "t" * 32


def load_example(name: str):
    spec = importlib.util.spec_from_file_location(f"ports_example_{name}", KIT / "examples" / name / "plugin.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def hub(tmp_path):
    h = PortHub(tmp_path / "ports")
    yield h
    h.store.close()


@pytest.fixture
def logger(tmp_path):
    """The run logger, started without a key yet: it learns its key like an owner would set it."""
    holder = {}

    def start(key: str):
        server = load_example("logger").build(key, tmp_path / "logger-out").start()
        holder["server"] = server
        return server
    yield start
    if "server" in holder:
        holder["server"].stop()


def test_the_kit_is_found():
    assert kit is not None and kit.CONTRACT == "cyclone.ports/1"


def test_endpoint_rules():
    assert normalize_endpoint("127.0.0.1:8771/") == "http://127.0.0.1:8771"
    assert normalize_endpoint("http://localhost:8771/cyclone-plugin.json") == "http://localhost:8771"
    assert normalize_endpoint("https://plugins.example.com/cyclone/") == "https://plugins.example.com/cyclone"
    for bad in ("http://192.168.1.4:8771", "ftp://127.0.0.1", "file:///etc/passwd", "https://u:p@example.com",
                "http://127.0.0.1:8771?x=1", "", None):
        with pytest.raises(PortsError):
            normalize_endpoint(bad)


def test_add_check_and_go_live_like_an_owner(hub, logger, tmp_path):
    # 1. a plugin that isn't running yet
    with pytest.raises(PortsError, match="Nothing answered"):
        hub.preview("http://127.0.0.1:9")

    # 2. the plugin runs with a placeholder key; the owner looks it up
    first = logger("k9.placeholder")
    preview = hub.preview(first.url)
    assert preview["problems"] == [] and preview["remote"] is False and preview["alreadyAdded"] is None
    assert preview["manifest"]["name"] == "run-logger"
    assert {s["port"] for s in preview["serves"] if s["allowed"]} == {s["port"] for s in preview["serves"]}

    # 3. add it with every port allowed: the key comes back once
    added = hub.add(first.url, [s["port"] for s in preview["serves"]])
    key = added["key"]["value"]
    assert key.startswith("k1.") and key in added["key"]["powershell"] and key in added["key"]["bash"]
    assert added["plugin"]["status"] == "waiting_key"
    assert key not in json.dumps(hub.overview()) and key not in json.dumps(hub.plugin("run-logger"))

    # 4. checks before the plugin has its key: not live, and the hint says so
    result = hub.check("run-logger")["plugin"]
    assert result["status"] == "waiting_key" and result["checks"]["passed"] is False
    assert any(c["hint"] == KEY_HINT for c in result["checks"]["items"])

    # 5. the owner sets the key and restarts the plugin on the same port
    port = int(first.url.rsplit(":", 1)[1])
    first.stop()
    second = load_example("logger").build(key, tmp_path / "logger-out", port=port).start()
    try:
        live = hub.check("run-logger")["plugin"]
        assert live["status"] == "active", [c for c in live["checks"]["items"] if not c["ok"]]
        assert live["checks"]["failed"] == 0 and live["seenAt"]

        # 6. a signed test message lands in the plugin's log
        sent = hub.send_test("run-logger")
        assert sent["ok"] and sent["port"] == "log.line"
        lines = (tmp_path / "logger-out" / "runs.jsonl").read_text().splitlines()
        assert json.loads(lines[-1])["data"]["text"] == "Test from Cyclone Glass"

        overview = hub.overview()
        served = {c["port"]: c["servedBy"] for c in overview["catalog"]}
        assert served["log.line"] == ["run-logger"] and served["code.in"] == []
        assert overview["today"]["messages"] >= 1

        # 7. switching a port off removes it from the catalog's routing
        hub.set_port("run-logger", "log.line", False)
        assert {c["port"]: c["servedBy"] for c in hub.overview()["catalog"]}["log.line"] == []
        assert hub.send_test("run-logger")["port"] == "run.event"

        # 8. pause and resume
        assert hub.pause("run-logger", True)["plugin"]["status"] == "paused"
        with pytest.raises(PortsError):
            hub.send_test("run-logger")
        assert hub.pause("run-logger", False)["plugin"]["status"] == "active"

        # 9. a new key takes it out of service until it passes again with that key
        renewed = hub.new_key("run-logger")
        assert renewed["key"]["value"].startswith("k2.") and renewed["plugin"]["status"] == "waiting_key"
    finally:
        second.stop()

    # 10. the activity log is metadata only: no key anywhere in it
    activity = json.dumps(hub.store.activity(200))
    assert key not in activity and renewed["key"]["value"] not in activity
    assert hub.remove("run-logger") == {"removed": "run-logger"}
    assert hub.overview()["plugins"] == []


def test_monitor_marks_unreachable_and_recovers(hub, logger):
    server = logger("k1.placeholder")
    hub.add(server.url, [])
    for _ in range(2):
        hub.monitor_once()
    assert hub.store.plugin("run-logger")["health"] == "ok"
    port = int(server.url.rsplit(":", 1)[1])
    server.stop()
    hub.monitor_once()
    assert hub.store.plugin("run-logger")["health"] == "ok"      # one miss is not an outage
    hub.monitor_once()
    assert hub.store.plugin("run-logger")["health"] == "failing"
    again = load_example("logger").build("k1.placeholder", "/tmp/unused-logger", port=port).start()
    try:
        hub.monitor_once()
        assert hub.store.plugin("run-logger")["health"] == "ok"
    finally:
        again.stop()


def test_manifest_drift_needs_review_before_anything_flows(hub, tmp_path):
    from cyclone_ports import PluginServer

    manifest = {"contract": "cyclone.ports/1", "name": "notes", "version": "1.0.0", "endpoint": "http://127.0.0.1:1",
                "serves": [{"port": "log.line", "way": "out"}]}
    server = PluginServer(dict(manifest), "k1.x").start()
    try:
        added = hub.add(server.url, ["log.line"])
        assert added["plugin"]["remote"] is False
        server.manifest["version"] = "1.0.1"                    # a version bump alone is fine
        hub.monitor_once()
        assert hub.store.plugin("notes")["pending"] is None
        assert hub.plugin("notes")["plugin"]["version"] == "1.0.1"
        server.manifest["serves"] = [{"port": "log.line", "way": "out"}, {"port": "screen.shot", "way": "out"}]
        server.manifest["needs"] = {"personal": True}
        hub.monitor_once()
        plugin = hub.plugin("notes")["plugin"]
        assert plugin["status"] == "needs_review"
        assert plugin["pending"]["added"] == ["screen.shot"] and plugin["pending"]["needsPersonal"]
        hub.approve_changes("notes")
        plugin = hub.plugin("notes")["plugin"]
        assert plugin["pending"] is None
        allowed = {s["port"]: s["allowed"] for s in plugin["serves"]}
        assert allowed == {"log.line": True, "screen.shot": False}   # a new port starts switched off
    finally:
        server.stop()


def test_consent_defaults_are_stricter_for_remote_plugins(hub):
    manifest = {"contract": "cyclone.ports/1", "name": "crm", "version": "1.0.0", "endpoint": "https://crm.example",
                "serves": [{"port": "log.line", "way": "out"}, {"port": "account.fields", "way": "out"}],
                "needs": {"personal": True}}
    fake = lambda method, url, *a, **k: (200, manifest, 3)  # noqa: E731
    remote = PortHub(Path("/nonexistent"), store=hub.store, fetch=fake)
    preview = remote.preview("https://crm.example")
    assert preview["remote"] is True
    assert {s["port"]: s["allowed"] for s in preview["serves"]} == {"log.line": True, "account.fields": False}
    with pytest.raises(PortsError, match="isn't a port"):
        remote.add("https://crm.example", ["code.in"])


def test_routes(hub, logger):
    server = logger("k1.placeholder")
    app = FastAPI()
    app.include_router(create_ports_router(type("R", (), {"ports": hub})(), TOKEN))
    client = TestClient(app)
    auth = {"Authorization": f"Bearer {TOKEN}"}
    assert client.get("/v1/ports/overview").status_code == 401
    assert client.get("/v1/ports/overview", headers=auth).json()["contract"] == "cyclone.ports/1"
    answer = client.post("/v1/ports/plugins/preview", json={"endpoint": "ftp://x"}, headers=auth)
    assert answer.status_code == 400 and answer.json()["detail"]["code"] == "INVALID_REQUEST"
    preview = client.post("/v1/ports/plugins/preview", json={"endpoint": server.url}, headers=auth).json()
    added = client.post("/v1/ports/plugins", json={"endpoint": server.url, "allowed": ["log.line"]}, headers=auth)
    assert added.status_code == 200 and added.json()["key"]["value"].startswith("k1.")
    assert preview["manifest"]["name"] == "run-logger"
    checked = client.post("/v1/ports/plugins/run-logger/check", headers=auth).json()
    assert checked["plugin"]["checks"]["total"] > 5
    assert client.post("/v1/ports/plugins/run-logger/pause", json={"paused": True}, headers=auth).json()["plugin"]["paused"]
    assert client.post("/v1/ports/plugins/run-logger/ports", json={"port": "nope", "allowed": True},
                       headers=auth).status_code == 400
    assert client.post("/v1/ports/plugins/run-logger/delete", headers=auth).json() == {"removed": "run-logger"}
    unavailable = TestClient(_app_without_ports())
    assert unavailable.get("/v1/ports/overview", headers=auth).status_code == 503


def _app_without_ports() -> FastAPI:
    app = FastAPI()
    app.include_router(create_ports_router(type("R", (), {})(), TOKEN))
    return app
