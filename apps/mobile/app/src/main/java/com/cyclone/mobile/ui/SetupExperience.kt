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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import com.cyclone.mobile.BridgeClient
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.MobilerunEmbedded
import com.cyclone.mobile.SetupNeed
import com.cyclone.mobile.SetupReminderState
import com.mobilerun.portal.service.MobilerunAccessibilityService
import kotlinx.coroutines.delay

private const val SETUP_VERSION = "2.2"
private const val SETUP_PREF = "onboardingVersionSeen"
private const val SETUP_STEPS = 5

private data class SetupStatus(
    val cycloneAccessibility: Boolean,
    val mobilerunAccessibility: Boolean,
    val notifications: Boolean,
    val calendar: Boolean,
    val overlay: Boolean,
    val coreConfigured: Boolean,
    val coreConnected: Boolean,
) {
    val minimumPhoneReady: Boolean get() = cycloneAccessibility
    val automationReady: Boolean get() = cycloneAccessibility && notifications
    val aiReady: Boolean get() = cycloneAccessibility && coreConnected
}

@Composable
fun CycloneMobileV22App() {
    CycloneTheme {
        Box(Modifier.fillMaxSize()) {
            CycloneMobileApp()
            SetupExperience()
        }
    }
}

@Composable
private fun SetupExperience() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("cyclone", Context.MODE_PRIVATE)
    var refreshTick by remember { mutableIntStateOf(0) }
    var showSetup by rememberSaveable { mutableStateOf(prefs.getString(SETUP_PREF, "") != SETUP_VERSION) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var snoozed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(800)
            refreshTick++
            if (!showSetup && SetupReminderState.need != null) {
                step = stepForNeed(SetupReminderState.need)
                showSetup = true
                snoozed = false
            }
        }
    }

    val status = remember(refreshTick, showSetup) { readSetupStatus(context) }
    val persistentNeed = firstMissingNeed(status)

    if (showSetup) {
        SetupWizard(
            initialStep = step,
            status = status,
            onStepChange = { step = it.coerceIn(0, SETUP_STEPS - 1) },
            onDismiss = {
                prefs.edit().putString(SETUP_PREF, SETUP_VERSION).apply()
                SetupReminderState.clear()
                showSetup = false
                snoozed = true
            },
            onComplete = {
                prefs.edit().putString(SETUP_PREF, SETUP_VERSION).apply()
                SetupReminderState.clear()
                showSetup = false
                snoozed = false
            },
        )
    } else if (!snoozed && persistentNeed != null) {
        SetupReminderBanner(
            need = SetupReminderState.need ?: persistentNeed,
            customMessage = SetupReminderState.message,
            onSetup = {
                step = stepForNeed(SetupReminderState.need ?: persistentNeed)
                showSetup = true
            },
            onLater = { snoozed = true },
        )
    }
}

private fun firstMissingNeed(status: SetupStatus): SetupNeed? = when {
    !status.cycloneAccessibility -> SetupNeed.PHONE_CONTROL
    !status.notifications -> SetupNeed.NOTIFICATIONS
    !status.coreConfigured || !status.coreConnected -> SetupNeed.CORE
    else -> null
}

private fun stepForNeed(need: SetupNeed?): Int = when (need) {
    SetupNeed.PHONE_CONTROL -> 1
    SetupNeed.NOTIFICATIONS, SetupNeed.CALENDAR, SetupNeed.OVERLAY -> 2
    SetupNeed.CORE -> 3
    null -> 0
}

