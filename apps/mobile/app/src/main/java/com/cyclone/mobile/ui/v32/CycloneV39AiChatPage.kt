package com.cyclone.mobile.ui.v32

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.AgentRunDiagnosticV39
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.CycloneAiAccessProfileStore
import com.cyclone.mobile.ai.OpenRouterAdaptiveAgent
import com.cyclone.mobile.ai.OpenRouterModelPreset
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.ProviderFailureClass
import com.cyclone.mobile.ai.QuickAgentConfig
import com.cyclone.mobile.ai.QuickAgentResult
import com.cyclone.mobile.ai.TaskResultActivityV292
import com.cyclone.mobile.ai.model.ModelQualificationOutcome
import com.cyclone.mobile.ai.model.ModelQualificationRunner
import com.cyclone.mobile.ai.model.ModelRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class V39ChatRole { USER, CYCLONE }

internal data class V39ChatMessage(
    val id: Long,
    val role: V39ChatRole,
    val text: String,
    val ok: Boolean? = null,
)

/** Process-session chat state only. Persistent diagnostics remain owned by Brain Recent Runs. */
internal object V39AiChatSessionRuntime {
    private val nextId = AtomicLong(1L)
    val messages = mutableStateListOf<V39ChatMessage>()
    val submitGate = V39AiSubmitGate()
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")

    fun append(role: V39ChatRole, text: String, ok: Boolean? = null) {
        val clean = text.trim()
        if (clean.isNotBlank()) messages += V39ChatMessage(nextId.getAndIncrement(), role, clean, ok)
    }
}

internal object V39AiChatContract {
    const val PREFS = "cyclone_ai"
    const val MODEL_KEY = "openrouter_model"
    const val PLACEHOLDER = "Ask Cyclone to do something…"

    fun normalizedRequest(value: String): String = value.trim()

    fun modelForStored(stored: String?): OpenRouterModelPreset =
        OpenRouterModelPresets.byId(stored.orEmpty().ifBlank { OpenRouterModelPresets.DEFAULT.id })

    fun storageId(model: OpenRouterModelPreset): String =
        ModelRegistry.profileForPreset(model)?.cycloneId ?: model.id

    fun models(): List<OpenRouterModelPreset> = OpenRouterModelPresets.all

    fun config(modelId: String, accessProfile: CycloneAiAccessProfile): QuickAgentConfig {
        val model = modelForStored(modelId)
        val profile = ModelRegistry.profileForPreset(model)
        return QuickAgentConfig(
            model = model,
            // Contributor never crosses into the normal default vision identity.
            visionModel = if (profile?.isContributor == true) model else OpenRouterModelPresets.GEMINI_3_8_FLASH,
            safeMode = accessProfile != CycloneAiAccessProfile.FULL,
            accessProfile = accessProfile,
        )
    }

    fun finalStatus(result: QuickAgentResult): String = if (result.ok) "Completed and checked" else "Stopped safely"
}

/** Atomic guard means recomposition/keyboard + icon races cannot double-submit the same task. */
internal class V39AiSubmitGate {
    private val active = AtomicBoolean(false)
    val busy: Boolean get() = active.get()

    fun tryAccept(rawRequest: String, hasKey: Boolean): String? {
        val request = V39AiChatContract.normalizedRequest(rawRequest)
        if (request.isBlank() || !hasKey) return null
        if (!active.compareAndSet(false, true)) return null
        return request
    }

    fun complete() {
        active.set(false)
    }
}

