package com.cyclone.mobile.ui.v32.ask

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R4 (docs/design/redesign/rounds/R4-home.md): Home on the AI page's material, with the drawn rain and no video. */
class HomeR4ContractTest {
    private fun source(relative: String): String = listOf(
        File("src/main/java/$relative"), File("app/src/main/java/$relative"), File("apps/mobile/app/src/main/java/$relative"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Could not locate $relative")

    private val app get() = source("com/cyclone/mobile/ui/v32/CycloneV32App.kt")
    private val home get() = app.substringAfter("private fun V32HomePage(").substringBefore("internal fun V32RoutineDetail(")

    @Test fun homeIsTheRainWithoutTheVideoBehindSmokedGlass() {
        assertTrue(home.contains("com.cyclone.mobile.ui.v32.ask.AskGlassPage(withScene = false)"))
        val kit = source("com/cyclone/mobile/ui/v32/ask/AskGlassKit.kt")
        assertTrue(kit.contains("AskRainField(Modifier.matchParentSize().layerBackdrop(backdrop), withScene = withScene)"))
        assertTrue(kit.contains("LocalGlassPalette provides GlassPalette.SMOKE"))
        val rain = source("com/cyclone/mobile/ui/v32/ask/AskRain.kt")
        // Without the scene the decoder is never created, so Home plays no video and decodes nothing.
        assertTrue(rain.contains("val scene = remember(withScene) { if (withScene) AskScene(context.applicationContext) else null }"))
    }

    @Test fun homeKeepsItsContentOnTheNewComponents() {
        val home = home
        val order = listOf(
            "AskHomeHeader(onSettings = onSettings, onAi = onAi)",
            "AskGreeting(greeting, readinessBody",
            "HomeQuickActions(",
            "InAppTaskStack(current)",
            "AskSectionHeader(\"Recent activity\", \"Open chat\", onAi)",
            "AskSectionHeader(\"Your routines\", \"See all\", onRoutines)",
            "CycloneHomeComposer(seed = seed)",
        ).map { home.indexOf(it) }
        assertTrue("order $order", order.all { it >= 0 } && order == order.sorted())
        // No teal Teal Matrix components remain on Home.
        listOf("CycloneMatrixCard(", "CycloneMatrixQuickAction(", "CycloneMatrixAppBar(", "CycloneMatrixIconTile(",
            "TealMatrix.", "CycloneMatrixCheck(", "CycloneMatrixRing(").forEach {
            assertFalse("home still uses $it", home.contains(it))
        }
        // Quick actions still only fill the Ask bar.
        assertTrue(home.contains("{ onSeed(\"Plan my day\") }"))
    }

    @Test fun homeAskBarIsTheSmokedBarWithItsDots() {
        val composer = source("com/cyclone/mobile/ui/v32/CycloneHomeComposer.kt")
        assertTrue(composer.contains("val askBackdrop = com.cyclone.mobile.ui.v32.ask.LocalAskBackdrop.current"))
        assertTrue(composer.contains(".askWhorls()"))
        assertTrue(composer.contains("CycloneSignatureGlass("))
        val glass = source("com/cyclone/mobile/ui/v32/CycloneSignatureGlass.kt")
        assertTrue(glass.contains("com.cyclone.mobile.ui.v32.ask.LocalAskBackdrop.current != null -> Color.White.copy(alpha = .14f)"))
    }
}
