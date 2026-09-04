#!/usr/bin/env python3
"""Fail-fast guard for Cyclone Mobile's production product surfaces."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32App.kt"
FEATURES = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32FeaturePages.kt"
AI_CHAT = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt"
BRAIN_V39 = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV39BrainPage.kt"
MANIFEST = ROOT / "apps/mobile/app/src/main/AndroidManifest.xml"
MAIN = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/MainActivity.kt"

REQUIRED_APP = (
    "V32Destination.HOME -> V32HomePage",
    "V32Destination.TEACH -> V32TeachPage",
    "V32Destination.AI -> V39AiChatPage",
    "V32Destination.ROUTINES -> V32RoutinesPage",
    "V32Destination.BRAIN -> CycloneV39BrainPage",
    "V32SettingsPage(context, refreshTick)",
)
REQUIRED_FEATURES = (
    "internal fun V32TeachPage",
    "internal fun V32AiPage",
    "internal fun V32BrainPage",
    "internal fun V32SettingsPage",
    "GatewayAiCard(context, refreshTick)",
    'TEAMWORK_SNIPER_PACKAGE = "com.cyclone.teamworksniper"',
    '"Teamwork Sniper"',
)
REQUIRED_AI_CHAT = (
    "internal fun V39AiChatPage",
    "OpenRouterAdaptiveAgent(context)",
    "OpenRouterModelPresets.all",
    '"Ask Cyclone to do something…"',
)
REQUIRED_BRAIN_V39 = (
    "internal fun CycloneV39BrainPage",
    'CycloneSectionTitle("Recent runs")',
    "TaskResultActivityV292",
    '"Tap to inspect and download .txt"',
)
REQUIRED_MANIFEST = (
    'android:name=".MainActivity"',
    'android:name=".gateway.GatewaySettingsActivity"',
    'android:name=".CycloneAccessibilityService"',
    'android:name=".CycloneNotificationListener"',
)
REQUIRED_MAIN = (
    "CycloneMobileV32App()",
    "AutomationRuntime.initialize(this)",
    "AppLearnerRuntime.initialize(this)",
    "CycloneBrainRuntime.initialize(this)",
    "BridgeClient.start(this)",
)


def missing_tokens(text: str, required: tuple[str, ...]) -> list[str]:
    return [token for token in required if token not in text]


def check() -> list[str]:
    errors: list[str] = []
    for path, required in (
        (APP, REQUIRED_APP),
        (FEATURES, REQUIRED_FEATURES),
        (AI_CHAT, REQUIRED_AI_CHAT),
        (BRAIN_V39, REQUIRED_BRAIN_V39),
        (MANIFEST, REQUIRED_MANIFEST),
        (MAIN, REQUIRED_MAIN),
    ):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as error:
            errors.append(f"{path.relative_to(ROOT)} unreadable: {error}")
            continue
        for token in missing_tokens(text, required):
            errors.append(f"{path.relative_to(ROOT)} missing invariant: {token}")
    return errors


def main() -> int:
    errors = check()
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(
        "Cyclone mobile product invariants preserved: Home, Teach, 3.9 Ask Cyclone, Routines, "
        "3.9 Brain runs, Settings, PC Gateway, accessibility and notification services"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
