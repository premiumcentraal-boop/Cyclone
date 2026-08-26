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


def declared_permissions(path: Path) -> set[str]:
    root = ET.parse(path).getroot()
    return {
        node.get(f"{ANDROID}name", "")
        for node in root.findall("uses-permission")
    }


class MobilePermissionArchitectureGuards(unittest.TestCase):
    def test_core_setup_permissions_are_declared(self):
        app = declared_permissions(ROOT / "apps/mobile/app/src/main/AndroidManifest.xml")
        embedded = declared_permissions(ROOT / "apps/mobile/mobilerun-embedded/src/main/AndroidManifest.xml")
        self.assertIn("android.permission.READ_CALENDAR", app)
        self.assertIn("android.permission.POST_NOTIFICATIONS", app)
        self.assertIn("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", app)
        self.assertIn("android.permission.SYSTEM_ALERT_WINDOW", embedded)
        self.assertIn("android.permission.SCHEDULE_EXACT_ALARM", embedded)

    def test_no_manifest_can_silently_expand_into_sensitive_domains(self):
        for manifest in (
            ROOT / "apps/mobile/app/src/main/AndroidManifest.xml",
            ROOT / "apps/mobile/mobilerun-embedded/src/main/AndroidManifest.xml",
        ):
            permissions = declared_permissions(manifest)
            self.assertFalse(
                FORBIDDEN_PERMISSIONS & permissions,
                f"{manifest.name}: {FORBIDDEN_PERMISSIONS & permissions}",
            )

    def test_sms_trigger_receiver_is_not_exposed_anywhere(self):
        for manifest in (
            ROOT / "apps/mobile/app/src/main/AndroidManifest.xml",
            ROOT / "apps/mobile/mobilerun-embedded/src/main/AndroidManifest.xml",
        ):
            root = ET.parse(manifest).getroot()
            application = root.find("application")
            receivers = {
                receiver.get(f"{ANDROID}name", "")
                for receiver in application.findall("receiver")
            }
            self.assertNotIn(
                "com.mobilerun.portal.triggers.TriggerSmsReceiver",
                receivers,
                manifest.name,
            )

    def test_every_declared_permission_is_infrastructure_or_has_a_setup_row(self):
        declared = (
            declared_permissions(ROOT / "apps/mobile/app/src/main/AndroidManifest.xml")
            | declared_permissions(
                ROOT / "apps/mobile/mobilerun-embedded/src/main/AndroidManifest.xml"
            )
        )
        unexplained = declared - INFRASTRUCTURE_PERMISSIONS - SETUP_ROW_PERMISSIONS
        self.assertEqual(
            set(),
            unexplained,
            f"Declared permissions without a setup row or infrastructure exemption: {unexplained}",
        )


if __name__ == "__main__":
    unittest.main()
