package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the 4.4.8 Apple-like visual cleanup: one shell, one theme, one page inset. */
class CycloneAppleUiContractTest {
    @Test fun retiredVersionedShellsAreGone() {
        val names = uiDir().list()?.toSet().orEmpty()
        listOf(
            "CycloneMobileV23App.kt",
            "CycloneMobileV24App.kt",
            "CycloneMobileV25App.kt",
            "CycloneMobileV26App.kt",
            "CycloneMobileV27App.kt",
            "CycloneMobileV291App.kt",
            "CycloneMobileV292App.kt",
            "SetupExperience.kt",
            "CycloneAlpineBackdrop.kt",
            "GatewayAiCard.kt",
            "SetupComposeCompat.kt",
        ).forEach { name ->
            assertFalse("retired UI shell still present: $name", names.contains(name) || File(uiDir(), name).isFile)
        }
        assertFalse(File(uiDir(), "v31").isDirectory)
        assertFalse(File(uiDir(), "modules").isDirectory)
        assertFalse(File(uiDir(), "GatewayAiCard.kt").isFile)
        assertFalse(File(uiDir(), "v32/CycloneAlpineBackdrop.kt").isFile)
        val features = source("CycloneV32FeaturePages.kt")
        assertTrue(features.contains("internal fun V32TeachPage"))
        assertFalse(features.contains("internal fun V32AiPage"))
        assertFalse(features.contains("internal fun V32BrainPage"))
        assertFalse(features.contains("internal fun V32SettingsPage"))
        assertTrue(File(uiDir(), "CycloneMobileApp.kt").isFile)
        val theme = File(uiDir(), "CycloneMobileApp.kt").readText()
        assertTrue(theme.contains("CycloneIdentityTheme"))
        assertFalse(theme.contains("dynamicLightColorScheme"))
        assertFalse(theme.contains("fun CycloneMobileApp()"))
    }

    @Test fun homeAndAskShareOneAttachmentMenu() {
        val home = source("CycloneHomeComposer.kt")
        val ask = source("CycloneV39AiChatPage.kt")
        assertTrue(home.contains("internal fun CycloneAttachmentTools"))
        assertTrue(home.contains("\"Camera\""))
        assertTrue(home.contains("\"Photos\""))
        assertTrue(home.contains("filesLabel: String = \"Files\""))
        assertFalse(home.contains("\"Files & photos\""))
        assertTrue(home.contains("\"Share screen\""))
        assertTrue(ask.contains("CycloneAttachmentTools("))
        assertFalse(ask.contains("Text(\"File\")"))
        assertFalse(ask.contains("Text(\"Photo\")"))
    }

    @Test fun pagesDoNotDoublePadTheTabBar() {
        listOf(
            "CycloneV32App.kt",
            "CycloneProfilesPage.kt",
            "CycloneRoutinesPage.kt",
            "CycloneV39BrainPage.kt",
            "CycloneFollowMePage.kt",
        ).forEach { name ->
            val text = source(name)
            assertTrue("$name should use cyclonePageInsets", text.contains("cyclonePageInsets("))
            assertFalse("$name still hard-codes 96.dp page padding", text.contains("bottom = 96.dp"))
        }
    }

    @Test fun activityDoesNotStackAnUnthemedRescueBarAboveScaffold() {
        val main = sequenceOf(
            File("src/main/java/com/cyclone/mobile/MainActivity.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/MainActivity.kt"),
        ).first { it.isFile }.readText()
        assertFalse(main.contains("ProfileRescueBar()"))
        assertTrue(main.contains("CycloneMobileV32App()"))
    }

    @Test fun consumerPagesShareOneLargeTitleAndNativeBack() {
        val design = source("CycloneV32DesignSystem.kt")
        assertTrue(design.contains("fun CyclonePageHeader("))
        assertTrue(design.contains("fun CycloneBackRow("))
        assertTrue(design.contains("fun CycloneHairline("))
        assertTrue(design.contains("headlineLarge = TextStyle(fontSize = 32.sp"))
        assertTrue(design.contains("defaultElevation = 0.dp"))
        assertTrue(design.contains("shadowElevation = 0.dp"))
        assertFalse(design.contains("eyebrow.uppercase()"))

        listOf(
            "CycloneV32App.kt",
            "CycloneProfilesPage.kt",
            "CycloneRoutinesPage.kt",
            "CycloneV39BrainPage.kt",
        ).forEach { name ->
            val text = source(name)
            assertTrue("$name should use CyclonePageHeader", text.contains("CyclonePageHeader("))
            assertFalse("$name still uses ASCII back chevrons", text.contains("‹"))
        }
        assertTrue(source("CycloneSettings426.kt").contains("CycloneHairline("))
        assertTrue(source("CycloneSettings426.kt").contains("cyclonePageInsets("))
        assertFalse(source("CycloneSettings426.kt").contains("shadowElevation = 1.dp"))
    }

