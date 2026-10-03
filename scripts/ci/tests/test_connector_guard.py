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
        self.assertEqual(core.count("need(ConnectorScope."), 5, "each non-hello call checks its scope")
        self.assertNotRegex(core, r'"profiles\.(switch|create|remove|rename)"|ProfileRegistryStore\.(rename|setLook|markRemoved|restore|drop)')

    def test_connector_data_stays_on_the_phone(self):
        for folder in ("gateway", "observability", "mind", "brain", "ports", "codes", "secrets", "agent"):
            for path in (MOBILE / folder).rglob("*.kt"):
                self.assertNotRegex(read(path), r"\.ext\b", f"{path.relative_to(MOBILE)} must not read connector data")
        registry = read(MOBILE / "runtime/workspaces/ProfileRegistryStore.kt")
        self.assertIn("ext = previous?.ext.orEmpty()", registry, "a checkpoint keeps connector data")
        self.assertIn('.put("ext", ext)', registry, "every save writes connector data back")

    def test_the_contract_is_one_json_call(self):
        aidl = read(APP / "aidl/com/cyclone/connector/ICycloneConnector.aidl")
        methods = re.findall(r"^\s*\w[\w<>]*\s+\w+\(.*\);", aidl, re.M)
        self.assertEqual(methods, ["    String call(String request);"])
        contract = read(CONNECTOR / "ConnectorContract.kt")
        self.assertIn('const val CONTRACT = "cyclone.connector/1"', contract)


if __name__ == "__main__":
    unittest.main()
