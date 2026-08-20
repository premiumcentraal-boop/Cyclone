import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from cyclone_phone_mcp.gateway import GatewayClient, GatewayError


class Handler(BaseHTTPRequestHandler):
    token = "test-token"
    posts = []
    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer test-token":
            self.send_response(401); self.end_headers(); return
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
        self.wfile.write(json.dumps({"path": self.path}).encode())
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(length) or b"{}")
        Handler.posts.append((self.path, payload))
        if self.path == "/structured-error":
            self.send_response(409); self.send_header("Content-Type", "application/json"); self.end_headers()
            self.wfile.write(json.dumps({"error": {"code": "STALE_OBSERVATION", "layer": "protocol"}}).encode())
            return
        if self.path.endswith("/observe"):
            response = {"protocol_version": "cyclone.gateway.capability.v1", "correlation_id": payload["correlation_id"], "capability_id": "phone.observe", "ok": True, "transport": {"ok": True, "status": "connected"}, "witness": {"observation_id": "obs-7"}, "observation": {"pageKey": "home"}, "error": None}
        else:
            response = {"protocol_version": "cyclone.gateway.capability.v1", "correlation_id": payload["correlation_id"], "capability_id": payload["capability_id"], "ok": True, "transport": {"ok": True}, "execution": {"ok": True}, "verification": {"ok": True, "status": "verified"}, "error": None}
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
        self.wfile.write(json.dumps(response).encode())
    def log_message(self, *args):
        pass


class GatewayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True); cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close()

    def test_bearer_auth_and_query_encoding(self):
        result = GatewayClient(self.url, "test-token").ui_search("Apps & notifications")
        self.assertIn("q=Apps+%26+notifications", result["path"])

    def test_requires_token(self):
        with self.assertRaises(GatewayError):
            GatewayClient(self.url, "").status()

    def test_typed_action_uses_last_observation_and_never_forwards_authorization(self):
        client = GatewayClient(self.url, "test-token")
        client.observe()
        client.action("phone.click", {"selector": {"text": "Apps"}}, "Open Apps")
        path, payload = Handler.posts[-1]
        self.assertEqual("/v1/capabilities/action", path)
        self.assertEqual("obs-7", payload["expected_observation_id"])
        self.assertNotIn("user_authorized", payload)

    def test_mutating_action_before_observe_fails_locally_with_typed_stale_error(self):
        before = len(Handler.posts)
        client = GatewayClient(self.url, "test-token")
        with self.assertRaises(GatewayError) as raised:
            client.action("phone.home", {}, "Go home")
        self.assertEqual(before, len(Handler.posts))
        self.assertEqual("STALE_OBSERVATION", raised.exception.body["error"]["code"])
        self.assertEqual("PROTOCOL", raised.exception.body["error"]["layer"])

    def test_structured_non_200_body_is_preserved(self):
        with self.assertRaises(GatewayError) as raised:
            GatewayClient(self.url, "test-token")._request("POST", "/structured-error", {})
        self.assertEqual(409, raised.exception.status)
        self.assertEqual("STALE_OBSERVATION", raised.exception.body["error"]["code"])

    def test_rejects_non_loopback_gateway(self):
        with self.assertRaises(GatewayError):
            GatewayClient("https://example.com", "test-token")


if __name__ == "__main__":
    unittest.main()
