import unittest

from cyclone_phone_mcp.compact import (
    PAGE_SUMMARY_SCHEMA,
    PAGE_TEXT_SCHEMA,
    compact_observation,
    redact,
)


def _apps_payload(**overrides):
    payload = {
        "package": "com.android.settings",
        "activity": ".Settings",
        "pageKey": "settings::root",
        "title": "Settings",
        "pageText": "Settings. Network & internet. Apps. Notifications. Battery.",
        "pageSummary": "Android Settings categories",
        "rawNodeCount": 2500,
        "semanticControlCount": 80,
        "agentControlCount": 36,
        "controls": [
            {"id": "network", "label": "Network & internet", "role": "button", "clickable": True},
            {"id": "apps", "label": "Apps", "resourceId": "android:id/apps", "clickable": True},
            {"id": "battery", "label": "Battery", "role": "button", "clickable": True},
        ],
    }
    payload.update(overrides)
    return payload


class CompactTests(unittest.TestCase):
    def test_truncates_controls_and_preserves_counts_without_fixed_twelve(self):
        payload = {
            "pageKey": "p",
            "rawNodeCount": 2500,
            "semanticControlCount": 80,
            "agentControlCount": 36,
            "controls": [{"id": str(i), "label": f"C{i}", "clickable": True} for i in range(80)],
        }
        result = compact_observation(payload)
        compact_n = len(result["controls"])
        self.assertGreater(compact_n, 0)
        self.assertLess(compact_n, 80)
        self.assertNotEqual(compact_n, 80, "compact must not dump the full semantic list")
        self.assertEqual(result["counts"]["raw"], 2500)
        self.assertEqual(result["counts"]["semantic"], 80)
        self.assertEqual(result["counts"]["agent"], 36)
        self.assertEqual(result["counts"]["compact"], compact_n)
        self.assertGreater(result["counts"]["semantic"], result["counts"]["compact"])
        self.assertTrue(result.get("nextCursor") or result.get("truncated"))
        # Product contract is a page card window, not "exactly 12 controls".
        self.assertIn("pageText", result)
        self.assertIn("pageSummary", result)

    def test_redacts_secret_keys(self):
        self.assertEqual(
            redact({"password": "x", "otp_code": "123456"}),
            {"password": "<redacted>", "otp_code": "<redacted>"},
        )

    def test_typed_observation_retains_correlation_and_witness(self):
        result = compact_observation({
            "correlation_id": "corr-1",
            "witness": {"observation_id": "obs-1", "gateway_record_id": "record-1"},
            "observation": {"pageKey": "home", "controls": []},
        })
        self.assertEqual("corr-1", result["correlationId"])
        self.assertEqual("obs-1", result["witness"]["observation_id"])
        self.assertEqual("page_card", result["kind"])
        self.assertEqual("home", result["pageKey"])

    def test_page_text_and_summary_present_after_compact(self):
        result = compact_observation(_apps_payload())
        self.assertEqual(result["pageTextSchema"], PAGE_TEXT_SCHEMA)
        self.assertEqual(result["pageSummarySchema"], PAGE_SUMMARY_SCHEMA)
        self.assertIn("pageText", result)
        self.assertIn("pageSummary", result)
        self.assertIn("Apps", result["pageText"])
        self.assertEqual("Android Settings categories", result["pageSummary"])
        self.assertEqual("com.android.settings", result["package"])
        self.assertEqual(".Settings", result["activity"])
        self.assertEqual("settings::root", result["pageKey"])
        self.assertEqual("Settings", result["title"])

    def test_page_text_and_summary_survive_when_absent_from_input(self):
        result = compact_observation({
            "package": "com.android.settings",
            "pageKey": "settings::apps",
            "title": "Apps",
            "controls": [{"id": "all-apps", "label": "See all apps", "clickable": True}],
        })
        self.assertIn("pageText", result)
        self.assertIn("pageSummary", result)
        self.assertIsInstance(result["pageText"], str)
        self.assertIsInstance(result["pageSummary"], str)
        self.assertIn("Apps", result["pageText"])
        self.assertTrue(result["pageSummary"])

    def test_password_fields_not_in_page_text(self):
        result = compact_observation({
            "pageKey": "login",
            "title": "Sign in",
            "pageText": "Email user@example.com Password hunter2 OTP 991122",
            "pageSummary": "Login form hunter2",
            "controls": [
                {"id": "email", "label": "Email", "text": "user@example.com", "clickable": True},
                {
                    "id": "pw",
                    "label": "Password",
                    "password": True,
                    "text": "hunter2",
                    "text_value": "hunter2",
                    "clickable": True,
                },
                {"id": "otp", "label": "OTP", "role": "otp", "text": "991122", "clickable": True},
            ],
        })
        blob = str(result["pageText"]) + str(result["pageSummary"]) + str(result.get("pageTextNote") or "")
        self.assertNotIn("hunter2", blob)
        self.assertNotIn("991122", blob)
        self.assertNotIn("hunter2", str(result["controls"]))
        self.assertIn("pageText", result)
        self.assertIn("pageSummary", result)
        self.assertIn("Email", result["pageText"])
        self.assertTrue(result["pageSummary"])

    def test_goal_apps_ranks_apps_above_unrelated_widgets(self):
        controls = [{"id": f"other-{i}", "label": f"Unrelated {i}", "clickable": True} for i in range(20)]
        controls.append({"id": "apps", "label": "Apps", "resourceId": "android:id/apps", "clickable": True})
        result = compact_observation(
            _apps_payload(controls=controls, semanticControlCount=21, agentControlCount=21),
            goal="Apps",
        )
        self.assertEqual("apps", result["controls"][0].get("id") or result["controls"][0].get("elementId"))
        self.assertGreater(result["controls"][0].get("goalScore", 0), 0)
        labels = [str(item.get("label")) for item in result["controls"]]
        self.assertLess(labels.index("Apps"), labels.index("Unrelated 0"))

    def test_counts_preserved(self):
        result = compact_observation(_apps_payload())
        self.assertEqual(result["counts"]["raw"], 2500)
        self.assertEqual(result["counts"]["semantic"], 80)
        self.assertEqual(result["counts"]["agent"], 36)
        self.assertEqual(result["counts"]["compact"], len(result["controls"]))

    def test_empty_observation_does_not_crash(self):
        for payload in (None, {}, [], "", 0, {"observation": None}, {"result": []}):
            result = compact_observation(payload)
            self.assertEqual("page_card", result["kind"])
            self.assertIn("pageText", result)
            self.assertIn("pageSummary", result)
            self.assertEqual([], result["controls"])
            self.assertEqual(0, result["counts"]["compact"])

    def test_last_transition_and_route_hints_survive(self):
        result = compact_observation({
            "pageKey": "settings::apps",
            "title": "Apps",
            "lastTransition": {"from": "settings::root", "via": "Apps"},
            "routeHints": ["Settings → Apps", "Apps → See all apps", "a", "b", "c", "dropped-sixth"],
            "controls": [],
        })
        self.assertEqual({"from": "settings::root", "via": "Apps"}, result["lastTransition"])
        self.assertEqual(5, len(result["routeHints"]))
        self.assertEqual(result["routeHints"], result["knownRouteHints"])
        self.assertNotIn("dropped-sixth", result["routeHints"])

    def test_does_not_emit_raw_tree(self):
        result = compact_observation({
            "pageKey": "p",
            "nodes": [{"id": str(i)} for i in range(2500)],
            "rawTree": {"nodeCount": 2500},
            "controls": [{"id": "1", "label": "Apps", "clickable": True}],
        })
        self.assertNotIn("nodes", result)
        self.assertNotIn("rawTree", result)
        self.assertEqual("page_card", result["kind"])


if __name__ == "__main__":
    unittest.main()
