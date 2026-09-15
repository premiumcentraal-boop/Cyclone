from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
BUILD = ROOT / ".github/workflows/_mobile-build.yml"
PUBLISH = ROOT / ".github/workflows/mobile-publish-v3910.yml"


class MobileReleaseAndroidBootstrapGuards(unittest.TestCase):
    def test_ci_and_publisher_never_request_retired_android_tools_package(self):
        build = BUILD.read_text(encoding="utf-8")
        publish = PUBLISH.read_text(encoding="utf-8")

        self.assertIn("packages: platform-tools", build)
        self.assertIn("packages: platform-tools", publish)

        signing_setup = publish.split("- name: Set up Android signing tools", 1)[1].split(
            "- name: Install Android build tools", 1
        )[0]
        self.assertIn("packages: platform-tools", signing_setup)
        self.assertNotIn("packages: tools", signing_setup)


if __name__ == "__main__":
    unittest.main()
