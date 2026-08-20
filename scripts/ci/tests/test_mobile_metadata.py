import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).parents[1] / "mobile_metadata.py"
SPEC = importlib.util.spec_from_file_location("mobile_metadata", SCRIPT)
mobile_metadata = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(mobile_metadata)


class MobileMetadataTest(unittest.TestCase):
    def read(self, gradle: str):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        source = root / "src"
        source.mkdir()
        (source / "PhoneToolExecutor.kt").write_text("object PhoneToolExecutor {}", encoding="utf-8")
        manifest = root / "AndroidManifest.xml"
        manifest.write_text(
            '<activity android:name=".MainActivity"><action android:name="android.intent.action.MAIN"/>'
            '<category android:name="android.intent.category.LAUNCHER"/></activity>',
            encoding="utf-8",
        )
        gradle_file = root / "build.gradle.kts"
        gradle_file.write_text(gradle, encoding="utf-8")
        with patch.multiple(mobile_metadata, GRADLE=gradle_file, MANIFEST=manifest, SOURCES=source):
            return mobile_metadata.read_metadata()

    def test_plain_semver_is_canonical(self):
        metadata = self.read('applicationId = "com.cyclone.mobile"\nversionCode = 17\nversionName = "2.9.5"\n')
        self.assertEqual("Cyclone-Mobile-2.9.5.apk", metadata["apk_name"])

    def test_decorated_semver_is_supported(self):
        metadata = self.read('applicationId = "com.cyclone.mobile"\nversionCode = 18\nversionName = "3.0.0-beta.1"\n')
        self.assertEqual("3.0.0-beta.1", metadata["version_name"])

    def test_duplicate_version_assignment_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "exactly one versionName"):
            self.read('applicationId = "com.cyclone.mobile"\nversionCode = 17\nversionName = "2.9.5"\nversionName = "3.0.0"\n')

    def test_malformed_version_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "malformed versionName"):
            self.read('applicationId = "com.cyclone.mobile"\nversionCode = 17\nversionName = "release-v3"\n')


if __name__ == "__main__":
    unittest.main()
