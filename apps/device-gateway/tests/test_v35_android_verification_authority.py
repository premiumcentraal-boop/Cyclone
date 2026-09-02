from __future__ import annotations

from types import SimpleNamespace

import pytest

from cyclone_device_gateway.desktop_runtime.agent import DesktopAgentService


class AndroidBridge:
    def __init__(self, verification: dict):
        self.verification = verification
        self.observation = 0

    def request(self, op, args=None, request_id=None):
        if op == "action.execute":
            return {
                "execution": {"ok": True},
                "androidExecution": {"ok": True},
                "verification": self.verification,
            }
        if op == "observe.semantic":
            self.observation += 1
            return {
                "observationId": f"obs-{self.observation}",
                "pageKey": "HOME",
                "package": "com.android.launcher3",
                "pageTitle": "Home",
                "pageText": "Home",
                "pageSummary": "Home launcher",
            }
        raise AssertionError(op)


class OneDeviceFleet:
    def __init__(self, bridge: AndroidBridge):
        self.session = SimpleNamespace(
            credential="credential",
            bridge=lambda: bridge,
        )

    def get(self, device_id):
        assert device_id == "dev_test"
        return self.session


@pytest.mark.parametrize(
    ("android_verification", "expected_status"),
    [
        ({"ok": False, "status": "FAILED", "code": "VERIFICATION_FAILED"}, "FAILED"),
        ({"ok": True, "status": "OBSERVED", "semanticSuccessClaimed": False}, "OBSERVED"),
    ],
)
def test_desktop_never_promotes_android_failure_or_observation_to_verified(
    android_verification, expected_status
):
    service = DesktopAgentService(OneDeviceFleet(AndroidBridge(android_verification)))
    result = service.action(
        "dev_test",
        {
            "capability_id": "phone.home",
            "expected_observation_id": "obs-before",
        },
    )

    assert result["execution"]["androidExecution"]["ok"] is True
    assert result["verification"]["passed"] is False
    assert result["verification"]["status"] == expected_status
    assert result["verification"]["authority"] == "ANDROID_CANONICAL"


def test_desktop_accepts_only_android_passed_semantic_verification():
    service = DesktopAgentService(
        OneDeviceFleet(AndroidBridge({"ok": True, "status": "PASSED"}))
    )
    result = service.action(
        "dev_test",
        {
            "capability_id": "phone.home",
            "expected_observation_id": "obs-before",
        },
    )

    assert result["verification"]["passed"] is True
    assert result["verification"]["status"] == "PASSED"
