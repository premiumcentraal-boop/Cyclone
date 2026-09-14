@file:Suppress("PackageDirectoryMismatch")

package androidx.compose.material3

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.v32.CycloneKyantLiquidButton
import com.cyclone.mobile.ui.v32.CycloneKyantLiquidIconButton
import com.cyclone.mobile.ui.v32.LocalCycloneLiquidBackdrop

/**
 * Cyclone-wide Material 3 compatibility layer.
 *
 * These deliberately narrower overloads win for the ordinary Button/IconButton calls used by the
 * product without requiring every feature page to carry a custom component import. Rendering is
 * still Kyant0/AndroidLiquidGlass Backdrop + the published LiquidButton effect recipe; this file
 * only maps Material button intent to the upstream tint/surfaceColor knobs.
 */

@Composable
private fun neutralGlassSurface(lightAlpha: Float, darkAlpha: Float): Color =
    MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (isSystemInDarkTheme()) darkAlpha else lightAlpha,
    )

@Composable
private fun requireCycloneBackdrop() = requireNotNull(LocalCycloneLiquidBackdrop.current) {
    "Cyclone Liquid Glass button rendered outside CycloneTheme/Backdrop host"
}

@Composable
private fun LiquidContent(contentColor: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    val tint = MaterialTheme.colorScheme.primary
    val contentColor = MaterialTheme.colorScheme.onPrimary
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        tint = tint,
    ) {
        LiquidContent(contentColor) { content() }
    }
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        tint = MaterialTheme.colorScheme.primaryContainer,
    ) {
        LiquidContent(MaterialTheme.colorScheme.onPrimaryContainer) { content() }
    }
}

@Composable
fun ElevatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        surfaceColor = neutralGlassSurface(lightAlpha = 0.11f, darkAlpha = 0.15f),
    ) {
        LiquidContent(MaterialTheme.colorScheme.onSurface) { content() }
    }
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        // This is Kyant's own optional surfaceColor path. A small on-surface wash is what keeps
        // the refractive lens readable on pure-white cards/backgrounds without inventing a border.
        surfaceColor = neutralGlassSurface(lightAlpha = 0.09f, darkAlpha = 0.13f),
    ) {
        LiquidContent(MaterialTheme.colorScheme.primary) { content() }
    }
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        surfaceColor = neutralGlassSurface(lightAlpha = 0.055f, darkAlpha = 0.085f),
    ) {
        LiquidContent(MaterialTheme.colorScheme.primary) { content() }
    }
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    CycloneKyantLiquidIconButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        enabled = enabled,
        surfaceColor = neutralGlassSurface(lightAlpha = 0.07f, darkAlpha = 0.11f),
    ) {
        LiquidContent(MaterialTheme.colorScheme.onSurface) { content() }
    }
}

@Composable
fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    CycloneKyantLiquidIconButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        enabled = enabled,
        tint = MaterialTheme.colorScheme.primary,
    ) {
        LiquidContent(MaterialTheme.colorScheme.onPrimary) { content() }
    }
}

@Composable
fun FilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    CycloneKyantLiquidIconButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        enabled = enabled,
        tint = MaterialTheme.colorScheme.primaryContainer,
    ) {
        LiquidContent(MaterialTheme.colorScheme.onPrimaryContainer) { content() }
    }
}

@Composable
fun OutlinedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    CycloneKyantLiquidIconButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        enabled = enabled,
        surfaceColor = neutralGlassSurface(lightAlpha = 0.09f, darkAlpha = 0.13f),
    ) {
        LiquidContent(MaterialTheme.colorScheme.primary) { content() }
    }
}
