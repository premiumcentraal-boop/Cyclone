#!/usr/bin/env python3
"""Fail-fast guard for Cyclone Mobile's production product surfaces."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32App.kt"
FEATURES = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32FeaturePages.kt"
SETTINGS_426 = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneSettings426.kt"
AI_CHAT = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt"
BRAIN_V39 = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV39BrainPage.kt"
MANIFEST = ROOT / "apps/mobile/app/src/main/AndroidManifest.xml"
MAIN = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/MainActivity.kt"
CAMERA_VIEWER = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/stream/CameraStreamViewerActivity.kt"

REQUIRED_APP = (
    "V32Destination.HOME -> V32HomePage",
    "V32Destination.PROFILES -> CycloneProfilesPage",
    "V32Destination.AI -> V39AiChatPage",
    "V32Destination.ROUTINES -> CycloneRoutinesPage",
    "V32Destination.BRAIN -> CycloneV39BrainPage",
    "CycloneSettingsPage426(context, refreshTick,",
)
REQUIRED_FEATURES = (
    "internal fun V32TeachPage",
    "internal fun V32AiPage",
    "internal fun V32BrainPage",
    "internal fun V32SettingsPage",
    "GatewayAiCard(context, refreshTick)",
    'CycloneSectionTitle("Optional PC companion")',
)
REQUIRED_SETTINGS_426 = (
    "internal fun CycloneSettingsPage426",
    'Settings426Row("Model & API"',
    'Settings426Row("Phone control"',
    'Settings426Row("Profile engine"',
    'Settings426Row("PC Gateway"',
    "CycloneModelPill",
    "CycloneAiAccessProfileStore.write",
    "CyclonePermissionSetup.phoneControlSnapshot",
    "BackgroundSetup.read",
)
REQUIRED_AI_CHAT = (
    "internal fun V39AiChatPage",
    "CycloneTextChat.answer(context",
    "RequestIntentRouter.route(",
    "WorkspaceTasks.queueRequest(normalized)",
    "OpenRouterCatalogStore.activeId(context)",
    '"Ask Cyclone…"',
    "CycloneModelPill(",
    "showModelPill = false",
)
REQUIRED_BRAIN_V39 = (
    "internal fun CycloneV39BrainPage",
    'CycloneSectionTitle("Recent outcomes")',
    "TaskResultActivityV292",
    '"View details"',
)
REQUIRED_MANIFEST = (
    'android:name=".MainActivity"',
    'android:name=".gateway.GatewaySettingsActivity"',
    'android:name=".stream.CameraStreamViewerActivity"',
    'android:name=".CycloneAccessibilityService"',
    'android:name=".CycloneNotificationListener"',
)
REQUIRED_MAIN = (
    "CycloneMobileV32App()",
    "AutomationRuntime.initialize(this)",
    "AppLearnerRuntime.initialize(this)",
    "CycloneBrainRuntime.initialize(this)",
)
REQUIRED_CAMERA_VIEWER = (
    'EXTRA_STREAM_TOKEN = "cyclone_stream_token"',
    'EXTRA_STREAM_TARGET = "cyclone_stream_target"',
    'VIEWER_TOKEN_HEADER = "X-Cyclone-Viewer-Token"',
    'VIEWER_TARGET_HEADER = "X-Cyclone-Viewer-Target"',
    ".addHeader(VIEWER_TOKEN_HEADER, streamToken)",
    ".addHeader(VIEWER_TARGET_HEADER, streamTarget)",
    "uri.query != null",
    "fitNativeAspect(",
)


def missing_tokens(text: str, required: tuple[str, ...]) -> list[str]:
    return [token for token in required if token not in text]


def check() -> list[str]:
    errors: list[str] = []
    supported_apps = {"mobile", "device-gateway", "pc-companion"}
    for path in (ROOT / "apps").iterdir():
        if path.is_dir() and path.name not in supported_apps:
            errors.append(f"Unsupported product component: apps/{path.name}")
    for path, required in (
        (APP, REQUIRED_APP),
        (FEATURES, REQUIRED_FEATURES),
        (SETTINGS_426, REQUIRED_SETTINGS_426),
        (AI_CHAT, REQUIRED_AI_CHAT),
        (BRAIN_V39, REQUIRED_BRAIN_V39),
        (MANIFEST, REQUIRED_MANIFEST),
        (MAIN, REQUIRED_MAIN),
        (CAMERA_VIEWER, REQUIRED_CAMERA_VIEWER),
    ):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as error:
            errors.append(f"{path.relative_to(ROOT)} unreadable: {error}")
            continue
        for token in missing_tokens(text, required):
            errors.append(f"{path.relative_to(ROOT)} missing invariant: {token}")
        if path in (APP, FEATURES, SETTINGS_426, AI_CHAT, MAIN):
            for retired in ("Teamwork Sniper", "TEAMWORK_SNIPER_PACKAGE", "coreWsUrl", "coreToken", "BridgeClient.start"):
                if retired in text:
                    errors.append(f"{path.relative_to(ROOT)} exposes retired integration: {retired}")
        if path == CAMERA_VIEWER and ("?token=" in text or "getQueryParameter(\"token\")" in text):
            errors.append("Camera viewer must never carry viewer credentials in its WebSocket URL")
    return errors


def main() -> int:
    errors = check()
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(
        "Cyclone mobile product invariants preserved: Home, Profiles, Ask Cyclone, Routines, "
        "Brain, utility Settings, PC Gateway, native-aspect camera viewer, accessibility and notification services"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
