from __future__ import annotations

import unittest

from cyclone_phone_mcp.action_contract import (
    PLAY_STORE_PACKAGE,
    canonical_action_name,
    format_phone_act_examples,
    play_store_details_uri,
    resolve_action,
)


class ActionContractTests(unittest.TestCase):
    def test_tap_aliases_click(self):
        tool, params = resolve_action("phone.tap", {"elementId": "e1"})
        self.assertEqual("phone.click", tool)
        self.assertEqual({"elementId": "e1"}, params)
        self.assertEqual("phone.click", canonical_action_name("phone.tap"))

    def test_open_app_accepts_package_name_alias(self):
        tool, params = resolve_action("phone.open_app", {"packageName": PLAY_STORE_PACKAGE})
        self.assertEqual("phone.open_app", tool)
        self.assertEqual({"package": PLAY_STORE_PACKAGE}, params)

    def test_open_app_play_store_example(self):
        tool, params = resolve_action("phone.open_app", {"package": PLAY_STORE_PACKAGE})
        self.assertEqual("phone.open_app", tool)
        self.assertEqual(PLAY_STORE_PACKAGE, params["package"])

    def test_open_app_market_uri_becomes_launch_intent(self):
        tool, params = resolve_action("phone.open_app", {"uri": "market://details?id=com.android.chrome"})
        self.assertEqual("phone.launch_intent", tool)
        self.assertEqual("market://details?id=com.android.chrome", params["uri"])
        self.assertEqual(PLAY_STORE_PACKAGE, params["package"])

    def test_play_store_https_details_is_accepted(self):
        tool, params = resolve_action(
            "phone.launch_intent",
            {"uri": "https://play.google.com/store/apps/details?id=com.android.chrome"},
        )
        self.assertEqual("phone.launch_intent", tool)
        self.assertEqual("market://details?id=com.android.chrome", params["uri"])

    def test_wait_for_package_equals(self):
        tool, params = resolve_action(
            "phone.wait_for",
            {"timeoutMs": 8000, "type": "package_equals", "packageName": PLAY_STORE_PACKAGE},
        )
        self.assertEqual("phone.wait_for", tool)
        self.assertEqual("package_equals", params["condition"]["type"])
        self.assertEqual(PLAY_STORE_PACKAGE, params["condition"]["package"])

    def test_unknown_tool_lists_supported(self):
        with self.assertRaises(ValueError) as raised:
            resolve_action("phone.swipe_up", {})
        self.assertIn("phone.click", str(raised.exception))
        self.assertIn("phone.tap is an alias", str(raised.exception))

    def test_examples_cover_tap_open_app_and_type(self):
        text = format_phone_act_examples()
        self.assertIn("phone.click", text)
        self.assertIn("phone.open_app", text)
        self.assertIn("phone.type", text)
        self.assertIn(PLAY_STORE_PACKAGE, text)

    def test_details_uri_helper(self):
        self.assertEqual("market://details?id=com.android.chrome", play_store_details_uri("com.android.chrome"))


if __name__ == "__main__":
    unittest.main()
