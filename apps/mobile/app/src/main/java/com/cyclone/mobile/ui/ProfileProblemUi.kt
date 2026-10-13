package com.cyclone.mobile.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.cyclone.mobile.runtime.workspaces.ProfileCapacity
import com.cyclone.mobile.runtime.workspaces.ProfileDebugReport
import com.cyclone.mobile.runtime.workspaces.ProfileLifecycle
import com.cyclone.mobile.runtime.workspaces.ProfileProblem
import com.cyclone.mobile.runtime.workspaces.ProfileRoom
import com.cyclone.mobile.runtime.workspaces.ProfileSetupFailure
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import com.cyclone.mobile.ui.v32.ProfileButton
import com.cyclone.mobile.ui.v32.ProfileButtonStyle
import com.cyclone.mobile.ui.v32.ProfileConfirmSheet
import com.cyclone.mobile.ui.v32.ProfileGlass
import com.cyclone.mobile.ui.v32.ProfileGroup
import com.cyclone.mobile.ui.v32.ProfileIconTile
import com.cyclone.mobile.ui.v32.ProfileNotice
import com.cyclone.mobile.ui.v32.ProfileNoticeKind
import com.cyclone.mobile.ui.v32.ProfileRoomMeter
import com.cyclone.mobile.ui.v32.ProfileRow
import com.cyclone.mobile.ui.v32.ProfileRowDivider
import com.cyclone.mobile.ui.v32.ProfileSheet
import com.cyclone.mobile.ui.v32.ask.AskGlass
import com.cyclone.mobile.ui.v32.ask.askGlass
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Plan 57 W6/W9: the profile problem card. What went wrong in the profile's own name and what to do, as words a person
 * reads once; the fixes as buttons (the first is the one to try); what Cyclone checked and Android's own words behind
 * Details; and the debug report. It lives on the Profiles home, in setup, and on the rescue screen.
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
    var details by remember { mutableStateOf(false) }

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
    // Android's own users screen is a way out, never the main suggestion: it goes last.
    val buttons = fixes.filter { it != ProfileProblem.Fix.ROOT_MANAGER && (it != ProfileProblem.Fix.RETRY || onRetry != null) }
        .sortedBy { it == ProfileProblem.Fix.ANDROID_USERS }

    Column(
        Modifier.fillMaxWidth().askGlass(24.dp, shineOffset = 0.35f).clip(ContinuousRoundedRectangle(24.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(ProfileGlass.Destructive), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PriorityHigh, null, Modifier.size(20.dp), tint = Color.White)
            }
            Text(issue.headline, Modifier.weight(1f), color = AskGlass.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(issue.reason, color = AskGlass.Muted, fontSize = 14.sp)
        Text(issue.action, color = AskGlass.Ink, fontSize = 14.sp)
        if (fixes.contains(ProfileProblem.Fix.ROOT_MANAGER)) {
            Text("Open Magisk, KernelSU or APatch and allow Cyclone.", color = AskGlass.Muted, fontSize = 13.sp)
        }
        if (ProfileProblem.isRoomProblem(issue.kind) && refusal != "Checking…" && ProfileProblem.Fix.ALLOW_MORE !in fixes) {
            Text(refusal, color = AskGlass.Muted, fontSize = 13.sp)
        }
        working?.let { ProfileNotice(it, ProfileNoticeKind.WORKING) }
        note?.let { ProfileNotice(it, ProfileNoticeKind.INFO) }

        buttons.forEachIndexed { index, fix ->
            val style = when {
                index == 0 -> ProfileButtonStyle.PRIMARY
                fix == ProfileProblem.Fix.ANDROID_USERS -> ProfileButtonStyle.QUIET
                else -> ProfileButtonStyle.SECONDARY
            }
            val enabled = working == null
            val modifier = Modifier.fillMaxWidth()
            when (fix) {
                ProfileProblem.Fix.DELETE_REMOVED -> ProfileButton("Empty Recently deleted", { confirm = "delete" }, modifier, style, enabled = enabled)
                ProfileProblem.Fix.CLEAN_UP -> ProfileButton("Clean up unfinished", { confirm = "cleanup" }, modifier, style, enabled = enabled)
                ProfileProblem.Fix.ALLOW_MORE -> ProfileButton("Make room", { confirm = "allow" }, modifier, style, enabled = enabled && room != null)
                ProfileProblem.Fix.ANDROID_USERS -> ProfileButton("Open Android's users", { openAndroidUsers(context) }, modifier, style)
                ProfileProblem.Fix.RETRY -> ProfileButton("Try again", { onRetry?.invoke() }, modifier, style, enabled = enabled)
                ProfileProblem.Fix.ROOT_MANAGER -> Unit
            }
        }

        // What Cyclone checked and Android's words: there for whoever wants them, folded away for everyone else.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(ContinuousRoundedRectangle(12.dp))
                .clickable(role = Role.Button) { details = !details }
                .semantics { stateDescription = if (details) "Shown" else "Hidden" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Details", color = AskGlass.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Icon(if (details) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp), tint = AskGlass.Muted)
            Box(Modifier.weight(1f))
            Text("Debug report", Modifier.clip(ContinuousRoundedRectangle(12.dp)).clickable(role = Role.Button) { confirm = "debug" }
                .padding(horizontal = 6.dp, vertical = 8.dp), color = AskGlass.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        AnimatedVisibility(details) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileProblem.checks(issue.kind, room, status?.manager?.name?.let(ProfileProblem::managerName)).forEach { (ok, line) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.Close, null, Modifier.size(16.dp),
                            tint = if (ok) AskGlass.Done else ProfileGlass.Destructive)
                        Text(line, color = AskGlass.Ink, fontSize = 13.sp)
                    }
                }
                room?.takeIf { ProfileProblem.isRoomProblem(issue.kind) }?.let { r ->
                    Text("In use: ${r.line()}", color = AskGlass.Muted, fontSize = 13.sp)
                }
                issue.platformMessage?.let { said ->
                    Text("Android said: $said", color = AskGlass.Muted, fontSize = 12.sp)
                }
            }
        }
    }

    when (confirm) {
        "delete" -> ProfileConfirmSheet("Empty Recently deleted?",
            "Each one is backed up first. If a backup can't be made, that profile stays.", "Empty",
            onConfirm = { confirm = null; run("Backing up and deleting…") { "Deleted ${ProfileLifecycle.emptyTrash(context)} from Recently deleted." } },
            onDismiss = { confirm = null })
        "cleanup" -> ProfileConfirmSheet("Clean up unfinished?",
            "Removes profiles Cyclone started but never finished. Nothing finished is touched.", "Clean up",
            onConfirm = { confirm = null; run("Cleaning up…") { "Cleaned up ${ProfileLifecycle.cleanUpUnfinished(context)}." } },
            onDismiss = { confirm = null })
        "allow" -> room?.let { r ->
            RoomSheet(r, status, startChoosing = true, onDismiss = { confirm = null; reload(); onChanged() })
        }
        "debug" -> ProfileSheet({ confirm = null }, "Debug report") { ProfileDebugButtons(issue) }
    }
}

