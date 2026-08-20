import unittest

from cyclone_phone_mcp.mcp_server import McpServer


class FakePhoneTools:
    def call(self, name, arguments): return [{"type": "text", "text": "{}"}]


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


if __name__ == "__main__": unittest.main()