private fun recordPreflightFailure(
    context: Context,
    request: String,
    outcome: ModelQualificationOutcome.Failed,
): String {
    val traceId = AgentTraceRuntime.start(context, request, outcome.profile.cycloneId)
    AgentTraceRuntime.event(
        context, traceId, "MODEL_SELECTED", outcome.profile.displayName,
        code = outcome.profile.cycloneId, ok = true,
        detail = "slug=${outcome.profile.openRouterSlug} · privacy=${outcome.profile.privacyClass}",
    )
    AgentTraceRuntime.event(
        context, traceId, "MODEL_PREFLIGHT_STARTED", "Provider-only model qualification started before Android observation",
        code = "model.preflight", ok = true,
        detail = "phoneMutations=0 · phoneObservation=false · screenshot=false",
    )
    if (outcome.failure.failureClass == ProviderFailureClass.MALFORMED_MODEL_OUTPUT) {
        AgentTraceRuntime.event(
            context, traceId, "MODEL_OUTPUT_RECEIVED", "Qualification response received",
            code = "model.output", ok = true,
        )
        AgentTraceRuntime.event(
            context, traceId, "MODEL_OUTPUT_REJECTED", "Qualification output did not satisfy Cyclone's contract",
            code = outcome.failure.code, ok = false,
        )
    }
    AgentTraceRuntime.event(
        context, traceId, "PROVIDER_ERROR", outcome.failure.userMessage,
        code = outcome.failure.code, ok = false,
        detail = listOfNotNull(
            "http=${outcome.failure.httpStatus}",
            outcome.failure.providerCode?.let { "providerCode=$it" },
            outcome.failure.providerName?.let { "provider=$it" },
            outcome.failure.requestId?.let { "requestId=$it" },
            "retryable=${outcome.failure.retryable}",
            "phoneMutations=0",
        ).joinToString(" · "),
    )
    AgentTraceRuntime.event(
        context, traceId, "MODEL_PREFLIGHT_FAILED", "Model qualification failed before Android execution",
        code = outcome.failure.code, ok = false,
        detail = "No Android failure attribution and no negative navigation evidence were recorded.",
    )
    AgentTraceRuntime.finish(context, traceId, "FAILED", outcome.failure.userMessage, 0)
    AgentRunDiagnosticV39.ensureCanonical(context, traceId)
    return traceId
}

private fun attachPreflightSuccess(
    context: Context,
    run: QuickAgentResult,
    outcome: ModelQualificationOutcome.Passed,
) {
    val traceId = run.taskId ?: return
    AgentTraceRuntime.event(
        context, traceId, "MODEL_SELECTED", outcome.profile.displayName,
        code = outcome.profile.cycloneId, ok = true,
        detail = "slug=${outcome.profile.openRouterSlug} · privacy=${outcome.profile.privacyClass} · causalStage=pre_android",
    )
    AgentTraceRuntime.event(
        context, traceId, "MODEL_PREFLIGHT_STARTED", "Provider-only qualification occurred before Android observation",
        code = "model.preflight", ok = true,
        detail = "phoneMutations=0 · phoneObservation=false · cached=${outcome.cached}",
    )
    if (!outcome.cached) {
        AgentTraceRuntime.event(
            context, traceId, "PROVIDER_ROUTED", "Qualification provider served the selected model identity",
            code = outcome.profile.openRouterSlug, ok = true,
            detail = listOfNotNull(
                outcome.providerName?.let { "provider=$it" },
                outcome.requestId?.let { "requestId=$it" },
                "allowFallbacks=${outcome.profile.allowProviderFallbacks}",
            ).joinToString(" · "),
        )
        AgentTraceRuntime.event(
            context, traceId, "MODEL_OUTPUT_RECEIVED", "Qualification output received",
            code = "model.output", ok = true,
        )
        if (outcome.repaired) {
            AgentTraceRuntime.event(
                context, traceId, "MODEL_OUTPUT_REPAIRED", "One harmless JSON wrapper was normalized",
                code = "model.output.single_repair", ok = true,
            )
        }
        AgentTraceRuntime.event(
            context, traceId, "MODEL_OUTPUT_VALIDATED", "Qualification output matched the Cyclone contract",
            code = "model.output.valid", ok = true,
        )
    }
    AgentTraceRuntime.event(
        context, traceId, "MODEL_PREFLIGHT_PASSED", if (outcome.cached) "Cached model qualification accepted" else "Model qualification passed",
        code = "model.preflight", ok = true,
        detail = "Qualification completed before phone observation/mutation; attached to this run after execution so it remains one downloadable diagnostic.",
    )
    AgentRunDiagnosticV39.ensureCanonical(context, traceId)
}