private fun openAndroidUsers(context: Context) {
    runCatching { context.startActivity(Intent("android.settings.USER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Make room (plan 57 §5): how full the phone is, a big "how many?" choice, the one restart named kindly, and Restore
 * default. Every change here is the owner's tap; the runtime does exactly one property and one module, read back.
 */
@Composable
private fun RoomSheet(room: ProfileCapacity.Room, initial: ProfileRoom.Status?, startChoosing: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(initial) }
    var switcher by remember { mutableStateOf<Boolean?>(null) }
    var choosing by remember { mutableStateOf(startChoosing) }
    var working by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var restoring by remember { mutableStateOf(false) }
    val choices = ProfileRoom.choices(room.used)
    var pick by remember { mutableStateOf(ProfileRoom.defaultChoice(room.used) ?: choices.firstOrNull()) }
    val freeGb = remember { runCatching { StatFs(context.filesDir.absolutePath).availableBytes / 1_000_000_000L }.getOrNull() }
    val refusal = ProfileRoom.refusal(room, status?.manager)
    val unlock = ProfileRoom.needsUnlock(room)
    val manager = status?.manager?.name?.let(ProfileProblem::managerName) ?: "root"

    fun reload() {
        scope.launch {
            status = withContext(Dispatchers.IO) { runCatching { ProfileRoom.status(context) }.getOrNull() } ?: status
            switcher = withContext(Dispatchers.IO) { runCatching { ProfileSetupRuntime.userSwitcherOn() }.getOrNull() }
        }
    }
    LaunchedEffect(Unit) { reload() }
    fun run(what: String, block: () -> String?) {
        working = what
        scope.launch {
            note = withContext(Dispatchers.IO) { runCatching(block).getOrElse { it.message ?: "That didn't work. Nothing was changed." } }
            working = null
            reload()
        }
    }

    if (restoring) {
        ProfileConfirmSheet("Restore Android's limit?",
            "Your profiles stay. Only new ones are limited again.", "Restore",
            onConfirm = {
                restoring = false
                run("Restoring…") {
                    val s = ProfileRoom.restoreDefault(context)
                    if (s.unlockTypeCap) "Restored. Restart your phone to finish." else "Restored Android's own limit."
                }
            },
            onDismiss = { restoring = false })
    }

    ProfileSheet(onDismiss, title = done?.let { if (it.second) "Restart to finish" else "Room made" } ?: if (choosing) "Make room" else "Room") {
        val finished = done
        if (finished != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(if (finished.second) AskGlass.Waiting else AskGlass.Done),
                    contentAlignment = Alignment.Center) {
                    Icon(if (finished.second) Icons.Rounded.RestartAlt else Icons.Rounded.Check, null, Modifier.size(38.dp), tint = Color.White)
                }
            }
            Text(
                if (finished.second) "After a restart, this phone holds up to ${finished.first} profiles."
                else "This phone now holds up to ${finished.first} profiles.",
                Modifier.fillMaxWidth(), color = AskGlass.Muted, fontSize = 15.sp, textAlign = TextAlign.Center,
            )
            ProfileButton("Done", onDismiss, Modifier.fillMaxWidth(), style = ProfileButtonStyle.PRIMARY)
            return@ProfileSheet
        }

        // How full the phone is, as a number and a meter.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "${room.used} of ${room.limit ?: "?"}",
                color = AskGlass.Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { contentDescription = "${room.used} of ${room.limit ?: "unknown"} places in use" },
            )
            ProfileRoomMeter(room.used, room.limit, Modifier.padding(horizontal = 24.dp))
            Text(
                when {
                    status?.restartNeeded == true -> "Restart your phone to finish"
                    status?.raisedByCyclone == true && status?.moduleDisabled == true -> "Raised by Cyclone · switched off in $manager"
                    status?.raisedByCyclone == true -> "Raised by Cyclone"
                    else -> "Android's own limit"
                },
                color = AskGlass.Muted, fontSize = 14.sp,
            )
        }

        working?.let { ProfileNotice(it, ProfileNoticeKind.WORKING) }
        note?.let { ProfileNotice(it, ProfileNoticeKind.INFO) }

        if (choosing && refusal == null && choices.isNotEmpty()) {
            Text("How many profiles?", Modifier.fillMaxWidth(), color = AskGlass.Ink, fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { n ->
                    val on = pick == n
                    Box(
                        Modifier.weight(1f).heightIn(min = 60.dp).clip(ContinuousRoundedRectangle(18.dp))
                            .background(if (on) Color.White else Color.White.copy(alpha = 0.10f))
                            .border(0.7.dp, Color.White.copy(alpha = if (on) 0f else 0.2f), ContinuousRoundedRectangle(18.dp))
                            .clickable(role = Role.RadioButton) { pick = n }
                            .semantics { stateDescription = if (on) "Selected" else "Not selected" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$n", color = if (on) Color(0xFF0B1040) else AskGlass.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, softWrap = false)
                    }
                }
            }
            ProfileGroup {
                ProfileRow("Storage", null, value = freeGb?.let { "$it GB free" } ?: "—",
                    subtitle = if (freeGb != null && freeGb < 3) "Low: new profiles may not fit" else "Each profile uses some",
                    leading = { ProfileIconTile(Icons.Rounded.SdStorage, Color(0xFF8E8E93)) })
                if (unlock) {
                    ProfileRowDivider()
                    ProfileRow("One restart", null, subtitle = "Turns on Android's debug flag until you undo",
                        leading = { ProfileIconTile(Icons.Rounded.RestartAlt, Color(0xFFFF9F0A)) })
                }
                ProfileRowDivider()
                ProfileRow("Undo anytime", null, subtitle = "A small $manager module · Restore default",
                    leading = { ProfileIconTile(Icons.Rounded.Undo, Color(0xFF30B85A)) })
            }
            ProfileButton(pick?.let { "Allow $it profiles" } ?: "Allow", {
                val limit = pick ?: return@ProfileButton
                working = "Making room for $limit profiles…"
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { ProfileRoom.allow(context, limit) } }
                    working = null
                    result.onSuccess { after -> done = limit to after.restartNeeded }
                        .onFailure { note = it.message ?: "That didn't work. Nothing was changed." }
                    reload()
                }
            }, Modifier.fillMaxWidth(), style = ProfileButtonStyle.PRIMARY, enabled = pick != null && working == null)
            ProfileButton("Not now", onDismiss, Modifier.fillMaxWidth(), style = ProfileButtonStyle.QUIET)
            return@ProfileSheet
        }

        if (refusal != null) ProfileNotice(refusal, ProfileNoticeKind.INFO)
        val s = status
        if (s?.needsApplyAgain == true) {
            ProfileButton("Apply again", { run("Applying…") { ProfileRoom.allow(context, s.moduleLimit!!); "Applied again." } },
                Modifier.fillMaxWidth(), style = ProfileButtonStyle.PRIMARY, enabled = working == null)
        }
        if (refusal == null && choices.isNotEmpty()) {
            ProfileButton(if (s?.raisedByCyclone == true) "Change limit" else "Make room", { choosing = true }, Modifier.fillMaxWidth(),
                style = if (s?.needsApplyAgain == true) ProfileButtonStyle.SECONDARY else ProfileButtonStyle.PRIMARY, enabled = working == null)
        }
        // Plan 57 P1: Android's own user switcher is the last way back when Cyclone can't switch.
        ProfileGroup {
            ProfileRow(
                "Android's switcher",
                onClick = null,
                subtitle = "Your way back if Cyclone can't switch",
                leading = { ProfileIconTile(Icons.Rounded.PeopleAlt, Color(0xFF32ADE6)) },
                value = if (switcher == true) "On" else null,
                trailing = {
                    if (switcher == false) {
                        ProfileButton("Turn on", {
                            run("Turning it on…") { ProfileSetupRuntime.enableUserSwitcher(); "Android's user switcher is on." }
                        }, compact = true, enabled = working == null)
                    }
                },
            )
        }
        if (s?.raisedByCyclone == true) {
            ProfileButton("Restore default", { restoring = true }, Modifier.fillMaxWidth(), style = ProfileButtonStyle.DESTRUCTIVE,
                enabled = working == null)
        }
    }
}

