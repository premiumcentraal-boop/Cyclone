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
            "product": "3.5.3",
            "expectedMobile": "3.5.3",
            "expectedPc": "3.5.3",
            "expectedGatewayPython": "3.5.3",
            "expectedMcpPython": "3.5.3",
            "androidVersionName": "3.5.3",
            "androidVersionCode": 39,
            "gatewayPython": "3.5.3",
            "mcpPython": "3.5.3",
            "agentMcpPython": "3.5.3",
            "pcPackage": "3.5.3",
            "pcPackageLock": "3.5.3",
            "pcCargo": "3.5.3",
            "pcTauri": "3.5.3",
            "expectedAndroidVersionCode": 39,
        }

    def test_3_5_3_components_are_aligned(self):
        self.assertEqual([], release_versions.check(self.coherent_values()))

    def test_mobile_version_drift_is_rejected(self):
        values = self.coherent_values()
        values["androidVersionName"] = "3.5.2-beta"
        self.assertIn(
            "androidVersionName='3.5.2-beta' expected '3.5.3'",
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
