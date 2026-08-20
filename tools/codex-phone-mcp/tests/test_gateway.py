import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from cyclone_phone_mcp.gateway import GatewayClient, GatewayError


class Handler(BaseHTTPRequestHandler):
    token = "test-token"
    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer test-token":
            self.send_response(401); self.end_headers(); return
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
        self.wfile.write(json.dumps({"path": self.path}).encode())
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


if __name__ == "__main__":
    unittest.main()
