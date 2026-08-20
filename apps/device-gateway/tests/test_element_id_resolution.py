from pathlib import Path

from cyclone_device_gateway.actions.router import ActionRouter
from cyclone_device_gateway.auth import AuditLog
from cyclone_device_gateway.retrieval.service import RetrievalService
from cyclone_device_gateway.state.store import StateStore


class Bridge:
    def __init__(self):
        self.last_action = None
        self.page = "HOME"
        self.seq = 0

    def semantic(self):
        self.seq += 1
        obs = f"obs-{self.seq}"
        return {
            "observationId": obs,
            "pageKey": self.page,
            "package": "com.test",
            "activity": "Main",
            "semanticControls": [
                {
                    "elementId": f"semantic:{obs}:apps",
                    "label": "Apps",
                    "resourceId": "android:id/apps",
                    "role": "button",
                    "clickable": True,
                }
            ],
            "rawAccessibility": {"nodes": []},
        }

    def request(self, op, args=None):
        if op == "action.execute":
            self.last_action = args
            self.page = "APPS"
            return {"execution": {"ok": True, "beforeFingerprint": "a", "afterFingerprint": "b", "error": None}}
        return self.semantic()


def test_observation_element_id_is_resolved_before_new_before_observation(tmp_path: Path):
    bridge = Bridge()
    store = StateStore(tmp_path / "db.sqlite")

    initial = bridge.semantic()
    store.add_observation(initial)
    element_id = initial["semanticControls"][0]["elementId"]

    def observe():
        semantic = bridge.semantic()
        oid = store.add_observation(semantic)
        return {**store.get_observation(oid), "device_serial": "PIXEL8"}

    router = ActionRouter(
        bridge,
        store,
        AuditLog(tmp_path / "audit.jsonl"),
        observe,
        stabilize=lambda: None,
    )
    result = router.execute(
        tool="phone.click",
        params={"elementId": element_id},
        goal="Open Apps",
    )

    assert result["success"] is True
    forwarded = bridge.last_action["params"]
    assert "elementId" not in forwarded
    assert forwarded["selector"]["resourceId"] == "android:id/apps"
    assert forwarded["selector"]["clickable"] is True
