import unittest

from cyclone_phone_mcp.compact import compact_observation, redact


class CompactTests(unittest.TestCase):
    def test_caps_controls_and_preserves_counts(self):
        payload = {"pageKey": "p", "rawNodeCount": 2500, "semanticControlCount": 80, "agentControlCount": 36, "controls": [{"id": str(i), "label": f"C{i}"} for i in range(80)]}
        result = compact_observation(payload)
        self.assertEqual(len(result["controls"]), 12)
        self.assertEqual(result["counts"]["raw"], 2500)
        self.assertEqual(result["counts"]["semantic"], 80)
        self.assertEqual(result["counts"]["agent"], 36)

    def test_redacts_secret_keys(self):
        self.assertEqual(redact({"password": "x", "otp_code": "123456"}), {"password": "<redacted>", "otp_code": "<redacted>"})


if __name__ == "__main__":
    unittest.main()
