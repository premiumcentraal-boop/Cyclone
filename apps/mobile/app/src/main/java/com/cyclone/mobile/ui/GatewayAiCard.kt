package com.cyclone.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.CycloneRelease
import com.cyclone.mobile.gateway.GatewayRuntime
import com.cyclone.mobile.gateway.GatewaySettingsActivity

/** Prominent, user-friendly entry to the full PC/Codex gateway from Cyclone AI. */
@Composable
internal fun GatewayAiCard(context: Context, refreshTick: Int) {
    val status = remember(refreshTick) { GatewayRuntime.status(context) }
    val enabled = status.optBoolean("gatewayEnabled")
    val accessibilityReady = status.optBoolean("accessibilityConnected")
    val session = status.optJSONObject("connectedSession")
    val pcConnected = session?.optBoolean("connected") == true
    val token = if (enabled) GatewayRuntime.tokenForUser(context).orEmpty() else ""
    val state = when (status.optString("gatewayState")) {
        "CONNECTED" -> "CONNECTED"
        "WAITING_FOR_PC" -> "WAITING FOR PC"
        "ATTENTION_NEEDED" -> "ATTENTION NEEDED"
        else -> "OFF"
    }
    val healthy = state == "CONNECTED" || state == "WAITING FOR PC"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("CYCLONE AI", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Full PC + Codex Gateway", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(state, style = MaterialTheme.typography.labelLarge, color = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { turnOn ->
                        runCatching {
                            if (turnOn) GatewayRuntime.enable(context) else GatewayRuntime.disable(context)
                        }.onFailure {
                            Toast.makeText(context, it.message ?: "Could not change Gateway state", Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }

            Text(
                "Connect ${CycloneRelease.label} to a trusted Windows PC over USB. Codex can use Cyclone's structured phone controls while Android policy and verification stay authoritative.",
                style = MaterialTheme.typography.bodyMedium,
            )

            GatewayStatusRow("Gateway", enabled, if (enabled) "On" else "Off")
            GatewayStatusRow("Phone control", accessibilityReady, if (accessibilityReady) "Ready" else "Accessibility off")
            GatewayStatusRow("USB / PC session", pcConnected, if (pcConnected) "Connected" else if (enabled) "Waiting for PC" else "Off")
            GatewayStatusRow("PC Gateway health", pcConnected, if (pcConnected) "Session active" else "Known after PC connects")

            status.optString("lastSafeError").takeIf { it.isNotBlank() && it != "null" }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { context.startActivity(Intent(context, GatewaySettingsActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Link, null)
                Spacer(Modifier.width(7.dp))
                Text(if (enabled) "Open Gateway control center" else "Set up Full Gateway")
            }

            if (enabled && token.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Cyclone Gateway session token", token))
                        Toast.makeText(context, "Session token copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.ContentCopy, null)
                    Spacer(Modifier.width(7.dp))
                    Text("Copy session token")
                }
            }
        }
    }
}

@Composable
private fun GatewayStatusRow(label: String, ready: Boolean, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
            null,
            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}
