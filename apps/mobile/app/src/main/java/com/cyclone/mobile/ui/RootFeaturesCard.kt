package com.cyclone.mobile.ui

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.UserManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.runtime.workspaces.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

@Composable
fun RootFeaturesCard() {
    var show by rememberSaveable { mutableStateOf(false) }
    var root by remember { mutableStateOf(RootProbe.status) }
    LaunchedEffect(Unit) { while (true) { root = RootProbe.status; delay(800) } }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(
        containerColor = if (root == RootStatus.ROOTED) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Security, null)
                Text("Root features", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                SuggestionChip(onClick = { show = true }, label = { Text(root.label) })
            }
            Text("Run multiple app profiles on one phone, then switch Cyclone between them")
            if (root != RootStatus.ROOTED) Text("Root unlocks verified cross-profile switching. Non-root dual apps with distinct packages can also work.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) { Text("Set up workspaces") }
        }
    }
    if (show) RootWizardDialog { show = false }
}

@Composable
private fun RootWizardDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var root by remember { mutableStateOf(RootProbe.status) }
    var label by rememberSaveable { mutableStateOf("") }
    var packageName by rememberSaveable { mutableStateOf("") }
    var userId by rememberSaveable { mutableStateOf("0") }
    var selected by rememberSaveable { mutableStateOf("") }
    var profiles by rememberSaveable { mutableIntStateOf(0) }
    var inventory by remember { mutableStateOf(emptyList<Workspace>()) }
    var holder by remember { mutableStateOf("None — input paused") }
    var picker by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var search by remember { mutableStateOf("") }
    val current = RootWizardStep.entries[step]
    fun command(tool: String, params: JSONObject = JSONObject(), done: (() -> Unit)? = null) {
        busy = true; message = "Working…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { PhoneToolExecutor.execute(context, PhoneToolRequest("setup-${UUID.randomUUID()}", tool, params)) }
            busy = false
            message = if (result.ok) "Done" else result.error?.message ?: "Could not complete this step"
            if (result.ok) done?.invoke()
        }
    }
    fun open(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { message = "Android could not open this screen. Try Settings on your phone." }
    }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                withContext(Dispatchers.IO) {
                    Layer2Workspaces.initialize(context)
                    Layer2Workspaces.engine.snapshot() to Layer2Workspaces.engine.holder()
                }
            }.onSuccess { (list, lease) -> inventory = list; holder = lease?.workspaceId ?: "None — input paused" }
                .onFailure { message = it.message.orEmpty() }
            delay(800)
        }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Root features", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close Root features") }
                }
                LinearProgressIndicator(progress = { (step + 1) / 5f }, modifier = Modifier.fillMaxWidth())
                Text("${step + 1} of 5 · ${current.title}", style = MaterialTheme.typography.titleMedium)
                when (current) {
                    RootWizardStep.ROOT -> {
                        Text(root.label, style = MaterialTheme.typography.headlineSmall)
                        Text("Cyclone checks existing root access. Android may ask you to allow the check. This never installs root or changes your boot image.")
                        if (root != RootStatus.ROOTED) Text("You can still prepare profiles and register ordinary apps. Cross-profile control stays locked until the phone can verify the correct profile.")
                    }
                    RootWizardStep.PROFILES -> {
                        Text("Choose one isolation provider. Create the profile there, install your app, and return. Availability and the number of profiles depend on Android and your phone.")
                        OutlinedButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/en/packages/net.typeblog.shelter/"))) }) { Text("Open Shelter") }
                        OutlinedButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.oasisfeng.island"))) }) { Text("Open Island") }
                        OutlinedButton(onClick = { open(Intent(Settings.ACTION_SETTINGS)) }) { Text("OEM Dual Apps / clones") }
                        Text("In your phone's Settings, search for Dual Apps, App Clone or Dual Messenger. Clones need a distinct package or a verifiable Android profile.", style = MaterialTheme.typography.bodySmall)
                        listOf("Profile A · main app", "Profile B · isolated app", "Profile C · optional extra clone").forEachIndexed { index, title ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = profiles and (1 shl index) != 0, onCheckedChange = { profiles = profiles xor (1 shl index) })
                                Text(title)
                            }
                        }
                    }
                    RootWizardStep.REGISTER -> {
                        OutlinedTextField(label, { label = it }, label = { Text("Workspace label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(packageName, { packageName = it }, label = { Text("App package") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(userId, { userId = it }, label = { Text("Android user ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = {
                            picker = !picker
                            scope.launch {
                                apps = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val user = context.getSystemService(UserManager::class.java).userProfiles.firstOrNull { it.identifier == userId.toIntOrNull() }
                                            ?: error("Profile is not accessible")
                                        context.getSystemService(LauncherApps::class.java).getActivityList(null, user)
                                            .map { it.label.toString() to it.componentName.packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
                                    }.getOrDefault(emptyList())
                                }
                            }
                        }) { Text("Choose installed app") }
                        if (picker) {
                            OutlinedTextField(search, { search = it }, label = { Text("Search apps") }, modifier = Modifier.fillMaxWidth())
                            if (apps.isEmpty()) Text("No apps visible for this profile. You can enter its package manually.")
                            apps.filter { it.first.contains(search, true) || it.second.contains(search, true) }.take(12).forEach { (name, pkg) ->
                                TextButton(onClick = { packageName = pkg; if (label.isBlank()) label = name; picker = false }) { Text("$name · $pkg") }
                            }
                        }
                        Text("Registered: ${inventory.size}. Use one entry for each app/profile pair.", style = MaterialTheme.typography.bodySmall)
                    }
                    RootWizardStep.SWITCH -> {
                        Text("Choose a workspace. Cyclone releases input, opens the app, and checks its package and profile before granting the lock. Return to Setup to see the result.")
                        if (inventory.isEmpty()) Text("Register a workspace first.")
                        inventory.forEach { w ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected == w.id, { selected = w.id })
                                Column { Text(w.label); Text("${w.appPackage} · user ${w.androidUserId}", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    RootWizardStep.LOCK -> {
                        Text("Input owner", style = MaterialTheme.typography.labelLarge)
                        Text(holder, style = MaterialTheme.typography.titleMedium)
                        Text("Only one workspace can act at a time. Pausing invalidates its lease; switching back requires a fresh screen check.")
                        OutlinedButton(onClick = { command("workspace.pause") }, enabled = !busy) { Text("Pause / release lock") }
                        OutlinedButton(onClick = { command("workspace.release") }, enabled = !busy) { Text("Stop queue and use main screen") }
                    }
                }
                if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && (current != RootWizardStep.SWITCH || selected.isNotBlank()), modifier = Modifier.fillMaxWidth(), onClick = {
                    when (current) {
                        RootWizardStep.ROOT -> { busy = true; scope.launch { root = withContext(Dispatchers.IO) { RootProbe.check() }; busy = false; message = "Check complete: ${root.label}" } }
                        RootWizardStep.PROFILES -> { step++; message = "" }
                        RootWizardStep.REGISTER -> {
                            val id = "profile-${UUID.randomUUID()}"
                            val user = userId.toIntOrNull()
                            if (user == null || user < 0) message = "Enter a valid Android user ID"
                            else command("workspace.register", JSONObject().put("id", id).put("label", label.trim()).put("appPackage", packageName.trim()).put("androidUserId", user)) { selected = id; message = "Workspace saved. Add another, or continue to Test switch." }
                        }
                        RootWizardStep.SWITCH -> command("workspace.switch", JSONObject().put("id", selected)) { step = RootWizardStep.LOCK.ordinal; message = "Switch verified. Return to the target app before agent input." }
                        RootWizardStep.LOCK -> onClose()
                    }
                }) { Text(current.action) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (step > 0) TextButton(onClick = { step--; message = "" }, enabled = !busy) { Text("Back") }
                    if (step < 4 && current != RootWizardStep.PROFILES) TextButton(onClick = { step++; message = "" }, enabled = !busy) { Text("Continue") }
                }
            }
        }
    }
}
