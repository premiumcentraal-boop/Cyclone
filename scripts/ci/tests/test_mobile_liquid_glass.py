from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
APP_BUILD = ROOT / "apps/mobile/app/build.gradle.kts"
V32 = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32"
RENDERER = V32 / "CycloneKyantLiquidGlass.kt"
OVERRIDES = ROOT / "apps/mobile/app/src/main/java/androidx/compose/material3/CycloneLiquidGlassOverrides.kt"
THEME = V32 / "CycloneV32DesignSystem.kt"
LIQUID = V32 / "CycloneLiquidChrome.kt"
SELECTORS = V32 / "CycloneLiquidSelectors.kt"
HOME = V32 / "CycloneHomeComposer.kt"
APP = V32 / "CycloneV32App.kt"
AI = V32 / "CycloneV39AiChatPage.kt"
INTELLIGENCE = V32 / "CycloneIntelligenceControls.kt"
REASONING = V32 / "CycloneReasoningSelector.kt"
ROUTINES = V32 / "CycloneRoutinesPage.kt"
COMPONENTS = V32 / "CycloneV32Components.kt"
OVERLAY_ROOT = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay"
OVERLAY = OVERLAY_ROOT / "OverlayChrome.kt"
OVERLAY_APPLE = OVERLAY_ROOT / "OverlayAppleLiquidComposer.kt"
OVERLAY_CONTRACT = OVERLAY_ROOT / "OverlayChromeContract.kt"


