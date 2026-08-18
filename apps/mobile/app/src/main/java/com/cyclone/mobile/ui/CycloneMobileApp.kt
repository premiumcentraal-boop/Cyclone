package com.cyclone.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DynamicTonalPalette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.cyclone.mobile.BridgeClient
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.MobilerunEmbedded
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.FailureAction
import com.cyclone.mobile.automation.Selector
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerType
import com.mobilerun.portal.service.MobilerunAccessibilityService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CycloneLight = lightColorScheme(
    primary = Color(0xFF5A54F4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E4FF),
    onPrimaryContainer = Color(0xFF17134C),
    secondary = Color(0xFF006B5F),
    secondaryContainer = Color(0xFF9FF2E2),
    background = Color(0xFFF7F7FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9E8F0),
)

private val CycloneDark = darkColorScheme(
    primary = Color(0xFFC7C4FF),
    primaryContainer = Color(0xFF403AA8),
    secondary = Color(0xFF82D5C6),
    background = Color(0xFF111116),
    surface = Color(0xFF18181F),
    surfaceVariant = Color(0xFF2B2A32),
)

@Composable
fun CycloneTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> CycloneDark
        else -> CycloneLight
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class MobileTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    AUTOMATIONS("Automations", Icons.Rounded.Bolt),
    ACTIVITY("Activity", Icons.Rounded.History),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycloneMobileApp() {
    CycloneTheme {
        val context = LocalContext.current
        var tab by rememberSaveable { mutableStateOf(MobileTab.HOME) }
        var refreshTick by remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) {
            while (true) {
                delay(900)
                refreshTick++
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Cyclone", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (DeviceState.bridgeConnected) "Connected to Core" else "Mobile V2.1",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        val ready = nativeAccessibilityEnabled(context) && DeviceState.controller == DeviceState.Controller.AGENT
                        StatusDot(ok = ready)
                        Spacer(Modifier.width(16.dp))
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    MobileTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (tab) {
                    MobileTab.HOME -> HomeScreen(context, refreshTick) { refreshTick++ }
                    MobileTab.AUTOMATIONS -> AutomationsScreen(context, refreshTick) { refreshTick++ }
                    MobileTab.ACTIVITY -> ActivityScreen(refreshTick)
                    MobileTab.SETTINGS -> SettingsScreen(context, refreshTick) { refreshTick++ }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(context: Context, refreshTick: Int, refresh: () -> Unit) {
    val prefs = context.getSharedPreferences("cyclone", Context.MODE_PRIVATE)
    var request by rememberSaveable { mutableStateOf(prefs.getString("pendingAiBuildRequest", "").orEmpty()) }
    val native = nativeAccessibilityEnabled(context)
    val notifications = notificationAccessEnabled(context)
    val core = DeviceState.bridgeConnected
    val enhanced = MobilerunEmbedded.accessibilityConnected()
    val readyCount = listOf(native, notifications, core).count { it }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            HeroCard(
                title = when {
                    DeviceState.controller == DeviceState.Controller.HUMAN -> "You have control"
                    native && core -> "Cyclone is ready"
                    else -> "Finish setup"
                },
                subtitle = when {
                    DeviceState.controller == DeviceState.Controller.HUMAN -> "Agent input is paused until you return control."
                    native && core -> "Hermes can observe and operate this phone through the protected phone toolbox."
                    else -> "$readyCount of 3 core connections are ready."
                },
                icon = if (native && core) Icons.Rounded.CheckCircle else Icons.Rounded.SmartToy,
            )
        }

        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Device status", "Everything important at a glance")
                    StatusRow("Phone control", native, if (native) "Available" else "Enable Accessibility")
                    StatusRow("Notifications", notifications, if (notifications) "Available" else "Permission needed")
                    StatusRow("Cyclone Core", core, if (core) "Connected" else "Not connected")
                    StatusRow("Enhanced engine", enhanced, if (enhanced) "Mobilerun active" else "Optional")
                    HorizontalDivider()
                    Text(
                        "Current app: ${DeviceState.currentPackage ?: "Waiting for phone state"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Ask Cyclone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Describe what you want the phone to do. Cyclone sends the request to Core/Hermes and keeps generated automations reviewable before activation.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = request,
                        onValueChange = { request = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        placeholder = { Text("Example: When a work shift notification arrives, open it and alert me if it overlaps my calendar.") },
                        shape = RoundedCornerShape(18.dp),
                    )
                    Button(
                        onClick = {
                            val clean = request.trim()
                            prefs.edit().putString("pendingAiBuildRequest", clean).apply()
                            BridgeClient.sendAutomationEvent("automation.build_request", mapOf("request" to clean))
                            DeviceState.addLog("Automation build request saved/sent")
                            refresh()
                        },
                        enabled = request.isNotBlank(),
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Build with Hermes")
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Control", "Human takeover is enforced, not simulated")
                    if (DeviceState.requireFreshObservation) {
                        InlineNotice(
                            Icons.Rounded.Warning,
                            "Fresh observation required",
                            "Cyclone will not mutate the phone until phone.observe succeeds after takeover.",
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            val next = if (DeviceState.controller == DeviceState.Controller.AGENT) {
                                DeviceState.Controller.HUMAN
                            } else {
                                DeviceState.Controller.AGENT
                            }
                            DeviceState.setController(next)
                            refresh()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            if (DeviceState.controller == DeviceState.Controller.AGENT) Icons.Rounded.Security else Icons.Rounded.Refresh,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (DeviceState.controller == DeviceState.Controller.AGENT) "Take control" else "Return control to Cyclone")
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationsScreen(context: Context, refreshTick: Int, refresh: () -> Unit) {
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var recordingName by rememberSaveable { mutableStateOf("Recorded workflow") }
    val automations = AutomationRuntime.store.listAutomations()
    val recorder = AutomationRuntime.recorder

    if (showCreate) {
        CreateAutomationDialog(
            onDismiss = { showCreate = false },
            onCreate = { definition ->
                AutomationRuntime.store.saveAutomation(definition)
                if (definition.trigger.type == TriggerType.SCHEDULE) {
                    AutomationRuntime.registerSchedule(context, definition)
                }
                DeviceState.addLog("Automation created: ${definition.name}")
                showCreate = false
                refresh()
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Automations", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Reusable phone workflows", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { showCreate = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("New")
                }
            }
        }

        if (automations.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Bolt,
                    title = "No automations yet",
                    body = "Create one manually or ask Hermes to propose a reusable workflow.",
                )
            }
        } else {
            items(automations, key = { it.id }) { automation ->
                AutomationCard(context, automation, refresh)
            }
        }

        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Teach a task", "Record semantic actions instead of fragile raw coordinates")
                    OutlinedTextField(
                        value = recordingName,
                        onValueChange = { recordingName = it },
                        enabled = !recorder.isRecording(),
                        label = { Text("Workflow name") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                    if (!recorder.isRecording()) {
                        FilledTonalButton(
                            onClick = {
                                recorder.start()
                                DeviceState.addLog("Automation recorder started")
                                refresh()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start recording")
                        }
                    } else {
                        Text("${recorder.snapshot().size} semantic steps captured", fontWeight = FontWeight.Medium)
                        recorder.snapshot().take(6).forEachIndexed { index, step ->
                            Text(
                                "${index + 1}. ${step.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val definition = recorder.stop(recordingName.ifBlank { "Recorded workflow" })
                                    AutomationRuntime.store.saveAutomation(definition)
                                    DeviceState.addLog("Recorded automation saved: ${definition.name}")
                                    refresh()
                                },
                            ) { Text("Save") }
                            OutlinedButton(
                                onClick = {
                                    recorder.cancel()
                                    refresh()
                                },
                            ) { Text("Cancel") }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun AutomationCard(context: Context, automation: AutomationDefinition, refresh: () -> Unit) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(automation.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${triggerLabel(automation.trigger.type)} · ${automation.steps.size} ${if (automation.steps.size == 1) "step" else "steps"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = automation.enabled,
                    onCheckedChange = { checked ->
                        val updated = automation.copy(enabled = checked)
                        AutomationRuntime.store.saveAutomation(updated)
                        if (updated.trigger.type == TriggerType.SCHEDULE) {
                            AutomationRuntime.registerSchedule(context, updated)
                        }
                        refresh()
                    },
                )
            }
            if (automation.description.isNotBlank()) {
                Text(automation.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        AutomationRuntime.router.runManual(automation.id)
                        DeviceState.addLog("Manual automation queued: ${automation.name}")
                        refresh()
                    },
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Run")
                }
                if (automation.steps.any { it.confirmationRequired }) {
                    StatusChip("Human confirmation", warning = true)
                }
            }
        }
    }
}

@Composable
private fun ActivityScreen(refreshTick: Int) {
    val runs = AutomationRuntime.store.listRuns(30)
    val logs = DeviceState.log.take(24)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Activity", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Runs, recoveries and device events", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (runs.isEmpty()) {
            item { EmptyState(Icons.Rounded.History, "Nothing has run yet", "Run an automation and its timeline will appear here.") }
        } else {
            items(runs, key = { it.id }) { run ->
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RunStateDot(run.state.name)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(run.automationName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${run.state.name.replace('_', ' ')} · ${formatTime(run.startedAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (run.error != null) InlineNotice(Icons.Rounded.Warning, "Needs attention", run.error)
                        Text(
                            "${run.steps.count { it.state.name == "SUCCESS" }} of ${run.steps.size} steps completed",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (run.state.name == "WAITING_FOR_HUMAN") {
                            Button(
                                onClick = {
                                    DeviceState.setController(DeviceState.Controller.AGENT)
                                    AutomationRuntime.router.resume(run.id)
                                },
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Return & resume")
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            SectionHeader("Device log", "Recent local events")
        }
        items(logs) { entry ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    entry.substringAfter(' ', entry),
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SettingsScreen(context: Context, refreshTick: Int, refresh: () -> Unit) {
    val prefs = context.getSharedPreferences("cyclone", Context.MODE_PRIVATE)
    val defaultDeviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.let { "android-$it" }.orEmpty()
    val defaultDeviceName = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")

    var coreUrl by rememberSaveable { mutableStateOf(prefs.getString("coreWsUrl", "").orEmpty()) }
    var token by rememberSaveable { mutableStateOf(prefs.getString("coreToken", "").orEmpty()) }
    var deviceName by rememberSaveable { mutableStateOf(prefs.getString("deviceName", defaultDeviceName).orEmpty()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Connection, permissions and enhanced runtime", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Cyclone Core", "Pair this phone with your Hermes runtime")
                    OutlinedTextField(
                        value = coreUrl,
                        onValueChange = { coreUrl = it },
                        label = { Text("WebSocket URL") },
                        placeholder = { Text("ws://192.168.1.10:8787/api/v1/mobile/connect") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Pairing token") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text("Device name") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                    Button(
                        onClick = {
                            prefs.edit()
                                .putString("coreWsUrl", coreUrl.trim())
                                .putString("coreToken", token)
                                .putString("deviceId", prefs.getString("deviceId", defaultDeviceId).orEmpty().ifBlank { defaultDeviceId })
                                .putString("deviceName", deviceName.trim().ifBlank { defaultDeviceName })
                                .apply()
                            BridgeClient.stop()
                            BridgeClient.start(context)
                            refresh()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (DeviceState.bridgeConnected) "Reconnect" else "Save & connect")
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader("Permissions", "Cyclone only works where Android explicitly grants access")
                    PermissionRow(
                        "Cyclone phone control",
                        nativeAccessibilityEnabled(context),
                        "Accessibility service",
                    ) {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    PermissionRow(
                        "Notification access",
                        notificationAccessEnabled(context),
                        "Notification listener",
                    ) {
                        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    }
                    PermissionRow(
                        "Calendar",
                        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED,
                        "Calendar-aware automations",
                    ) {
                        (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 100)
                    }
                    PermissionRow(
                        "Draw over apps",
                        Settings.canDrawOverlays(context),
                        "Mobilerun overlay / takeover helpers",
                    ) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.DeveloperMode, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Enhanced Mobilerun engine", fontWeight = FontWeight.Bold)
                            Text("Embedded in this APK", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    StatusRow(
                        "Mobilerun Accessibility",
                        MobilerunEmbedded.accessibilityConnected(),
                        if (MobilerunEmbedded.accessibilityConnected()) "Active" else "Enable for full enhanced control",
                    )
                    StatusRow(
                        "Portal runtime",
                        MobilerunEmbedded.portalServiceRunning(),
                        if (MobilerunEmbedded.portalServiceRunning()) "Running" else "Stopped",
                    )
                    if (!mobilerunAccessibilityEnabled(context)) {
                        InlineNotice(
                            Icons.Rounded.Info,
                            "Optional second accessibility engine",
                            "Enable “Cyclone Enhanced Control” in Android Accessibility when you want Mobilerun's local server, richer event stack and streaming features. Cyclone native control remains available separately.",
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (MobilerunEmbedded.portalServiceRunning()) {
                            OutlinedButton(onClick = { MobilerunEmbedded.stopPortalService(context); refresh() }) {
                                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Stop runtime")
                            }
                        } else {
                            Button(onClick = { MobilerunEmbedded.startPortalService(context); refresh() }) {
                                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Start runtime")
                            }
                        }
                        OutlinedButton(onClick = { MobilerunEmbedded.openPortalDashboard(context) }) {
                            Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Portal dashboard")
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(onClick = { MobilerunEmbedded.openPortalSettings(context) }) {
                            Icon(Icons.Rounded.Tune, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Advanced settings")
                        }
                        FilledTonalButton(onClick = { MobilerunEmbedded.openTriggers(context) }) { Text("Triggers") }
                        FilledTonalButton(onClick = { MobilerunEmbedded.openTaskHistory(context) }) { Text("Task history") }
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("About this build", "Cyclone Mobile 0.3.0-v2.1")
                    Text("Native Cyclone phone toolbox + Automation Studio + Hermes bridge + embedded Mobilerun Portal runtime.")
                    HorizontalDivider()
                    Text(
                        "Mobilerun Portal is included from upstream commit d3dae858ecc5ec3bfd3701ff27d58465c9f661b4 and remains licensed under GNU AGPL-3.0-or-later. Cyclone preserves upstream source and attribution through the pinned git submodule.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun HeroCard(title: String, subtitle: String, icon: ImageVector) {
    val gradient = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
            MaterialTheme.colorScheme.secondary,
        ),
    )
    Card(shape = RoundedCornerShape(28.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(ok)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Security,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onClick) { Text(if (ok) "Manage" else "Enable") }
    }
}

@Composable
private fun StatusDot(ok: Boolean) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (ok) Color(0xFF1FAD72) else Color(0xFFE6A23C)),
    )
}

@Composable
private fun RunStateDot(state: String) {
    val good = state == "SUCCESS"
    val waiting = state == "WAITING_FOR_HUMAN" || state == "WAITING"
    Box(
        Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(
                when {
                    good -> Color(0xFF1FAD72)
                    waiting -> Color(0xFFE6A23C)
                    state == "FAILED" -> Color(0xFFE34B4B)
                    else -> MaterialTheme.colorScheme.primary
                },
            ),
    )
}

@Composable
private fun StatusChip(text: String, warning: Boolean = false) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(
                if (warning) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun InlineNotice(icon: ImageVector, title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CreateAutomationDialog(onDismiss: () -> Unit, onCreate: (AutomationDefinition) -> Unit) {
    var name by rememberSaveable { mutableStateOf("New automation") }
    var triggerChoice by rememberSaveable { mutableStateOf("Manual") }
    var triggerValue by rememberSaveable { mutableStateOf("") }
    var actionChoice by rememberSaveable { mutableStateOf("Open app") }
    var target by rememberSaveable { mutableStateOf("com.android.settings") }
    var value by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf(false) }

    val triggerOptions = listOf("Manual", "Notification", "App opened")
    val actionOptions = listOf("Open app", "Tap text", "Type text", "Back", "Home")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New automation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Text("When", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    triggerOptions.forEach { option ->
                        FilterChip(selected = triggerChoice == option, onClick = { triggerChoice = option }, label = { Text(option) })
                    }
                }
                if (triggerChoice != "Manual") {
                    OutlinedTextField(
                        value = triggerValue,
                        onValueChange = { triggerValue = it },
                        label = { Text(if (triggerChoice == "Notification") "App package or filter" else "App package") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                Text("Do", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actionOptions.forEach { option ->
                        FilterChip(selected = actionChoice == option, onClick = { actionChoice = option }, label = { Text(option) })
                    }
                }
                if (actionChoice in listOf("Open app", "Tap text", "Type text")) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = {
                            Text(
                                when (actionChoice) {
                                    "Open app" -> "Package name"
                                    else -> "Text to find"
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                if (actionChoice == "Type text") {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Text to enter") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmation, onCheckedChange = { confirmation = it })
                    Text("Ask me before this action")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trigger = when (triggerChoice) {
                        "Notification" -> TriggerDefinition(TriggerType.NOTIFICATION, triggerValue.takeIf { it.isNotBlank() }?.let { mapOf("package" to it.trim()) }.orEmpty())
                        "App opened" -> TriggerDefinition(TriggerType.APP_OPENED, triggerValue.takeIf { it.isNotBlank() }?.let { mapOf("package" to it.trim()) }.orEmpty())
                        else -> TriggerDefinition(TriggerType.MANUAL)
                    }
                    val step = when (actionChoice) {
                        "Tap text" -> StepDefinition(
                            name = "Tap ${target.ifBlank { "element" }}",
                            type = StepType.PHONE_TOOL,
                            parameters = mapOf("tool" to "phone.click"),
                            selector = Selector(text = target.trim()),
                            confirmationRequired = confirmation,
                        )
                        "Type text" -> StepDefinition(
                            name = "Type text",
                            type = StepType.PHONE_TOOL,
                            parameters = mapOf("tool" to "phone.type", "text" to value),
                            selector = Selector(text = target.trim()),
                            confirmationRequired = confirmation,
                        )
                        "Back" -> StepDefinition(
                            name = "Go back",
                            type = StepType.PHONE_TOOL,
                            parameters = mapOf("tool" to "phone.back"),
                            confirmationRequired = confirmation,
                        )
                        "Home" -> StepDefinition(
                            name = "Go home",
                            type = StepType.PHONE_TOOL,
                            parameters = mapOf("tool" to "phone.home"),
                            confirmationRequired = confirmation,
                        )
                        else -> StepDefinition(
                            name = "Open app",
                            type = StepType.PHONE_TOOL,
                            parameters = mapOf("tool" to "phone.open_app", "package" to target.trim()),
                            confirmationRequired = confirmation,
                        )
                    }
                    onCreate(
                        AutomationDefinition(
                            name = name.trim().ifBlank { "New automation" },
                            trigger = trigger,
                            steps = listOf(step),
                            failureBehavior = FailureAction.ABORT,
                        ),
                    )
                },
            ) { Text("Create") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun nativeAccessibilityEnabled(context: Context): Boolean =
    serviceEnabled(context, CycloneAccessibilityService::class.java.name)

private fun mobilerunAccessibilityEnabled(context: Context): Boolean =
    serviceEnabled(context, MobilerunAccessibilityService::class.java.name)

private fun serviceEnabled(context: Context, className: String): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { flattened ->
        val component = ComponentName.unflattenFromString(flattened)
        component?.packageName == context.packageName && component.className == className
    }
}

private fun notificationAccessEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun triggerLabel(type: TriggerType): String = when (type) {
    TriggerType.MANUAL -> "Manual"
    TriggerType.NOTIFICATION -> "Notification"
    TriggerType.SCHEDULE -> "Schedule"
    TriggerType.APP_OPENED -> "App opened"
    TriggerType.CYCLONE_REMOTE -> "Cyclone remote"
    TriggerType.WEBSOCKET -> "WebSocket"
    TriggerType.CALENDAR_TIME -> "Calendar"
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
