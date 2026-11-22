from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
BUILD = ROOT / ".github/workflows/_mobile-build.yml"
# The one release signer (fast release lane) and the owner-test signer.
SIGNERS = (ROOT / ".github/workflows/v5-publish.yml", ROOT / ".github/workflows/_sign-owner-test.yml")


class MobileReleaseAndroidBootstrapGuards(unittest.TestCase):
    def test_ci_and_signers_never_request_retired_android_tools_package(self):
        self.assertIn("packages: platform-tools", BUILD.read_text(encoding="utf-8"))
        for signer in SIGNERS:
            text = signer.read_text(encoding="utf-8")
            self.assertIn("packages: platform-tools", text, signer.name)
            self.assertNotIn("packages: tools\n", text, signer.name)

    def test_rotated_signing_covers_mobile_min_sdk_and_checks_the_lineage(self):
        mobile = (ROOT / "apps/mobile/app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("minSdk = 33", mobile)
        for signer in SIGNERS:
            text = signer.read_text(encoding="utf-8")
            self.assertIn("--rotation-min-sdk-version 33", text, signer.name)
            self.assertIn('verify --min-sdk-version 33 --verbose --print-certs "signed/$APK_NAME"', text, signer.name)
            self.assertIn('lineage --in "$lineage" --print-certs -v > signed/signing-lineage.txt', text, signer.name)
            self.assertIn('grep -q "$RELEASE_CERT_SHA256" signed/signing-lineage.txt', text, signer.name)
            self.assertIn('grep -q "$HISTORICAL_CERT_SHA256" signed/signing-lineage.txt', text, signer.name)


if __name__ == "__main__":
    unittest.main()
