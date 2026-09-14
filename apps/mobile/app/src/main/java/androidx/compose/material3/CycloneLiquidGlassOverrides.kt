@file:Suppress("PackageDirectoryMismatch")

package androidx.compose.material3

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.v32.CycloneKyantLiquidButton
import com.cyclone.mobile.ui.v32.CycloneKyantLiquidIconButton
import com.cyclone.mobile.ui.v32.LocalCycloneLiquidBackdrop

/**
 * Cyclone-wide Material 3 compatibility layer.
 *
 * These deliberately narrower overloads win for the Button/IconButton call shapes used by the
 * 4.4.2 product without forcing every feature page to carry a custom import. Rendering is still
 * Kyant0/AndroidLiquidGlass Backdrop + the published LiquidButton effect recipe. Material `shape`
 * values are accepted only so existing call sites resolve here; the optical shape remains Kyant's
 * ContinuousCapsule. Material contentPadding is layout-only and is forwarded to the glass row.
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

private val KyantDefaultPadding = PaddingValues(horizontal = 16.dp)

@Composable
private fun CyclonePrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        tint = MaterialTheme.colorScheme.primary,
        contentPadding = contentPadding,
    ) {
        LiquidContent(MaterialTheme.colorScheme.onPrimary) { content() }
    }
}

@Composable
private fun CycloneTonalButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        tint = MaterialTheme.colorScheme.primaryContainer,
        contentPadding = contentPadding,
    ) {
        LiquidContent(MaterialTheme.colorScheme.onPrimaryContainer) { content() }
    }
}

@Composable
private fun CycloneElevatedButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        surfaceColor = neutralGlassSurface(lightAlpha = 0.11f, darkAlpha = 0.15f),
        contentPadding = contentPadding,
    ) {
        LiquidContent(MaterialTheme.colorScheme.onSurface) { content() }
    }
}

@Composable
private fun CycloneOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        surfaceColor = neutralGlassSurface(lightAlpha = 0.09f, darkAlpha = 0.13f),
        contentPadding = contentPadding,
    ) {
        LiquidContent(MaterialTheme.colorScheme.primary) { content() }
    }
}

@Composable
private fun CycloneTextButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = requireCycloneBackdrop()
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        surfaceColor = neutralGlassSurface(lightAlpha = 0.055f, darkAlpha = 0.085f),
        contentPadding = contentPadding,
    ) {
        LiquidContent(MaterialTheme.colorScheme.primary) { content() }
    }
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = CyclonePrimaryButton(onClick, modifier, enabled, KyantDefaultPadding, content)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) = CyclonePrimaryButton(onClick, modifier, enabled, contentPadding, content)

@Suppress("UNUSED_PARAMETER")
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape,
    contentPadding: PaddingValues = KyantDefaultPadding,
    content: @Composable RowScope.() -> Unit,
) = CyclonePrimaryButton(onClick, modifier, enabled, contentPadding, content)

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = CycloneTonalButton(onClick, modifier, enabled, KyantDefaultPadding, content)

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) = CycloneTonalButton(onClick, modifier, enabled, contentPadding, content)

@Suppress("UNUSED_PARAMETER")
@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape,
    contentPadding: PaddingValues = KyantDefaultPadding,
    content: @Composable RowScope.() -> Unit,
) = CycloneTonalButton(onClick, modifier, enabled, contentPadding, content)

@Composable
fun ElevatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = CycloneElevatedButton(onClick, modifier, enabled, KyantDefaultPadding, content)

@Composable
fun ElevatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) = CycloneElevatedButton(onClick, modifier, enabled, contentPadding, content)

@Suppress("UNUSED_PARAMETER")
@Composable
fun ElevatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape,
    contentPadding: PaddingValues = KyantDefaultPadding,
    content: @Composable RowScope.() -> Unit,
) = CycloneElevatedButton(onClick, modifier, enabled, contentPadding, content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = CycloneOutlinedButton(onClick, modifier, enabled, KyantDefaultPadding, content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) = CycloneOutlinedButton(onClick, modifier, enabled, contentPadding, content)

@Suppress("UNUSED_PARAMETER")
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape,
    contentPadding: PaddingValues = KyantDefaultPadding,
    content: @Composable RowScope.() -> Unit,
) = CycloneOutlinedButton(onClick, modifier, enabled, contentPadding, content)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = CycloneTextButton(onClick, modifier, enabled, KyantDefaultPadding, content)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) = CycloneTextButton(onClick, modifier, enabled, contentPadding, content)

@Suppress("UNUSED_PARAMETER")
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape,
    contentPadding: PaddingValues = KyantDefaultPadding,
    content: @Composable RowScope.() -> Unit,
) = CycloneTextButton(onClick, modifier, enabled, contentPadding, content)

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
