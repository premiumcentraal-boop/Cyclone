package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.CycloneRelease
import com.cyclone.mobile.automation.AutomationDefinition

enum class V32Destination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    TEACH("Teach", Icons.Rounded.School),
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
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (settingsOpen) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            } else {
                Surface(
                    modifier = Modifier.size(46.dp).clickable(onClick = onSettings),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Box(contentAlignment = Alignment.Center) { Text("C", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge) }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(if (settingsOpen) "Settings" else title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(CycloneRelease.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!settingsOpen) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = if (ready) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).background(if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary, CircleShape))
                        Text(if (ready) "Ready" else "Set up", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Icon(Icons.Rounded.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun CycloneV32BottomBar(selected: V32Destination, onSelect: (V32Destination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        V32Destination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = {
                    if (destination == V32Destination.AI) {
                        Box(
                            Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(destination.icon, destination.label, tint = MaterialTheme.colorScheme.onPrimary) }
                    } else Icon(destination.icon, destination.label)
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
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { index, label ->
                Surface(
                    modifier = Modifier.weight(1f).clickable { onSelect(index) },
                    shape = RoundedCornerShape(15.dp),
                    color = if (selected == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected == index) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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
    val colors = cyclonePastel(tone)
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.content),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = colors.content.copy(alpha = 0.12f), contentColor = colors.content) {
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(21.dp)) }
                }
                Column(Modifier.weight(1f)) {
                    Text(automation.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(automation.v32TriggerSummary(), style = MaterialTheme.typography.bodySmall, color = colors.content.copy(alpha = 0.74f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = automation.enabled, onCheckedChange = onEnabledChange)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${automation.steps.size} ${if (automation.steps.size == 1) "action" else "actions"}", style = MaterialTheme.typography.labelMedium)
                Text(if (automation.enabled) "On" else "Off", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(if (ready) Icons.Rounded.Check else icon, null, tint = if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(if (ready) "Ready" else "Enable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}
