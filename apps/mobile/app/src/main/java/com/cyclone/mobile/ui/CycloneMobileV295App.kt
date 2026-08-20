package com.cyclone.mobile.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.gateway.GatewaySettingsActivity

/**
 * Cyclone 2.9.5 shell.
 *
 * The actual product UI remains the exact 2.9.3 CycloneMobileV292App. 2.9.5 only
 * adds one small in-app entry to the PC Gateway so the gateway does not need its
 * own launcher icon or a second-looking app surface.
 */
@Composable
fun CycloneMobileV295App() {
    val context = LocalContext.current
    CycloneTheme {
        Box(Modifier.fillMaxSize()) {
            CycloneMobileV292App()
            FilledTonalIconButton(
                onClick = {
                    context.startActivity(Intent(context, GatewaySettingsActivity::class.java))
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 58.dp)
                    .size(40.dp),
            ) {
                Icon(Icons.Rounded.Link, contentDescription = "PC Gateway")
            }
        }
    }
}