/** Save / Share / Copy the debug report. Built on demand; nothing leaves the phone unless the owner sends it. */
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
                "Saved."
            }.getOrElse { "Couldn't save it: ${it.message}" }
        }
    }
    fun build(then: (ProfileDebugReport.Facts, ByteArray) -> Unit) {
        line = "Making the report…"
        scope.launch {
            val made = withContext(Dispatchers.IO) {
                runCatching { ProfileDebugReport.collect(context, issue).let { it to ProfileDebugReport.zip(it) } }
            }
            made.onSuccess { (facts, bytes) -> line = null; then(facts, bytes) }
                .onFailure { line = "Couldn't make the report: ${it.message}" }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("What Cyclone knows about profiles here. No keys, passwords, codes or app data.",
            Modifier.fillMaxWidth(), color = AskGlass.Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileButton("Share", { build { facts, bytes -> share(context, ProfileDebugReport.fileName(facts.atMs), bytes) } },
                Modifier.weight(1f), style = ProfileButtonStyle.PRIMARY)
            ProfileButton("Save", { build { facts, bytes -> pending = bytes; save.launch(ProfileDebugReport.fileName(facts.atMs)) } },
                Modifier.weight(1f))
            ProfileButton("Copy", {
                build { facts, _ ->
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Cyclone profile debug", ProfileDebugReport.summary(facts)))
                    line = "Summary copied."
                }
            }, Modifier.weight(1f))
        }
        line?.let { ProfileNotice(it, if (it.endsWith("…")) ProfileNoticeKind.WORKING else ProfileNoticeKind.INFO) }
    }
}