@Composable
private fun SetupReminderBanner(
    need: SetupNeed,
    customMessage: String?,
    onSetup: () -> Unit,
    onLater: () -> Unit,
) {
    val title = when (need) {
        SetupNeed.PHONE_CONTROL -> "Phone control needs setup"
        SetupNeed.NOTIFICATIONS -> "Notification automations need access"
        SetupNeed.CALENDAR -> "Calendar matching needs permission"
        SetupNeed.OVERLAY -> "Takeover tools need overlay access"
        SetupNeed.CORE -> "AI features need Cyclone Core"
    }
    val body = customMessage ?: when (need) {
        SetupNeed.PHONE_CONTROL -> "Enable Accessibility so Cyclone can read screens and perform actions."
        SetupNeed.NOTIFICATIONS -> "Enable notification access to react instantly without screenshot polling."
        SetupNeed.CALENDAR -> "Allow calendar access only if you want schedule-aware automations."
        SetupNeed.OVERLAY -> "Allow drawing over apps for enhanced takeover and runtime helpers."
        SetupNeed.CORE -> "Pair this phone with Cyclone Core to use Hermes, remote agents and AI workflow building."
    }

    Card(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 14.dp, vertical = 90.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.inverseOnSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    body,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.76f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onSetup) { Text("Fix") }
                androidx.compose.material3.TextButton(onClick = onLater) { Text("Later") }
            }
        }
    }
}

