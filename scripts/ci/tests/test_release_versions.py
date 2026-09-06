import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "release_versions.py"
SPEC = importlib.util.spec_from_file_location("release_versions", SCRIPT)
release_versions = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(release_versions)


class ReleaseVersionsTest(unittest.TestCase):
    def coherent_values(self):
        return {
            "product": "3.9.8",
            "expectedMobile": "3.9.8",
            "expectedPc": "0.2.0",
            "expectedGatewayPython": "3.9.8",
            "expectedMcpPython": "3.9.8",
            "androidVersionName": "3.9.8",
            "androidVersionCode": 62,
            "gatewayPython": "3.9.8",
            "mcpPython": "3.9.8",
            "agentMcpPython": "3.9.8",
            "pcPackage": "0.2.0",
            "pcPackageLock": "0.2.0",
            "pcCargo": "0.2.0",
            "pcTauri": "0.2.0",
            "expectedAndroidVersionCode": 62,
        }

    def test_current_component_versions_are_allowed_to_be_independent(self):
        self.assertEqual([], release_versions.check(self.coherent_values()))

    def test_mobile_version_drift_is_rejected(self):
        values = self.coherent_values()
        values["androidVersionName"] = "3.8.9"
        self.assertIn(
            "androidVersionName='3.8.9' expected '3.9.8'",
            release_versions.check(values),
        )

    def test_version_code_must_advance_past_preserved_3_5_1(self):
        values = self.coherent_values()
        values["androidVersionCode"] = 37
        values["expectedAndroidVersionCode"] = 37
        self.assertIn(
            "Distributed Cyclone Android builds must remain above the preserved 3.5.1 versionCode 37",
            release_versions.check(values),
        )


if __name__ == "__main__":
    unittest.main()