private fun share(context: Context, name: String, bytes: ByteArray) {
    val dir = File(context.cacheDir, "profile-debug").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, name).apply { writeBytes(bytes) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ProfileDebugReport.AUTHORITY_SUFFIX, file)
    val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share the debug report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Plan 57 §5.4: Profiles → Room. One glass row: how many places are in use, a meter, and a word on its state. A tap
 * opens the Room sheet (Make room, Change, Restore default, Apply again, Android's switcher).
 */
@Composable
internal fun ProfileRoomCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ProfileRoom.Status?>(null) }
    var room by remember { mutableStateOf<ProfileCapacity.Room?>(null) }
    var open by remember { mutableStateOf(false) }
    fun reload() {
        scope.launch {
            status = withContext(Dispatchers.IO) { runCatching { ProfileRoom.status(context) }.getOrNull() }
            room = withContext(Dispatchers.IO) { runCatching { ProfileSetupRuntime.room(context) }.getOrNull() }
        }
    }
    LaunchedEffect(Unit) { reload() }
    val r = room
    val s = status
    val full = r?.limit != null && r.used >= r.limit
    Column(
        modifier.fillMaxWidth().askGlass(22.dp, shineOffset = 0.5f).clip(ContinuousRoundedRectangle(22.dp))
            .clickable(enabled = r != null, role = Role.Button, onClickLabel = "Open Room") { open = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Room", Modifier.weight(1f), color = AskGlass.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    r == null -> "Checking…"
                    r.limit == null -> "${r.used} in use"
                    else -> "${r.used} of ${r.limit}"
                },
                color = if (full) AskGlass.Waiting else AskGlass.Muted, fontSize = 15.sp, maxLines = 1, softWrap = false,
            )
        }
        ProfileRoomMeter(r?.used ?: 0, r?.limit)
        Text(
            when {
                s?.restartNeeded == true -> "Restart your phone to finish making room"
                s?.needsApplyAgain == true -> "The raised limit wasn't applied. Tap to apply again"
                full && r != null && ProfileRoom.refusal(r, s?.manager) == null -> "Full · tap to make room"
                full -> "Full"
                s?.raisedByCyclone == true -> "Raised by Cyclone"
                else -> "Android's own limit"
            },
            color = AskGlass.Muted, fontSize = 13.sp,
        )
    }
    if (open && r != null) RoomSheet(r, s, startChoosing = false, onDismiss = { open = false; reload() })
}
