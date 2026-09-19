package com.cyclone.mobile.ui.v32

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import com.cyclone.mobile.ui.overlay.PendingTaskAttachment
import com.cyclone.mobile.ui.overlay.TaskAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class V39ChatRole { USER, CYCLONE }
internal data class V39ChatMessage(val id: Long, val role: V39ChatRole, val text: String, val ok: Boolean? = null)

/** Process-session chat only. Brain owns persistent run diagnostics. The Ask page is the live Gemini-style canvas. Plus stays a short sheet. */
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
        OpenRouterModelPresets.byId(stored.orEmpty())
    fun storageId(model: OpenRouterModelPreset): String = ModelRegistry.profileForPreset(model)?.cycloneId ?: model.id

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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val task by WorkspaceTasks.state.collectAsState()
    val queuedRequests by WorkspaceTasks.requests.state.collectAsState()
    val foregroundActivity by OverlayChromeRuntime.activity.collectAsState()
    val foregroundSnapshot = remember(foregroundActivity) { OverlayChromeRuntime.snapshot() }
    val foregroundWorking = task == null && foregroundActivity in setOf(OverlayChromeState.WORKING, OverlayChromeState.LIVE)
    val attached by PendingTaskAttachment.present.collectAsState()
    val backdrop = LocalCycloneLiquidBackdrop.current
    val prefs = context.getSharedPreferences(V39AiChatContract.PREFS, Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val session = V39AiChatSessionRuntime
    var chatJob by remember { mutableStateOf<Job?>(null) }
    var composer by rememberSaveable { mutableStateOf("") }
    var toolsOpen by remember { mutableStateOf(false) }
    var voiceOpen by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var drawerCollapsed by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val catalogRevision by com.cyclone.mobile.ai.OpenRouterCatalogStore.revision.collectAsState()
    var selectedModelId by rememberSaveable(catalogRevision, refreshTick) {
        mutableStateOf(com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context))
    }
    var reasoningEffort by rememberSaveable {
        mutableStateOf(prefs.getString("openrouter_reasoning_effort", "medium") ?: "medium")
    }
    val hasKey = remember(refreshTick) { OpenRouterSecretStore.hasKey(context) }
    val previewRoute = remember(composer, attached) { RequestIntentRouter.route(composer, hasAttachment = attached) }
    val emptyCanvas = session.messages.isEmpty() && !session.busy && task == null && !foregroundWorking

    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        voiceOpen = false
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

        if (com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context).isBlank()) {
            message = "Choose models in Settings → Model & API first."
            return
        }
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
                val model = OpenRouterModelPresets.byId(com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context)).copy(reasoningEffort = reasoningEffort)
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

    fun startVoice() {
        toolsOpen = false
        modelMenuOpen = false
        voiceOpen = true
        runCatching {
            dictation.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
            )
        }.onFailure {
            voiceOpen = false
            message = "Dictation isn't available. You can type your request."
        }
    }

    fun openCamera() {
        toolsOpen = false
        context.startActivity(
            Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java)
                .putExtra("camera", true),
        )
    }

    fun openFiles() {
        toolsOpen = false
        context.startActivity(Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java))
    }

    fun shareScreen() {
        toolsOpen = false
        com.cyclone.mobile.capture.LiveScreenShare.start(context)
    }

    LaunchedEffect(keyboardOpen, toolsOpen, modelMenuOpen) {
        if (keyboardOpen) {
            modelMenuOpen = false
            toolsOpen = false
            drawerCollapsed = false
        } else if (toolsOpen || modelMenuOpen) {
            drawerCollapsed = false
        }
    }

    LaunchedEffect(Unit) {
        session.pendingRequest.takeIf(String::isNotBlank)?.let {
            session.pendingRequest = ""
            composer = it
            submit(it)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(askCycloneCanvasBrush()),
    ) {
        AskCycloneDotField(Modifier.matchParentSize())

        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AskCycloneHeader(
                onMenu = onSettings,
                onProfile = onSettings,
                model = {
                    CycloneModelPill(
                        modelId = selectedModelId,
                        effort = reasoningEffort,
                        enabled = !session.busy,
                        compactHeader = true,
                        expandInLayout = false,
                        expanded = modelMenuOpen,
                        onExpandedChange = {
                            modelMenuOpen = it
                            if (it) toolsOpen = false
                        },
                        onChange = ::persistAiControls,
                    )
                },
            )

            if (emptyCanvas && composer.isBlank()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(bottom = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AskCycloneEmptyState()
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = if (keyboardOpen) 2.dp else 4.dp, bottom = 8.dp),
                ) {
                    if (session.messages.isNotEmpty()) {
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
            }

            if (drawerCollapsed) {
                CycloneCollapsedAskPill(
                    onExpand = { drawerCollapsed = false },
                    active = task?.working == true || foregroundWorking || session.busy,
                    status = when {
                        task?.working == true -> task?.subtitle?.takeIf(String::isNotBlank) ?: "Current run"
                        foregroundWorking -> foregroundSnapshot.statusMessage ?: "Current run"
                        session.busy -> "Answering…"
                        queuedRequests.isNotEmpty() -> "Tasks waiting"
                        else -> null
                    },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            } else {
                CycloneChatDrawerSurface(
                    onCollapse = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        toolsOpen = false
                        modelMenuOpen = false
                        drawerCollapsed = true
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
                ) {
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

            CycloneLiquidPanel(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                cornerRadius = 30.dp,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
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

                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CycloneTrayIconAction(
                            onClick = {
                                toolsOpen = !toolsOpen
                                if (toolsOpen) modelMenuOpen = false
                            },
                            enabled = !session.busy,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Add,
                                "Add attachment",
                                Modifier.size(22.dp),
                                tint = if (toolsOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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

                        val sendEnabled = composer.isNotBlank() && when (previewRoute.intent) {
                            RequestIntent.PHONE_TASK -> true
                            RequestIntent.CHAT -> hasKey && !session.busy
                        }
                        if (composer.isBlank() && !session.busy) {
                            Surface(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clickable(role = Role.Button, onClick = { startVoice() }),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shadowElevation = 0.dp,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.GraphicEq, "Voice mode", Modifier.size(22.dp))
                                }
                            }
                        } else if (backdrop != null) {
                            CycloneKyantLiquidIconButton(
                                onClick = { submit() },
                                backdrop = backdrop,
                                enabled = sendEnabled,
                                modifier = Modifier.size(46.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowUpward,
                                    "Send request",
                                    Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        } else {
                            CycloneTrayIconAction(
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
            }

        AnimatedVisibility(
            visible = toolsOpen,
            modifier = Modifier.matchParentSize().zIndex(3f),
            enter = fadeIn() + slideInVertically { it / 5 },
            exit = fadeOut() + slideOutVertically { it / 5 },
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = .28f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { toolsOpen = false },
                        ),
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .98f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                    ) {
                        CycloneSheetDismissHandle(onDismiss = { toolsOpen = false })
                        CycloneAttachmentTools(
                            onCamera = { openCamera() },
                            onFiles = { openFiles() },
                            onShareScreen = { shareScreen() },
                            filesLabel = "Files",
                            extras = listOf(
                                Icons.Rounded.Bolt to "Create a routine",
                            ),
                            onExtra = { label ->
                                toolsOpen = false
                                if (label == "Create a routine") composer = "Create a routine"
                            },
                        )
                        CycloneModelIntelligencePanel(
                            modelId = selectedModelId,
                            effort = reasoningEffort,
                            showModelSelector = false,
                            onChange = ::persistAiControls,
                        )
                    }
                }
            }
        }

        if (modelMenuOpen) {
            if (!keyboardOpen) {
            Box(Modifier.matchParentSize().zIndex(4f)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = .18f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { modelMenuOpen = false },
                        ),
                )
                CycloneLiquidPanel(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 56.dp, start = 24.dp, end = 24.dp)
                        .fillMaxWidth(),
                    cornerRadius = 22.dp,
                    contentPadding = PaddingValues(6.dp),
                ) {
                    CycloneModelPickerList(
                        modelId = selectedModelId,
                        onChange = ::persistAiControls,
                        onDismiss = { modelMenuOpen = false },
                    )
                }
            }
            }
        }

        if (voiceOpen) {
            AskCycloneVoiceMode(onClose = { voiceOpen = false })
        }
    }
}

@Composable
private fun AskCycloneHeader(
    onMenu: () -> Unit,
    onProfile: () -> Unit,
    model: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = "Ask Cyclone" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onMenu)
                .semantics { contentDescription = "Settings" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Menu, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            model()
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onProfile)
                .semantics { contentDescription = "Profile" },
            contentAlignment = Alignment.Center,
        ) {
            CycloneOrbitMark(Modifier.size(22.dp))
        }
    }
}

