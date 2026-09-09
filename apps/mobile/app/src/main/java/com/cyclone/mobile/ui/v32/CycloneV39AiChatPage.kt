package com.cyclone.mobile.ui.v32

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.OpenRouterModelPreset
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.QuickAgentConfig
import com.cyclone.mobile.ai.QuickAgentResult
import com.cyclone.mobile.ai.RequestDispatch
import com.cyclone.mobile.ai.RequestIntent
import com.cyclone.mobile.ai.RequestIntentRouter
import com.cyclone.mobile.ai.model.ModelRegistry
import com.cyclone.mobile.runtime.background.WorkspaceActivity
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.overlay.PendingTaskAttachment
import com.cyclone.mobile.ui.overlay.TaskAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class V39ChatRole { USER, CYCLONE }
internal data class V39ChatMessage(val id: Long, val role: V39ChatRole, val text: String, val ok: Boolean? = null)

/** Process-session chat only. Brain owns persistent run diagnostics. */
internal object V39AiChatSessionRuntime {
    private val nextId = AtomicLong(1L)
    val messages = mutableStateListOf<V39ChatMessage>()
    val submitGate = V39AiSubmitGate()
    var pendingRequest by mutableStateOf("")
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    fun append(role: V39ChatRole, text: String, ok: Boolean? = null) {
        text.trim().takeIf(String::isNotBlank)?.let { messages += V39ChatMessage(nextId.getAndIncrement(), role, it, ok) }
    }
}

internal object V39AiChatContract {
    const val PREFS = "cyclone_ai"
    const val MODEL_KEY = "openrouter_model"
    const val PLACEHOLDER = "Ask Cyclone…"
    fun normalizedRequest(value: String) = value.trim()
    fun modelForStored(stored: String?): OpenRouterModelPreset =
        OpenRouterModelPresets.byId(stored.orEmpty().ifBlank { OpenRouterModelPresets.DEFAULT.id })
    fun storageId(model: OpenRouterModelPreset): String = ModelRegistry.profileForPreset(model)?.cycloneId ?: model.id
    fun models(): List<OpenRouterModelPreset> = OpenRouterModelPresets.all
    fun config(modelId: String, accessProfile: CycloneAiAccessProfile): QuickAgentConfig {
        val model = modelForStored(modelId)
        return QuickAgentConfig(
            model = model,
            visionModel = if (ModelRegistry.profileForPreset(model)?.isContributor == true) model else OpenRouterModelPresets.GEMINI_3_8_FLASH,
            safeMode = accessProfile != CycloneAiAccessProfile.FULL,
            accessProfile = accessProfile,
        )
    }
    fun finalStatus(result: QuickAgentResult) = if (result.ok) "Completed and checked" else "Stopped safely"
}

internal class V39AiSubmitGate {
    private val active = AtomicBoolean(false)
    fun tryAccept(rawRequest: String, hasKey: Boolean): String? {
        val request = V39AiChatContract.normalizedRequest(rawRequest)
        if (request.isBlank() || !hasKey || !active.compareAndSet(false, true)) return null
        return request
    }
    fun complete() = active.set(false)
}

