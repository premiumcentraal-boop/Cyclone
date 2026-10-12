package com.cyclone.mobile.ui.v32

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.runtime.workspaces.CarryReport
import com.cyclone.mobile.runtime.workspaces.CarryRules
import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import com.cyclone.mobile.runtime.workspaces.ProfileBackup
import com.cyclone.mobile.runtime.workspaces.ProfileTrash
import com.cyclone.mobile.ui.v32.ask.AskGlass
import java.text.DateFormat
import java.util.Date

/** The colours a profile can wear (plan 40 P1), the same family as Settings' group tiles. */
internal val ProfileColors: List<Long> = listOf(0xFF3E7BFA, 0xFF7B61FF, 0xFF30B85A, 0xFFFF9F0A, 0xFFFF6B5E, 0xFF32ADE6, 0xFFE056FD, 0xFF8E8E93)

/** One profile in Recently deleted, as a row: its face, how long it stays, Restore, and a bin for Delete now. */
@Composable
internal fun ProfileTrashCard(
    record: CycloneProfileRecord,
    nowMs: Long,
    busy: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    ProfileRow(
        title = record.label,
        subtitle = ProfileTrash.line(record.removedAtMs ?: nowMs, nowMs),
        onClick = null,
        leading = { ProfileAvatar(record.label, record.emoji, record.color, size = 40.dp, dimmed = true) },
        trailing = {
            ProfileButton("Restore", onRestore, enabled = !busy, compact = true)
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(enabled = !busy, role = Role.Button, onClick = onDelete)
                    .semantics { contentDescription = "Delete ${record.label} now" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(21.dp), tint = ProfileGlass.Destructive) }
        },
    )
}

/** An automatic backup, as a row: which profile, when and how big, and a bin. */
@Composable
internal fun ProfileBackupRow(context: Context, backup: ProfileBackup, busy: Boolean, onDelete: () -> Unit) {
    ProfileRow(
        title = backup.label,
        subtitle = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(backup.createdAtMs)) + " · " +
            Formatter.formatShortFileSize(context, backup.bytes),
        onClick = null,
        leading = { ProfileIconTile(Icons.Rounded.Archive, Color(0xFF8E8E93)) },
        trailing = {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(enabled = !busy, role = Role.Button, onClick = onDelete)
                    .semantics { contentDescription = "Delete backup of ${backup.label}" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(21.dp), tint = ProfileGlass.Destructive) }
        },
    )
}

/** A round badge in the profile's colour with its emoji, or its letter. */
@Composable
internal fun ProfileBadge(emoji: String?, color: Long?, label: String, size: Int = 44) =
    ProfileAvatar(label, emoji, color, size = size.dp)

/** Name, emoji and colour in one sheet, with the face changing as you type. */
@Composable
internal fun ProfileLookDialog(
    record: CycloneProfileRecord,
    onSave: (label: String, emoji: String?, color: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(record.label) }
    var emoji by remember { mutableStateOf(record.emoji.orEmpty()) }
    var color by remember { mutableStateOf(record.color ?: ProfileColors.first()) }
    ProfileSheet(onDismiss, "Edit profile") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ProfileAvatar(label.ifBlank { "?" }, emoji.ifBlank { null }, color, size = 88.dp)
        }
        ProfileTextField(label, { label = it.take(40) }, "Name", large = true)
        ProfileTextField(emoji, { emoji = it.take(16) }, "Emoji (optional)")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ProfileColors.forEach { c ->
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                        .then(if (c == color) Modifier.border(2.5.dp, Color.White, CircleShape) else Modifier)
                        .clickable(role = Role.RadioButton) { color = c }
                        .semantics { contentDescription = if (c == color) "Colour, selected" else "Colour" },
                )
            }
        }
        ProfileButton("Save", { onSave(label, emoji.ifBlank { null }, color) }, Modifier.fillMaxWidth(),
            style = ProfileButtonStyle.PRIMARY, enabled = label.isNotBlank())
        ProfileButton("Cancel", onDismiss, Modifier.fillMaxWidth(), style = ProfileButtonStyle.QUIET)
    }
}

/** A plain confirmation with the consequence spelled out. */
@Composable
internal fun ProfileConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) =
    ProfileConfirmSheet(title, body, confirm, onConfirm, onDismiss)

/**
 * Plan 40 P2: Cyclone Carry in one quiet line: what the last switch brought into this profile, or a note when the last
 * carry out of it didn't arrive (the switch itself still happened). Nothing is shown when there is nothing to say.
 */
@Composable
internal fun ProfileCarryNote(report: CarryReport?, sentFailed: Boolean) {
    val line = report?.let { r -> CarryRules.line(r)?.let { "$it · ${DateUtils.getRelativeTimeSpanString(r.atMs)}" } }
    when {
        sentFailed -> ProfileNotice("What you learned here goes with your next switch.", ProfileNoticeKind.INFO)
        line != null -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp), tint = AskGlass.Muted)
            Text(line, color = AskGlass.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The words for each lifecycle step, in one place (and pinned by tests). */
internal object ProfileLifecycleCopy {
    const val REMOVE_TITLE = "Remove this profile?"
    fun removeBody(label: String) =
        "$label stops and moves to Recently deleted. It stays there ${ProfileTrash.TRASH_DAYS} days, untouched: Restore brings it back " +
            "exactly as it was. After that, Cyclone backs it up automatically and deletes it."
    const val DELETE_TITLE = "Delete for good?"
    fun deleteBody(label: String) =
        "Cyclone backs up $label first (its apps' data and Cyclone's own, as much as fits), then Android deletes the profile. " +
            "This can't be undone; the backup is kept ${ProfileTrash.BACKUP_DAYS} days."
    const val EMPTY_TITLE = "Delete all?"
    const val EMPTY_BODY = "Every profile in Recently deleted is backed up, then deleted for good."
    const val TRASH_NOTE = "Removed profiles stay here ${ProfileTrash.TRASH_DAYS} days. Then Cyclone backs each one up and deletes it."
}
