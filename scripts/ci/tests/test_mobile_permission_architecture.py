from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
ANDROID = "{http://schemas.android.com/apk/res/android}"

# Auto-granted infrastructure permissions that keep the app alive and connected but do not
# expose user data or device capabilities; they intentionally have no setup row.
INFRASTRUCTURE_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.WAKE_LOCK",
    "android.permission.KILL_BACKGROUND_PROCESSES",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
}

# Every permission in this set must appear in the Cyclone setup UI as a row that maps to the
# capability it backs. Add new permissions here only together with a real setup row.
SETUP_ROW_PERMISSIONS = {
    "android.permission.POST_NOTIFICATIONS",  # Result notifications
    "android.permission.READ_CALENDAR",  # Calendar context
    "android.permission.RECORD_AUDIO",  # Voice requests
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",  # Unrestricted battery
    "android.permission.SYSTEM_ALERT_WINDOW",  # Display over apps
    "android.permission.SCHEDULE_EXACT_ALARM",  # Precise timing
}

FORBIDDEN_PERMISSIONS = {
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_CONTACTS",
    "android.permission.RECEIVE_SMS",
    "android.permission.REQUEST_INSTALL_PACKAGES",
}


def manifest_root(path: Path) -> ET.Element:
    return ET.parse(path).getroot()


def declared_permissions(path: Path) -> set[str]:
    return {
        node.get(f"{ANDROID}name", "")
        for node in manifest_root(path).findall("uses-permission")
    }


def application_node(path: Path) -> ET.Element | None:
    return manifest_root(path).find("application")


def service_nodes(path: Path) -> dict[str, ET.Element]:
    application = application_node(path)
    if application is None:
        return {}
    return {
        service.get(f"{ANDROID}name", ""): service
        for service in application.findall("service")
    }


def receiver_names(path: Path) -> set[str]:
    application = application_node(path)
    if application is None:
        return set()
    return {
        receiver.get(f"{ANDROID}name", "")
        for receiver in application.findall("receiver")
    }


def service_actions(service: ET.Element) -> set[str]:
    return {
        action.get(f"{ANDROID}name", "")
        for intent_filter in service.findall("intent-filter")
        for action in intent_filter.findall("action")
    }


class MobilePermissionArchitectureGuards(unittest.TestCase):
    def setUp(self):
        self.app_manifest = ROOT / "apps/mobile/app/src/main/AndroidManifest.xml"
        self.diagnostics_manifest = ROOT / "apps/mobile/mobilerun-embedded/src/main/AndroidManifest.xml"

    def test_core_setup_permissions_are_declared_by_canonical_app(self):
        app = declared_permissions(self.app_manifest)
        for permission in SETUP_ROW_PERMISSIONS:
            self.assertIn(permission, app)
        self.assertEqual(set(), declared_permissions(self.diagnostics_manifest))

    def test_final_apk_exposes_only_native_cyclone_control_endpoints(self):
        app = service_nodes(self.app_manifest)
        embedded = service_nodes(self.diagnostics_manifest)

        canonical_accessibility = app[".CycloneAccessibilityService"]
        canonical_notifications = app[".CycloneNotificationListener"]
        self.assertIn(
            "android.accessibilityservice.AccessibilityService",
            service_actions(canonical_accessibility),
        )
        self.assertIn(
            "android.service.notification.NotificationListenerService",
            service_actions(canonical_notifications),
        )
        self.assertEqual(set(), set(embedded))
        self.assertFalse(any(name.startswith("com.mobilerun.portal.") for name in app))

    def test_diagnostics_library_cannot_expand_final_manifest(self):
        root = manifest_root(self.diagnostics_manifest)
        self.assertEqual([], root.findall("uses-permission"))
        self.assertIsNone(root.find("application"))
        self.assertEqual([], root.findall("queries"))

    def test_main_shell_respects_android_status_bar_inset(self):
        main_activity = (
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/MainActivity.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("statusBarsPadding()", main_activity)
        self.assertIn("Box(Modifier.fillMaxSize().statusBarsPadding())", main_activity)

    def test_legacy_enhanced_control_row_is_not_rendered(self):
        components = (
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32Components.kt"
        ).read_text(encoding="utf-8")
        self.assertIn(
            'private const val LEGACY_ENHANCED_CONTROL_ROW = "Enhanced control engine"',
            components,
        )
        self.assertIn("if (title == LEGACY_ENHANCED_CONTROL_ROW) return", components)

    def test_enhanced_control_compatibility_uses_primary_grant(self):
        permission_setup = (
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/permissions/CyclonePermissionSetup.kt"
        ).read_text(encoding="utf-8")
        self.assertNotIn("MobilerunAccessibilityService", permission_setup)
        self.assertIn(
            "fun enhancedControlEnabled(context: Context): Boolean = primaryControlEnabled(context)",
            permission_setup,
        )

    def test_no_manifest_can_silently_expand_into_sensitive_domains(self):
        for manifest in (self.app_manifest, self.diagnostics_manifest):
            permissions = declared_permissions(manifest)
            self.assertFalse(
                FORBIDDEN_PERMISSIONS & permissions,
                f"{manifest.name}: {FORBIDDEN_PERMISSIONS & permissions}",
            )

    def test_sms_trigger_receiver_is_not_exposed_anywhere(self):
        for manifest in (self.app_manifest, self.diagnostics_manifest):
            self.assertNotIn(
                "com.mobilerun.portal.triggers.TriggerSmsReceiver",
                receiver_names(manifest),
                manifest.name,
            )

    def test_every_declared_permission_is_infrastructure_or_has_a_setup_row(self):
        declared = declared_permissions(self.app_manifest) | declared_permissions(self.diagnostics_manifest)
        unexplained = declared - INFRASTRUCTURE_PERMISSIONS - SETUP_ROW_PERMISSIONS
        self.assertEqual(
            set(),
            unexplained,
            f"Declared permissions without a setup row or infrastructure exemption: {unexplained}",
        )


if __name__ == "__main__":
    unittest.main()