@Composable
private fun AskCycloneEmptyState() {
    val greeting = when (java.time.LocalTime.now().hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Ready when you are. Tell Cyclone what to do on your phone." },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AskCycloneOrb()
        Text(
            greeting,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "What can I do for you?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun askCycloneCanvasBrush(): Brush {
    val base = if (isSystemInDarkTheme()) Color.Black else Color.White
    return Brush.verticalGradient(listOf(base, base))
}

/**
 * One animated dot field owns the entire Ask Cyclone canvas. The dots stay fixed in position and only
 * breathe in size/opacity, which keeps the motion calm while the smooth spatial envelope gives the
 * lower page a soft bowl of blue/cyan/lilac without drawing a visible U-shaped edge. Because this is
 * the first child of the full-screen page Box, it continues behind the floating composer.
 */
@Composable
private fun AskCycloneDotField(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val motion = rememberInfiniteTransition(label = "askDotField")
    val phase by motion.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 20_000,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "askDotPhase",
    )

    val lilac = if (dark) Color(0xFF9B8CFF) else Color(0xFF7D83FF)
    val blue = if (dark) Color(0xFF69A7FF) else Color(0xFF3D8DFF)
    val cyan = if (dark) Color(0xFF5DD8FF) else Color(0xFF43C6F6)

    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val spacing = 17.dp.toPx()
        val tinyRadius = 0.45.dp.toPx()
        val radiusRange = 2.65.dp.toPx()
        val columns = (size.width / spacing).toInt() + 2
        val rows = (size.height / spacing).toInt() + 2
        val startX = (size.width - (columns - 1) * spacing) / 2f

        fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
            val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        fun blend(a: Color, b: Color, amount: Float): Color {
            val t = amount.coerceIn(0f, 1f)
            return Color(
                red = a.red + (b.red - a.red) * t,
                green = a.green + (b.green - a.green) * t,
                blue = a.blue + (b.blue - a.blue) * t,
                alpha = 1f,
            )
        }

        for (row in 0 until rows) {
            val y = row * spacing
            val ny = (y / size.height).coerceIn(0f, 1f)
            for (column in 0 until columns) {
                val x = startX + column * spacing
                val nx = (x / size.width).coerceIn(0f, 1f)
                val edge = (kotlin.math.abs(nx - 0.5f) * 2f).coerceIn(0f, 1f)
                val edge2 = edge * edge

                // The sides begin slightly higher than the middle, but the smooth fade prevents a hard U.
                val rise = 0.44f - 0.12f * edge2
                val vertical = smoothStep(rise, 1f, ny)
                val bottom = smoothStep(0.66f, 1f, ny)
                val bowl = 0.18f + 0.82f * edge2

                val broadDrift = 0.92f + 0.08f * kotlin.math.sin(
                    (phase * 0.55f + nx * 4.6f - ny * 3.3f).toDouble(),
                ).toFloat()
                val envelope = ((vertical * bowl) + (bottom * bottom * 0.16f))
                    .times(broadDrift)
                    .coerceIn(0f, 1f)
                if (envelope < 0.012f) continue

                val localWave = 0.5f + 0.5f * kotlin.math.sin(
                    (phase + column * 0.43f + row * 0.31f).toDouble(),
                ).toFloat()
                val pulseScale = 0.78f + localWave * 0.42f
                val radius = (tinyRadius + radiusRange * envelope) * pulseScale
                val opacity = envelope * (if (dark) 0.66f else 0.56f) * (0.90f + localWave * 0.10f)

                val tint = if (nx < 0.5f) {
                    blend(lilac, blue, nx * 2f)
                } else {
                    blend(blue, cyan, (nx - 0.5f) * 2f)
                }
                drawCircle(
                    color = tint.copy(alpha = opacity.coerceIn(0f, 0.72f)),
                    radius = radius,
                    center = Offset(x, y),
                )
            }
        }
    }
}

