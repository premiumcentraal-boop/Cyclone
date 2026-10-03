"""Plan 51 K3 (alpha.105): the PC sees approved phone connectors and their selector entries, nothing else."""
from __future__ import annotations

import pytest

from cyclone_device_gateway.cyclone_bridge.protocol import ALLOWED_OPS
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from cyclone_device_gateway.desktop_runtime.v5_contract import V5ContractService

from test_v5_contract import FakeBridge, FakeFleet

GOOD = {"connectors": [{"id": "acme-profiles", "label": "Acme Profiles", "entries": [
    {"id": "work-cloud", "type": "acme.cloud", "label": "Cloud work", "subtitle": "3 devices", "state": "attention", "text": "Sign in again"}]}]}


class ConnectorsBridge(FakeBridge):
    def __init__(self, result=None, error=None):
        super().__init__()
        self.result, self.error = result, error

    def request(self, op, args, request_id=None):
        if op == "connectors.list":
            if self.error:
                from cyclone_device_gateway.cyclone_bridge.client import BridgeOperationError
                raise BridgeOperationError(self.error)
            return self.result
        return super().request(op, args, request_id)


def test_connectors_are_read_and_checked():
    assert "connectors.list" in ALLOWED_OPS
    got = V5ContractService(FakeFleet(ConnectorsBridge(GOOD))).connectors_list("phone-1")
    assert got == {**GOOD, "supported": True}
    entry = GOOD["connectors"][0]["entries"][0]
    for bad in [
        {},
        {**GOOD, "ext": {}},
        {"connectors": [{**GOOD["connectors"][0], "package": "com.acme"}]},
        {"connectors": [{**GOOD["connectors"][0], "id": "Acme"}]},
        {"connectors": [{**GOOD["connectors"][0], "entries": [{**entry, "ext": {"tier": "gold"}}]}]},
        {"connectors": [{**GOOD["connectors"][0], "entries": [{**entry, "state": "on"}]}]},
        {"connectors": [{**GOOD["connectors"][0], "entries": [{**entry, "label": "x" * 41}]}]},
        {"connectors": [{**GOOD["connectors"][0], "entries": [entry] * 9}]},
        {"connectors": [GOOD["connectors"][0]] * 17},
    ]:
        with pytest.raises(DesktopRuntimeError) as error:
            V5ContractService(FakeFleet(ConnectorsBridge(bad))).connectors_list("phone-1")
        assert error.value.code == "PROTOCOL_MISMATCH"


def test_an_older_phone_means_no_connectors_not_an_error():
    got = V5ContractService(FakeFleet(ConnectorsBridge(error="UNKNOWN_OPERATION"))).connectors_list("phone-1")
    assert got == {"connectors": [], "supported": False}
