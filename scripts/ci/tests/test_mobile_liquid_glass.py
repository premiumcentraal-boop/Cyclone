from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
APP_BUILD = ROOT / "apps/mobile/app/build.gradle.kts"
RENDERER = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneKyantLiquidGlass.kt"
OVERRIDES = ROOT / "apps/mobile/app/src/main/java/androidx/compose/material3/CycloneLiquidGlassOverrides.kt"
THEME = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneV32DesignSystem.kt"


class MobileLiquidGlassGuards(unittest.TestCase):
    def test_442_identity_and_runtime_contract_are_preserved(self):
        build = APP_BUILD.read_text(encoding="utf-8")
        self.assertIn('versionCode = 102', build)
        self.assertIn('versionName = "4.4.2"', build)
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
        self.assertIn("surfaceColor = neutralGlassSurface", source)

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

    def test_only_known_lint_crashes_are_suppressed(self):
        build = APP_BUILD.read_text(encoding="utf-8")
        expected = {
            'disable += "RememberInComposition"',
            'disable += "NullSafeMutableLiveData"',
            'disable += "FrequentlyChangingValue"',
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
