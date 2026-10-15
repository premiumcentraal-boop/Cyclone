package com.cyclone.mobile.ui.v32

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.PersistableBundle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cyclone.mobile.identity.IdentityBook
import com.cyclone.mobile.identity.IdentitySaver
import com.cyclone.mobile.identity.IdentityVault
import com.cyclone.mobile.identity.SavedIdentity
import com.cyclone.mobile.runtime.workspaces.ProfileBootstrapRuntime
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plan 59 (build 1): Settings → Accounts. Every account this phone keeps, with a badge for the Cyclone profile it
 * belongs to, and the 30-day trash.
 *
 * - **Open** switches to the account's profile (the owner's own staged switch, with its way back) and opens the app.
 * - **Details** (names, birthday, email, phone, password) show only after the phone's own lock (fingerprint, face or
 *   PIN), for 30 seconds.
 * - **Delete** moves it to the trash for 30 days. It never touches the real account in the app.
 * - Accounts are kept in Main; another profile's Settings says so and offers the way back.
 */
@Composable
fun AccountsSettingsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inMain = remember { runCatching { IdentityVault.isMain(context) }.getOrDefault(false) }
    var tick by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(CycloneSpacing.Content)) {
        CycloneSectionTitle("Accounts")
        if (!inMain) {
            CycloneSimpleCard(Modifier.fillMaxWidth()) {
                Text("Your accounts are kept in the Main profile", style = MaterialTheme.typography.titleSmall)
                Text("That way a deleted profile never takes its accounts with it. Open Settings → Accounts in Main to see them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        val profiles = remember(tick) {
            runCatching { ProfileRegistryStore.records(context) }.getOrDefault(emptyList())
        }
        val book = remember(tick) {
            runCatching { IdentityVault.book(context, profiles.map { it.id }.toSet()) }.getOrNull()
        }
        if (book == null) {
            CycloneSimpleCard(Modifier.fillMaxWidth()) {
                Text("The saved accounts couldn't be opened on this phone.", style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }
        val labels = profiles.filter { !it.inTrash }.associate { it.id to it.label }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CycloneSpacing.Small)) {
            FilterChip(filter == "all", { filter = "all" }, { Text("All") })
            FilterChip(filter == IdentityBook.MAIN, { filter = IdentityBook.MAIN }, { Text("Main") })
            labels.forEach { (id, label) -> FilterChip(filter == id, { filter = id }, { Text(label) }) }
            FilterChip(filter == "unbound", { filter = "unbound" }, { Text("Not bound") })
            if (book.active().any { it.bound && !it.boundHere(book.me) }) {
                FilterChip(filter == "elsewhere", { filter = "elsewhere" }, { Text("Other phones") })
            }
            FilterChip(filter == "trash", { filter = "trash" }, { Text("Trash") })
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search accounts") })
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        val shown = AccountsPresenter.visible(book, filter, query)
        if (shown.isEmpty()) {
            CycloneSimpleCard(Modifier.fillMaxWidth()) {
                Text(when (filter) {
                    "trash" -> "The trash is empty. Deleted accounts stay here for ${IdentityBook.TRASH_DAYS} days."
                    "unbound" -> "Every account is bound to a profile."
                    else -> "No accounts yet. Accounts Cyclone creates are saved here at once, bound to the profile they were made in."
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        shown.forEach { identity ->
            AccountCard(
                identity = identity,
                appLabel = AccountsPresenter.appLabel(context, identity.app),
                badge = AccountsPresenter.badge(identity, labels, book.me),
                here = !identity.bound || identity.boundHere(book.me),
                usedLine = AccountsPresenter.usedLine(book.uses(identity.id), labels, System.currentTimeMillis()),
                revealed = revealed == identity.id,
                profiles = listOf(IdentityBook.MAIN to "Main") + labels.toList(),
                busy = busy,
                onOpen = {
                    busy = true
                    scope.launch {
                        notice = withContext(Dispatchers.IO) { AccountsPresenter.open(context, identity, profiles) { step -> notice = step } }
                        busy = false
                    }
                },
                onDetails = {
                    if (revealed == identity.id) revealed = null
                    else (context as? Activity)?.let { activity ->
                        confirmOwner(activity, { notice = it }) {
                            revealed = identity.id
                            scope.launch { delay(30_000); if (revealed == identity.id) revealed = null }
                        }
                    } ?: run { notice = "Open Settings from Cyclone's own screen to see details." }
                },
                onCopy = {
                    copySensitive(context, identity.password)
                    notice = "Password copied. The clipboard is cleared in 30 seconds."
                    scope.launch { delay(30_000); copySensitive(context, "") }
                },
                onBind = { profileId, label ->
                    runCatching { IdentityVault.change(context) { b, now -> b.bind(identity.id, profileId, label, now) } }
                    notice = "Bound to $label."
                    tick++
                },
                onTrash = {
                    runCatching { IdentityVault.change(context) { b, now -> b.moveToTrash(identity.id, now) } }
                    notice = "Moved to the trash for ${IdentityBook.TRASH_DAYS} days. The real account is untouched."
                    if (revealed == identity.id) revealed = null
                    tick++
                },
                onRestore = {
                    runCatching { IdentityVault.change(context) { b, now -> b.restore(identity.id, now) } }
                    notice = "Restored."
                    tick++
                },
                onPurge = {
                    runCatching { IdentityVault.change(context) { b, now -> b.purge(identity.id, now) } }
                    notice = "Deleted for good, on every phone in the hive. The real account is untouched."
                    tick++
                },
            )
        }
    }
    LaunchedEffect(Unit) { tick++ }
}

@Composable
private fun AccountCard(
    identity: SavedIdentity,
    appLabel: String,
    badge: String,
    /** Bound to a profile on this phone (or not bound): Open works here. */
    here: Boolean,
    /** Plan 59 run 2c: "Used by Food tasks · 2 days ago" or "Not used on this phone yet". */
    usedLine: String,
    revealed: Boolean,
    profiles: List<Pair<String, String>>,
    busy: Boolean,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onCopy: () -> Unit,
    onBind: (String, String) -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    var binding by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf(false) }
    CycloneSimpleCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("$appLabel · ${identity.handle.ifBlank { "account" }}", style = MaterialTheme.typography.titleSmall)
                AccountsPresenter.maskedEmail(identity.card.email)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!identity.trashed) Text(usedLine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (identity.trashed) {
                    Text(AccountsPresenter.purgeLine(identity, System.currentTimeMillis()), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            CycloneStatusPill(badge, positive = identity.bound && here)
        }
        if (revealed) {
            Column(verticalArrangement = Arrangement.spacedBy(CycloneSpacing.Small)) {
                identity.card.rows().forEach { (label, value) ->
                    Text("$label: $value", style = MaterialTheme.typography.bodySmall)
                }
                if (identity.password.isNotEmpty()) Text("Password: ${identity.password}", style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.End) {
            if (identity.trashed) {
                TextButton(onClick = onRestore) { Text("Restore") }
                TextButton(onClick = { if (confirmPurge) onPurge() else confirmPurge = true }) {
                    Text(if (confirmPurge) "Delete for good?" else "Delete forever")
                }
            } else {
                TextButton(onClick = onOpen, enabled = !busy && identity.bound && here) { Text("Open") }
                TextButton(onClick = onDetails) { Text(if (revealed) "Hide" else "Details") }
                if (revealed && identity.password.isNotEmpty()) TextButton(onClick = onCopy) { Text("Copy password") }
                Column {
                    TextButton(onClick = { binding = true }) { Text(if (identity.bound) "Move to profile" else "Bind to profile") }
                    DropdownMenu(expanded = binding, onDismissRequest = { binding = false }) {
                        profiles.filter { !here || it.first != identity.profileId }.forEach { (id, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { binding = false; onBind(id, label) })
                        }
                    }
                }
                TextButton(onClick = onTrash) { Text("Delete") }
            }
        }
    }
}

/** Plan 59: pure parts of the Accounts page, JVM-tested. */
internal object AccountsPresenter {
    fun visible(book: IdentityBook, filter: String, query: String): List<SavedIdentity> {
        val base = when (filter) {
            "trash" -> book.trash()
            "unbound" -> book.active().filter { !it.bound }
            "elsewhere" -> book.active().filter { it.bound && !it.boundHere(book.me) }
            "all" -> book.active()
            else -> book.active().filter { it.profileId == filter && it.boundHere(book.me) }
        }
        val q = query.trim().lowercase()
        return if (q.isEmpty()) base else base.filter { i ->
            listOf(i.app, i.handle, i.card.email, i.card.username, i.card.fullName, i.profileLabel.orEmpty()).any { it.lowercase().contains(q) }
        }
    }

    /**
     * "Food", "Main", "Food on Galaxy S24" (bound on another phone in the hive) or "Not bound (was Food)". A profile
     * on this phone renamed since binding shows its current name.
     */
    fun badge(identity: SavedIdentity, labels: Map<String, String>, me: String = ""): String = when {
        identity.bound && !identity.boundHere(me) ->
            "${if (identity.profileId == IdentityBook.MAIN) "Main" else identity.profileLabel ?: "A profile"} on ${identity.boundDeviceName ?: "another phone"}"
        identity.profileId == IdentityBook.MAIN -> "Main"
        identity.profileId != null -> labels[identity.profileId] ?: identity.profileLabel ?: "A profile"
        identity.wasProfileLabel != null -> "Not bound (was ${identity.wasProfileLabel})"
        else -> "Not bound"
    }

    /**
     * Plan 59 run 2c: where a task on this phone really used the account. "Used by Food tasks · 2 days ago", "Used by
     * Food and Main tasks · today", or "Not used on this phone yet".
     */
    fun usedLine(uses: List<com.cyclone.mobile.identity.AccountUseRecord>, labels: Map<String, String>, now: Long): String {
        if (uses.isEmpty()) return "Not used on this phone yet"
        val names = uses.map { if (it.profileId == IdentityBook.MAIN) "Main" else labels[it.profileId] ?: it.label }.distinct()
        val who = if (names.size <= 2) names.joinToString(" and ") else "${names.take(2).joinToString(", ")} and ${names.size - 2} more"
        val days = ((now - uses.maxOf { it.lastMs }).coerceAtLeast(0) / 86_400_000)
        val ago = when (days) { 0L -> "today"; 1L -> "yesterday"; else -> "$days days ago" }
        return "Used by $who tasks · $ago"
    }

    /** "b•••@gmail.com" in the list; the full address opens with the details. */
    fun maskedEmail(email: String): String? {
        val at = email.indexOf('@')
        if (at < 1) return null
        return email.first() + "•••" + email.substring(at)
    }

    fun purgeLine(identity: SavedIdentity, now: Long): String {
        val days = ((identity.purgeAtMs() ?: now) - now).coerceAtLeast(0).let { (it + 86_399_999) / 86_400_000 }
        return "Deleted for good in $days day${if (days == 1L) "" else "s"}"
    }

    fun appLabel(context: Context, app: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(app, 0)).toString()
    }.getOrDefault(app.substringAfterLast('.').replaceFirstChar { it.uppercase() })

    /** Switches to the account's profile when it isn't this one, then opens the app there. Returns a line for the page. */
    fun open(context: Context, identity: SavedIdentity, profiles: List<com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord>,
             onStep: (String) -> Unit): String = runCatching {
        val target = identity.profileId ?: return "Bind it to a profile first."
        if (!identity.boundHere(IdentityVault.book(context).me)) return "It's bound to a profile on ${identity.boundDeviceName ?: "another phone"}."
        val here = IdentitySaver.currentProfile(context).first
        if (target == here) {
            val launch = context.packageManager.getLaunchIntentForPackage(identity.app) ?: return "That app isn't installed in this profile."
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Opened."
        }
        val record = profiles.firstOrNull { it.id == target }
        if (target != IdentityBook.MAIN && (record == null || record.inTrash)) return "That profile isn't on this phone any more."
        ProfileSetupRuntime.openProfile(context, if (target == IdentityBook.MAIN) null else target, onStep)
        val user = if (target == IdentityBook.MAIN) ProfileSetupRuntime.profileAUserId() else record!!.androidUserId ?: return "Switched."
        ProfileBootstrapRuntime.launchAppIn(user, identity.app)
        "Opened in ${record?.label ?: "Main"}."
    }.getOrElse { it.message ?: "Couldn't open it." }
}

/** The phone's own lock (fingerprint, face or PIN) before an account's details show. */
private fun confirmOwner(activity: Activity, onProblem: (String) -> Unit, onOk: () -> Unit) {
    val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (activity.getSystemService(BiometricManager::class.java)?.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
        onProblem("Set a screen lock (PIN, fingerprint or face) to see account details.")
        return
    }
    BiometricPrompt.Builder(activity)
        .setTitle("Show account details")
        .setSubtitle("Confirm it's you")
        .setAllowedAuthenticators(allowed)
        .build()
        .authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                    onProblem(errString?.toString() ?: "The phone couldn't confirm it's you.")
                }
            }
        })
}

private fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText("Password", text).apply {
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}