@Composable
private fun AskCycloneOrb() {
    val pulse = rememberInfiniteTransition(label = "orb")
    val glow by pulse.animateFloat(
        initialValue = 0.42f,
        targetValue = 0.78f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )
    Canvas(Modifier.size(58.dp)) {
        val c = center
        val r = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                0.28f to Color(0xFF5B8CFF).copy(alpha = glow * 0.48f),
                0.62f to Color(0xFF7A5CFF).copy(alpha = glow * 0.18f),
                1f to Color.Transparent,
            ),
            radius = r,
            center = c,
        )
        val orb = r * 0.52f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFB9D4FF), Color(0xFF4D7DFF), Color(0xFF1C3F9C), Color(0xFF14245A)),
                center = Offset(c.x - orb * 0.28f, c.y - orb * 0.34f),
                radius = orb * 1.55f,
            ),
            radius = orb,
            center = c,
        )
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.90f),
                1f to Color.Transparent,
            ),
            radius = orb * 0.34f,
            center = Offset(c.x - orb * 0.24f, c.y - orb * 0.30f),
        )
    }
}

@Composable
private fun AskCycloneVoiceMode(onClose: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "voice")
    val scales = listOf(0.35f, 0.62f, 1f, 0.62f, 0.35f)
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07101F), Color(0xFF123D72), Color(0xFF07101F)),
                ),
            )
            .semantics { contentDescription = "Listening…" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                scales.forEachIndexed { index, base ->
                    val amount by pulse.animateFloat(
                        initialValue = base * 0.45f,
                        targetValue = base,
                        animationSpec = infiniteRepeatable(
                            animation = tween(700 + index * 90, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "bar$index",
                    )
                    Canvas(Modifier.size(width = 6.dp, height = 56.dp)) {
                        val half = size.height / 2f * amount
                        drawLine(
                            color = Color(0xFF9EC5FF),
                            start = Offset(size.width / 2f, size.height / 2f - half),
                            end = Offset(size.width / 2f, size.height / 2f + half),
                            strokeWidth = size.width,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            Text("Listening…", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("Speak naturally", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .72f))
            Spacer(Modifier.height(24.dp))
            Surface(
                modifier = Modifier
                    .size(64.dp)
                    .clickable(role = Role.Button, onClick = onClose),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Stop, "Stop listening", Modifier.size(28.dp))
                }
            }
        }
        Icon(
            Icons.Rounded.Close,
            "Close voice",
            Modifier
                .align(Alignment.TopStart)
                .padding(18.dp)
                .size(28.dp)
                .clickable(onClick = onClose),
            tint = Color.White,
        )
    }
}

@Composable
private fun V39ChatBubble(message: V39ChatMessage) {
    val isUser = message.role == V39ChatRole.USER
    if (!isUser) {
        Column(
            Modifier.fillMaxWidth().padding(end = 28.dp, top = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                message.text.replace("**", ""),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (message.ok != null) {
                Text(
                    if (message.ok) "Checked" else "Stopped",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }
    val shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    val color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f)
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .fillMaxWidth(.82f)
                .clip(shape)
                .background(color),
        ) {
            Text(
                message.text.replace("**", ""),
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
        }
    }
}
