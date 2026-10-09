"""Plan 51 (alpha.104): phone connectors.

- One Binder door: ConnectorService is the only new exported component, and every call is checked in code.
- Nothing a connector calls reaches the phone executor, the gateway, the vault, codes, Ports, the Mind or Brain.
- A connector's profile data (`ext`) never reaches the gateway, the PC, a model, Brain or diagnostics.
- The contract is one AIDL call, JSON in and out, so it grows without changing the interface.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "apps/mobile/app/src/main"
MOBILE = APP / "java/com/cyclone/mobile"
CONNECTOR = MOBILE / "connector"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class ConnectorGuard(unittest.TestCase):
    def test_connector_code_reaches_nothing_powerful(self):
        forbidden = re.compile(r"com\.cyclone\.mobile\.(gateway|secrets|mind|brain|codes|ports|agent|automation|task)\b|"
                               r"\bPhoneToolExecutor\b|\bOverlayChromeRuntime\b|\bProfileApps\.switchTo\b|\bProfileSetupRuntime\b")
        for path in CONNECTOR.glob("*.kt"):
            self.assertNotRegex(read(path), forbidden, path.name)

    def test_one_exported_door_and_signature_permissions(self):
        manifest = read(APP / "AndroidManifest.xml")
        service = re.search(r'<service android:name="\.connector\.ConnectorService"[^>]*>', manifest)
        self.assertIsNotNone(service)
        self.assertIn('android:exported="true"', service.group(0))
        self.assertIn('<action android:name="com.cyclone.connector.SERVICE" />', manifest)
        for perm in ("CONNECTOR_HOST", "WAKE_CONNECTOR"):
            self.assertIn(f'<permission android:name="com.cyclone.mobile.permission.{perm}" android:protectionLevel="signature" />', manifest)
        receiver = re.search(r'<receiver android:name="\.connector\.ConnectorPackageReceiver".*?</receiver>', manifest, re.S).group(0)
        self.assertIn("PACKAGE_FULLY_REMOVED", receiver)
        self.assertEqual(manifest.count("com.cyclone.connector."), 2, "SERVICE and CONNECT (queries), nothing else")

    def test_identity_comes_from_binder_and_every_call_is_checked(self):
        runtime = read(CONNECTOR / "ConnectorRuntime.kt")
        self.assertIn("Binder.getCallingUid()", runtime)
        self.assertIn("if (packages.size != 1) return null", runtime, "shared user ids are refused")
        core = read(CONNECTOR / "ConnectorCore.kt")
        self.assertIn('if (method == "hello") return hello(caller, approval, args)', core)
        self.assertIn('throw ConnectorException("NOT_APPROVED"', core)
        self.assertEqual(core.count("need(ConnectorScope."), 9, "each method family checks its scope")
        # Plan 57 P3: root.status.v1 is built from what Cyclone last saw; nothing in its path runs a command.
        status = read(CONNECTOR / "ConnectorRootStatus.kt")
        self.assertNotRegex(status, r'ProcessBuilder|Runtime\.getRuntime|runRequired|runBestEffort|ProfileRoom\.status|"su"')
        runtime = read(CONNECTOR / "ConnectorRuntime.kt")
        root = runtime[runtime.index("override fun rootStatus()"):]
        root = root[:root.index("    }")]
        self.assertIn("ProfileRoom.cached(context)", root)
        self.assertNotIn("ProfileRoom.status", root)
        self.assertNotRegex(core, r'"profiles\.(switch|create|remove|rename)"|ProfileRegistryStore\.(rename|setLook|markRemoved|restore|drop)')

    def test_startup_has_a_deadline_identity_check_and_no_reference_execution(self):
        runtime = read(CONNECTOR / "ProfileBehaviorRuntime.kt")
        self.assertIn("linkToDeath", runtime)
        self.assertIn("uid / 100_000", runtime)
        self.assertIn("ProfileReplyGate(p.uid, deadline", runtime)
        self.assertIn("replyGate.accept(Binder.getCallingUid())", runtime)
        self.assertIn("deadline - android.os.SystemClock.elapsedRealtime()", runtime)
        self.assertIn("ConnectorScope.PROFILE_STARTUP !in granted", runtime)
        self.assertNotRegex(runtime, r"startActivity|openInputStream|Uri.parse|Runtime.getRuntime|ProcessBuilder")
        for name in ("IProfileBehaviorProvider.aidl", "IProfileBehaviorResult.aidl"):
            self.assertEqual(read(APP / "aidl/com/cyclone/connector" / name), read(ROOT / "apps/mobile/connector-client/src/main/aidl/com/cyclone/connector" / name))

    def test_connector_data_stays_on_the_phone(self):
        for folder in ("gateway", "observability", "mind", "brain", "ports", "codes", "secrets", "agent"):
            for path in (MOBILE / folder).rglob("*.kt"):
                self.assertNotRegex(read(path), r"\.ext\b", f"{path.relative_to(MOBILE)} must not read connector data")
        registry = read(MOBILE / "runtime/workspaces/ProfileRegistryStore.kt")
        self.assertIn("ext = previous?.ext.orEmpty()", registry, "a checkpoint keeps connector data")
        self.assertIn('.put("ext", ext)', registry, "every save writes connector data back")

    def test_original_json_transaction_is_preserved_and_registration_is_appended(self):
        aidl = read(APP / "aidl/com/cyclone/connector/ICycloneConnector.aidl")
        methods = re.findall(r"^\s*\w[\w<>]*\s+\w+\(.*\);", aidl, re.M)
        self.assertEqual(methods, ["    String call(String request);", "    String registerProfileProvider(String request, IProfileBehaviorProvider provider);"])
        contract = read(CONNECTOR / "ConnectorContract.kt")
        self.assertIn('const val CONTRACT = "cyclone.connector/1"', contract)


class ConnectorKitGuard(unittest.TestCase):
    """Plan 51 K4: the client library and the sample are separate, small and only speak the public contract."""

    MOBILE_ROOT = ROOT / "apps/mobile"

    def test_the_client_aidl_is_the_apps_aidl(self):
        app = read(APP / "aidl/com/cyclone/connector/ICycloneConnector.aidl")
        client = read(self.MOBILE_ROOT / "connector-client/src/main/aidl/com/cyclone/connector/ICycloneConnector.aidl")
        self.assertEqual(app, client)

    def test_the_kit_never_links_cyclone_itself(self):
        settings = read(self.MOBILE_ROOT / "settings.gradle.kts")
        for module in (":connector-client", ":connector-sample"):
            self.assertIn(f'"{module}"', settings)
        for folder in ("connector-client", "connector-sample"):
            build = read(self.MOBILE_ROOT / folder / "build.gradle.kts")
            self.assertNotIn('project(":app")', build)
            for path in (self.MOBILE_ROOT / folder / "src").rglob("*.kt"):
                self.assertNotRegex(read(path), r"import com\.cyclone\.mobile\.|PhoneToolExecutor", path.name)

    def test_the_sample_protects_its_doors_with_cyclones_permissions(self):
        manifest = read(self.MOBILE_ROOT / "connector-sample/src/main/AndroidManifest.xml")
        self.assertRegex(manifest, r'(?s)<service[^>]*android:permission="com\.cyclone\.mobile\.permission\.CONNECTOR_HOST"')
        self.assertRegex(manifest, r'(?s)<receiver[^>]*android:permission="com\.cyclone\.mobile\.permission\.WAKE_CONNECTOR"')

    def test_ci_builds_and_publishes_the_kit_with_checksums(self):
        build = read(ROOT / ".github/workflows/_mobile-build.yml")
        self.assertIn(":connector-client:assembleRelease :connector-sample:assembleDebug", build)
        self.assertIn("sha256sum * > SHA256SUMS", build)
        publish = read(ROOT / ".github/workflows/v5-publish.yml")
        self.assertIn("sha256sum --check SHA256SUMS", publish)
        self.assertIn("kit = sorted(Path('connector').glob('Cyclone-Connector-*'))", publish)
        self.assertIn("manifest['sha256'].update(", publish, "every kit file is in the release manifest")
        self.assertIn("connector/* release-manifest.json", publish)

    def test_the_published_vectors_are_checked_on_both_sides(self):
        schemas = ROOT / "tools/cyclone-connector-sdk/schemas"
        self.assertTrue((schemas / "vectors.json").is_file())
        self.assertTrue((ROOT / "apps/mobile/app/src/test/java/com/cyclone/mobile/connector/ConnectorVectorsTest.kt").is_file())
        self.assertTrue((ROOT / "apps/device-gateway/tests/test_connector_schemas.py").is_file())


if __name__ == "__main__":
    unittest.main()
