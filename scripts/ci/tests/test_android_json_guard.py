"""Unit tests see Android's org.json, not a lookalike.

Unit tests run on the desktop JVM. They used JSON-java (org.json:json), which differs from the phone: optString on an
explicit JSON null is "" there and "null" on Android, keys() order, escaping of '/', JSONObject(Map) with nulls.
Code could pass every test and break on the phone (alpha 141 lesson). The app's unit tests now compile AOSP's own
org.json from src/testAndroidJson; this guard keeps it that way.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "apps/mobile/app"
JSON = APP / "src/testAndroidJson/java/org/json"


class AndroidJsonGuard(unittest.TestCase):
    def test_tests_use_androids_org_json(self):
        gradle = (APP / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertNotIn('"org.json:json', gradle, "JSON-java behaves differently from Android's org.json")
        self.assertIn('getByName("test").java.srcDir("src/testAndroidJson/java")', gradle)
        for name in ("JSON", "JSONArray", "JSONException", "JSONObject", "JSONStringer", "JSONTokener"):
            self.assertTrue((JSON / f"{name}.java").is_file(), name)

    def test_it_is_aosps_code_with_androids_null_behaviour(self):
        obj = (JSON / "JSONObject.java").read_text(encoding="utf-8")
        self.assertIn("The Android Open Source Project", obj)
        # Android: optString on JSONObject.NULL gives "null" (JSON.toString), the behaviour tests must see.
        body = obj[obj.index("public String optString(String name, String fallback)"):]
        self.assertIn("String result = JSON.toString(object);", body[:300])
        self.assertIn('return "null";', obj)


    def test_model_output_drops_json_nulls_before_it_is_read(self):
        """On Android optString(k) on a JSON null is "null": model JSON goes through ModelJson, which drops nulls."""
        base = APP / "src/main/java/com/cyclone/mobile"
        sites = {
            "mind/MindConversation.kt": "ModelJson.parse(arguments",          # every Mind tool call's arguments
            "mind/one/PlanLines.kt": "ModelJson.parse(text)",                 # OneMind and Flash plan lines
            "ai/PageAgentProtocol.kt": "ModelJson.parse(stripFence(raw))",     # the page agent's actions
            "voice/VoiceUnderstanding.kt": "ModelJson.parse(text.substring(",
            "ai/OpenRouterQuickAgent.kt": "ModelJson.parse(repaired)",
            "mind/modes/DecisionBox.kt": "ModelJson.parse(trimmed)",
            "mind/pilot/Pilot.kt": "ModelJson.parse(trimmed)",
        }
        for path, needle in sites.items():
            self.assertIn(needle, (base / path).read_text(encoding="utf-8"), path)


if __name__ == "__main__":
    unittest.main()
