package com.cyclone.mobile.ui.v32

import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.automation.AutomationDefinition

private const val LEGACY_ENHANCED_CONTROL_ROW = "Enhanced control engine"

enum class V32Destination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    PROFILES("Profiles", Icons.Rounded.Person),
    AI("AI", Icons.Rounded.AutoAwesome),
    ROUTINES("Routines", Icons.Rounded.Bolt),
    BRAIN("Brain", Icons.Rounded.AccountTree),
}

@Composable
fun CycloneV32TopBar(
    title: String,
    settingsOpen: Boolean,
    ready: Boolean,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CycloneSpacing.Page, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (settingsOpen) {
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                }
            } else {
                Surface(
                    modifier = Modifier.size(40.dp).clickable(onClick = onSettings),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) { CycloneOrbitMark(Modifier.size(24.dp)) }
                }
            }
            Text(
                if (settingsOpen) "Settings" else title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (!settingsOpen) {
                Surface(
                    modifier = Modifier.clickable(onClick = onSettings),
                    shape = RoundedCornerShape(999.dp),
                    color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = if (ready) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.size(7.dp).background(
                                if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                                CircleShape,
                            ),
                        )
                        Text(if (ready) "Ready" else "Setup", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun CycloneV32BottomBar(selected: V32Destination, onSelect: (V32Destination) -> Unit) {
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    if (imeVisible) return

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = .97f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .padding(horizontal = 6.dp)
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                V32Destination.entries.forEach { destination ->
                    val isSelected = selected == destination
                    val isAi = destination == V32Destination.AI
                    val itemTint = when {
                        isAi && isSelected -> MaterialTheme.colorScheme.onPrimary
                        isSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(destination) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Surface(
                            modifier = if (isAi) Modifier.size(48.dp) else Modifier.size(width = 38.dp, height = 30.dp),
                            shape = if (isAi) CircleShape else RoundedCornerShape(12.dp),
                            color = when {
                                isAi && isSelected -> MaterialTheme.colorScheme.primary
                                isAi -> MaterialTheme.colorScheme.surfaceVariant
                                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = .78f)
                                else -> Color.Transparent
                            },
                            contentColor = itemTint,
                            shadowElevation = if (isAi && isSelected) 3.dp else 0.dp,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(
                                        when (destination) {
                                            V32Destination.HOME -> com.cyclone.mobile.R.drawable.ic_cyclone_home_42
                                            V32Destination.PROFILES -> com.cyclone.mobile.R.drawable.ic_cyclone_profiles_42
                                            V32Destination.AI -> com.cyclone.mobile.R.drawable.ic_cyclone_ai_42
                                            V32Destination.ROUTINES -> com.cyclone.mobile.R.drawable.ic_cyclone_routines_42
                                            V32Destination.BRAIN -> com.cyclone.mobile.R.drawable.ic_cyclone_brain_42
                                        },
                                    ),
                                    contentDescription = destination.label,
                                    modifier = Modifier.size(if (isAi) 25.dp else 23.dp),
                                    tint = itemTint,
                                )
                            }
                        }
                        androidx.compose.foundation.layout.Spacer(Modifier.height(if (isAi) 0.dp else 4.dp))
                        Text(
                            destination.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CycloneSegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp).padding(3.dp)) {
            val gap = 3.dp
            val segmentWidth = (maxWidth - gap * (options.size - 1).toFloat()) / options.size.toFloat()
            val targetOffset by animateDpAsState(
                targetValue = (segmentWidth + gap) * selected.coerceIn(options.indices).toFloat(),
                animationSpec = tween(durationMillis = 210),
                label = "Cyclone segment position",
            )
            Surface(
                modifier = Modifier.offset(x = targetOffset).width(segmentWidth).fillMaxHeight(),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 1.dp,
            ) {}
            Row(Modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                options.forEachIndexed { index, label ->
                    val active = selected == index
                    val textColor by animateColorAsState(
                        targetValue = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(durationMillis = 170),
                        label = "Cyclone segment text",
                    )
                    Box(
                        modifier = Modifier
                            .width(segmentWidth)
                            .fillMaxHeight()
                            .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = textColor,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CycloneRoutineCard(
    automation: AutomationDefinition,
    tone: CyclonePastel,
    onOpen: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        CycloneAppIcon(automation.appPackages.firstOrNull(), Modifier.size(30.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(automation.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        automation.v32TriggerSummary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = automation.enabled, onCheckedChange = onEnabledChange)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append("${automation.steps.size} ${if (automation.steps.size == 1) "action" else "actions"}")
                        automation.categories.firstOrNull()?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (automation.enabled) {
                    CycloneStatusPill("On")
                } else {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text("Off", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun CyclonePermissionRow(
    icon: ImageVector,
    title: String,
    body: String,
    ready: Boolean,
    actionLabel: String = if (ready) "Ready" else "Enable",
    onClick: () -> Unit,
) {
    if (title == LEGACY_ENHANCED_CONTROL_ROW) return

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(13.dp),
            color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                Icon(
                    if (ready) Icons.Rounded.Check else icon,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(actionLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}
