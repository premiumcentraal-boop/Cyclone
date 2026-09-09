package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
                    Box(contentAlignment = Alignment.Center) {
                        CycloneOrbitMark(Modifier.size(24.dp))
                    }
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
            } else {
                Icon(Icons.Rounded.Settings, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun CycloneV32BottomBar(selected: V32Destination, onSelect: (V32Destination) -> Unit) {
    NavigationBar(
        modifier = Modifier.height(74.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        V32Destination.entries.forEach { destination ->
            val isSelected = selected == destination
            val isAi = destination == V32Destination.AI
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(destination) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = if (isAi) Color.Transparent else MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                icon = {
                    if (isAi) {
                        Surface(
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(destination.icon, destination.label, modifier = Modifier.size(22.dp))
                            }
                        }
                    } else {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(
                                when (destination) {
                                    V32Destination.HOME -> com.cyclone.mobile.R.drawable.ic_cyclone_home_42
                                    V32Destination.PROFILES -> com.cyclone.mobile.R.drawable.ic_cyclone_profiles_42
                                    V32Destination.ROUTINES -> com.cyclone.mobile.R.drawable.ic_cyclone_routines_42
                                    V32Destination.BRAIN -> com.cyclone.mobile.R.drawable.ic_cyclone_brain_42
                                    V32Destination.AI -> com.cyclone.mobile.R.drawable.ic_cyclone_ai_42
                                },
                            ),
                            contentDescription = destination.label,
                            modifier = Modifier.size(25.dp),
                        )
                    }
                },
                label = { Text(destination.label, style = MaterialTheme.typography.labelSmall) },
            )
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
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.fillMaxWidth().padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEachIndexed { index, label ->
                val active = selected == index
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) }),
                    shape = RoundedCornerShape(13.dp),
                    color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                    contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Box(Modifier.padding(vertical = 9.dp, horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .68f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        CycloneAppIcon(automation.appPackages.firstOrNull(), Modifier.size(30.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        automation.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (automation.enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (automation.enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        if (automation.enabled) "On" else "Off",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
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
    // V3.2 beta.3 collapses the old second Accessibility permission into Cyclone's canonical
    // service. Keep the large Settings source binary-compatible while no longer rendering the
    // legacy duplicate row to users.
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
