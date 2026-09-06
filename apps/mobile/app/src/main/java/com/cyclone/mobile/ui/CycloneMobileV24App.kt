package com.cyclone.mobile.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.CycloneAccessibilityService

/** V2.4 adds a persistent teach-a-routine overlay on top of the V2.3 Quick Agent. */
@Composable
fun CycloneMobileV24App() {
    CycloneTheme {
        val context = LocalContext.current
        Box(Modifier.fillMaxSize()) {
            CycloneMobileV23App()
            ExtendedFloatingActionButton(
                onClick = {
                    val service = CycloneAccessibilityService.instance
                    if (service == null) {
                        Toast.makeText(context, "Enable Cyclone Accessibility first", Toast.LENGTH_LONG).show()
                    } else {
                        service.showGuidedRecorderOverlay()
                        Toast.makeText(context, "Recorder bubble is ready — show Cyclone the routine", Toast.LENGTH_SHORT).show()
                        (context as? Activity)?.moveTaskToBack(true)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 88.dp),
                icon = { Icon(Icons.Rounded.Gesture, contentDescription = null) },
                text = { Text("Teach") },
            )
        }
    }
}