@Composable
private fun SetupWizard(
    initialStep: Int,
    status: SetupStatus,
    onStepChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("cyclone", Context.MODE_PRIVATE)
    var step by remember(initialStep) { mutableIntStateOf(initialStep.coerceIn(0, SETUP_STEPS - 1)) }
    val defaultDeviceName = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
    val defaultDeviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.let { "android-$it" }.orEmpty()
    var coreUrl by rememberSaveable { mutableStateOf(prefs.getString("coreWsUrl", "").orEmpty()) }
    var token by rememberSaveable { mutableStateOf(prefs.getString("coreToken", "").orEmpty()) }
    var deviceName by rememberSaveable { mutableStateOf(prefs.getString("deviceName", defaultDeviceName).orEmpty()) }

    fun move(next: Int) {
        step = next.coerceIn(0, SETUP_STEPS - 1)
        onStepChange(step)
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Cyclone Mobile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("V2.2 guided setup", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close setup") }
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { (step + 1).toFloat() / SETUP_STEPS.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(18.dp))

                Box(Modifier.weight(1f)) {
                    when (step) {
                        0 -> WelcomeSetupCard()
                        1 -> PhoneControlSetupCard(context, status)
                        2 -> EventAccessSetupCard(context, status)
                        3 -> CoreSetupCard(
                            coreUrl = coreUrl,
                            token = token,
                            deviceName = deviceName,
                            connected = status.coreConnected,
                            onUrl = { coreUrl = it },
                            onToken = { token = it },
                            onDeviceName = { deviceName = it },
                            onConnect = {
                                prefs.edit()
                                    .putString("coreWsUrl", normalizeCoreUrl(coreUrl))
                                    .putString("coreToken", token.trim())
                                    .putString("deviceId", prefs.getString("deviceId", defaultDeviceId).orEmpty().ifBlank { defaultDeviceId })
                                    .putString("deviceName", deviceName.trim().ifBlank { defaultDeviceName })
                                    .apply()
                                coreUrl = normalizeCoreUrl(coreUrl)
                                BridgeClient.stop()
                                BridgeClient.start(context)
                                DeviceState.addLog("V2.2 setup requested Core connection")
                            },
                        )
                        else -> ReadySetupCard(status)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (step > 0) {
                        OutlinedButton(onClick = { move(step - 1) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Back")
                        }
                    }
                    Button(
                        onClick = {
                            if (step == SETUP_STEPS - 1) onComplete() else move(step + 1)
                        },
                        modifier = Modifier.weight(1.35f),
                    ) {
                        Text(if (step == SETUP_STEPS - 1) "Start using Cyclone" else "Continue")
                        if (step < SETUP_STEPS - 1) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Rounded.ArrowForward, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeSetupCard() {
    SetupPageCard(
        icon = Icons.Rounded.AutoAwesome,
        eyebrow = "WELCOME TO CYCLONE",
        title = "Your phone becomes an AI toolbox",
        body = "Cyclone can observe app UI, tap, type, scroll, react to notifications, run deterministic automations and hand difficult states to Hermes. You stay in control of every permission.",
    ) {
        SetupFeature("Fast by default", "Known workflows run locally without wasting AI tokens.")
        SetupFeature("AI when it matters", "Hermes handles unfamiliar screens, recovery and workflow creation.")
        SetupFeature("Human takeover", "When login or verification needs you, agents pause and wait without polling.")
        SetupFeature("Private permissions", "Android grants are explicit and can be changed at any time.")
    }
}

@Composable
private fun PhoneControlSetupCard(context: Context, status: SetupStatus) {
    SetupPageCard(
        icon = Icons.Rounded.Security,
        eyebrow = "STEP 1",
        title = "Let Cyclone understand your screen",
        body = "Accessibility is the main non-root control path. It gives Cyclone structured UI elements and safe gesture actions. The embedded Mobilerun engine can provide an additional runtime path.",
    ) {
        SetupActionRow(
            title = "Cyclone phone control",
            detail = if (status.cycloneAccessibility) "Ready" else "Required for phone actions",
            ok = status.cycloneAccessibility,
            action = if (status.cycloneAccessibility) "Manage" else "Enable",
        ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        SetupActionRow(
            title = "Mobilerun enhanced engine",
            detail = if (status.mobilerunAccessibility) "Ready" else "Optional enhanced Accessibility backend",
            ok = status.mobilerunAccessibility,
            action = if (status.mobilerunAccessibility) "Manage" else "Enable",
        ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        FilledTonalButton(onClick = { MobilerunEmbedded.openPortalDashboard(context) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Open enhanced runtime dashboard")
        }
    }
}

@Composable
private fun EventAccessSetupCard(context: Context, status: SetupStatus) {
    SetupPageCard(
        icon = Icons.Rounded.Notifications,
        eyebrow = "STEP 2",
        title = "Wake on events, not screenshots",
        body = "These permissions let Cyclone react immediately and stay battery-efficient. Calendar and overlay access are optional until an automation needs them.",
    ) {
        SetupActionRow(
            "Notification access",
            if (status.notifications) "Ready for instant notification triggers" else "Recommended for event-driven automations",
            status.notifications,
            if (status.notifications) "Manage" else "Enable",
        ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        SetupActionRow(
            "Calendar",
            if (status.calendar) "Ready for schedule matching" else "Optional for calendar-aware workflows",
            status.calendar,
            if (status.calendar) "Manage" else "Allow",
        ) {
            if (status.calendar) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            } else {
                (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 100)
            }
        }
        SetupActionRow(
            "Draw over apps",
            if (status.overlay) "Ready for enhanced takeover helpers" else "Optional for overlay/takeover helpers",
            status.overlay,
            if (status.overlay) "Manage" else "Allow",
        ) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        }
    }
}

@Composable
private fun CoreSetupCard(
    coreUrl: String,
    token: String,
    deviceName: String,
    connected: Boolean,
    onUrl: (String) -> Unit,
    onToken: (String) -> Unit,
    onDeviceName: (String) -> Unit,
    onConnect: () -> Unit,
) {
    SetupPageCard(
        icon = Icons.Rounded.Link,
        eyebrow = "STEP 3",
        title = "Connect your Cyclone brain",
        body = "Core pairing unlocks Hermes, remote agents, AI recovery and natural-language automation building. Local phone automations can still run without Core.",
    ) {
        if (connected) {
            SetupSuccess("Connected to Cyclone Core")
        }
        OutlinedTextField(
            value = coreUrl,
            onValueChange = onUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Cyclone Core address") },
            placeholder = { Text("192.168.1.10:8787") },
            supportingText = { Text("You can paste a host/IP; Cyclone will add the mobile WebSocket path.") },
            shape = RoundedCornerShape(16.dp),
        )
        OutlinedTextField(
            value = token,
            onValueChange = onToken,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Pairing token") },
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(16.dp),
        )
        OutlinedTextField(
            value = deviceName,
            onValueChange = onDeviceName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Device name") },
            shape = RoundedCornerShape(16.dp),
        )
        Button(onClick = onConnect, enabled = coreUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Link, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (connected) "Reconnect" else "Save & connect")
        }
        Text(
            "Tip: keep the phone and Cyclone Core on the same trusted network for the easiest first setup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadySetupCard(status: SetupStatus) {
    SetupPageCard(
        icon = Icons.Rounded.CheckCircle,
        eyebrow = "READY CHECK",
        title = if (status.aiReady) "Cyclone is ready" else "You can start now",
        body = "Setup reminders will appear later only when a feature needs access that is still missing. You can reopen every setting from the Settings tab.",
    ) {
        SetupSummaryRow("Phone control", status.cycloneAccessibility, if (status.cycloneAccessibility) "Ready" else "Enable before phone actions")
        SetupSummaryRow("Notifications", status.notifications, if (status.notifications) "Ready" else "Needed for notification triggers")
        SetupSummaryRow("Calendar", status.calendar, if (status.calendar) "Ready" else "Optional")
        SetupSummaryRow("Cyclone Core", status.coreConnected, if (status.coreConnected) "Connected" else "Optional for local automations; required for Hermes")
        SetupSummaryRow("Mobilerun engine", status.mobilerunAccessibility, if (status.mobilerunAccessibility) "Enhanced runtime ready" else "Optional")
        HorizontalDivider()
        Text(
            when {
                status.aiReady -> "AI phone control, local automations and event triggers are available."
                status.automationReady -> "Local automations are ready. Pair Core when you want Hermes AI features."
                status.minimumPhoneReady -> "Phone control is ready. Add notification access for 24/7 event-driven workflows."
                else -> "Enable Cyclone phone control before attempting UI automation."
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SetupPageCard(
    icon: ImageVector,
    eyebrow: String,
    title: String,
    body: String,
    content: @Composable Column.() -> Unit,
) {
    val gradient = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.surface,
        ),
    )
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(gradient)
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
            }
            Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun SetupFeature(title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupActionRow(title: String, detail: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Settings,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun SetupSummaryRow(title: String, ok: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Settings,
            contentDescription = null,
            tint = if (ok) Color(0xFF1FAD72) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupSuccess(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

private fun readSetupStatus(context: Context): SetupStatus {
    val prefs = context.getSharedPreferences("cyclone", Context.MODE_PRIVATE)
    return SetupStatus(
        cycloneAccessibility = accessibilityServiceEnabled(context, CycloneAccessibilityService::class.java.name),
        mobilerunAccessibility = accessibilityServiceEnabled(context, MobilerunAccessibilityService::class.java.name),
        notifications = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
        calendar = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED,
        overlay = Settings.canDrawOverlays(context),
        coreConfigured = prefs.getString("coreWsUrl", "").orEmpty().isNotBlank() && prefs.getString("coreToken", "").orEmpty().isNotBlank(),
        coreConnected = DeviceState.bridgeConnected,
    )
}

private fun accessibilityServiceEnabled(context: Context, className: String): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { flattened ->
        val component = ComponentName.unflattenFromString(flattened)
        component?.packageName == context.packageName && component.className == className
    }
}

private fun normalizeCoreUrl(raw: String): String {
    val value = raw.trim().removeSuffix("/")
    if (value.isBlank()) return value
    if (value.startsWith("ws://") || value.startsWith("wss://")) {
        return if (value.contains("/api/v1/mobile/connect")) value else "$value/api/v1/mobile/connect"
    }
    val base = if (value.startsWith("http://")) "ws://${value.removePrefix("http://")}" else if (value.startsWith("https://")) "wss://${value.removePrefix("https://")}" else "ws://$value"
    return if (base.contains("/api/v1/mobile/connect")) base else "$base/api/v1/mobile/connect"
}