@Composable
internal fun V39AiChatPage(context: Context, refreshTick: Int, onSettings: () -> Unit) {
    V39AiChatContent(context, refreshTick, onSettings)
}

@Composable
private fun V39AiChatContent(
    context: Context,
    refreshTick: Int,
    onSettings: () -> Unit,
) {
    val task by com.cyclone.mobile.runtime.background.WorkspaceTasks.state.collectAsState()
    val attached by com.cyclone.mobile.ui.overlay.PendingTaskAttachment.present.collectAsState()
    val prefs = context.getSharedPreferences(V39AiChatContract.PREFS, Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val agent = remember { OpenRouterAdaptiveAgent(context) }
    val qualifier = remember { ModelQualificationRunner(context) }
    val session = V39AiChatSessionRuntime
    var composer by rememberSaveable { mutableStateOf("") }
    var toolsMenuOpen by remember { mutableStateOf(false) }
    var inputMessage by remember { mutableStateOf("") }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                composer = listOf(composer, it).filter(String::isNotBlank).joinToString(" ")
            }
        }
    }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var selectedModelId by rememberSaveable {
        mutableStateOf(
            V39AiChatContract.storageId(
                V39AiChatContract.modelForStored(prefs.getString(V39AiChatContract.MODEL_KEY, null)),
            ),
        )
    }
    val selectedModel = V39AiChatContract.modelForStored(selectedModelId)
    val selectedProfile = ModelRegistry.profileForPreset(selectedModel)
    val accessProfile = remember(refreshTick) { CycloneAiAccessProfileStore.read(context) }
    val hasKey = remember(refreshTick) { OpenRouterSecretStore.hasKey(context) }
    val latestRun = remember(refreshTick, session.busy, session.messages.size) {
        AgentTraceRuntime.store.listSessions(1).firstOrNull()
    }

    fun submit() {
        val request = session.submitGate.tryAccept(composer, hasKey) ?: return
        val baseConfig = V39AiChatContract.config(selectedModelId, CycloneAiAccessProfileStore.read(context))
        val config = baseConfig.copy(model = baseConfig.model.copy(reasoningEffort = prefs.getString("openrouter_reasoning_effort", "medium") ?: "medium"), attachment = com.cyclone.mobile.ui.overlay.PendingTaskAttachment.take())
        composer = ""
        session.busy = true
        session.status = "Qualifying ${config.model.label}…"
        session.append(V39ChatRole.USER, request)
        scope.launch {
            try {
                when (val qualification = qualifier.qualify(config.model)) {
                    is ModelQualificationOutcome.Failed -> {
                        recordPreflightFailure(context, request, qualification)
                        session.status = "Model unavailable"
                        session.append(V39ChatRole.CYCLONE, qualification.failure.userMessage, false)
                        return@launch
                    }
                    is ModelQualificationOutcome.Passed -> {
                        session.status = if (qualification.cached) "Model ready · starting…" else "Model qualified · starting…"
                        val run = agent.execute(request, config) { progress ->
                            scope.launch {
                                if (session.submitGate.busy) session.status = progress.trim().ifBlank { "Working…" }
                            }
                        }
                        attachPreflightSuccess(context, run, qualification)
                        session.status = V39AiChatContract.finalStatus(run)
                        session.append(V39ChatRole.CYCLONE, run.message, run.ok)
                    }
                }
            } catch (cancelled: CancellationException) {
                agent.cancelActiveTask()
                throw cancelled
            } catch (_: Exception) {
                session.status = "Stopped safely"
                session.append(V39ChatRole.CYCLONE, "Cyclone stopped before the task completed.", false)
            } finally {
                session.submitGate.complete()
                session.busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CycloneOrbitMark(Modifier.size(32.dp))
            Spacer(Modifier.size(10.dp))
            Text("Cyclone", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Box {
            TextButton(onClick = { modelMenuOpen = true }, enabled = !session.busy,
                modifier = Modifier.semantics { contentDescription = "AI model selector" }) {
                Text(selectedModel.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
            DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                V39AiChatContract.models().forEach { model ->
                    DropdownMenuItem(text = { Text(model.label) }, onClick = {
                        selectedModelId = V39AiChatContract.storageId(model)
                        prefs.edit().putString(V39AiChatContract.MODEL_KEY, selectedModelId).apply()
                        modelMenuOpen = false
                    })
                }
            }
        }
        if (selectedProfile?.isContributor == true) {
            Text("Contributor · prompts and responses may be used for training.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (session.messages.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CycloneOrbitMark(Modifier.size(64.dp))
                        Text("A little less doing.\nA little more done.", fontSize = 32.sp, lineHeight = 39.sp,
                            fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface)
                        Text("What would you like me to do?", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(session.messages, key = { it.id }) { message -> V39ChatBubble(message) }
            }
            if (session.busy || session.status.isNotBlank()) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(.9f),
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (session.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Column {
                                    Text("Cyclone", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    Text(if (session.busy) "Working on your request…" else session.status, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
            if (!session.busy && session.messages.lastOrNull()?.role == V39ChatRole.CYCLONE && latestRun != null && latestRun.status != "RUNNING") {
                item {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(context, TaskResultActivityV292::class.java)
                                .putExtra(TaskResultActivityV292.EXTRA_SESSION_ID, latestRun.id)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) {
                        Icon(Icons.Rounded.History, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.size(5.dp))
                        Text("View run")
                    }
                }
            }
        }

        if (!hasKey) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Key, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("OpenRouter key required", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSettings) { Text("Add API key in Settings") }
                }
            }
        }

        if (session.busy) {
            TextButton(onClick = { session.status = "Stopping…"; agent.cancelActiveTask() }) { Text("Stop task") }
        }
        if (inputMessage.isNotBlank()) Text(inputMessage, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        task?.let { CycloneTaskProgress(it) }
        if (attached) Text("Attachment ready", style = MaterialTheme.typography.labelSmall)
        CycloneGlassSurface(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CycloneIntelligenceControls(enabled = !session.busy)
                Box {
                    IconButton(onClick = { toolsMenuOpen = true }, enabled = !session.busy) { Icon(Icons.Rounded.Add, "Add to task") }
                    DropdownMenu(expanded = toolsMenuOpen, onDismissRequest = { toolsMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("File") }, onClick = { toolsMenuOpen = false; context.startActivity(Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java)) })
                        DropdownMenuItem(text = { Text("Take photo") }, onClick = { toolsMenuOpen = false; context.startActivity(Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java).putExtra("camera", true)) })
                        DropdownMenuItem(text = { Text("Share screen") }, onClick = { toolsMenuOpen = false; context.startActivity(Intent(context, com.cyclone.mobile.capture.LiveCaptureConsentActivity::class.java)) })
                        DropdownMenuItem(text = { Text("Background task") }, onClick = { toolsMenuOpen = false; context.startActivity(Intent(context, com.cyclone.mobile.runtime.background.WorkspaceActivity::class.java).putExtra("goal", composer)) })
                    }
                }
                BasicTextField(value = composer, onValueChange = { composer = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp).semantics { contentDescription = "Ask Cyclone composer" },
                    decorationBox = { field -> Box { if (composer.isEmpty()) Text("Ask Cyclone", color = MaterialTheme.colorScheme.onSurfaceVariant); field() } })
                if (!session.busy) IconButton(onClick = {
                    runCatching { dictation.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
                        .onFailure { inputMessage = "Dictation isn't available. You can type your request." }
                }) { Icon(Icons.Rounded.Mic, "Dictate request") }
                FilledIconButton(onClick = { if (session.busy) agent.cancelActiveTask() else submit() }, enabled = session.busy || (hasKey && composer.isNotBlank())) {
                    if (session.busy) Text("■") else Icon(Icons.Rounded.ArrowUpward, "Send request")
                }
            }
        }
    }
}

@Composable
private fun V39ChatBubble(message: V39ChatMessage) {
    val isUser = message.role == V39ChatRole.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            border = null,
            modifier = Modifier.fillMaxWidth(.88f),
        ) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (isUser) "You" else "Cyclone", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                if (!isUser && message.ok != null) {
                    Text(
                        if (message.ok) "Checked" else "Stopped",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
