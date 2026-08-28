import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).parents[1] / "teamwork_sniper_metadata.py"
SPEC = importlib.util.spec_from_file_location("teamwork_sniper_metadata", SCRIPT)
metadata = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(metadata)


class TeamworkSniperMetadataTest(unittest.TestCase):
    def app(self, gradle: str) -> Path:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "app").mkdir()
        (root / "app/build.gradle.kts").write_text(gradle, encoding="utf-8")
        return root

    def test_expected_identity_and_filename(self):
        root = self.app(
            'applicationId = "com.cyclone.teamworksniper"\n'
            'versionCode = 1\n'
            'versionName = "3.5.2-beta"\n'
        )
        with patch.object(metadata, "expected_identity", return_value=("3.5.2-beta", 1)):
            result = metadata.read_metadata(root)
        self.assertTrue(result["present"])
        self.assertEqual("Teamwork-Sniper-3.5.2-beta.apk", result["apk_name"])

    def test_wrong_package_is_rejected(self):
        root = self.app(
            'applicationId = "com.cyclone.mobile"\n'
            'versionCode = 1\n'
            'versionName = "3.5.2-beta"\n'
        )
        with patch.object(metadata, "expected_identity", return_value=("3.5.2-beta", 1)):
            with self.assertRaisesRegex(ValueError, "unexpected applicationId"):
                metadata.read_metadata(root)

    def test_missing_app_is_reported_without_fabricating_metadata(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.assertEqual({"present": False}, metadata.read_metadata(Path(temporary.name)))


if __name__ == "__main__":
    unittest.main()
