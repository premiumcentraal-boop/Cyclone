from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]


class OwnerTestSigningBoundary(unittest.TestCase):
    def test_signing_requires_manual_opt_in_after_the_phone_build(self):
        ci = (ROOT / '.github/workflows/mobile-ci.yml').read_text(encoding='utf-8')
        signing = ci.split('  signed-owner-test:', 1)[1]
        self.assertIn("if: github.event_name == 'workflow_dispatch' && inputs.sign_owner_test", signing)
        self.assertIn('needs: mobile', signing)
        self.assertIn('default: false', ci.split('  workflow_dispatch:', 1)[1].split('permissions:', 1)[0])
        self.assertIn('environment: mobile-release-approval', self.workflow())

    def test_artifact_signing_cannot_publish_a_release(self):
        signing = self.workflow()
        self.assertIn('contents: read', signing)
        self.assertNotIn('contents: write', signing)
        self.assertNotIn('gh release create', signing)
        self.assertNotIn('releases/upload', signing)
        self.assertIn('path: signed/', signing)
        self.assertIn('retention-days: 3', signing)

    def test_only_the_verified_candidate_and_expected_signer_are_exported(self):
        signing = self.workflow()
        for evidence in ('candidate/source-sha.txt', 'candidate/run-id.txt', 'UNSIGNED_VERIFIED_CANDIDATE',
                         'sha256sum --check', '--rotation-min-sdk-version 33',
                         'test "$new_cert" = "$RELEASE_CERT_SHA256"', 'versionCode=', 'versionName=',
                         'SIGNED_OWNER_TEST_ARTIFACT_NOT_PUBLISHED'):
            self.assertIn(evidence, signing)
        self.assertIn('if: always()', signing)
        self.assertIn('rm -f "$RUNNER_TEMP/cyclone-owner-test.keystore" "$RUNNER_TEMP/cyclone-owner-test.lineage"', signing)
        self.assertNotIn('cp "$keystore" signed/', signing)
        self.assertNotIn('cp "$lineage" signed/', signing)

    @staticmethod
    def workflow():
        return (ROOT / '.github/workflows/_sign-owner-test.yml').read_text(encoding='utf-8')


if __name__ == '__main__':
    unittest.main()
