package com.cyclone.mobile.ui.v32

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.capsule.ContinuousCapsule

/**
 * Cyclone semantic Liquid Chrome.
 *
 * One optical object owns each interaction region. Content cards remain quiet Material surfaces.
 * Trays deliberately use Kyant's restrained blur/lens recipe: the previous 8dp blur + 24/24 lens
 * created a thick second border on white backgrounds and made controls look nested inside boxes.
 */
@Composable
internal fun CycloneLiquidTray(
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    contentPadding: Dp = 4.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalCycloneLiquidBackdrop.current
    val dark = isSystemInDarkTheme()
    val container = if (dark) Color(0xFF121212).copy(alpha = 0.30f) else Color.White.copy(alpha = 0.22f)

    if (backdrop == null) {
        Box(
            modifier
                .height(height)
                .fillMaxWidth()
                .background(container, ContinuousCapsule)
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
            content = content,
        )
        return
    }

    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { ContinuousCapsule },
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx())
                },
                onDrawSurface = { drawRect(container) },
            )
            .height(height)
            .fillMaxWidth()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Variable-height chrome for the Ask Cyclone overlay and other genuine floating panels. */
@Composable
internal fun CycloneLiquidPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 32.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalCycloneLiquidBackdrop.current
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(cornerRadius)
    val container = if (dark) Color(0xFF121212).copy(alpha = 0.34f) else Color.White.copy(alpha = 0.25f)
    val base = modifier.fillMaxWidth()

    if (backdrop == null) {
        Box(
            base.background(container, shape).padding(contentPadding),
            contentAlignment = Alignment.Center,
            content = content,
        )
        return
    }

    Box(
        base
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 22f.dp.toPx())
                },
                onDrawSurface = { drawRect(container) },
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * Selected lens inside a shared tray. A fixed inset keeps the lens visibly detached from the outer
 * glass edge; it should read as refraction moving inside one object, never as a second box.
 */
@Composable
internal fun CycloneLiquidSelectionLens(
    selectedIndex: Int,
    itemCount: Int,
    totalWidth: Dp,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    horizontalInset: Dp = 3.dp,
) {
    if (itemCount <= 0) return
    val backdrop = LocalCycloneLiquidBackdrop.current
    val itemWidth = totalWidth / itemCount
    val safeInset = horizontalInset.coerceAtMost(itemWidth / 4)
    val lensWidth = (itemWidth - safeInset * 2).coerceAtLeast(1.dp)
    val targetOffset by animateDpAsState(
        targetValue = itemWidth * selectedIndex.coerceIn(0, itemCount - 1) + safeInset,
        animationSpec = tween(240),
        label = "Cyclone liquid selection",
    )
    val dark = isSystemInDarkTheme()
    val surface = if (dark) Color.White.copy(alpha = 0.075f) else Color.Black.copy(alpha = 0.035f)
    val base = modifier.offset(x = targetOffset).width(lensWidth).height(height)

    if (backdrop == null) {
        Box(base.background(surface, ContinuousCapsule))
        return
    }

    Box(
        base.drawBackdrop(
            backdrop = backdrop,
            shape = { ContinuousCapsule },
            effects = { lens(7f.dp.toPx(), 11f.dp.toPx(), chromaticAberration = false) },
            onDrawSurface = { drawRect(surface) },
        ),
    )
}

/** Compact neutral/prominent liquid action for chrome-level text actions. */
@Composable
internal fun CycloneLiquidTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = false,
) {
    val backdrop = LocalCycloneLiquidBackdrop.current
    val dark = isSystemInDarkTheme()
    val neutral = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.055f)
    if (backdrop == null) {
        val shape = RoundedCornerShape(999.dp)
        val surface = if (prominent) {
            MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .45f)
        } else {
            neutral.copy(alpha = neutral.alpha * if (enabled) 1f else .45f)
        }
        Box(
            modifier
                .heightIn(min = 44.dp)
                .background(surface, shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = (if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    .copy(alpha = if (enabled) 1f else .55f),
            )
        }
        return
    }
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        tint = if (prominent) MaterialTheme.colorScheme.primary else Color.Unspecified,
        surfaceColor = if (prominent) Color.Unspecified else neutral,
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Destructive action stays liquid but does not masquerade as the app's blue primary action. */
@Composable
internal fun CycloneLiquidDestructiveAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val backdrop = LocalCycloneLiquidBackdrop.current
    val dark = isSystemInDarkTheme()
    val surface = MaterialTheme.colorScheme.error.copy(alpha = if (dark) 0.18f else 0.10f)
    if (backdrop == null) {
        val shape = RoundedCornerShape(999.dp)
        Box(
            modifier
                .heightIn(min = 44.dp)
                .background(surface, shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else .55f),
            )
        }
        return
    }
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        surfaceColor = surface,
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
    }
}

/** One refractive search object; there is no inner field container or second visible outline. */
@Composable
internal fun CycloneLiquidSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    CycloneLiquidTray(modifier = modifier, height = 52.dp, contentPadding = 3.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .semantics { contentDescription = placeholder },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        field()
                    }
                },
            )
            if (value.isNotEmpty()) {
                CycloneTrayIconAction(onClick = { onValueChange("") }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Small filter is a glass control; rows remain plain content. */
@Composable
internal fun CycloneLiquidFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backdrop = LocalCycloneLiquidBackdrop.current
    val dark = isSystemInDarkTheme()
    val neutral = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.055f)
    if (backdrop == null) {
        val shape = RoundedCornerShape(999.dp)
        Box(
            modifier
                .heightIn(min = 40.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .18f) else neutral, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        return
    }
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        tint = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
        surfaceColor = if (selected) Color.Unspecified else neutral,
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Transparent hit target for secondary icons living inside one liquid object. */
@Composable
internal fun CycloneTrayIconAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
