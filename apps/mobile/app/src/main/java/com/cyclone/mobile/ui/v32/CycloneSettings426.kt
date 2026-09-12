package com.cyclone.mobile.ui.v32

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.cyclone.mobile.CycloneRelease
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.CycloneAiAccessProfileStore
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.gateway.GatewaySettingsActivity
import com.cyclone.mobile.permissions.CyclonePermissionSetup
import com.cyclone.mobile.runtime.background.BackgroundSetup
import com.cyclone.mobile.runtime.background.BackgroundSetupActivity
import com.cyclone.mobile.ui.RootFeaturesCard

private data class Settings426Row(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val value: String? = null,
)

@Composable
internal fun CycloneSettingsPage426(context: Context, refreshTick: Int, refresh: () -> Unit,
    section: String, onSection: (String) -> Unit) {

    val prefs = context.getSharedPreferences(V39AiChatContract.PREFS, Context.MODE_PRIVATE)
    var selectedModel by rememberSaveable(refreshTick) {
        mutableStateOf(com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context))
    }
    var reasoningEffort by rememberSaveable(refreshTick) {
        mutableStateOf(prefs.getString("openrouter_reasoning_effort", "medium") ?: "medium")
    }
    var accessProfile by rememberSaveable(refreshTick) { mutableStateOf(CycloneAiAccessProfileStore.read(context)) }
    val phoneControl = CyclonePermissionSetup.phoneControlSnapshot(context)
    val notificationAccess = CyclonePermissionSetup.notificationAccessEnabled(context)
    val resultNotifications = CyclonePermissionSetup.resultNotificationsEnabled(context)
    val batteryUnrestricted = CyclonePermissionSetup.batteryUnrestricted(context)
    val background = remember(refreshTick) { BackgroundSetup.read(context) }
    val essentialsReady = listOf(phoneControl.ready, notificationAccess, resultNotifications, batteryUnrestricted).count { it }

    fun modelValue(): String = V39AiChatContract.modelForStored(selectedModel).label
    fun effortValue(): String = when (reasoningEffort.lowercase()) {
        "low" -> "Low"
        "high", "max" -> "High"
        else -> "Medium"
    }
    fun phoneValue(): String = when {
        phoneControl.ready -> "Ready"
        phoneControl.needsRepair -> "Repair"
        else -> "Setup"
    }
    fun backgroundValue(): String = if (background.setupFailure == null) "Ready" else "Setup"

    if (section.isEmpty()) {
        Settings426Root(
            groups = listOf(
                "AI" to listOf(
                    Settings426Row("Model & API", "Model & API", Icons.Rounded.Psychology, modelValue()),
                    Settings426Row("Default intelligence", "Default intelligence", Icons.Rounded.Tune, effortValue()),
                    Settings426Row("Phone autonomy", "Phone autonomy", Icons.Rounded.PhoneAndroid, accessProfile.displayName),
                ),
                "Phone" to listOf(
                    Settings426Row("Quick setup", "Quick setup", Icons.Rounded.Bolt, "With root"),
                    Settings426Row("Phone control", "Phone control", Icons.Rounded.Smartphone, phoneValue()),
                    Settings426Row("Notifications", "Notifications", Icons.Rounded.Notifications, if (resultNotifications) "On" else "Off"),
                    Settings426Row("Background work", "Background work", Icons.Rounded.CloudQueue, backgroundValue()),
                    Settings426Row("Permissions", "Permissions", Icons.Rounded.Security, "$essentialsReady/4"),
                ),
                "Profiles" to listOf(
                    Settings426Row("Profile engine", "Profile engine", Icons.Rounded.Layers, "Manage"),
                    Settings426Row("Storage", "Storage", Icons.Rounded.Storage, "On device"),
                ),
                "Connections" to listOf(
                    Settings426Row("PC Gateway", "PC Gateway", Icons.Rounded.Smartphone, "Optional"),
                ),
                "Privacy & safety" to listOf(
                    Settings426Row("Privacy & safety", "Privacy & safety", Icons.Rounded.Security),
                ),
                "About" to listOf(
                    Settings426Row("About", "Cyclone", Icons.Rounded.Info, CycloneRelease.label),
                ),
            ),
            onOpen = onSection,
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(section, style = MaterialTheme.typography.headlineSmall)
                Settings426DetailSubtitle(section)?.let { subtitle ->
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        when (section) {
            "Quick setup" -> item { CycloneQuickSetup(context, refresh) { onSection("Permissions") } }
            "Model & API" -> item {
                ModelApi426Card(
                    context = context,
                    modelId = selectedModel,
                    effort = reasoningEffort,
                    onChanged = { model, effort ->
                        selectedModel = model
                        reasoningEffort = effort
                        prefs.edit()
                            .putString(V39AiChatContract.MODEL_KEY, model)
                            .putString("openrouter_reasoning_effort", effort)
                            .apply()
                        refresh()
                    },
                )
            }

            "Default intelligence" -> item {
                Settings426Surface {
                    CycloneModelIntelligencePanel(selectedModel, reasoningEffort) { model, effort ->
                        selectedModel = model
                        reasoningEffort = effort
                        prefs.edit()
                            .putString(V39AiChatContract.MODEL_KEY, model)
                            .putString("openrouter_reasoning_effort", effort)
                            .apply()
                        refresh()
                    }
                }
            }

            "Phone autonomy" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CycloneAiAccessProfile.entries.forEach { profile ->
                            Settings426AutonomyRow(
                                profile = profile,
                                selected = accessProfile == profile,
                                onClick = {
                                    accessProfile = profile
                                    CycloneAiAccessProfileStore.write(context, profile)
                                    refresh()
                                },
                            )
                        }
                        Text(
                            "Payments, credentials, destructive changes and final send actions still require confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            "Phone control" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Settings426Readiness("Phone control", phoneControl.detail, phoneControl.ready)
                        CyclonePermissionRow(
                            Icons.Rounded.Security,
                            "Phone control",
                            phoneControl.detail,
                            phoneControl.ready,
                            phoneControl.actionLabel,
                        ) { open426(context, CyclonePermissionSetup.accessibilitySettings()) }
                        CyclonePermissionRow(
                            Icons.Rounded.Notifications,
                            "Notification triggers",
                            "React to selected app notifications.",
                            notificationAccess,
                            if (notificationAccess) "Manage" else "Enable",
                        ) { open426(context, CyclonePermissionSetup.notificationAccessSettings()) }
                        CyclonePermissionRow(
                            Icons.Rounded.BatteryChargingFull,
                            "Unrestricted battery",
                            "Keep scheduled work reliable while the phone is idle.",
                            batteryUnrestricted,
                            if (batteryUnrestricted) "Manage" else "Allow",
                        ) { open426(context, if (batteryUnrestricted) CyclonePermissionSetup.batteryOptimizationSettings() else CyclonePermissionSetup.batteryExemptionRequest(context)) }
                    }
                }
            }

            "Notifications" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CyclonePermissionRow(
                            Icons.Rounded.Notifications,
                            "Notification triggers",
                            "Let routines react to selected app notifications.",
                            notificationAccess,
                            if (notificationAccess) "Manage" else "Enable",
                        ) { open426(context, CyclonePermissionSetup.notificationAccessSettings()) }
                        CyclonePermissionRow(
                            Icons.Rounded.CheckCircle,
                            "Task results",
                            "Show a concise result after a task or routine.",
                            resultNotifications,
                            if (resultNotifications) "Manage" else "Allow",
                        ) {
                            if (!resultNotifications && Build.VERSION.SDK_INT >= 33) {
                                (context as? Activity)?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 320) }
                            } else open426(context, CyclonePermissionSetup.appDetails(context))
                        }
                    }
                }
            }

            "Background work" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Settings426Readiness(
                            title = if (background.setupFailure == null) "Background work is ready" else "Background work needs setup",
                            body = if (background.setupFailure == null) "Cyclone can keep a task running while you use your phone." else "Open setup to check the secure workspace requirements.",
                            ready = background.setupFailure == null,
                        )
                        Button(
                            onClick = { open426(context, Intent(context, BackgroundSetupActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (background.setupFailure == null) "Review background setup" else "Set up background work") }
                    }
                }
            }

            "Permissions" -> item {
                Permissions426Card(context)
            }

            "Profile engine" -> item {
                Settings426Surface { RootFeaturesCard() }
            }

            "Storage" -> item {
                Settings426Surface {
                    Settings426InfoRow(
                        Icons.Rounded.Memory,
                        "Stored on this phone",
                        "Routines and learned app knowledge stay in Cyclone's local app storage. Manage routines in Routines and learned knowledge in Brain.",
                    )
                }
            }

            "PC Gateway" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Settings426InfoRow(
                            Icons.Rounded.Smartphone,
                            "Optional PC companion",
                            "Cyclone's phone experience works without PC pairing. Connect a PC only when you want desktop control or agent integration.",
                        )
                        Button(
                            onClick = { open426(context, Intent(context, GatewaySettingsActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Open PC Gateway") }
                    }
                }
            }

            "Privacy & safety" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Settings426InfoRow(Icons.Rounded.Security, "One action authority", "AI, routines and PC requests use the same Cyclone policy and phone executor.")
                        Settings426InfoRow(Icons.Rounded.Visibility, "Verified changes", "Cyclone checks the phone state instead of treating transport success as task success.")
                        Settings426InfoRow(Icons.Rounded.Key, "Sensitive text stays private", "Passwords, OTPs, tokens and typed values are excluded from learning reports.")
                    }
                }
            }

            "About" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Cyclone", style = MaterialTheme.typography.titleLarge)
                        Text(CycloneRelease.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("com.cyclone.mobile", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Settings426Root(groups: List<Pair<String, List<Settings426Row>>>, onOpen: (String) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        groups.forEach { (groupTitle, rows) ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        groupTitle,
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 1.dp,
                    ) {
                        Column {
                            rows.forEachIndexed { index, row ->
                                Settings426ListRow(row, onClick = { onOpen(row.id) })
                                if (index != rows.lastIndex) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = 58.dp, end = 14.dp)
                                            .size(height = 1.dp, width = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Settings426ListRow(row: Settings426Row, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Icon(row.icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        row.value?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f))
    }
}

@Composable
private fun ModelApi426Card(
    context: Context,
    modelId: String,
    effort: String,
    onChanged: (String, String) -> Unit,
) {
    Settings426Surface {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CycloneOpenRouterCatalog(context) {
                onChanged(com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context), effort)
            }
            Text("Model", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CycloneModelPill(modelId, effort, modifier = Modifier.align(Alignment.Start), onChange = onChanged)
        }
    }
}

@Composable
private fun Permissions426Card(context: Context) {
    val agentKeyboard = CyclonePermissionSetup.agentKeyboardEnabled(context)
    val overlay = CyclonePermissionSetup.overlayEnabled(context)
    val exactTiming = CyclonePermissionSetup.exactTimingEnabled(context)
    val calendar = CyclonePermissionSetup.calendarEnabled(context)
    val microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    Settings426Surface {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            CyclonePermissionRow(Icons.Rounded.Keyboard, "Cyclone Agent Keyboard", "Optional reliable text entry for stubborn fields.", agentKeyboard, if (agentKeyboard) "Manage" else "Enable") {
                open426(context, CyclonePermissionSetup.keyboardSettings())
            }
            CyclonePermissionRow(Icons.Rounded.Layers, "Display over apps", "Show takeover and task controls above the current app.", overlay, if (overlay) "Manage" else "Allow") {
                open426(context, CyclonePermissionSetup.overlaySettings(context))
            }
            CyclonePermissionRow(Icons.Rounded.Schedule, "Precise timing", "Optional exact scheduling for strict routine times.", exactTiming, if (exactTiming) "Manage" else "Allow") {
                open426(context, CyclonePermissionSetup.exactTimingSettings(context))
            }
            CyclonePermissionRow(Icons.Rounded.CalendarMonth, "Calendar context", "Optional read-only matching for calendar-aware routines.", calendar, if (calendar) "Manage" else "Allow") {
                if (!calendar) (context as? Activity)?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.READ_CALENDAR), 321) }
                else open426(context, CyclonePermissionSetup.appDetails(context))
            }
            CyclonePermissionRow(Icons.Rounded.Mic, "Voice requests", "Speak directly into Ask Cyclone.", microphone, if (microphone) "Manage" else "Allow") {
                if (!microphone) (context as? Activity)?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.RECORD_AUDIO), 322) }
                else open426(context, CyclonePermissionSetup.appDetails(context))
            }
            Settings426InfoRow(Icons.Rounded.ScreenShare, "Screen sharing asks every session", "Android capture consent remains temporary and explicit.")
        }
    }
}

@Composable
private fun Settings426AutonomyRow(profile: CycloneAiAccessProfile, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(profile.displayName, style = MaterialTheme.typography.titleSmall)
                Text(profile.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, "Selected", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Settings426Readiness(title: String, body: String, ready: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (ready) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Settings426InfoRow(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Settings426Surface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

private fun Settings426DetailSubtitle(section: String): String? = when (section) {
    "Model & API" -> "Choose the model Cyclone uses and secure your API access."
    "Default intelligence" -> "Set the default reasoning level for new tasks."
    "Phone autonomy" -> "Choose how independently Cyclone may use phone tools."
    "Phone control" -> "Core phone access and reliability."
    "Notifications" -> "Triggers and task-result alerts."
    "Background work" -> "Work while you keep using your main screen."
    "Permissions" -> "Optional capabilities for harder workflows."
    "Profile engine" -> "Create and manage separate app profiles."
    "Storage" -> "Where Cyclone keeps local knowledge."
    "PC Gateway" -> "Connect Cyclone to desktop agents when needed."
    else -> null
}

private fun open426(context: Context, intent: Intent) {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
