package com.cyclone.mobile.runtime.safe

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Private owner surface. Recovery words never enter saved state, clipboard, diagnostics or screenshots. */
class SafeActivity : ComponentActivity() {
    private val runtime by lazy { SafeRuntime(applicationContext) }
    private var folderResult by mutableStateOf("")
    private var phrase: CharArray? = null
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) folderResult = try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runtime.setFolder(uri)
            "Safe folder selected."
        } catch (_: Exception) { "The folder could not be saved. Choose a writable local folder." }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("Physical uninstall/reinstall verification is UNVERIFIED.") }
                var words by remember { mutableStateOf("") }
                var showRecovery by remember { mutableStateOf(false) }
                var status by remember { mutableStateOf(runCatching { runtime.state() }.getOrDefault(org.json.JSONObject())) }
                val startup = intent.getBooleanExtra("startup", false)
                val main = android.os.Process.myUid() / 100000 == 0
                var found by remember { mutableStateOf<List<String>>(emptyList()) }
                LaunchedEffect(startup) {
                    if (startup) found = withContext(Dispatchers.IO) { runCatching { runtime.previewRoot() }.getOrDefault(emptyList()) }
                }
                fun action(block: suspend () -> String) {
                    busy = true
                    scope.launch {
                        message = try { block() }
                        catch (_: Exception) { "Safe operation could not finish. Existing copies were kept. Check root access or choose a recovery folder and retry." }
                        finally { busy = false }
                        status = runCatching { runtime.state() }.getOrDefault(status)
                    }
                }
                fun setup(folderOnly: Boolean) = action {
                    phrase = withContext(Dispatchers.IO) { runtime.enable(folderOnly) }
                    showRecovery = true
                    withContext(Dispatchers.IO) { runtime.backup(); SafeSchedule.install(applicationContext) }
                    "First encrypted backup verified. Write down the recovery words privately."
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp).statusBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (startup) "Restore Cyclone Safe" else "Cyclone Safe", style = MaterialTheme.typography.headlineMedium)
                        Text("Encrypted recovery copies protect saved data after uninstall; Android can still uninstall Cyclone.")
                        Text("Android user ${android.os.Process.myUid() / 100000}")
                        Text(if (status.optLong("lastSaved") > 0) "${if (status.optBoolean("rootSaved")) "Root" else "Folder"} copy verified: ${java.util.Date(status.getLong("lastSaved"))}" else "No verified backup recorded in this installation.")
                        Text(message)
                        found.forEach { Text(it) }
                        if (status.optBoolean("folderSaved")) Text("Chosen folder: last copy authenticated after read-back.")
                        if (status.optLong("pcSavedAt") > 0) Text("Paired PC receipt: ${java.util.Date(status.getLong("pcSavedAt"))}.")
                        if (status.optBoolean("rootPending")) Text("Restored locally; root replication still needs attention. Keep the source copy.")
                        if (status.optBoolean("deleting")) Text("Deletion is paused or in progress. Automatic saves are stopped. Finish Delete all Cyclone data in Privacy with a new screen-lock confirmation.")
                        val pending = status.optJSONArray("profilesPending")
                        if (pending != null && pending.length() > 0) Text("Profiles pending (locked, stopped or unavailable): $pending. Existing copies were kept.")
                        if (folderResult.isNotEmpty()) Text(folderResult)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (main && !status.optBoolean("enabled")) {
                            Button(enabled = !busy, onClick = { setup(false) }) { Text("Set up Safe with root") }
                            Button(enabled = !busy && runtime.folder() != null, onClick = { setup(true) }) { Text("Set up Safe in chosen folder without root") }
                            Text("Without root, keep the recovery words and folder. Other Android users remain pending until their own copy can be captured.")
                        }
                        if (showRecovery) {
                            Text("Recovery words — shown only in this session. Do not share them.")
                            Text(phrase?.concatToString().orEmpty(), Modifier.clearAndSetSemantics { })
                            Button(onClick = { phrase?.fill('\u0000'); phrase = null; showRecovery = false }) { Text("I saved my recovery words") }
                        }
                        if (status.optBoolean("enabled") && !status.optBoolean("restorePending") && main) Button(enabled = !busy, onClick = {
                            action { withContext(Dispatchers.IO) { runtime.backup(); SafeSchedule.install(applicationContext) }; "Encrypted backup authenticated after read-back. Paired PC replication runs while connected; its receipt is shown separately." }
                        }) { Text("Save Safe now") }
                        Button(enabled = !busy, onClick = { pickFolder.launch(android.provider.DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:Documents/Cyclone Safe")) }) { Text("Choose Safe folder") }
                        if (startup) {
                            Button(enabled = !busy, onClick = { action {
                                com.cyclone.mobile.gateway.GatewayRuntime.startPairingBootstrap(applicationContext)
                                "Use the existing Allow this PC pairing flow. In Glass, unlock the vault and choose Restore Safe for this returning phone. Then choose Restore from paired PC below."
                            } }) { Text("Connect paired PC for restore") }
                            Button(enabled = !busy, onClick = { action {
                                withContext(Dispatchers.IO) { runtime.restorePc() }; "Restore complete. Continue to reopen Cyclone."
                            } }) { Text("Restore from paired PC") }
                            Button(enabled = !busy, onClick = { action {
                                withContext(Dispatchers.IO) { runtime.restoreRoot() }; "Restore complete. Continue to reopen Cyclone; pair this installation with your PC again."
                            } }) { Text("Restore from root copy") }
                            OutlinedTextField(value = words, onValueChange = { words = it }, label = { Text("Recovery words") }, visualTransformation = PasswordVisualTransformation())
                            Button(enabled = !busy && words.isNotBlank(), onClick = {
                                val input = words.trim().split(Regex("\\s+")).joinToString(" ").toCharArray(); words = ""
                                action { try { withContext(Dispatchers.IO) { runtime.restorePcRecovery(input) }; "Restore complete. Continue to reopen Cyclone." } finally { input.fill('\u0000') } }
                            }) { Text("Open paired PC copy with recovery words") }
                            Button(enabled = !busy && words.isNotBlank(), onClick = {
                                val input = words.trim().split(Regex("\\s+")).joinToString(" ").toCharArray(); words = ""
                                action { try { withContext(Dispatchers.IO) { runtime.restoreFolder(input) }; "Restore complete. Continue to reopen Cyclone." } finally { input.fill('\u0000') } }
                            }) { Text("Restore from chosen folder") }
                            Button(enabled = !busy, onClick = { action {
                                withContext(Dispatchers.IO) { runtime.continueWithoutRestore() }
                                startActivity(Intent(this@SafeActivity, com.cyclone.mobile.MainActivity::class.java))
                                finish()
                                "Opening Cyclone."
                            } }) { Text(if (status.optBoolean("enabled")) "Continue" else "Continue without restoring") }
                        }
                    }
                }
            }
        }
    }
    override fun onDestroy() { phrase?.fill('\u0000'); phrase = null; super.onDestroy() }
}
