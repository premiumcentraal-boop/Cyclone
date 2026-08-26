from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
ANDROID = "{http://schemas.android.com/apk/res/android}"


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

    def test_embedded_runtime_cannot_silently_expand_into_sensitive_domains(self):
        permissions = declared_permissions(
            ROOT / "apps/mobile/mobilerun-embedded/src/main/AndroidManifest.xml"
        )
        forbidden = {
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_CONTACTS",
            "android.permission.RECEIVE_SMS",
            "android.permission.REQUEST_INSTALL_PACKAGES",
        }
        self.assertFalse(forbidden & permissions, forbidden & permissions)

    def test_sms_trigger_receiver_is_not_exposed(self):
        root = ET.parse(
            ROOT / "apps/mobile/mobilerun-embedded/src/main/AndroidManifest.xml"
        ).getroot()
        application = root.find("application")
        receivers = {
            receiver.get(f"{ANDROID}name", "")
            for receiver in application.findall("receiver")
        }
        self.assertNotIn(
            "com.mobilerun.portal.triggers.TriggerSmsReceiver",
            receivers,
        )


if __name__ == "__main__":
    unittest.main()
