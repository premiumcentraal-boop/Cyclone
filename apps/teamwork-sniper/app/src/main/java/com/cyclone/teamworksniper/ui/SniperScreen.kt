package com.cyclone.teamworksniper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.teamworksniper.UiState
import com.cyclone.teamworksniper.data.ShiftCode
import com.cyclone.teamworksniper.data.ShiftRule
import com.cyclone.teamworksniper.data.SniperSettings
import com.cyclone.teamworksniper.rules.TargetSelectionRules
import com.cyclone.teamworksniper.teamwork.ShiftTemplateProvider
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val TeamworkGreen = Color(0xFF0B7A55)
private val TeamworkGreenSoft = Color(0xFFE6F4EE)
private val SniperOrange = Color(0xFFE85D04)
private val AppSurface = Color(0xFFF5F7F5)
private val CardSurface = Color.White
private val Ink = Color(0xFF16211C)
private val Muted = Color(0xFF64706A)
private val DayFormat = DateTimeFormatter.ofPattern("EEE, d MMM")
private val RangeFormat = DateTimeFormatter.ofPattern("d MMM")
private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun SniperScreen(
    state: UiState,
    onSettings: (SniperSettings) -> Unit,
    onRules: (List<ShiftRule>) -> Unit,
    onNotification: () -> Unit,
    onAccessibility: () -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    MaterialTheme(colorScheme = teamworkSniperPalette()) {
        Surface(Modifier.fillMaxSize(), color = AppSurface) {
            Column {
                AppHeader(
                    selectedCount = targetCount(state.rules),
                    settingsVisible = showSettings,
                    onSettings = { showSettings = !showSettings },
                )
                if (showSettings) {
                    SettingsPanel(state, onSettings, onNotification, onAccessibility)
                } else {
                    TargetCalendar(state.rules, onRules)
                }
            }
        }
    }
}

@Composable
private fun AppHeader(selectedCount: Int, settingsVisible: Boolean, onSettings: () -> Unit) {
    Surface(color = CardSurface, shadowElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Teamwork", color = Ink, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SNIPER", color = SniperOrange, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("$selectedCount targets", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onSettings) { Text(if (settingsVisible) "Calendar" else "Settings") }
        }
    }
}

@Composable
private fun TargetCalendar(rules: List<ShiftRule>, onRules: (List<ShiftRule>) -> Unit) {
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    val weekStart = remember(weekOffset) {
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset.toLong())
    }
    val templates = remember { ShiftTemplateProvider() }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Calendar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
            Text("Choose the shifts Sniper may claim", style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        item { WeekChooser(weekStart, { weekOffset-- }, { weekOffset++ }) }
        items(7) { index ->
            val date = weekStart.plusDays(index.toLong())
            DayTargets(
                date = date,
                rules = rules,
                templates = templates,
                onToggle = { code -> onRules(TargetSelectionRules.toggle(rules, date, code)) },
            )
        }
    }
}

@Composable
private fun WeekChooser(weekStart: LocalDate, onPrevious: () -> Unit, onNext: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onPrevious) { Text("‹") }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Week ${weekStart.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)}", fontWeight = FontWeight.Bold)
                Text(
                    "${weekStart.format(RangeFormat)} – ${weekStart.plusDays(6).format(RangeFormat)}",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onNext) { Text("›") }
        }
    }
}

@Composable
private fun DayTargets(
    date: LocalDate,
    rules: List<ShiftRule>,
    templates: ShiftTemplateProvider,
    onToggle: (ShiftCode) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(date.format(DayFormat), color = Ink, fontWeight = FontWeight.Bold)
                    Text("Target shifts", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("◎", color = SniperOrange, style = MaterialTheme.typography.titleLarge)
            }
            templates.forDate(date).forEach { shift ->
                val selected = rules.any { TargetSelectionRules.isExactTarget(it, date, shift.code) }
                ShiftTargetRow(
                    code = shift.code,
                    time = shift.start?.let { start -> shift.end?.let { end -> "${start.format(TimeFormat)} – ${end.format(TimeFormat)}" } },
                    selected = selected,
                    onClick = { onToggle(shift.code) },
                )
            }
        }
    }
}

@Composable
private fun ShiftTargetRow(code: ShiftCode, time: String?, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) TeamworkGreen else TeamworkGreenSoft
    val foreground = if (selected) Color.White else Ink
    val secondary = if (selected) Color.White.copy(alpha = 0.86f) else Muted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("◎", color = if (selected) Color.White else SniperOrange, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                time ?: "Time not mapped yet",
                color = foreground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(if (selected) "Selected for Sniper" else "Tap to select", color = secondary, style = MaterialTheme.typography.bodySmall)
        }
        Surface(
            color = if (selected) Color.White.copy(alpha = 0.2f) else Color.White,
            shape = MaterialTheme.shapes.small,
            border = if (selected) null else BorderStroke(1.dp, TeamworkGreen.copy(alpha = 0.35f)),
        ) {
            Text(
                code.name,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                color = foreground,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    state: UiState,
    onSettings: (SniperSettings) -> Unit,
    onNotification: () -> Unit,
    onAccessibility: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink) }
        item {
            SettingsCard {
                SettingsToggle(
                    title = "Sniper enabled",
                    subtitle = "Allows saved target shifts to be checked after Teamwork notifications.",
                    checked = state.settings.enabled && state.settings.armed,
                    onCheckedChange = { enabled -> onSettings(state.settings.copy(enabled = enabled, armed = enabled)) },
                )
            }
        }
        item {
            SettingsCard {
                SettingsToggle(
                    title = "Legacy Teamwork overlay",
                    subtitle = "Off by default. Shows controls over the official Teamwork app.",
                    checked = state.settings.legacyOverlayEnabled,
                    onCheckedChange = { enabled -> onSettings(state.settings.copy(legacyOverlayEnabled = enabled)) },
                )
            }
        }
        item {
            SettingsCard {
                PermissionRow("Notification access", state.permissions.notificationAccess, onNotification)
                Spacer(Modifier.height(8.dp))
                PermissionRow("Accessibility access", state.permissions.accessibilityAccess, onAccessibility)
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SettingsToggle(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionRow(label: String, enabled: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Ink, fontWeight = FontWeight.SemiBold)
            Text(if (enabled) "Enabled" else "Required", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onOpen) { Text(if (enabled) "Manage" else "Enable") }
    }
}

private fun targetCount(rules: List<ShiftRule>): Int = rules.count {
    it.enabled && it.dates.size == 1 && it.codes.size == 1 && TargetSelectionRules.isExactTarget(it, it.dates.single(), it.codes.single())
}

private fun teamworkSniperPalette() = lightColorScheme(
    primary = TeamworkGreen,
    onPrimary = Color.White,
    secondary = SniperOrange,
    background = AppSurface,
    onBackground = Ink,
    surface = CardSurface,
    onSurface = Ink,
)
