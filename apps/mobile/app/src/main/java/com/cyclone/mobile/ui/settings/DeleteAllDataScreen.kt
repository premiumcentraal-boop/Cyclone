package com.cyclone.mobile.ui.settings

import android.app.KeyguardManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.safe.SafeDelete
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** No intent extras, TaskCommands or agent route. Native screen-lock success is the only mutation entry. */
class DeleteAllDataScreen : ComponentActivity() {
    private var cancellation: CancellationSignal? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = true
        setContent {
            MaterialTheme {
                val main = android.os.Process.myUid() / 100000 == 0
                val scope = rememberCoroutineScope()
                var typed by remember { mutableStateOf("") }
                var running by remember { mutableStateOf(false) }
                var deleteHiveItems by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("Nothing has been deleted.") }
                var locations by remember { mutableStateOf("Checking Safe locations…") }
                LaunchedEffect(Unit) {
                    locations = if (!main) "Ask on Main." else withContext(Dispatchers.IO) {
                        runCatching {
                            val runtime = com.cyclone.mobile.runtime.safe.SafeRuntime(applicationContext)
                            val pcs = runtime.archiveRecord().optJSONArray("pcs") ?: org.json.JSONArray()
                            val names = ((0 until pcs.length()).map { pcs.getJSONObject(it).getString("name") } +
                                com.cyclone.mobile.gateway.AndroidGatewayTrustRepository(applicationContext).all().map { it.pcLabel }).distinct()
                            val profiles = com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore.records(applicationContext).mapNotNull { it.androidUserId }.filter { it > 0 }.distinct().size
                            val folder = if (com.cyclone.mobile.runtime.safe.SafeRuntime(applicationContext).folder() == null) "no Safe folder selected" else "the selected Safe folder"
                            val root = if (runtime.state().optBoolean("folderOnly")) "root storage is not used by this Safe" else "Root Safe and keyfile"
                            "PC copies: ${names.ifEmpty { listOf("none recorded") }.joinToString()}. $root, $folder, this installation and $profiles Cyclone profiles."
                        }.getOrDefault("Safe locations could not be checked. Deletion cannot proceed until root storage is readable.")
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp).statusBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Delete all Cyclone data", style = MaterialTheme.typography.headlineMedium)
                        Text(locations)
                        Text("Saved accounts on other phones and in Glass remain. This phone leaves the hive. Each step stops on failure; completed steps cannot be undone.")
                        Text(message)
                        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (main) {
                            Row {
                                Checkbox(checked = deleteHiveItems, onCheckedChange = { deleteHiveItems = it }, enabled = !running)
                                Text("Also delete saved accounts from the hive on other phones and in Glass. This does not delete the real accounts.")
                            }
                            OutlinedTextField(value = typed, onValueChange = { typed = it }, label = { Text("Type DELETE") }, enabled = !running)
                            Button(enabled = typed == "DELETE" && !running, onClick = {
                                if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) {
                                    message = "Set up a phone screen lock first. Nothing was deleted."
                                } else {
                                    running = true
                                    typed = ""
                                    cancellation = CancellationSignal()
                                    BiometricPrompt.Builder(this@DeleteAllDataScreen)
                                        .setTitle("Delete all Cyclone data")
                                        .setDescription("Confirm with this phone's screen lock.")
                                        .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                                        .build().authenticate(cancellation!!, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                                if (result.authenticationType != BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL) {
                                                    running = false; message = "Screen-lock confirmation is required. Nothing was deleted."; return
                                                }
                                                val confirmation = SafeDelete.Confirmation.afterScreenLock(System.currentTimeMillis())
                                                scope.launch {
                                                    message = "Waiting for recorded PC copies to confirm deletion…"
                                                    message = withContext(Dispatchers.IO) {
                                                        runCatching {
                                                            val done = SafeDelete.execute(applicationContext, confirmation, deleteHiveItems)
                                                            "${done.message}. Completed: ${done.completed.joinToString()}." + (done.stoppedAt?.let { " Stopped at $it." } ?: "")
                                                        }.getOrDefault("Nothing more was deleted. Check Safe access and retry with a new confirmation.")
                                                    }
                                                    running = false
                                                }
                                            }
                                            override fun onAuthenticationError(code: Int, text: CharSequence) {
                                                running = false; message = "Screen-lock confirmation was cancelled. Nothing was deleted."
                                            }
                                        })
                                }
                            }) { Text("Confirm with screen lock") }
                        }
                    }
                }
            }
        }
    }
    override fun onDestroy() { cancellation?.cancel(); super.onDestroy() }
}
