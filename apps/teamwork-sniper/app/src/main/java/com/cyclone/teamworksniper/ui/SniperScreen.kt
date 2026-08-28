package com.cyclone.teamworksniper.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cyclone.teamworksniper.UiState
import com.cyclone.teamworksniper.data.AiSettings
import com.cyclone.teamworksniper.data.ShiftCode
import com.cyclone.teamworksniper.data.ShiftRule
import com.cyclone.teamworksniper.data.SniperSettings
import com.cyclone.teamworksniper.data.RuleType
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

private enum class DraftMode { ANY, SEQUENCE }

@Composable
fun SniperScreen(
    state: UiState,
    onSettings: (SniperSettings) -> Unit,
    onAiSettings: (AiSettings) -> Unit,
    onSaveAiKey: (String) -> Unit,
    onClearAiKey: () -> Unit,
    onRules: (List<ShiftRule>) -> Unit,
    onNotification: () -> Unit,
    onAccessibility: () -> Unit,
    onEvaluate: () -> Unit,
) {
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var modelDraft by remember(state.aiSettings.model) { mutableStateOf(state.aiSettings.model) }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Column {
                        Text(
                            "Teamwork Sniper",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            when {
                                !state.permissions.notificationAccess -> "MISSING NOTIFICATION ACCESS"
                                !state.permissions.accessibilityAccess -> "MISSING ACCESSIBILITY ACCESS"
                                !state.settings.enabled -> "DISABLED"
                                state.settings.armed -> "ARMED"
                                else -> "READY · DISARMED"
                            },
                        )
                        Text(
                            "Local semantic automation · PC optional · AI optional",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("Permissions", fontWeight = FontWeight.Bold)
                            PermissionRow(
                                "Notification access",
                                state.permissions.notificationAccess,
                                onNotification,
                            )
                            PermissionRow(
                                "Accessibility access",
                                state.permissions.accessibilityAccess,
                                onAccessibility,
                            )
                        }
                    }
                }

                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Toggle(
                                title = "Sniper enabled",
                                subtitle = "Allows Teamwork triggers, semantic UI mapping and rule evaluation",
                                checked = state.settings.enabled,
                            ) {
                                onSettings(state.settings.copy(enabled = it))
                            }
                            Toggle(
                                title = "Armed",
                                subtitle = "Only this explicit state permits claim actions",
                                checked = state.settings.armed,
                            ) {
                                onSettings(state.settings.copy(armed = it))
                            }
                            if (!state.settings.armed) {
                                Text(
                                    "Disarmed mode still reads and logs what WOULD happen.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                item {
                    Card {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Optional AI assist", fontWeight = FontWeight.Bold)
                            Text(
                                "The sniper works without AI and without a PC. OpenRouter can only prioritize between candidates that already passed your rules and fresh Open-to-take checks.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Toggle(
                                title = "Use OpenRouter when useful",
                                subtitle = "Skipped for simple single-match decisions and bypassed automatically on errors",
                                checked = state.aiSettings.enabled,
                            ) {
                                onAiSettings(state.aiSettings.copy(enabled = it))
                            }

                            OutlinedTextField(
                                value = modelDraft,
                                onValueChange = { modelDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("OpenRouter model") },
                                singleLine = true,
                            )
                            OutlinedButton(
                                onClick = {
                                    onAiSettings(
                                        state.aiSettings.copy(
                                            model = modelDraft.trim().ifBlank { "openrouter/auto" },
                                        ),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Save model")
                            }

                            if (state.aiKeyPresent) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("OpenRouter key secured", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Encrypted with Android Keystore",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    TextButton(onClick = onClearAiKey) { Text("Remove") }
                                }
                            } else {
                                OutlinedTextField(
                                    value = keyDraft,
                                    onValueChange = { keyDraft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("OpenRouter API key") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                )
                                Button(
                                    onClick = {
                                        onSaveAiKey(keyDraft)
                                        keyDraft = ""
                                    },
                                    enabled = keyDraft.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Secure key")
                                }
                            }

                            Text(
                                "AI never creates a shift, bypasses Armed, resolves an ambiguous claim node, or expands beyond your selected rules.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                item {
                    RuleComposer(state.rules, onRules)
                }

                if (state.rules.isNotEmpty()) {
                    item { Text("Desired rules", fontWeight = FontWeight.Bold) }
                    items(state.rules, key = { it.id }) { rule ->
                        Card {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(rule.name, fontWeight = FontWeight.SemiBold)
                                    Text(rule.type.name, style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { enabled ->
                                        onRules(
                                            state.rules.map {
                                                if (it.id == rule.id) it.copy(enabled = enabled) else it
                                            },
                                        )
                                    },
                                )
                                TextButton(
                                    onClick = {
                                        onRules(state.rules.filterNot { it.id == rule.id })
                                    },
                                ) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }

                item {
                    Button(
                        onClick = onEvaluate,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.permissions.accessibilityAccess,
                    ) {
                        Text("Evaluate Teamwork now")
                    }
                }

                item {
                    Text(
                        "Semantic UI map",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Teamwork Sniper learns successful semantic navigation labels/resource IDs locally, then re-observes the live hierarchy before every shift decision.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                item { Text("Recent activity", fontWeight = FontWeight.Bold) }
                items(state.activity.take(12), key = { it.id }) { entry ->
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                entry.triggerSource.name + " · " + entry.decision,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Decision: " + entry.decisionEngine +
                                    " · Armed " + entry.armedState +
                                    " · attempted " + entry.claimAttempted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            entry.aiAdvice?.let {
                                Text("AI: " + it, style = MaterialTheme.typography.bodySmall)
                            }
                            entry.firstComparisonLatencyMs?.let {
                                Text("First comparison " + it + "ms", style = MaterialTheme.typography.bodySmall)
                            }
                            if (entry.openShifts.isNotEmpty()) {
                                Text(
                                    "Open: " + entry.openShifts.joinToString(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            entry.claimResult?.let {
                                Text("Claim: " + it, style = MaterialTheme.typography.bodySmall)
                            }
                            entry.verificationResult?.let {
                                Text("Verify: " + it, style = MaterialTheme.typography.bodySmall)
                            }
                            entry.failureReason?.let {
                                Text("Reason: " + it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean, open: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(if (ok) "Enabled" else "Required", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = open) { Text(if (ok) "Open" else "Enable") }
    }
}

@Composable
private fun Toggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RuleComposer(
    existing: List<ShiftRule>,
    onRules: (List<ShiftRule>) -> Unit,
) {
    var codes by remember { mutableStateOf(emptySet<ShiftCode>()) }
    var mode by remember { mutableStateOf(DraftMode.ANY) }
    var weeks by remember { mutableStateOf(setOf(0, 1)) }
    var days by remember { mutableStateOf(emptySet<DayOfWeek>()) }
    var dates by remember { mutableStateOf(emptySet<LocalDate>()) }

    val ordered = codes.sortedBy { it.order }
    val validSequence = ordered.size >= 2 &&
        ordered.zipWithNext().all { (first, second) -> second.order == first.order + 1 }

    Card {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("New desired rule", fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShiftCode.entries.forEach { code ->
                    FilterChip(
                        selected = code in codes,
                        onClick = { codes = codes.toggle(code) },
                        label = { Text(code.name) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = mode == DraftMode.ANY,
                    onClick = { mode = DraftMode.ANY },
                    label = { Text("Claim any selected") },
                )
                FilterChip(
                    selected = mode == DraftMode.SEQUENCE,
                    onClick = { mode = DraftMode.SEQUENCE },
                    label = { Text("Require full sequence") },
                )
            }

            Text(
                if (mode == DraftMode.ANY) {
                    "Selected codes are independent."
                } else {
                    "All consecutive codes must be open on the same date."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(0 to "This week", 1 to "+1 week", 2 to "+2 weeks").forEach { (week, label) ->
                    FilterChip(
                        selected = week in weeks,
                        onClick = { weeks = weeks.toggle(week) },
                        label = { Text(label) },
                    )
                }
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day in days,
                        onClick = { days = days.toggle(day) },
                        label = { Text(day.name.take(2)) },
                    )
                }
            }

            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (0..13).map { LocalDate.now().plusDays(it.toLong()) }.forEach { date ->
                    FilterChip(
                        selected = date in dates,
                        onClick = { dates = dates.toggle(date) },
                        label = { Text(date.dayOfMonth.toString() + "/" + date.monthValue) },
                    )
                }
            }

            val canAdd = codes.isNotEmpty() &&
                weeks.isNotEmpty() &&
                (mode == DraftMode.ANY || validSequence)

            Button(
                onClick = {
                    val type = if (mode == DraftMode.SEQUENCE) {
                        RuleType.SEQUENCE
                    } else if (ordered.size == 1) {
                        RuleType.EXACT
                    } else {
                        RuleType.COMBINATION
                    }
                    val separator = if (type == RuleType.SEQUENCE) " → " else " / "
                    onRules(
                        existing + ShiftRule(
                            id = UUID.randomUUID().toString(),
                            name = ordered.joinToString(separator) { it.name },
                            type = type,
                            codes = ordered,
                            enabled = true,
                            weekOffsets = weeks,
                            dates = dates,
                            days = days,
                        ),
                    )
                    codes = emptySet()
                    dates = emptySet()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canAdd,
            ) {
                Text("Add rule")
            }
        }
    }
}

private fun <T> Set<T>.toggle(value: T) =
    if (value in this) this - value else this + value
