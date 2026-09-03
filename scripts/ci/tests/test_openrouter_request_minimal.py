import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt"


class OpenRouterMinimalRequestTest(unittest.TestCase):
    def test_page_chat_sends_only_model_and_messages(self):
        source = SOURCE.read_text(encoding="utf-8")
        start = source.index("private fun pageChat(")
        body_start = source.index("val body = JSONObject()", start)
        request_start = source.index("val request = Request.Builder()", body_start)
        body = source[body_start:request_start]

        puts = re.findall(r'\.put\("([^"]+)"', body)
        self.assertEqual(["model", "messages"], puts)
        for forbidden in (
            "max_tokens",
            "max_completion_tokens",
            "temperature",
            "reasoning",
            "response_format",
            "provider",
            "require_parameters",
            "stream",
        ):
            self.assertNotIn(forbidden, body)


if __name__ == "__main__":
    unittest.main()
