package com.cyclone.mobile.ui.v32

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.permissions.RootQuickSetup
import com.cyclone.mobile.runtime.background.BackgroundSetup
import com.cyclone.mobile.runtime.background.BackgroundSetupActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
internal fun CycloneQuickSetup(context: Context, refresh: () -> Unit, openPermissions: () -> Unit) {
    var independent by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var checks by remember { mutableStateOf(RootQuickSetup.checks(context)) }
    var background by remember { mutableStateOf(BackgroundSetup.read(context)) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) {
            checks = RootQuickSetup.checks(context)
            background = BackgroundSetup.read(context)
            delay(1000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Quick setup", style = MaterialTheme.typography.headlineSmall)
        Text("Set up this profile with root: phone control, notifications, microphone, calendar, overlay, battery access, routine timing and Cyclone’s keyboard. Other profiles stay unchanged.")
        Row {
            Checkbox(independent, { independent = it }, enabled = !busy)
            Column {
                Text("Independent phone autonomy")
                Text("Routine actions run without asking. Payments, account access, deletion and final sends still need confirmation.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    message = withContext(Dispatchers.IO) { RootQuickSetup.apply(context.applicationContext, independent) }
                    checks = RootQuickSetup.checks(context)
                    refresh()
                } finally { busy = false }
            }
        }) { Text(if (busy) "Setting up…" else "Enable with root") }
        message?.let { Text(it) }
        checks.forEach { Text("${if (it.ready) "✓" else "○"} ${it.label}${if (it.ready) "" else " — needs attention"}") }
        if (checks.all { it.ready }) Text("Phone permissions ready")
        TextButton(onClick = openPermissions) { Text("Review or fix permissions") }
        Text(if (background.setupFailure == null) "✓ Isolated background work ready"
            else background.setupFailure.orEmpty())
        Text("Shizuku can start with root. Its install/start/access prompts remain in the guided background setup. Isolated work needs Android 15+. Screen sharing still asks each session.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { context.startActivity(Intent(context, BackgroundSetupActivity::class.java)) }) {
            Text("Finish background setup")
        }
    }
}
