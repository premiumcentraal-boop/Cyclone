package com.cyclone.mobile.gateway

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.v32.CycloneTheme
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay

/** Current Cyclone control center for the USB-only PC bridge. */
class GatewaySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GatewayRuntime.startPairingBootstrap(this)
        setContent {
            CycloneTheme {
                GatewayControlCenter(
                    context = this,
                    onClose = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewayControlCenter(
    context: Context,
    onClose: () -> Unit,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    var technicalOpen by remember { mutableStateOf(false) }
    val livePhoneState by LivePhoneMode.state.collectAsState()

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        while (true) {
            delay(750)
            refreshTick++
        }
    }

    val status = remember(refreshTick) { GatewayRuntime.status(context) }
    val enabled = status.optBoolean("gatewayEnabled")
    val accessibilityReady = status.optBoolean("accessibilityConnected")
    val phoneControlReady = if (status.has("phoneControlReady")) {
        status.optBoolean("phoneControlReady")
    } else {
        accessibilityReady
    }
    val phoneControlNeedsRepair = status.optBoolean("phoneControlNeedsRepair")
    val nextActionJson = status.optJSONObject("nextAction")
    val nextAction = nextActionJson?.optString("code")?.takeIf { it.isNotBlank() }?.let {
        GatewayReadyNextAction(
            code = it,
            title = nextActionJson.optString("title"),
            body = nextActionJson.optString("body"),
            actionLabel = nextActionJson.optString("actionLabel"),
        )
    }
    val session = status.optJSONObject("connectedSession")
    val connected = session?.optBoolean("connected") == true
    val clientCount = session?.optInt("clientCount", 0) ?: 0
    val bootstrapReady = status.optBoolean("pairingBootstrapListening")
    val productionAuthority = status.optBoolean("productionActionAuthorityBound")
    val trust = status.optJSONObject("trust")
    val trustState = trust?.optString("trustState").orEmpty()
    val trustedPcCount = trust?.optInt("trustedPcCount", 0) ?: 0
    val trustedSessionCount = trust?.optInt("activeSessionCount", 0) ?: 0
    val pendingTrust = remember(refreshTick) { GatewayV33TrustManager.pendingForUser(context) }
    val pairingCode = remember(refreshTick) { GatewayDesktopPairingManager.codeForUser() }
    val pairingExpiresAt = remember(refreshTick) { GatewayDesktopPairingManager.expiresAtForUser() }
    val clipboardEnabled = remember(refreshTick) { GatewayDesktopPreferences.clipboardEnabled(context) }
    val pcPresent = connected || pendingTrust != null || clientCount > 0
    val trustReady = connected || trustedPcCount > 0

    val heroTitle = when {
        connected -> "Connected"
        pendingTrust != null || (pcPresent && !trustReady) -> "USB connected · approval needed"
        !phoneControlReady -> "Phone control needed"
        enabled && bootstrapReady -> "Ready for PC"
        enabled -> "Starting PC Gateway"
        else -> "PC Gateway off"
    }
    val heroSubtitle = when {
        connected -> "This PC can use Cyclone's approved phone controls."
        pendingTrust != null -> "Approve the trust request below to finish this connection."
        pcPresent && !trustReady -> "The phone sees your PC. Approve Cyclone AI trust when the request appears."
        !phoneControlReady -> "Turn on phone control before the PC can operate this screen."
        enabled && bootstrapReady -> "Connect Cyclone One over USB when you want PC control."
        enabled -> "Preparing the local USB bridge."
        else -> "Turn it on when you want to use Cyclone from your PC."
    }

    val localRepairAction = nextAction?.takeIf {
        it.code == GatewayReadyDoctor.TURN_ON_GATEWAY ||
            it.code == GatewayReadyDoctor.FIX_USB_BRIDGE ||
            it.code == GatewayReadyDoctor.REPAIR_PHONE_CONTROL ||
            it.code == GatewayReadyDoctor.ENABLE_PHONE_CONTROL
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("PC Gateway", fontWeight = FontWeight.SemiBold)
                        Text(
                            "USB trust + phone control",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to Cyclone")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                GatewayHero(
                    title = heroTitle,
                    subtitle = heroSubtitle,
                    enabled = enabled,
                    connected = connected,
                    attention = pendingTrust != null || phoneControlNeedsRepair,
                ) { turnOn ->
                    runCatching {
                        if (turnOn) GatewayRuntime.enable(context) else GatewayRuntime.disable(context)
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "Could not change Gateway state", Toast.LENGTH_LONG).show()
                    }
                    refreshTick++
                }
            }

            if (pendingTrust != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                    Icon(
                                        Icons.Rounded.Security,
                                        null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(8.dp).size(20.dp),
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text("Allow this PC?", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        pendingTrust.pcLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                            Text(
                                "Approve this PC for short-lived Cyclone sessions. Sensitive actions still require confirmation.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val accepted = GatewayV33TrustManager.decideTrust(context, pendingTrust.challengeId, true)
                                        Toast.makeText(
                                            context,
                                            if (accepted) "PC approved" else "Trust request expired. Request it again on the PC.",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        refreshTick++
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Allow this PC") }
                                TextButton(
                                    onClick = {
                                        GatewayV33TrustManager.decideTrust(context, pendingTrust.challengeId, false)
                                        refreshTick++
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Not now") }
                            }
                        }
                    }
                }
            }

            if (localRepairAction != null && pendingTrust == null) {
                item {
                    GatewayNextActionCard(
                        action = localRepairAction,
                        gatewayEnabled = enabled,
                        onTurnOnGateway = {
                            runCatching { GatewayRuntime.enable(context) }.onFailure {
                                Toast.makeText(context, it.message ?: "Could not change Gateway state", Toast.LENGTH_LONG).show()
                            }
                            refreshTick++
                        },
                        onOpenAccessibility = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                    )
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        Text("Connection", style = MaterialTheme.typography.titleMedium)
                        GatewayStatusLine(
                            icon = Icons.Rounded.Link,
                            title = "USB bridge",
                            value = when {
                                pcPresent -> "Connected"
                                bootstrapReady -> "Ready"
                                else -> "Starting"
                            },
                            ready = pcPresent || bootstrapReady,
                        )
                        GatewayStatusLine(
                            icon = Icons.Rounded.PhoneAndroid,
                            title = "Phone control",
                            value = when {
                                phoneControlReady -> "Ready"
                                phoneControlNeedsRepair -> "Needs repair"
                                else -> "Off"
                            },
                            ready = phoneControlReady,
                        )
                        GatewayStatusLine(
                            icon = Icons.Rounded.Computer,
                            title = "Cyclone AI trust",
                            value = when {
                                pendingTrust != null -> "Approve"
                                connected -> "Session active"
                                trustedPcCount > 0 -> "Trusted"
                                else -> "Not trusted"
                            },
                            ready = trustReady && pendingTrust == null,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Clipboard paste", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "PC → phone paste; password, OTP and token-like values stay blocked.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = clipboardEnabled,
                                onCheckedChange = {
                                    GatewayDesktopPreferences.setClipboardEnabled(context, it)
                                    refreshTick++
                                },
                            )
                        }
                    }
                }
            }

            if (livePhoneState != "Not connected") {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("Live phone", style = MaterialTheme.typography.titleSmall)
                                Text(livePhoneState, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (livePhoneState == "Paused" || livePhoneState == "Stopped") {
                                FilledTonalButton(onClick = { LivePhoneMode.setPaused(context, false) }) { Text("Resume") }
                            } else {
                                TextButton(onClick = { LivePhoneMode.setPaused(context, true) }) { Text("Pause") }
                                TextButton(onClick = { LivePhoneMode.setPaused(context, true, true) }) { Text("Stop") }
                            }
                        }
                    }
                }
            }

            if (trustedPcCount > 0 || connected) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Rounded.Lock, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text("Trusted PCs", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "$trustedPcCount trusted · $trustedSessionCount active session${if (trustedSessionCount == 1) "" else "s"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (connected) {
                                    OutlinedButton(
                                        onClick = {
                                            GatewayRuntime.disconnect()
                                            refreshTick++
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Disconnect") }
                                }
                                TextButton(
                                    onClick = {
                                        val count = GatewayV33TrustManager.revokeAllLocal(context)
                                        GatewayRuntime.disconnect()
                                        Toast.makeText(
                                            context,
                                            if (count > 0) "Revoked $count trusted PC${if (count == 1) "" else "s"}" else "No trusted PCs to revoke",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        refreshTick++
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Revoke trust") }
                            }
                        }
                    }
                }
            }

            if (!connected && trustedPcCount == 0 && pendingTrust == null) {
                item {
                    GatewayConnectGuide(
                        pcPresent = pcPresent,
                        usbReady = bootstrapReady,
                        phoneReady = phoneControlReady,
                    )
                }
            }

            status.optString("lastSafeError").takeIf { it.isNotBlank() && it != "null" }?.let { safeError ->
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Connection needs attention", style = MaterialTheme.typography.titleSmall)
                                Text(safeError, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            item {
                TextButton(
                    onClick = { technicalOpen = !technicalOpen },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (technicalOpen) "Hide technical details" else "Technical details")
                }
            }

            if (technicalOpen) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Bridge diagnostics", style = MaterialTheme.typography.titleSmall)
                            DiagnosticLine("Socket", status.optString("socketLifecycleState").ifBlank { "UNKNOWN" })
                            DiagnosticLine("Gateway authority", if (enabled) "ENABLED" else "DISABLED")
                            DiagnosticLine("ADB clients", clientCount.toString())
                            DiagnosticLine("AI trust", trustState.ifBlank { "UNKNOWN" })
                            DiagnosticLine("Semantic", status.optString("semanticObservationState").ifBlank { "UNKNOWN" })
                            DiagnosticLine("Action authority", status.optString("actionAuthorityState").ifBlank { if (productionAuthority) "READY" else "DEGRADED" })
                            DiagnosticLine("Clipboard", if (clipboardEnabled) "PC → PHONE" else "OFF")
                            DiagnosticLine("Protocol", status.optString("protocolVersion").ifBlank { "Unknown" })

                            if (pairingCode != null) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                                Text("Fallback pairing", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Only for transition clients that cannot use normal USB trust.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = { scanDesktopPairingQr(context) }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Scan fallback QR")
                                }
                                Text(pairingCode, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                val seconds = (((pairingExpiresAt ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)
                                Text("Expires in about ${seconds}s", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GatewayHero(
    title: String,
    subtitle: String,
    enabled: Boolean,
    connected: Boolean,
    attention: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = when {
                    connected -> MaterialTheme.colorScheme.secondaryContainer
                    attention -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                Icon(
                    if (connected) Icons.Rounded.CheckCircle else Icons.Rounded.Usb,
                    null,
                    modifier = Modifier.padding(10.dp).size(23.dp),
                    tint = when {
                        connected -> MaterialTheme.colorScheme.secondary
                        attention -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
private fun GatewayNextActionCard(
    action: GatewayReadyNextAction,
    gatewayEnabled: Boolean,
    onTurnOnGateway: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(action.title, style = MaterialTheme.typography.titleMedium)
            Text(action.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    when (action.code) {
                        GatewayReadyDoctor.TURN_ON_GATEWAY -> onTurnOnGateway()
                        GatewayReadyDoctor.FIX_USB_BRIDGE -> if (!gatewayEnabled) onTurnOnGateway()
                        GatewayReadyDoctor.REPAIR_PHONE_CONTROL,
                        GatewayReadyDoctor.ENABLE_PHONE_CONTROL,
                        -> onOpenAccessibility()
                    }
                },
            ) { Text(action.actionLabel) }
        }
    }
}

@Composable
private fun GatewayStatusLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    ready: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Icon(
            if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
            null,
            modifier = Modifier.size(17.dp),
            tint = if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(5.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GatewayConnectGuide(pcPresent: Boolean, usbReady: Boolean, phoneReady: Boolean) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(if (pcPresent) "Finish connecting" else "Connect your PC", style = MaterialTheme.typography.titleMedium)
            if (pcPresent) {
                Text(
                    "Your PC is visible over USB. Open Cyclone One and approve the trust request on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                GatewayStep("1", if (usbReady) "Connect USB" else "Start with USB", "Plug this phone into your Windows PC.")
                if (!phoneReady) GatewayStep("2", "Enable phone control", "Cyclone needs phone control before PC actions can run.")
                GatewayStep(if (phoneReady) "2" else "3", "Choose this phone", "Open Cyclone One and select this device.")
                GatewayStep(if (phoneReady) "3" else "4", "Approve trust", "Allow the request that appears on this phone.")
            }
        }
    }
}

@Composable
private fun GatewayStep(number: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                number,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun scanDesktopPairingQr(context: Context) {
    val activity = context as? ComponentActivity ?: return
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()
    GmsBarcodeScanning.getClient(activity, options).startScan()
        .addOnSuccessListener { barcode ->
            val approved = barcode.rawValue?.let(GatewayDesktopPairingManager::approveQrPayload) == true
            Toast.makeText(
                context,
                if (approved) "Fallback pairing approved. Return to Cyclone on your PC."
                else "That fallback QR is invalid or expired. Request a new one on your PC.",
                Toast.LENGTH_LONG,
            ).show()
        }
        .addOnFailureListener {
            Toast.makeText(context, "QR scanner unavailable. Use the fallback code below.", Toast.LENGTH_LONG).show()
        }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}
