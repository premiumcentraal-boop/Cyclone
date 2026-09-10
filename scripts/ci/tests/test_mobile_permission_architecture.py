from __future__ import annotations

import ast
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
ANDROID = "{http://schemas.android.com/apk/res/android}"

INFRASTRUCTURE_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.WAKE_LOCK",
    "android.permission.WRITE_SECURE_SETTINGS",
}
SETUP_ROW_PERMISSIONS = {
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
}


def manifest_root(path: Path) -> ET.Element:
    return ET.parse(path).getroot()


def application_node(path: Path) -> ET.Element:
    app = manifest_root(path).find("application")
    assert app is not None
    return app


def receiver_names(path: Path) -> set[str]:
    return {
        node.get(f"{ANDROID}name", "")
        for node in application_node(path).findall("receiver")
    }


def declared_permissions(path: Path) -> set[str]:
    return {
        node.get(f"{ANDROID}name", "")
        for node in manifest_root(path).findall("uses-permission")
    }


class MobilePermissionArchitectureGuards(unittest.TestCase):
    def setUp(self):
        self.app_manifest = ROOT / "apps/mobile/app/src/main/AndroidManifest.xml"
        self.diagnostics_manifest = ROOT / "apps/mobile/mobilerun-embedded/src/main/AndroidManifest.xml"

    def test_primary_cyclone_accessibility_is_system_bindable(self):
        manifest = application_node(self.app_manifest)
        service = next(
            node for node in manifest.findall("service")
            if node.get(f"{ANDROID}name") == ".CycloneAccessibilityService"
        )
        self.assertEqual("android.permission.BIND_ACCESSIBILITY_SERVICE", service.get(f"{ANDROID}permission"))
        self.assertEqual("true", service.get(f"{ANDROID}exported"))

    def test_final_apk_exposes_only_native_cyclone_control_endpoints(self):
        manifest = self.app_manifest.read_text(encoding="utf-8")
        diagnostics = self.diagnostics_manifest.read_text(encoding="utf-8")
        self.assertIn(".CycloneAccessibilityService", manifest)
        self.assertNotIn("com.mobilerun.portal.control.ControlAccessibilityService", manifest)
        self.assertNotIn("com.mobilerun.portal.control.ControlAccessibilityService", diagnostics)
        self.assertNotIn("com.mobilerun.portal.triggers.TriggerSmsReceiver", manifest)
        self.assertNotIn("com.mobilerun.portal.triggers.TriggerSmsReceiver", diagnostics)

    def test_diagnostics_library_cannot_expand_final_manifest(self):
        text = self.diagnostics_manifest.read_text(encoding="utf-8")
        self.assertNotIn("<uses-permission", text)
        self.assertNotIn("<service", text)
        self.assertNotIn("<receiver", text)
        self.assertNotIn("<activity", text)
        self.assertNotIn("<provider", text)

    def test_core_setup_permissions_are_declared_by_canonical_app(self):
        permissions = declared_permissions(self.app_manifest)
        self.assertIn("android.permission.CAMERA", permissions)
        self.assertIn("android.permission.RECORD_AUDIO", permissions)
        self.assertIn("android.permission.POST_NOTIFICATIONS", permissions)

    def test_primary_accessibility_callback_boundary_cannot_run_heavy_init_directly(self):
        source = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/CycloneAccessibilityService.kt").read_text(encoding="utf-8")
        callback = source.split("override fun onAccessibilityEvent", 1)[1].split("override fun", 1)[0]
        for forbidden in (
            "AutomationRuntime.initialize",
            "AppLearnerRuntime.initialize",
            "CycloneBrainRuntime.initialize",
            "Layer2Workspaces.initialize",
            "GatewayService.start",
        ):
            self.assertNotIn(forbidden, callback)

    def test_primary_accessibility_does_not_subscribe_to_type_all_mask(self):
        config = (ROOT / "apps/mobile/app/src/main/res/xml/cyclone_accessibility_service.xml").read_text(encoding="utf-8")
        self.assertNotIn("typeAllMask", config)

    def test_pair_begin_is_protocol_only_and_gateway_boundary_contains_nonfatal_throwables(self):
        gateway = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewayService.kt").read_text(encoding="utf-8")
        begin = gateway.split('"pair.begin"', 1)[1].split('"pair.complete"', 1)[0]
        self.assertNotIn("FleetVideoService.start", begin)
        self.assertIn("catch (error: Throwable)", gateway)

    def test_gateway_socket_worker_avoids_android_isclosed_crash_and_contains_transport_failures(self):
        source = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/gateway/GatewaySocketServer.kt").read_text(encoding="utf-8")
        self.assertNotIn("server.isClosed", source)
        self.assertIn("runCatching", source)

    def test_desktop_pairing_does_not_auto_start_fleet_video_and_has_crash_capture(self):
        main = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/MainActivity.kt").read_text(encoding="utf-8")
        crash = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/ProcessCrashJournal.kt").read_text(encoding="utf-8")
        self.assertNotIn("FleetVideoService.start", main)
        self.assertIn("Thread.setDefaultUncaughtExceptionHandler", crash)

    def test_process_crash_journal_captures_uncaught_and_historical_exit_reason(self):
        crash = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/ProcessCrashJournal.kt").read_text(encoding="utf-8")
        self.assertIn("ApplicationExitInfo", crash)
        self.assertIn("uncaughtException", crash)

    def test_retired_core_transport_cannot_start_or_forward_data(self):
        roots = [
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile",
            ROOT / "apps/mobile/mobilerun-embedded/src/main/java",
        ]
        files = [p for root in roots for p in root.rglob("*.kt")]
        text = "\n".join(p.read_text(encoding="utf-8") for p in files)
        self.assertNotIn("BridgeClient.start", text)
        self.assertNotIn("coreWsUrl", text)
        self.assertNotIn("coreToken", text)

    def test_enhanced_control_compatibility_uses_primary_grant(self):
        source = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneSettings426.kt").read_text(encoding="utf-8")
        self.assertIn("CycloneAccessibilityService.isEnabled", source)
        self.assertNotIn("ControlAccessibilityService", source)

    def test_legacy_enhanced_control_row_is_not_rendered(self):
        components = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32Components.kt").read_text(encoding="utf-8")
        self.assertIn('LEGACY_ENHANCED_CONTROL_ROW = "Enhanced control engine"', components)
        app = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32App.kt").read_text(encoding="utf-8")
        self.assertNotIn('Text("Enhanced control engine")', app)

    def test_main_shell_respects_android_status_bar_inset(self):
        app = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32App.kt").read_text(encoding="utf-8")
        self.assertIn("statusBarsPadding", app)

    def test_helper_install_is_explicit_pinned_and_narrowly_shared(self):
        helper = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/OfficialHelperDownload.kt").read_text(encoding="utf-8")
        screen = (ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background/BackgroundSetupActivity.kt").read_text(encoding="utf-8")
        self.assertIn("EXPECTED_SHA256", helper)
        self.assertIn("FileProvider", helper)
        self.assertIn("Back", screen)
        back_label = screen.find("Back")
        when_phase = screen.find("when (state.phase)")
        self.assertGreaterEqual(back_label, 0)
        self.assertGreaterEqual(when_phase, 0)
        self.assertLess(back_label, when_phase)
        self.assertNotIn("market://", screen)
        self.assertNotIn("play.google.com", screen)
        provider = next(p for p in application_node(self.app_manifest).findall("provider")
                        if p.get(f"{ANDROID}name") == "androidx.core.content.FileProvider")
        self.assertEqual("false", provider.get(f"{ANDROID}exported"))
        paths = ET.parse(ROOT / "apps/mobile/app/src/main/res/xml/setup_helper_paths.xml").getroot()
        self.assertEqual([("cache-path", "setup-helper/")], [(p.tag, p.get("path")) for p in paths])

    def test_helper_and_profile_setup_never_send_users_to_play_store(self):
        roots = [
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/background",
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/runtime/workspaces",
        ]
        files = [path for root in roots for path in root.rglob("*.kt")]
        profile_surfaces = [
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/RootFeaturesCard.kt",
            ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/ProfileSetup429.kt",
        ]
        files.extend(profile_surfaces)
        self.assertTrue(files)
        for path in files:
            text = path.read_text(encoding="utf-8")
            self.assertNotIn("market://", text, f"{path}: helper/profile setup must not open Play")
            self.assertNotIn("play.google.com", text, f"{path}: helper/profile setup must not open Play")
        profile = "\n".join(path.read_text(encoding="utf-8") for path in profile_surfaces)
        self.assertIn('contentDescription = "Back"', profile)
        self.assertNotIn("Shelter", profile)
        self.assertNotIn("Island", profile)

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
        self.assertEqual(set(), unexplained, f"Unexplained manifest permissions: {sorted(unexplained)}")

    def test_no_manifest_can_silently_expand_into_sensitive_domains(self):
        forbidden_fragments = (
            "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CALL_LOG", "WRITE_CALL_LOG",
            "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALENDAR", "WRITE_CALENDAR",
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
        )
        for path in (self.app_manifest, self.diagnostics_manifest):
            text = path.read_text(encoding="utf-8")
            for fragment in forbidden_fragments:
                self.assertNotIn(fragment, text, f"{path.name} unexpectedly requests {fragment}")

    def test_sms_trigger_receiver_is_not_exposed_anywhere_duplicate_guard(self):
        # Keep an explicit duplicate-name regression guard for older manifests that may be merged in locally.
        for manifest in (self.app_manifest, self.diagnostics_manifest):
            self.assertNotIn("com.mobilerun.portal.triggers.TriggerSmsReceiver", receiver_names(manifest))

    def test_every_declared_permission_is_infrastructure_or_has_a_setup_row_duplicate_guard(self):
        declared = declared_permissions(self.app_manifest) | declared_permissions(self.diagnostics_manifest)
        self.assertEqual(set(), declared - INFRASTRUCTURE_PERMISSIONS - SETUP_ROW_PERMISSIONS)


if __name__ == "__main__":
    unittest.main()