    @Test fun askEmptyStateIsAProductGreetingNotAQuoteCard() {
        val ask = source("CycloneV39AiChatPage.kt")
        assertTrue(ask.contains("\"Ask Cyclone\""))
        assertTrue(ask.contains("\"Good morning\""))
        assertTrue(ask.contains("\"Good afternoon\""))
        assertTrue(ask.contains("\"Good evening\""))
        assertTrue(ask.contains("\"What can I do for you?\""))
        assertTrue(ask.contains("AskCycloneDotField(Modifier.matchParentSize())"))
        assertFalse(ask.contains("CycloneAlpineBackdrop"))
        assertFalse(ask.contains("progress today"))
        assertFalse(ask.contains("Ideas become real"))
        assertFalse(ask.contains("Text(\"You\""))
    }

    @Test fun liquidSelectorsAreOneObjectWithChromaticMotion() {
        val chrome = source("CycloneLiquidChrome.kt")
        val selectors = source("CycloneLiquidSelectors.kt")
        val overlay = sequenceOf(
            File("src/main/java/com/cyclone/mobile/ui/overlay/OverlayAppleLiquidComposer.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayAppleLiquidComposer.kt"),
        ).first { it.isFile }.readText()
        assertTrue(chrome.contains("LocalCycloneInsideLiquidHost"))
        assertTrue(chrome.contains("chromaticAberration = true"))
        assertTrue(chrome.contains("spring(dampingRatio = 0.84f, stiffness = 420f)"))
        assertTrue(chrome.contains("0.88f"))
        assertTrue(chrome.contains("0.84f"))
        assertTrue(chrome.contains("Color.White.copy(alpha = 0.62f)"))
        assertTrue(chrome.contains("blur(8f.dp.toPx())"))
        assertTrue(chrome.contains("blur(14f.dp.toPx())"))
        assertTrue(chrome.contains("LocalCycloneOverlayChrome"))
        assertFalse(chrome.contains("Color.White.copy(alpha = 0.22f)"))
        assertFalse(chrome.contains("Color.White.copy(alpha = 0.25f)"))
        assertFalse(chrome.contains("Color.White.copy(alpha = 0.48f)"))
        assertFalse(chrome.contains("chromaticAberration = false"))
        assertFalse(chrome.contains("Color.Black.copy(alpha = 0.035f)"))
        assertTrue(selectors.contains("LocalCycloneInsideLiquidHost.current"))
        assertTrue(selectors.contains("if (embedded)"))
        assertTrue(overlay.contains("chromaticAberration = true"))
        assertTrue(overlay.contains("OverlayDarkScheme"))
        assertTrue(overlay.contains("0.90f"))
        assertTrue(overlay.contains("blur(16f.dp.toPx())"))
        assertTrue(overlay.contains("LocalCycloneOverlayChrome provides true"))
        assertFalse(overlay.contains("chromaticAberration = false"))
        assertFalse(overlay.contains("OverlayGlassRim"))
        val overlayChrome = sequenceOf(
            File("src/main/java/com/cyclone/mobile/ui/overlay/OverlayChrome.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChrome.kt"),
        ).first { it.isFile }.readText()
        assertTrue(overlayChrome.contains("ComposerAccessory.MODEL -> OverlayAppleGlass("))
        assertFalse(overlayChrome.contains("ComposerAccessory.MODEL -> CycloneLiquidPanel("))
    }

    @Test fun overlayIdleGlowStaysCoolNotMagenta() {
        val overlay = sequenceOf(
            File("src/main/java/com/cyclone/mobile/ui/overlay/OverlayChrome.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChrome.kt"),
        ).first { it.isFile }.readText()
        assertTrue(overlay.contains("AuroraBlue"))
        assertTrue(overlay.contains("AuroraCyan"))
        assertFalse(overlay.contains("0xFFE56CFF"))
        assertFalse(overlay.contains("0xFF8568FF"))
        assertFalse(overlay.contains("AuroraMagenta"))
        assertFalse(overlay.contains("AuroraViolet"))
    }

    private fun uiDir(): File = sequenceOf(
        File("src/main/java/com/cyclone/mobile/ui"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui"),
    ).first { it.isDirectory }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/ui/v32/$name"
        return sequenceOf(File(relative), File("apps/mobile/app/$relative"))
            .first { it.isFile }.readText()
    }
}
