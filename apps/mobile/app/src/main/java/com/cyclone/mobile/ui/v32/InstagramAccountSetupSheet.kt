package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.market.InstagramSkills
import com.cyclone.mobile.mind.signup.*

/** Personal values are form state only; the password is never saveable, logged, or an ordinary recipe input. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstagramAccountSetupSheet(context: Context, onDismiss: () -> Unit) {
    val map = remember {
        SignupMapStore.load(context, InstagramSkills.PACKAGE)?.takeIf { it.complete && it.finalLabel != null }
            ?: StarterSignupMaps.load(context, InstagramSkills.PACKAGE)
    }
    val values = remember { mutableStateMapOf<String, String>() }
    var password by remember { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    var starting by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { password = ""; values.clear() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Set up an Instagram account", style = MaterialTheme.typography.titleLarge)
            Text("Fill in every required detail before Cyclone starts. Your existing accounts stay signed in.")
            Text("Tested Android phone-number signup. Instagram can change page order; Cyclone checks the live screen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (map == null) {
                Text("The signup guide is unavailable. Account setup cannot start.", color = MaterialTheme.colorScheme.error)
            } else {
                MobileSignupForm.fields(map).forEach { field ->
                    if (field.kind == SignupFieldKind.PASSWORD) {
                        OutlinedTextField(value = password, onValueChange = { password = it.take(4_096); reviewed = false },
                            label = { Text("${field.label} · required") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            supportingText = { Text("Used only for this run. Never shown to the model or saved over an existing account's password.") })
                    } else if (field.choices.isNotEmpty()) {
                        Text(field.label + if (field.required) " · required" else " · optional", style = MaterialTheme.typography.labelLarge)
                        field.choices.forEach { choice ->
                            FilterChip(selected = values[field.key] == choice, onClick = { values[field.key] = choice; reviewed = false }, label = { Text(choice) })
                        }
                    } else {
                        OutlinedTextField(value = values[field.key].orEmpty(), onValueChange = { values[field.key] = it.take(300); reviewed = false },
                            label = { Text(field.label + if (field.required) " · required" else " · optional") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = when (field.kind) { SignupFieldKind.PHONE -> KeyboardType.Phone; SignupFieldKind.EMAIL -> KeyboardType.Email; else -> KeyboardType.Text }),
                            supportingText = { Text(when (field.kind) { SignupFieldKind.BIRTHDAY -> "YYYY-MM-DD"; SignupFieldKind.PHONE -> "Include your country code, for example +31"; else -> field.hint.takeIf { it.isNotBlank() } ?: "Use your own details" }) })
                    }
                }
                HorizontalDivider()
                Text("Verification codes", style = MaterialTheme.typography.labelLarge)
                Text("Cyclone reads SMS codes on this phone when available. Otherwise it asks you during verification; no code is required now.", style = MaterialTheme.typography.bodySmall)
                Text("Instagram terms", style = MaterialTheme.typography.labelLarge)
                Text("You review and accept Instagram's actual terms when they appear. Starting setup does not accept them.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.semantics(mergeDescendants = true) { }
                    .toggleable(value = reviewed, role = Role.Checkbox, onValueChange = { reviewed = it })) {
                    Checkbox(checked = reviewed, onCheckedChange = null)
                    Text("I have checked these account details", Modifier.padding(top = 12.dp))
                }
                errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(enabled = reviewed && !starting, modifier = Modifier.fillMaxWidth(), onClick = {
                    errors = MobileSignupForm.errors(map, values.toMap(), password.length)
                    if (errors.isEmpty()) {
                        when {
                            !com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.isAttached() -> errors = listOf("Turn on Cyclone's accessibility service first.")
                            context.packageManager.getLaunchIntentForPackage(InstagramSkills.PACKAGE) == null -> errors = listOf("Install Instagram first.")
                            else -> {
                                starting = true
                                val chars = password.toCharArray()
                                val id = try { com.cyclone.mobile.mind.mission.MindMissions.startLocalSetup(context, MobileSignupForm.plan(map, values.toMap()), chars) }
                                    catch (_: Exception) { null } finally { chars.fill('\u0000') }
                                if (id != null) { password = ""; values.clear(); onDismiss() }
                                else { starting = false; errors = listOf("Finish or stop the current phone task, then try again.") }
                            }
                        }
                    }
                }) { Text(if (starting) "Starting…" else "Start account setup") }
            }
        }
    }
}
