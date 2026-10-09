package com.cyclone.mobile.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.cyclone.mobile.runtime.workspaces.ProfileCapacity
import com.cyclone.mobile.runtime.workspaces.ProfileDebugReport
import com.cyclone.mobile.runtime.workspaces.ProfileLifecycle
import com.cyclone.mobile.runtime.workspaces.ProfileProblem
import com.cyclone.mobile.runtime.workspaces.ProfileRoom
import com.cyclone.mobile.runtime.workspaces.ProfileSetupFailure
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Plan 57 W6/W9: the profile error screen. Says what went wrong in the profile's own name, what Cyclone checked,
 * Android's own words, the fixes that apply (free a place, clean up, allow more profiles on a rooted phone) and the
 * debug file: Save, Share, Copy summary.
 */
@Composable
internal fun ProfileProblemPanel(issue: ProfileSetupFailure, onRetry: (() -> Unit)?, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var room by remember { mutableStateOf<ProfileCapacity.Room?>(null) }
    var status by remember { mutableStateOf<ProfileRoom.Status?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var showAndroid by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            room = withContext(Dispatchers.IO) { runCatching { ProfileSetupRuntime.room(context) }.getOrNull() }
            status = withContext(Dispatchers.IO) { runCatching { ProfileRoom.status(context) }.getOrNull() }
        }
    }
    LaunchedEffect(issue) { reload() }

    fun run(what: String, block: () -> String) {
        working = what
        scope.launch {
            note = withContext(Dispatchers.IO) { runCatching(block).getOrElse { it.message ?: "That didn't work." } }
            working = null
            reload()
            onChanged()
        }
    }

    val refusal = room?.let { ProfileRoom.refusal(it, status?.manager) } ?: "Checking…"
    val fixes = ProfileProblem.fixes(issue.kind, room, refusal)

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(issue.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(issue.reason, style = MaterialTheme.typography.bodyMedium)
            ProfileProblem.checks(issue.kind, room, status?.manager?.name?.let(ProfileProblem::managerName)).forEach { (ok, line) ->
                Text((if (ok) "✓ " else "✗ ") + line, style = MaterialTheme.typography.bodySmall)
            }
            room?.takeIf { ProfileProblem.isRoomProblem(issue.kind) }?.let { r ->
                Text("Using the places: ${r.line()}", style = MaterialTheme.typography.bodySmall)
            }
            issue.platformMessage?.let { said ->
                TextButton(onClick = { showAndroid = !showAndroid }) { Text(if (showAndroid) "Hide Android's words" else "Show Android's words") }
                if (showAndroid) Text(said, style = MaterialTheme.typography.bodySmall)
            }
            Text(issue.action, style = MaterialTheme.typography.bodyMedium)
            if (ProfileProblem.isRoomProblem(issue.kind) && refusal != "Checking…" && ProfileProblem.Fix.ALLOW_MORE !in fixes) {
                Text(refusal, style = MaterialTheme.typography.bodySmall)
            }
            working?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            fixes.forEach { fix ->
                when (fix) {
                    ProfileProblem.Fix.DELETE_REMOVED -> FilledTonalButton(onClick = { confirm = "delete" }, enabled = working == null,
                        modifier = Modifier.fillMaxWidth()) { Text("Delete profiles in Recently deleted") }
                    ProfileProblem.Fix.CLEAN_UP -> FilledTonalButton(onClick = { confirm = "cleanup" }, enabled = working == null,
                        modifier = Modifier.fillMaxWidth()) { Text("Clean up unfinished profiles") }
                    ProfileProblem.Fix.ALLOW_MORE -> FilledTonalButton(onClick = { confirm = "allow" }, enabled = working == null,
                        modifier = Modifier.fillMaxWidth()) { Text("Allow more profiles") }
                    ProfileProblem.Fix.ANDROID_USERS -> OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent("android.settings.USER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open Android's users settings") }
                    ProfileProblem.Fix.ROOT_MANAGER -> Text("Open your root manager (Magisk, KernelSU or APatch) and allow Cyclone.",
                        style = MaterialTheme.typography.bodySmall)
                    ProfileProblem.Fix.RETRY -> if (onRetry != null) FilledTonalButton(onClick = onRetry, enabled = working == null,
                        modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
            }
            ProfileDebugButtons(issue)
        }
    }

    when (confirm) {
        "delete" -> ProblemConfirm("Delete profiles in Recently deleted?",
            "Each one is backed up first; if a backup can't be made, that profile isn't deleted. This frees their places.", "Delete",
            onConfirm = { confirm = null; run("Backing up and deleting…") { "Deleted ${ProfileLifecycle.emptyTrash(context)} from Recently deleted." } },
            onDismiss = { confirm = null })
        "cleanup" -> ProblemConfirm("Clean up unfinished profiles?",
            "Removes Android users Cyclone started making but never finished. They hold no finished profile.", "Clean up",
            onConfirm = { confirm = null; run("Cleaning up…") { "Cleaned up ${ProfileLifecycle.cleanUpUnfinished(context)}." } },
            onDismiss = { confirm = null })
        "allow" -> room?.let { r ->
            AllowMoreDialog(r.used, status?.manager?.name, onAllow = { limit ->
                confirm = null
                run("Making room for $limit profiles…") {
                    ProfileRoom.allow(context, limit)
                    "This phone now holds up to $limit profiles."
                }
            }, onDismiss = { confirm = null })
        }
    }
}

