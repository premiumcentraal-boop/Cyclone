package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneTealGlassV2Test {
    private val frame = 1_000_000_000L / 60

    @Test fun livingBackdropIsCappedAt30FpsAndNeverJumpsOnResume() {
        val (c0, s0) = TealMatrixField.advance(10f, 0L, 5_000L)
        assertEquals(10f, c0, 0f)
        assertEquals(5_000L, s0)
        // One 60 Hz vsync later: below the 30 fps interval, clock holds.
        val (c1, s1) = TealMatrixField.advance(c0, s0, s0 + frame)
        assertEquals(10f, c1, 0f)
        assertEquals(s0, s1)
        // Two vsyncs: advances by the real elapsed time.
        val (c2, s2) = TealMatrixField.advance(c1, s1, s1 + 2 * frame)
        assertEquals(10f + 2f / 60f, c2, 1e-4f)
        assertEquals(s1 + 2 * frame, s2)
        // Returning after 5 minutes in the background advances at most 0.1 s.
        val (c3, _) = TealMatrixField.advance(c2, s2, s2 + 300_000_000_000L)
        assertEquals(c2 + 0.1f, c3, 1e-4f)
    }

    @Test fun removeAnimationsFreezesTheField() {
        assertTrue(TealMatrixField.motionReduced(0f))
        assertFalse(TealMatrixField.motionReduced(1f))
        assertFalse(TealMatrixField.motionReduced(0.5f))
    }

    @Test fun matrixDotsAreFixedAndOnlyBrightnessBreathes() {
        val sksl = TealMatrixField.SOURCE
        assertTrue(sksl.contains("float2 cell = floor(xy / cellPx);"))
        // Dot position depends on the pixel grid only; time only modulates brightness.
        val placement = sksl.substringAfter("float2 cell = floor(xy / cellPx);").substringBefore("float lit")
        assertFalse(Regex("\\bt\\b").containsMatchIn(placement))
        assertTrue(sksl.contains("col += (h(xy + t) - 0.5) / 255.0;"))
    }

    @Test fun realGlassIsReservedForTheControlsLayer() {
        val glass = source("CycloneSignatureGlass.kt")
        assertTrue(glass.contains("val backdrop = if (refract && !solidBacking) LocalCycloneLiquidBackdrop.current else null"))
        assertTrue(glass.contains("highlight = { Highlight.Default }"))
        assertTrue(glass.contains("blur(14f.dp.toPx())"))
        assertTrue(glass.contains("if (CycloneGlassOptics.lens) lens("))
        // Controls refract the living canvas.
        assertTrue(source("CycloneLiquidChrome.kt").contains("refract = true,"))
        assertTrue(source("CycloneHomeComposer.kt").contains("refract = true,"))
        val matrix = source("CycloneTealMatrix.kt")
        assertTrue(matrix.substringAfter("fun CycloneMatrixQuickAction(").contains("refract = true,"))
        assertTrue(matrix.substringAfter("fun CycloneMatrixQuickAction(").contains("focused = pressed,"))
        // Content cards stay matte and readable.
        assertTrue(matrix.substringAfter("fun CycloneMatrixCard(").substringBefore("fun CycloneMatrixIconTile(").contains("solidBacking = true,"))
        assertTrue(source("CycloneV32DesignSystem.kt").contains("TealMatrixBackdrop(Modifier.fillMaxSize().layerBackdrop(liquidBackdrop))"))
        assertTrue(source("CycloneTealMatrixField.kt").contains("TealMatrixStaticBackdrop(modifier)"))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/ui/v32/$name"
        return sequenceOf(File(relative), File("apps/mobile/app/$relative")).first { it.isFile }.readText()
    }
}
