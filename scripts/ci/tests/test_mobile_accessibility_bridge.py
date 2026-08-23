from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
ANDROID = "{http://schemas.android.com/apk/res/android}"


class MobileAccessibilityBridgeGuards(unittest.TestCase):
    def _service(self, manifest_path: Path, class_name: str) -> ET.Element:
        root = ET.parse(manifest_path).getroot()
        application = root.find("application")
        self.assertIsNotNone(application, manifest_path)
        for service in application.findall("service"):
            if service.get(f"{ANDROID}name") == class_name:
                return service
        self.fail(f"Missing accessibility service {class_name} in {manifest_path}")

    def _assert_system_bindable(self, service: ET.Element) -> None:
        self.assertEqual("true", service.get(f"{ANDROID}exported"))
        self.assertEqual(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            service.get(f"{ANDROID}permission"),
        )

    def test_primary_cyclone_accessibility_is_system_bindable(self):
        service = self._service(
            ROOT / "apps/mobile/app/src/main/AndroidManifest.xml",
            ".CycloneAccessibilityService",
        )
        self._assert_system_bindable(service)

    def test_enhanced_control_is_system_bindable_and_uses_own_config(self):
        service = self._service(
            ROOT / "apps/mobile/mobilerun-embedded/src/main/AndroidManifest.xml",
            "com.mobilerun.portal.service.MobilerunAccessibilityService",
        )
        self._assert_system_bindable(service)
        metadata = service.find("meta-data")
        self.assertIsNotNone(metadata)
        self.assertEqual(
            "@xml/cyclone_enhanced_accessibility_service_config",
            metadata.get(f"{ANDROID}resource"),
        )

        config = ROOT / "apps/mobile/mobilerun-embedded/src/main/res/xml/cyclone_enhanced_accessibility_service_config.xml"
        self.assertTrue(config.is_file())
        root = ET.parse(config).getroot()
        self.assertEqual("true", root.get(f"{ANDROID}canPerformGestures"))
        self.assertEqual("true", root.get(f"{ANDROID}canRetrieveWindowContent"))
        self.assertEqual("true", root.get(f"{ANDROID}canTakeScreenshot"))

    def test_embedded_source_adapter_does_not_request_touch_exploration_modes(self):
        gradle = (ROOT / "apps/mobile/mobilerun-embedded/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('"AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE",\n                    "0"', gradle)
        self.assertIn(
            '"flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH",\n                    "flags = flags"',
            gradle,
        )

    def test_bridge_startup_has_url_validation_and_nonfatal_construction(self):
        source = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/BridgeClient.kt").read_text(encoding="utf-8")
        self.assertIn("isSupportedWebSocketUrl(url)", source)
        self.assertIn("val started = runCatching", source)
        self.assertIn("Core bridge could not start; accessibility remains available", source)
        self.assertNotIn('DeviceState.addLog("Bridge failure: ${t.message}")', source)


if __name__ == "__main__":
    unittest.main()
