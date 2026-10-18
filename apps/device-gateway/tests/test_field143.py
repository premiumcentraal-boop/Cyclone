"""Alpha 144: field test 143's PC-side findings (B6 busy reason, B10 stale page), as tests."""
from types import SimpleNamespace

import pytest

from cyclone_device_gateway.cyclone_bridge.client import BridgeOperationError
from cyclone_device_gateway.desktop_runtime import agent as agent_module
from cyclone_device_gateway.desktop_runtime.agent import DesktopAgentService
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from cyclone_device_gateway.desktop_runtime.v5_contract import V5ContractService
from test_v5_contract import FOREGROUND, AskBridge, FakeFleet


class BusyBridge(AskBridge):
    def request(self, op, args, request_id=None):
        if op == "ask.start":
            raise BridgeOperationError("ASK_BUSY", 'The phone is running another task: "Open my profile". Try again when it ends.\x07')
        return super().request(op, args, request_id)


def test_a_busy_phone_says_what_is_in_the_way_and_may_be_retried():
    svc = V5ContractService(FakeFleet(BusyBridge()))
    with pytest.raises(DesktopRuntimeError) as error:
        svc.forward("phone-1", "ask.start", {"goal": "open clock", **FOREGROUND})
    assert error.value.code == "ASK_BUSY"
    assert "Open my profile" in error.value.safe_message and "Try again" in error.value.safe_message
    assert "\x07" not in error.value.safe_message
    assert error.value.retryable is True


def test_the_phone_reason_is_bounded_and_printable():
    long = BridgeOperationError("ASK_BUSY", "x" * 1000 + "\n\tend")
    assert len(long.message) == BridgeOperationError.MAX_MESSAGE
    assert BridgeOperationError("ASK_BUSY").message is None
    assert BridgeOperationError("ASK_BUSY", "a\nb\tc").message == "a b c"


class PageBridge:
    def __init__(self):
        self.reads = 0

    def request(self, op, args=None, request_id=None):
        if op == "observe.semantic":
            self.reads += 1
            return {"observationId": f"obs-{self.reads}", "pageKey": f"page-{self.reads}", "package": "com.instagram.android",
                    "pageText": {"protocol": "cyclone-page-text-v1", "lines": [{"text": "Home"}]},
                    "pageSummary": {"protocol": "cyclone-page-summary-v1", "title": "Home"}}
        raise AssertionError(op)


def _agent(bridge):
    session = SimpleNamespace(credential="paired", bridge_ok=True, last_heartbeat_ms=1, reconnect_attempts=0,
                              next_reconnect_at_ms=None, bridge_last_error=None, bridge_error_class=None,
                              bridge=lambda token=None, auto_forward=False: bridge)
    fleet = SimpleNamespace(get=lambda device_id: session, record_bridge_status=lambda *_: None)
    return DesktopAgentService(fleet)


def test_the_current_page_is_read_again_when_its_record_is_old(monkeypatch):
    # Field test 143: the Settings page came back while Instagram was open (the phone's own runs never update it).
    bridge = PageBridge()
    agent = _agent(bridge)
    now = [1_000_000]
    monkeypatch.setattr(agent_module, "now_ms", lambda: now[0])
    agent.observe("dev")
    fresh = agent.current_page("dev")
    assert bridge.reads == 1 and fresh["stale"] is False and fresh["page"]["pageKey"] == "page-1"
    now[0] += agent.CURRENT_PAGE_FRESH_MS + 1
    again = agent.current_page("dev")
    assert bridge.reads == 2 and again["stale"] is False and again["ageMs"] == 0