@Composable
internal fun V39AiChatPage(context: Context, refreshTick: Int, onSettings: () -> Unit) {
    val keyboardOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val task by WorkspaceTasks.state.collectAsState()
    val queuedRequests by WorkspaceTasks.requests.state.collectAsState()
    val attached by PendingTaskAttachment.present.collectAsState()
    val prefs = context.getSharedPreferences(V39AiChatContract.PREFS, Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val session = V39AiChatSessionRuntime
    var chatJob by remember { mutableStateOf<Job?>(null) }
    var composer by rememberSaveable { mutableStateOf("") }
    var toolsOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selectedModelId by rememberSaveable {
        mutableStateOf(V39AiChatContract.storageId(V39AiChatContract.modelForStored(prefs.getString(V39AiChatContract.MODEL_KEY, null))))
    }
    val selectedModel = V39AiChatContract.modelForStored(selectedModelId)
    val selectedProfile = ModelRegistry.profileForPreset(selectedModel)
    val hasKey = remember(refreshTick) { OpenRouterSecretStore.hasKey(context) }
    val previewRoute = remember(composer, attached) { RequestIntentRouter.route(composer, hasAttachment = attached) }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                composer = listOf(composer, it).filter(String::isNotBlank).joinToString(" ")
            }
        }
    }

    fun restoreAttachmentAfterChatFailure(attachment: TaskAttachment?) {
        if (attachment != null && !PendingTaskAttachment.present.value) PendingTaskAttachment.set(attachment)
    }

    fun submit(raw: String = composer) {
        message = ""
        val normalized = V39AiChatContract.normalizedRequest(raw)
        if (normalized.isBlank()) return

        // Classification is local and happens before any Android readiness/observation/mutation check.
        val route = RequestIntentRouter.route(normalized, hasAttachment = attached)
        val dispatch = if (route.intent == RequestIntent.CHAT) RequestDispatch.CHAT else
            RequestIntentRouter.dispatch(route, canStartPhoneTask = WorkspaceTasks.canStartRequest())

        when (dispatch) {
            RequestDispatch.START_PHONE_TASK -> runCatching {
                context.startActivity(Intent(context, WorkspaceActivity::class.java).putExtra("goal", normalized))
            }.onSuccess {
                composer = ""
                message = "Choose the app Cyclone should work in."
            }.onFailure { message = it.message ?: "Couldn't open the phone-task setup." }

            RequestDispatch.QUEUE_PHONE_TASK -> runCatching { WorkspaceTasks.queueRequest(normalized) }
                .onSuccess { composer = ""; message = "Saved to Up next. Your current task continues." }
                .onFailure { message = it.message ?: "Couldn't save this task." }

            RequestDispatch.CHAT -> {
                val request = session.submitGate.tryAccept(normalized, hasKey) ?: run {
                    if (!hasKey) message = "Add an OpenRouter key in Settings to chat."
                    return
                }
                val history = session.messages.map { (if (it.role == V39ChatRole.USER) "user" else "assistant") to it.text }
                val attachment = PendingTaskAttachment.take()
                val model = selectedModel.copy(reasoningEffort = prefs.getString("openrouter_reasoning_effort", "medium") ?: "medium")
                composer = ""
                session.busy = true
                session.status = "Answering…"
                session.append(V39ChatRole.USER, request)
                chatJob = scope.launch {
                    try {
                        val answer = com.cyclone.mobile.ai.CycloneTextChat.answer(context, model, history, request, attachment)
                        session.append(V39ChatRole.CYCLONE, answer)
                        session.status = ""
                    } catch (cancelled: CancellationException) {
                        restoreAttachmentAfterChatFailure(attachment)
                        session.status = "Reply stopped"
                        throw cancelled
                    } catch (error: Exception) {
                        restoreAttachmentAfterChatFailure(attachment)
                        session.status = "Couldn't get a reply"
                        session.append(V39ChatRole.CYCLONE, error.message ?: "Chat failed. Try again.")
                    } finally {
                        session.submitGate.complete()
                        session.busy = false
                        chatJob = null
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        session.pendingRequest.takeIf(String::isNotBlank)?.let {
            session.pendingRequest = ""
            composer = it
            submit(it)
        }
    }

    CycloneAlpineBackdrop {
        Column(
            Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!keyboardOpen) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CycloneOrbitMark(Modifier.size(32.dp)); Spacer(Modifier.size(10.dp))
                Text("Cyclone", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            Box {
                TextButton(onClick = { modelOpen = true }, enabled = !session.busy,
                    modifier = Modifier.semantics { contentDescription = "AI model selector" }) {
                    Text(selectedModel.label, maxLines = 1, overflow = TextOverflow.Ellipsis); Icon(Icons.Rounded.ArrowDropDown, null)
                }
                DropdownMenu(modelOpen, { modelOpen = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                    Text("Select model", Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
                    V39AiChatContract.models().forEach { model ->
                        DropdownMenuItem(text = { Text(model.label) }, onClick = {
                            selectedModelId = V39AiChatContract.storageId(model)
                            prefs.edit().putString(V39AiChatContract.MODEL_KEY, selectedModelId).apply()
                            modelOpen = false
                        })
                    }
                }
            }
            if (selectedProfile?.isContributor == true) Text(
                "Contributor · prompts and responses may be used for training.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (session.messages.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CycloneOrbitMark(Modifier.size(64.dp))
                        Text("Ask Cyclone", fontSize = 32.sp, lineHeight = 39.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text("Ask a question or tell Cyclone what you want done. It will choose the right path.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                } else items(session.messages, key = { it.id }) { V39ChatBubble(it) }
                if (session.busy || session.status.isNotBlank()) item {
                    Surface(
                        shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f)),
                        tonalElevation = 0.dp, shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth(.9f),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (session.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(if (session.busy) "Answering your message…" else session.status,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (task != null || queuedRequests.isNotEmpty()) Surface(
                Modifier.fillMaxWidth().heightIn(max = if (keyboardOpen) 132.dp else 230.dp),
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
                tonalElevation = 0.dp, shadowElevation = 0.dp,
            ) {
                LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                    task?.let { current -> item(key = "current-${current.taskId}") { CycloneAskTaskPanel(current) } }
                    if (queuedRequests.isNotEmpty()) item(key = "queued") { CyclonePendingRequests() }
                }
            }

            if (!hasKey) Surface(
                shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer, tonalElevation = 0.dp, shadowElevation = 0.dp,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Key, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp))
                    Text("OpenRouter key required for chat", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onSettings) { Text("Settings") }
                }
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (attached) Text("Attachment ready", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)

            Surface(
                Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .95f), contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f)),
                tonalElevation = 0.dp, shadowElevation = 0.dp,
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    if (session.busy) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Current reply · Answering", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { chatJob?.cancel() }) { Text("Stop reply") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CycloneIntelligenceControls(enabled = !session.busy)
                        Box {
                            IconButton(onClick = { toolsOpen = true }, enabled = !session.busy) {
                                Icon(Icons.Rounded.Add, "Add attachment", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(toolsOpen, { toolsOpen = false }) {
                                DropdownMenuItem(text = { Text("File") }, onClick = {
                                    toolsOpen = false; context.startActivity(Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java))
                                })
                                DropdownMenuItem(text = { Text("Take photo") }, onClick = {
                                    toolsOpen = false; context.startActivity(Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java).putExtra("camera", true))
                                })
                                DropdownMenuItem(text = { Text("Share screen") }, onClick = {
                                    toolsOpen = false; context.startActivity(Intent(context, com.cyclone.mobile.capture.LiveCaptureConsentActivity::class.java))
                                })
                            }
                        }
                        BasicTextField(
                            composer, { composer = it }, modifier = Modifier.weight(1f).padding(vertical = 10.dp)
                                .semantics { contentDescription = "Ask Cyclone composer" }, maxLines = 5,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }),
                            decorationBox = { field -> Box { if (composer.isEmpty()) Text(V39AiChatContract.PLACEHOLDER, color = MaterialTheme.colorScheme.onSurfaceVariant); field() } },
                        )
                        IconButton(onClick = {
                            runCatching { dictation.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
                                .onFailure { message = "Dictation isn't available. You can type your request." }
                        }) { Icon(Icons.Rounded.Mic, "Dictate request", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        val sendEnabled = composer.isNotBlank() && when (previewRoute.intent) {
                            RequestIntent.PHONE_TASK -> true
                            RequestIntent.CHAT -> hasKey && !session.busy
                        }
                        FilledIconButton(onClick = { submit() }, enabled = sendEnabled) { Icon(Icons.Rounded.ArrowUpward, "Send request") }
                    }
                }
            }
        }
    }
}

@Composable
private fun V39ChatBubble(message: V39ChatMessage) {
    val isUser = message.role == V39ChatRole.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = .94f),
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            border = if (isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .62f)),
            tonalElevation = 0.dp, shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth(.88f),
        ) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (isUser) "You" else "Cyclone", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                if (!isUser && message.ok != null) Text(if (message.ok) "Checked" else "Stopped",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}
