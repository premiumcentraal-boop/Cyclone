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
            "product": "3.5.2-beta",
            "expectedMobile": "3.5.2-beta",
            "expectedPc": "3.5.1",
            "expectedGatewayPython": "3.5.1",
            "expectedMcpPython": "3.5.1",
            "androidVersionName": "3.5.2-beta",
            "androidVersionCode": 38,
            "gatewayPython": "3.5.1",
            "mcpPython": "3.5.1",
            "agentMcpPython": "3.5.1",
            "pcPackage": "3.5.1",
            "pcPackageLock": "3.5.1",
            "pcCargo": "3.5.1",
            "pcTauri": "3.5.1",
            "expectedAndroidVersionCode": 38,
        }

    def test_mobile_beta_can_advance_without_relabeling_pc_components(self):
        self.assertEqual([], release_versions.check(self.coherent_values()))

    def test_mobile_version_drift_is_rejected(self):
        values = self.coherent_values()
        values["androidVersionName"] = "3.5.1"
        self.assertIn(
            "androidVersionName='3.5.1' expected '3.5.2-beta'",
            release_versions.check(values),
        )

    def test_version_code_must_advance_past_preserved_3_5_1(self):
        values = self.coherent_values()
        values["androidVersionCode"] = 37
        values["expectedAndroidVersionCode"] = 37
        self.assertIn(
            "Cyclone 3.5.2 distributed Android builds require versionCode > 37",
            release_versions.check(values),
        )


if __name__ == "__main__":
    unittest.main()
