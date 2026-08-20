import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github/workflows"


class MobileWorkflowArchitectureTest(unittest.TestCase):
    def text(self, name: str) -> str:
        return (WORKFLOWS / name).read_text(encoding="utf-8")

    def test_only_reusable_lane_assembles_android(self):
        assemblers = []
        for path in WORKFLOWS.glob("*.yml"):
            if ":app:assembleDebug" in path.read_text(encoding="utf-8"):
                assemblers.append(path.name)
        self.assertEqual(["_mobile-build.yml"], assemblers)

    def test_guards_precede_toolchain_submodule_and_single_gradle_invocation(self):
        workflow = self.text("_mobile-build.yml")
        guard = workflow.index("mobile_metadata.py")
        for later in ("actions/setup-java", "android-actions/setup-android", "gradle/actions/setup-gradle", "git submodule update"):
            self.assertLess(guard, workflow.index(later))
        self.assertEqual(1, workflow.count("./apps/mobile/gradlew"))
        self.assertIn(":app:testDebugUnitTest :app:assembleDebug", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertLess(workflow.index("wrapper-validation"), workflow.index("./apps/mobile/gradlew"))

    def test_release_reuses_and_verifies_without_build_or_publication(self):
        workflow = self.text("mobile-release.yml")
        self.assertIn("actions/download-artifact", workflow)
        self.assertIn("sha256sum --check", workflow)
        self.assertNotIn("gradlew", workflow)
        self.assertNotIn("gh release", workflow)
        self.assertIn("contents: read", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn(".github/workflows/mobile-ci.yml", workflow)
        self.assertIn(".conclusion", workflow)
        self.assertIn("EXPECTED_HEAD_SHA", workflow)
        self.assertIn("run-id.txt", workflow)

    def test_legacy_android_workflows_are_manual_only(self):
        legacy = (
            "android-mobile.yml",
            "mobile-fast-apk.yml",
            "mobile-v2-embedded.yml",
            "cyclone-v2.9.4-full-gateway.yml",
            "cyclone-v2.9.5-original-ui-gateway.yml",
            "apply-v295-ui-polish.yml",
        )
        for name in legacy:
            workflow = self.text(name)
            self.assertIn("workflow_dispatch:", workflow)
            self.assertNotIn("\n  push:", workflow)
            self.assertNotIn("\n  pull_request:", workflow)
            self.assertNotIn("contents: write", workflow)

    def test_authoritative_ci_is_read_only_path_filtered_and_cancels_stale_runs(self):
        workflow = self.text("mobile-ci.yml")
        self.assertIn("\n  push:", workflow)
        self.assertIn("\n  pull_request:", workflow)
        self.assertIn("paths:", workflow)
        self.assertIn("contents: read", workflow)
        self.assertIn("cancel-in-progress: true", workflow)
        self.assertIn("'third_party/mobilerun-portal'", workflow)
        self.assertIn("'third_party/mobilerun-portal/**'", workflow)
        self.assertIn("'!apps/mobile/**/*.md'", workflow)

    def test_new_workflow_actions_are_immutable_sha_pinned(self):
        import re
        for name in ("_mobile-build.yml", "mobile-release.yml"):
            for action, ref in re.findall(r"uses:\s*([^\s@]+)@([^\s#]+)", self.text(name)):
                if action.startswith("./"):
                    continue
                self.assertRegex(ref, r"^[0-9a-f]{40}$", f"{name}: {action}")

    def test_core_mobile_runtime_does_not_run_for_android_only_changes(self):
        workflow = self.text("mobile-ai-runtime.yml")
        self.assertNotIn("apps/mobile/**", workflow)
        self.assertNotIn("third_party/mobilerun-portal", workflow)


if __name__ == "__main__":
    unittest.main()
