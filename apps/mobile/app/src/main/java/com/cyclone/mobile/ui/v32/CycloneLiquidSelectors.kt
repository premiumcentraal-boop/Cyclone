package com.cyclone.mobile.ui.v32

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Discrete choice control for 2-4 mutually-exclusive options.
 *
 * One Kyant liquid tray owns the whole control and one refractive lens moves between choices. This
 * avoids the row-of-independent-pills look and keeps selection motion physically coherent. A null
 * selected index is intentional: it represents a provider/model default without falsely lighting an
 * override choice.
 */
@Composable
internal fun CycloneLiquidChoiceBar(
    options: List<String>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (options.isEmpty()) return
    val height = if (compact) 44.dp else 50.dp
    val lensHeight = if (compact) 36.dp else 42.dp

    CycloneLiquidTray(modifier = modifier, height = height, contentPadding = 4.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
            selectedIndex?.takeIf { it in options.indices }?.let { index ->
                CycloneLiquidSelectionLens(
                    selectedIndex = index,
                    itemCount = options.size,
                    totalWidth = maxWidth,
                    modifier = Modifier.align(Alignment.CenterStart),
                    height = lensHeight,
                )
            }
            Row(
                Modifier.fillMaxWidth().fillMaxHeight().selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEachIndexed { index, label ->
                    val active = selectedIndex == index
                    val color by animateColorAsState(
                        targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(170),
                        label = "Cyclone liquid choice tint",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = active,
                                role = Role.RadioButton,
                                onClick = { onSelect(index) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 5.dp),
                            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                            color = color,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact binary control made from the same tray + moving refractive lens as the rest of Cyclone.
 * The control keeps the familiar switch footprint without introducing a stock Material thumb.
 */
@Composable
internal fun CycloneLiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    CycloneLiquidTray(
        modifier = modifier.width(64.dp),
        height = 40.dp,
        contentPadding = 4.dp,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        ) {
            CycloneLiquidSelectionLens(
                selectedIndex = if (checked) 1 else 0,
                itemCount = 2,
                totalWidth = maxWidth,
                modifier = Modifier.align(Alignment.CenterStart),
                height = 32.dp,
            )
            if (checked) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(maxWidth / 2)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * A single menu trigger rendered as one liquid object. The popup itself remains a Material menu;
 * only the persistent interactive chrome uses the refractive treatment.
 */
@Composable
internal fun CycloneLiquidMenuTrigger(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    CycloneLiquidTray(
        modifier = modifier,
        height = if (compact) 48.dp else 56.dp,
        contentPadding = 4.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (compact) {
                    Text(
                        value,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Choose $title",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
