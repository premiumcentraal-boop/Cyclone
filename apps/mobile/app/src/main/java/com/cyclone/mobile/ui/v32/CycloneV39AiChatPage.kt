package com.cyclone.mobile.ui.v32

import android.content.Context
import android.content.Intent
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
internal fun V39AiChatPage(
    context: Context,
    refreshTick: Int,
    onSettings: () -> Unit,
) {
    val prefs = context.getSharedPreferences(V39AiChatContract.PREFS, Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val agent = remember { OpenRouterAdaptiveAgent(context) }
    val qualifier = remember { ModelQualificationRunner(context) }
    val session = V39AiChatSessionRuntime
    var composer by rememberSaveable { mutableStateOf("") }
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
        val config = V39AiChatContract.config(selectedModelId, accessProfile)
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
        Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Cyclone AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Ask for one outcome. Cyclone handles the verified phone steps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Works on this phone with your API key. PC companion is optional.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (session.busy) {
                TextButton(onClick = {
                    session.status = "Stopping…"
                    agent.cancelActiveTask()
                }) { Text("Stop task") }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Model", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton(
                    onClick = { modelMenuOpen = true },
                    enabled = !session.busy,
                    modifier = Modifier.semantics { contentDescription = "AI model selector" },
                ) {
                    Text(selectedModel.label)
                    Spacer(Modifier.size(4.dp))
                    Icon(Icons.Rounded.ArrowDropDown, null)
                }
                DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                    V39AiChatContract.models().forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.label) },
                            onClick = {
                                selectedModelId = V39AiChatContract.storageId(model)
                                prefs.edit().putString(V39AiChatContract.MODEL_KEY, selectedModelId).apply()
                                modelMenuOpen = false
                            },
                        )
                    }
                }
            }
            if (selectedProfile?.isContributor == true) {
                Text(
                    "Data-contributing / lower-cost tier. Selection is explicit; Cyclone never uses Contributor as a fallback for standard Muse.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (session.messages.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("What should Cyclone do?", fontWeight = FontWeight.Bold)
                            Text("Try “Go to ad.nl” or describe another phone task.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
                                    Text(session.status.ifBlank { "Working…" }, style = MaterialTheme.typography.bodyMedium)
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

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(start = 6.dp, top = 6.dp, end = 7.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = composer,
                    onValueChange = { composer = it },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Ask Cyclone composer" },
                    enabled = !session.busy,
                    minLines = 1,
                    maxLines = 5,
                    placeholder = { Text(V39AiChatContract.PLACEHOLDER) },
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                FilledIconButton(
                    onClick = { submit() },
                    enabled = hasKey && composer.isNotBlank() && !session.busy,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Send Ask Cyclone request" },
                ) {
                    if (session.busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(Icons.AutoMirrored.Rounded.Send, "Send")
                }
            }
        }
        Spacer(Modifier.height(2.dp))
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
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = if (isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
