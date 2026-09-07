package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** Cyclone's own quiet aurora identity; shared by the composer and task surfaces. */
internal object CycloneIntelligenceStyle {
    val Ink = Color(0xFF101418)
    val Surface = Color(0xFF1C2228)
    val Raised = Color(0xFF252D35)
    val Text = Color(0xFFF1F4F8)
    val Muted = Color(0xFFAFBBC9)
    val Mint = Color(0xFF8DE3D2)
    val Blue = Color(0xFFA5C8FF)
    val Gradient = Brush.linearGradient(listOf(Blue, Color(0xFFC4B8FF), Mint))
}

@Composable
internal fun CycloneIntelligenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = CycloneIntelligenceStyle.Blue, onPrimary = Color(0xFF102943),
        primaryContainer = Color(0xFF293C50), onPrimaryContainer = CycloneIntelligenceStyle.Text,
        secondary = CycloneIntelligenceStyle.Mint, onSecondary = Color(0xFF103B33),
        background = CycloneIntelligenceStyle.Ink, surface = CycloneIntelligenceStyle.Surface,
        onBackground = CycloneIntelligenceStyle.Text, onSurface = CycloneIntelligenceStyle.Text,
        surfaceVariant = CycloneIntelligenceStyle.Raised, onSurfaceVariant = CycloneIntelligenceStyle.Muted,
        outlineVariant = Color(0xFF3B4652), error = Color(0xFFFFB4B0),
    )) { CompositionLocalProvider(LocalContentColor provides CycloneIntelligenceStyle.Text, content = content) }
}

/** Three open orbital strokes evoke a cyclone without borrowing another product's logo. */
@Composable
internal fun CycloneOrbitMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val width = size.minDimension
        for (index in 0..2) {
            val inset = width * (0.10f + index * 0.115f)
            drawArc(CycloneIntelligenceStyle.Gradient, -75f + index * 110f, 245f, false,
                Offset(inset, inset), Size(width - inset * 2, width - inset * 2),
                style = Stroke(width * 0.065f, cap = StrokeCap.Round))
        }
    }
}
