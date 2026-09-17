package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneAttachmentToolsLayoutTest {
    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("apps/mobile/app/src/main/java/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate source file $relative from ${File(".").absolutePath}")
    }

    @Test
    fun inAppAttachmentChoicesAreRoomyHorizontalCards() {
        val tools = source("com/cyclone/mobile/ui/v32/CycloneHomeComposer.kt")
        assertTrue(tools.contains("horizontalScroll(rememberScrollState())"))
        assertTrue(tools.contains("CyclonePlusTile(icon, label, Modifier.width(124.dp), click)"))
        assertTrue(tools.contains("Icons.Rounded.CameraAlt, \"Camera\""))
        assertTrue(tools.contains("Icons.Rounded.PhotoLibrary, \"Photos\""))
        assertTrue(tools.contains("Icons.Rounded.AttachFile, filesLabel"))
        assertFalse(tools.contains("CyclonePlusTile(icon, label, Modifier.weight(1f), click)"))
    }

    @Test
    fun overlayAttachmentChoicesMatchCameraPhotosFilesCatalog() {
        val tools = source("com/cyclone/mobile/ui/overlay/OverlayAppleLiquidComposer.kt")
        assertTrue(tools.contains("OverlayAppleToolTile(Icons.Rounded.CameraAlt, \"Camera\""))
        assertTrue(tools.contains("OverlayAppleToolTile(Icons.Rounded.PhotoLibrary, \"Photos\""))
        assertTrue(tools.contains("OverlayAppleToolTile(Icons.Rounded.AttachFile, \"Files\""))
        assertTrue(tools.contains(".width(118.dp)"))
        assertTrue(tools.contains("horizontalScroll(rememberScrollState())"))
    }
}
