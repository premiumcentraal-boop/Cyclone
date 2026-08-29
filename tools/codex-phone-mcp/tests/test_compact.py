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

    def test_typed_observation_retains_correlation_and_witness(self):
        result = compact_observation({
            "correlation_id": "corr-1",
            "witness": {"observation_id": "obs-1", "gateway_record_id": "record-1"},
            "observation": {"pageKey": "home", "controls": []},
        })
        self.assertEqual("corr-1", result["correlationId"])
        self.assertEqual("obs-1", result["witness"]["observation_id"])

    def test_page_card_preserves_meaningful_context_and_goal_ranks_candidates(self):
        result = compact_observation({
            "correlation_id": "corr-2",
            "witness": {"observation_id": "obs-2"},
            "observation": {
                "page": {
                    "package": "com.android.settings",
                    "activity": "Settings",
                    "title": "Apps",
                    "pageKey": "settings/apps",
                    "location": "Settings > Apps",
                    "pageText": "Apps. Recently opened apps and notifications.",
                    "pageSummary": "App settings list",
                    "controls": [
                        {"id": "nav", "label": "Network", "clickable": True},
                        {"id": "apps", "label": "Apps", "resourceId": "android:id/apps", "clickable": True},
                    ],
                },
                "counts": {"raw": 2200, "semantic": 80, "agent": 30},
            },
        }, goal="Open Apps")
        self.assertEqual("page_card", result["kind"])
        self.assertEqual("obs-2", result["observationScope"]["id"])
        self.assertEqual("Settings > Apps", result["location"]["location"])
        self.assertEqual("Apps", result["location"]["title"])
        self.assertIn("Recently opened", result["pageText"])
        self.assertEqual("App settings list", result["pageSummary"])
        self.assertEqual("apps", result["candidates"]["goalRanked"][0]["elementId"])
        self.assertTrue(result["truncated"]["rawTreeExcluded"])


if __name__ == "__main__":
    unittest.main()