@Composable
private fun AllowMoreDialog(used: Int, manager: String?, onAllow: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val choices = ProfileRoom.choices(used)
    var pick by remember { mutableStateOf(ProfileRoom.defaultChoice(used) ?: choices.firstOrNull()) }
    val freeGb = remember { runCatching { StatFs(context.filesDir.absolutePath).availableBytes / 1_000_000_000L }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow more profiles?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { n -> FilterChip(selected = pick == n, onClick = { pick = n }, label = { Text("$n") }) }
                }
                pick?.let { Text(ProfileProblem.allowText(it, used, freeGb, manager)) }
            }
        },
        confirmButton = { TextButton(onClick = { pick?.let(onAllow) }, enabled = pick != null) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun ProblemConfirm(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Save debug file / Share / Copy summary. Built on demand; nothing leaves the phone unless the owner sends it. */
@Composable
internal fun ProfileDebugButtons(issue: ProfileSetupFailure?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pending
        pending = null
        if (uri != null && bytes != null) {
            line = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("No file to write")
                "Debug file saved."
            }.getOrElse { "Couldn't save it: ${it.message}" }
        }
    }
    fun build(then: (ProfileDebugReport.Facts, ByteArray) -> Unit) {
        line = "Making the debug file…"
        scope.launch {
            val made = withContext(Dispatchers.IO) {
                runCatching { ProfileDebugReport.collect(context, issue).let { it to ProfileDebugReport.zip(it) } }
            }
            made.onSuccess { (facts, bytes) -> line = null; then(facts, bytes) }
                .onFailure { line = "Couldn't make the debug file: ${it.message}" }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Debug file", style = MaterialTheme.typography.titleSmall)
        Text("Everything Cyclone knows about profiles on this phone and Android's own answers. No keys, passwords, codes or app data.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { build { facts, bytes -> pending = bytes; save.launch(ProfileDebugReport.fileName(facts.atMs)) } }) { Text("Save") }
            OutlinedButton(onClick = { build { facts, bytes -> share(context, ProfileDebugReport.fileName(facts.atMs), bytes) } }) { Text("Share") }
            OutlinedButton(onClick = {
                build { facts, _ ->
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Cyclone profile debug", ProfileDebugReport.summary(facts)))
                    line = "Summary copied."
                }
            }) { Text("Copy summary") }
        }
        line?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun share(context: Context, name: String, bytes: ByteArray) {
    val dir = File(context.cacheDir, "profile-debug").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, name).apply { writeBytes(bytes) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ProfileDebugReport.AUTHORITY_SUFFIX, file)
    val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share the debug file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Plan 57 §5.4: Profiles → Profile room. The current limit, Change, Restore default, and Apply again after a restart. */
@Composable
internal fun ProfileRoomCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ProfileRoom.Status?>(null) }
    var room by remember { mutableStateOf<ProfileCapacity.Room?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    fun reload() {
        scope.launch {
            status = withContext(Dispatchers.IO) { runCatching { ProfileRoom.status(context) }.getOrNull() }
            room = withContext(Dispatchers.IO) { runCatching { ProfileSetupRuntime.room(context) }.getOrNull() }
        }
    }
    LaunchedEffect(Unit) { reload() }
    fun run(block: () -> String) {
        note = "Working…"
        scope.launch {
            note = withContext(Dispatchers.IO) { runCatching(block).getOrElse { it.message ?: "That didn't work." } }
            reload()
        }
    }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Profile room", style = MaterialTheme.typography.titleSmall)
            val s = status
            val r = room
            Text(
                when {
                    r == null -> "Checking…"
                    s?.raisedByCyclone == true && s.moduleLimit != null ->
                        "${r.used} of ${r.limit ?: "?"} places in use · raised by Cyclone (module${if (s.moduleDisabled) ", switched off in ${ProfileProblem.managerName(s.manager?.name ?: "")}" else ""})"
                    else -> "${r.used} of ${r.limit ?: "?"} places in use · Android's own limit"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (s?.needsApplyAgain == true) {
                Text("After the last restart the raised limit wasn't applied.", style = MaterialTheme.typography.bodySmall)
                FilledTonalButton(onClick = { run { ProfileRoom.allow(context, s.moduleLimit!!); "Applied again." } }) { Text("Apply again") }
            }
            if (r != null && ProfileRoom.refusal(r, s?.manager) == null) {
                OutlinedButton(onClick = { dialog = "allow" }) { Text(if (s?.raisedByCyclone == true) "Change" else "Allow more profiles") }
            }
            if (s?.raisedByCyclone == true) {
                OutlinedButton(onClick = { dialog = "restore" }) { Text("Restore default") }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    when (dialog) {
        "allow" -> room?.let { r ->
            AllowMoreDialog(r.used, status?.manager?.name, onAllow = { n -> dialog = null; run { ProfileRoom.allow(context, n); "This phone now holds up to $n profiles." } },
                onDismiss = { dialog = null })
        }
        "restore" -> ProblemConfirm("Restore Android's own limit?",
            "Removes the Cyclone profiles module and puts the user limit back as it was. Profiles you have stay; only new ones are limited again.",
            "Restore", onConfirm = { dialog = null; run { ProfileRoom.restoreDefault(context); "Restored Android's own limit." } },
            onDismiss = { dialog = null })
    }
}
