import unittest
import tempfile

from cyclone_phone_mcp.mcp_server import McpServer
from cyclone_phone_mcp.reports import SessionRecorder
from cyclone_phone_mcp.tools import PhoneTools


class FakePhoneTools:
    def __init__(self): self.last_call_failed = False
    def call(self, name, arguments): return [{"type": "text", "text": "{}"}]


class ObserveGateway:
    def __init__(self, response): self.response = response
    def observe(self, **_): return self.response


class McpProtocolTests(unittest.TestCase):
    def setUp(self): self.server = McpServer(FakePhoneTools())

    def test_initialize_echoes_protocol_and_instructions(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}})
        self.assertEqual(response["result"]["protocolVersion"], "2025-06-18")
        self.assertIn("semantic-first", response["result"]["instructions"])

    def test_tool_list_has_no_shell(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        names = {t["name"] for t in response["result"]["tools"]}
        self.assertIn("phone_act", names)
        self.assertFalse(any("shell" in name or "root" in name or "adb" in name for name in names))

    def test_unknown_tool_rejected(self):
        response = self.server.handle({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "root_shell", "arguments": {}}})
        self.assertIn("error", response)

    def test_tools_call_uses_canonical_failure_flag_not_error_substring(self):
        self.server.phone_tools.last_call_failed = True
        response = self.server.handle({"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "phone_status", "arguments": {}}})
        self.assertTrue(response["result"]["isError"])
        self.server.phone_tools.last_call_failed = False
        response = self.server.handle({"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "phone_status", "arguments": {}}})
        self.assertFalse(response["result"]["isError"])

    def test_compact_observe_failure_and_protocol_mismatch_are_mcp_errors(self):
        responses = (
            {
                "protocol_version": "cyclone.gateway.capability.v1",
                "capability_id": "phone.observe",
                "ok": False,
                "transport": {"ok": False, "error": {"code": "DEVICE_DISCONNECTED", "layer": "TRANSPORT"}},
                "error": {"code": "DEVICE_DISCONNECTED", "layer": "TRANSPORT"},
            },
            {
                "protocol_version": "future.protocol.v9",
                "capability_id": "phone.observe",
                "ok": False,
                "transport": {"ok": True},
                "error": {"code": "PROTOCOL_MISMATCH", "layer": "PROTOCOL"},
            },
        )
        with tempfile.TemporaryDirectory() as report_dir:
            for index, raw in enumerate(responses):
                server = McpServer(PhoneTools(ObserveGateway(raw), SessionRecorder(report_dir)))
                response = server.handle({
                    "jsonrpc": "2.0",
                    "id": 20 + index,
                    "method": "tools/call",
                    "params": {"name": "phone_observe", "arguments": {"mode": "compact"}},
                })
                self.assertTrue(response["result"]["isError"])
                self.assertIn(raw["error"]["code"], response["result"]["content"][0]["text"])


if __name__ == "__main__": unittest.main()