class MobileLiquidGlassGuards(unittest.TestCase):
    def test_444_identity_and_runtime_contract_are_preserved(self):
        # Repair work stays on top of the immutable published 4.4.4 source until a release is cut.
        build = APP_BUILD.read_text(encoding="utf-8")
        self.assertIn('versionCode = 104', build)
        self.assertIn('versionName = "4.4.4"', build)
        self.assertIn('minSdk = 33', build)
        self.assertIn('targetSdk = 35', build)
        self.assertIn('compileSdk = 36', build)
        self.assertIn('implementation("io.github.kyant0:backdrop:1.0.0")', build)
        self.assertIn('implementation("io.github.kyant0:capsule:2.1.1")', build)

    def test_renderer_keeps_upstream_liquid_button_recipe(self):
        source = RENDERER.read_text(encoding="utf-8")
        for token in (
            "ContinuousCapsule",
            "vibrancy()",
            "blur(2f.dp.toPx())",
            "lens(12f.dp.toPx(), 24f.dp.toPx())",
            "BlendMode.Hue",
            "RuntimeShader",
            "tanh(initialDerivative * offset.x / maxOffset)",
            "tanh(initialDerivative * offset.y / maxOffset)",
        ):
            self.assertIn(token, source)

    def test_material_action_families_route_through_liquid_layer(self):
        source = OVERRIDES.read_text(encoding="utf-8")
        for component in (
            "fun Button(",
            "fun FilledTonalButton(",
            "fun ElevatedButton(",
            "fun OutlinedButton(",
            "fun TextButton(",
            "fun IconButton(",
            "fun FilledIconButton(",
            "fun FilledTonalIconButton(",
            "fun OutlinedIconButton(",
        ):
            self.assertIn(component, source)
        self.assertIn("surfaceColor = surface", source)

    def test_material_actions_have_safe_transparent_overlay_fallback(self):
        source = OVERRIDES.read_text(encoding="utf-8")
        self.assertNotIn("requireCycloneBackdrop", source)
        self.assertIn("private fun FallbackButton(", source)
        self.assertIn("private fun FallbackIconButton(", source)
        self.assertGreaterEqual(source.count("if (backdrop == null)"), 9)
        self.assertIn(".clickable(enabled = enabled, role = Role.Button, onClick = onClick)", source)

    def test_custom_material_shape_and_padding_still_resolve_to_kyant_glass(self):
        source = OVERRIDES.read_text(encoding="utf-8")
        self.assertIn("shape: Shape", source)
        self.assertIn("contentPadding: PaddingValues", source)
        self.assertIn("contentPadding = contentPadding", source)
        renderer = RENDERER.read_text(encoding="utf-8")
        self.assertIn("shape = { ContinuousCapsule }", renderer)
        self.assertIn(".padding(contentPadding)", renderer)

    def test_theme_owns_one_non_recursive_backdrop_source(self):
        source = THEME.read_text(encoding="utf-8")
        self.assertIn("rememberLayerBackdrop()", source)
        self.assertIn("LocalCycloneLiquidBackdrop provides liquidBackdrop", source)
        self.assertIn("layerBackdrop(liquidBackdrop)", source)

    def test_overlay_theme_is_transparent_and_content_sized(self):
        theme = THEME.read_text(encoding="utf-8")
        overlay = OVERLAY.read_text(encoding="utf-8")
        self.assertIn("CycloneV32Theme(drawBackground = false)", overlay)
        self.assertIn("Box(Modifier.wrapContentSize())", theme)
        self.assertIn("LocalCycloneLiquidBackdrop provides null", theme)
        self.assertIn("if (drawBackground)", theme)
        transparent_branch = theme.split("} else {", 1)[1]
        self.assertNotIn("Modifier.fillMaxSize()", transparent_branch.split("enum class CyclonePastel", 1)[0])
        self.assertNotIn("layerBackdrop(liquidBackdrop)", transparent_branch.split("enum class CyclonePastel", 1)[0])

    def test_transparent_overlay_fallback_controls_remain_visible_and_interactive(self):
        source = LIQUID.read_text(encoding="utf-8")
        selection = source.split("internal fun CycloneLiquidSelectionLens(", 1)[1].split("/** Compact neutral", 1)[0]
        text_action = source.split("internal fun CycloneLiquidTextAction(", 1)[1].split("/** Destructive", 1)[0]
        destructive = source.split("internal fun CycloneLiquidDestructiveAction(", 1)[1].split("/** One refractive", 1)[0]
        filter_chip = source.split("internal fun CycloneLiquidFilterChip(", 1)[1].split("/** Transparent hit", 1)[0]
        self.assertIn("Box(base.background(surface, ContinuousCapsule))", selection)
        self.assertNotIn("if (backdrop == null) return", text_action)
        self.assertNotIn("?: return", destructive)
        self.assertNotIn("?: return", filter_chip)
        self.assertIn(".clickable(enabled = enabled, role = Role.Button, onClick = onClick)", text_action)

    def test_overlay_resting_composer_matches_single_apple_style_glass_bar(self):
        overlay = OVERLAY.read_text(encoding="utf-8")
        apple = OVERLAY_APPLE.read_text(encoding="utf-8")
        contract = OVERLAY_CONTRACT.read_text(encoding="utf-8")
        self.assertIn("OverlayAppleComposerBar(", overlay)
        self.assertIn("OverlayAppleToolsMenu(", overlay)
        self.assertIn("const val COMPOSER_HEIGHT_DP = 66", contract)
        self.assertIn("height(66.dp)", apple)
        self.assertIn("cornerRadius = 33.dp", apple)
        self.assertIn('contentDescription = "Ask Cyclone"', apple)
        self.assertIn('working -> "Stop task"', apple)
        self.assertIn('else -> "Start voice request"', apple)
        self.assertIn("OverlayVoiceWaveform()", apple)
        for label in ("Camera", "Files & photos", "Share screen", "Cross-app share", "Model & intelligence"):
            self.assertIn(f'"{label}"', apple)
        self.assertIn("OverlayGlassStrong", apple)
        self.assertIn("OverlayGlassRim", apple)

    def test_liquid_selection_is_inset_not_a_second_full_box(self):
        liquid = LIQUID.read_text(encoding="utf-8")
        selectors = SELECTORS.read_text(encoding="utf-8")
        components = COMPONENTS.read_text(encoding="utf-8")
        self.assertIn("horizontalInset: Dp = 3.dp", liquid)
        self.assertIn("lensWidth = (itemWidth - safeInset * 2)", liquid)
        self.assertIn("chromaticAberration = false", liquid)
        self.assertIn("horizontalInset = 4.dp", selectors)
        self.assertIn("height = 34.dp", components)
        self.assertIn("CycloneLiquidTray(modifier = modifier, height = 44.dp", components)

    def test_ai_settings_never_put_backdrop_controls_in_popup_windows(self):
        for path in (AI, INTELLIGENCE, REASONING):
            source = path.read_text(encoding="utf-8")
            self.assertNotIn("DropdownMenu(", source, path.name)
            self.assertNotIn("DropdownMenuItem(", source, path.name)
        intelligence = INTELLIGENCE.read_text(encoding="utf-8")
        self.assertIn("if (intelligenceOpen)", AI.read_text(encoding="utf-8"))
        self.assertIn("CycloneLiquidPanel(", AI.read_text(encoding="utf-8"))
        self.assertIn(".heightIn(max = 320.dp)", intelligence)
        self.assertIn(".verticalScroll(rememberScrollState())", intelligence)

    def test_home_launcher_is_compact_and_has_one_settings_action(self):
        home = HOME.read_text(encoding="utf-8")
        app = APP.read_text(encoding="utf-8")
        self.assertNotIn("height = 112.dp", home)
        self.assertIn("cornerRadius = 28.dp", home)
        self.assertNotIn("CycloneIntelligenceControls(", home)
        self.assertIn('label = "Settings · $readinessLabel"', app)
        self.assertNotIn('TextButton(onClick = onSettings) { Text("Settings") }', app)

    def test_routines_use_one_organization_control_and_no_popup_create_dialog(self):
        source = ROUTINES.read_text(encoding="utf-8")
        self.assertIn('listOf("Apps", "Categories", "All")', source)
        self.assertNotIn("AlertDialog(", source)
        self.assertNotIn("grouped by", source.lower())
        self.assertIn("CycloneLiquidPanel(", source)

    def test_routine_switches_use_liquid_toggle(self):
        app = APP.read_text(encoding="utf-8")
        components = COMPONENTS.read_text(encoding="utf-8")
        self.assertIn("CycloneLiquidToggle(enabled", app)
        self.assertIn("CycloneLiquidToggle(checked = automation.enabled", components)
        self.assertNotIn("Switch(checked = automation.enabled", components)
        self.assertNotIn("Switch(enabled", app)

    def test_only_known_lint_crashes_are_suppressed(self):
        build = APP_BUILD.read_text(encoding="utf-8")
        expected = {
            'disable += "RememberInComposition"',
            'disable += "NullSafeMutableLiveData"',
            'disable += "FrequentlyChangingValue"',
            'disable += "AutoboxingStateCreation"',
        }
        disabled = {
            line.strip()
            for line in build.splitlines()
            if line.strip().startswith("disable +=")
        }
        self.assertEqual(disabled, expected)
        self.assertNotIn("abortOnError = false", build)
        self.assertNotIn("checkReleaseBuilds = false", build)


if __name__ == "__main__":
    unittest.main()
