package com.cyclone.mobile.ui.v32

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import com.cyclone.mobile.ui.overlay.PendingTaskAttachment
import com.cyclone.mobile.ui.overlay.TaskAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalTime
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
        text.trim().takeIf(String::isNotBlank)?.let {
            messages += V39ChatMessage(nextId.getAndIncrement(), role, it, ok)
        }
    }
}

internal object V39AiChatContract {
    const val PREFS = "cyclone_ai"
    const val MODEL_KEY = "openrouter_model"
    const val PLACEHOLDER = "Ask Cyclone…"

    fun normalizedRequest(value: String) = value.trim()
    fun modelForStored(stored: String?): OpenRouterModelPreset =
        OpenRouterModelPresets.byId(stored ?: OpenRouterModelPresets.DEFAULT.id)
    fun storageId(model: OpenRouterModelPreset): String = ModelRegistry.profileForPreset(model)?.cycloneId ?: model.id
    fun models(): List<OpenRouterModelPreset> = OpenRouterModelPresets.all

    fun config(modelId: String, accessProfile: CycloneAiAccessProfile): QuickAgentConfig {
        val model = modelForStored(modelId)
        return QuickAgentConfig(
            model = model,
            visionModel = model,
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
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val task by WorkspaceTasks.state.collectAsState()
    val queuedRequests by WorkspaceTasks.requests.state.collectAsState()
    val foregroundActivity by OverlayChromeRuntime.activity.collectAsState()
    val foregroundSnapshot = remember(foregroundActivity) { OverlayChromeRuntime.snapshot() }
    val foregroundWorking = task == null && foregroundActivity in setOf(OverlayChromeState.WORKING, OverlayChromeState.LIVE)
    val attached by PendingTaskAttachment.present.collectAsState()
    val prefs = context.getSharedPreferences(V39AiChatContract.PREFS, Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val session = V39AiChatSessionRuntime
    var chatJob by remember { mutableStateOf<Job?>(null) }
    var composer by rememberSaveable { mutableStateOf("") }
    var toolsOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selectedModelId by rememberSaveable {
        mutableStateOf(V39AiChatContract.storageId(V39AiChatContract.modelForStored(prefs.getString(V39AiChatContract.MODEL_KEY, null))))
    }
    var reasoningEffort by rememberSaveable {
        mutableStateOf(prefs.getString("openrouter_reasoning_effort", "medium") ?: "medium")
    }
    val selectedModel = V39AiChatContract.modelForStored(selectedModelId)
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

    fun persistAiControls(modelId: String, effort: String) {
        selectedModelId = modelId
        reasoningEffort = effort
        prefs.edit()
            .putString(V39AiChatContract.MODEL_KEY, modelId)
            .putString("openrouter_reasoning_effort", effort)
            .apply()
    }

    fun submit(raw: String = composer) {
        message = ""
        val normalized = V39AiChatContract.normalizedRequest(raw)
        if (normalized.isBlank()) return

        val route = RequestIntentRouter.route(normalized, hasAttachment = attached)
        val dispatch = if (route.intent == RequestIntent.CHAT) RequestDispatch.CHAT else
            RequestIntentRouter.dispatch(route, canStartPhoneTask = WorkspaceTasks.canStartRequest())

        when (dispatch) {
            RequestDispatch.START_PHONE_TASK -> runCatching {
                check(OverlayChromeRuntime.isAttached()) { "Phone control needs repair. Open Phone control in Settings." }
                OverlayChromeRuntime.submitRequest(normalized)
            }.onSuccess {
                composer = ""
            }.onFailure { message = it.message ?: "Couldn't open the phone-task setup." }

            RequestDispatch.QUEUE_PHONE_TASK -> runCatching { WorkspaceTasks.queueRequest(normalized) }
                .onSuccess {
                    composer = ""
                    message = "Saved to Up next. Your current task continues."
                }
                .onFailure { message = it.message ?: "Couldn't save this task." }

            RequestDispatch.CHAT -> {
                val request = session.submitGate.tryAccept(normalized, hasKey) ?: run {
                    if (!hasKey) message = "Add an OpenRouter key in Settings to chat."
                    return
                }
                val history = session.messages.map { (if (it.role == V39ChatRole.USER) "user" else "assistant") to it.text }
                val attachment = PendingTaskAttachment.take()
                val model = selectedModel.copy(reasoningEffort = reasoningEffort)
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

    val greeting = remember {
        when (LocalTime.now().hour) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    CycloneAlpineBackdrop {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = if (keyboardOpen) 2.dp else 8.dp, bottom = 4.dp),
            ) {
                if (session.messages.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(top = if (keyboardOpen) 0.dp else 10.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!keyboardOpen) {
                                Text(greeting, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    "Let’s make\nprogress today.",
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(.78f)
                                        .padding(top = 6.dp)
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = .52f)),
                                ) {
                                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                        Text("Ideas become real when you take the next step.", style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "— Cyclone",
                                            modifier = Modifier.padding(top = 5.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(session.messages, key = { it.id }) { V39ChatBubble(it) }
                }

                if (session.busy || session.status.isNotBlank()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth(.72f)
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = .56f)),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                if (session.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    if (session.busy) "Thinking…" else session.status,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            if (task != null || queuedRequests.isNotEmpty() || foregroundWorking) {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = if (keyboardOpen) 132.dp else 230.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    task?.let { current ->
                        item(key = "current-${current.taskId}") { CycloneAskTaskPanel(current) }
                    }
                    if (foregroundWorking) {
                        item(key = "foreground-${foregroundSnapshot.sessionId}") {
                            CycloneForegroundWorkCard(foregroundSnapshot)
                        }
                    }
                    if (queuedRequests.isNotEmpty()) {
                        item(key = "queued") { CyclonePendingRequests() }
                    }
                }
            }

            if (!hasKey) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .90f),
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Key, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("OpenRouter key required for chat", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onSettings) { Text("Settings") }
                    }
                }
            }

            if (message.isNotBlank()) {
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (attached) {
                Text("Attachment ready", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }

            Column(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!keyboardOpen) {
                    CycloneModelPill(
                        modelId = selectedModelId,
                        effort = reasoningEffort,
                        enabled = !session.busy,
                        onChange = ::persistAiControls,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(32.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = .88f))
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                ) {
                    if (session.busy) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Answering",
                                Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            TextButton(onClick = { chatJob?.cancel() }) { Text("Stop reply") }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CycloneIntelligenceControls(
                            enabled = !session.busy,
                            showModelPill = false,
                            onChanged = {
                                selectedModelId = V39AiChatContract.storageId(
                                    V39AiChatContract.modelForStored(prefs.getString(V39AiChatContract.MODEL_KEY, null)),
                                )
                                reasoningEffort = prefs.getString("openrouter_reasoning_effort", "medium") ?: "medium"
                            },
                        )

                        Box {
                            IconButton(onClick = { toolsOpen = true }, enabled = !session.busy, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Rounded.Add, "Add attachment", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = toolsOpen, onDismissRequest = { toolsOpen = false }) {
                                DropdownMenuItem(text = { Text("File") }, onClick = {
                                    toolsOpen = false
                                    context.startActivity(Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java))
                                })
                                DropdownMenuItem(text = { Text("Take photo") }, onClick = {
                                    toolsOpen = false
                                    context.startActivity(
                                        Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java)
                                            .putExtra("camera", true),
                                    )
                                })
                                DropdownMenuItem(text = { Text("Share screen") }, onClick = {
                                    toolsOpen = false
                                    context.startActivity(Intent(context, com.cyclone.mobile.capture.LiveCaptureConsentActivity::class.java))
                                })
                            }
                        }

                        BasicTextField(
                            value = composer,
                            onValueChange = { composer = it },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 46.dp, max = 96.dp)
                                .padding(horizontal = 8.dp, vertical = 12.dp)
                                .semantics { contentDescription = "Ask Cyclone composer" },
                            maxLines = 4,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            decorationBox = { field ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (composer.isEmpty()) {
                                        Text(
                                            V39AiChatContract.PLACEHOLDER,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    field()
                                }
                            },
                        )

                        IconButton(
                            onClick = {
                                runCatching {
                                    dictation.launch(
                                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                                    )
                                }.onFailure { message = "Dictation isn't available. You can type your request." }
                            },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(Icons.Rounded.Mic, "Dictate request", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        val sendEnabled = composer.isNotBlank() && when (previewRoute.intent) {
                            RequestIntent.PHONE_TASK -> true
                            RequestIntent.CHAT -> hasKey && !session.busy
                        }
                        FilledIconButton(
                            onClick = { submit() },
                            enabled = sendEnabled,
                            modifier = Modifier.size(46.dp),
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, "Send request", Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun V39ChatBubble(message: V39ChatMessage) {
    val isUser = message.role == V39ChatRole.USER
    val shape = RoundedCornerShape(if (isUser) 20.dp else 18.dp)
    val color = if (isUser) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f)
        else MaterialTheme.colorScheme.surface.copy(alpha = .46f)
    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isUser) .82f else .88f)
                .clip(shape)
                .background(color),
        ) {
            Column(
                Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isUser) {
                    Text("You", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = contentColor)
                }
                Text(message.text.replace("**", ""), style = MaterialTheme.typography.bodyMedium, color = contentColor)
                if (!isUser && message.ok != null) {
                    Text(
                        if (message.ok) "Checked" else "Stopped",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}