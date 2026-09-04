from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}\n--- needle ---\n{old}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Convergence must be based on verified progress, not changing observation hashes.
agent = "apps/mobile/app/src/main/java/com/cyclone/mobile/agent/CycloneLocalAgent.kt"
replace_once(
    agent,
    '''    val maxBacktrackAttempts: Int = 3,\n    val maxStaleTargetRetries: Int = 2,\n) {''',
    '''    val maxBacktrackAttempts: Int = 3,\n    val maxStaleTargetRetries: Int = 2,\n    val maxMutationsWithoutVerifiedProgress: Int = 10,\n) {''',
)
replace_once(
    agent,
    '''        require(maxBacktrackAttempts > 0)\n        require(maxStaleTargetRetries > 0)''',
    '''        require(maxBacktrackAttempts > 0)\n        require(maxStaleTargetRetries > 0)\n        require(maxMutationsWithoutVerifiedProgress > 0)''',
)
replace_once(
    agent,
    '''    private var cancelled = false\n    private var state = restoredState''',
    '''    private var cancelled = false\n    private var mutationsWithoutVerifiedProgress = 0\n    private var state = restoredState''',
)
replace_once(
    agent,
    '''            val newEvidence = observation.identity != oldObs || observation.pageIdentity != oldPage\n            state = state.copy(\n                latestObservationIdentity = observation.identity,\n                latestPageIdentity = observation.pageIdentity,\n                requireFreshObservation = false,\n                repeatedIdenticalActionWithoutProgress = if (newEvidence) 0 else state.repeatedIdenticalActionWithoutProgress,\n                lastActionSignature = if (newEvidence) null else state.lastActionSignature,\n                consecutiveRecoveryCyclesWithoutNewEvidence = if (newEvidence) 0 else state.consecutiveRecoveryCyclesWithoutNewEvidence,\n            )''',
    '''            val newEvidence = observation.identity != oldObs || observation.pageIdentity != oldPage\n            state = state.copy(\n                latestObservationIdentity = observation.identity,\n                latestPageIdentity = observation.pageIdentity,\n                requireFreshObservation = false,\n            )''',
)
replace_once(
    agent,
    '''            emit(CycloneTraceEventType.TOOL_REQUESTED, "tool.requested", observation, turn.actionSignature)\n            if (repeated > convergence.maxRepeatedIdenticalActionWithoutProgress) return nonConvergence("convergence.repeated_action")\n\n            val tool = tools.execute(state, observation, turn)''',
    '''            emit(CycloneTraceEventType.TOOL_REQUESTED, "tool.requested", observation, turn.actionSignature)\n            if (repeated > convergence.maxRepeatedIdenticalActionWithoutProgress) return nonConvergence("convergence.repeated_action")\n            mutationsWithoutVerifiedProgress += 1\n            if (mutationsWithoutVerifiedProgress > convergence.maxMutationsWithoutVerifiedProgress) {\n                return nonConvergence("convergence.mutations_without_verified_progress")\n            }\n\n            val tool = tools.execute(state, observation, turn)''',
)
replace_once(
    agent,
    '''                    recentSuccessfulVerifiedActions = bounded(state.recentSuccessfulVerifiedActions + turn.actionSignature, 24),\n                    lastVerifiedProgressTimeMs = now(),\n                    latestObservationIdentity = verification.evidenceIdentity ?: tool.evidenceIdentity ?: observation.evidenceIdentity,\n                    consecutiveRecoveryCyclesWithoutNewEvidence = 0,\n                    repeatedIdenticalActionWithoutProgress = 0,\n                    staleTargetRetries = 0,\n                    finalClassification = CycloneTaskClassification.RECOVERABLE,\n                )\n                emit(CycloneTraceEventType.RECOVERY_CLASSIFIED, "progress.continue", observation, turn.actionSignature); checkpoint(); continue''',
    '''                    recentSuccessfulVerifiedActions = bounded(state.recentSuccessfulVerifiedActions + turn.actionSignature, 24),\n                    recentFailedActions = emptyList(),\n                    lastVerifiedProgressTimeMs = now(),\n                    latestObservationIdentity = verification.evidenceIdentity ?: tool.evidenceIdentity ?: observation.evidenceIdentity,\n                    consecutiveRecoveryCyclesWithoutNewEvidence = 0,\n                    repeatedIdenticalActionWithoutProgress = 0,\n                    staleTargetRetries = 0,\n                    lastActionSignature = null,\n                    finalClassification = CycloneTaskClassification.RECOVERABLE,\n                )\n                mutationsWithoutVerifiedProgress = 0\n                emit(CycloneTraceEventType.RECOVERY_CLASSIFIED, "progress.continue", observation, turn.actionSignature); checkpoint(); continue''',
)
replace_once(
    agent,
    '''        val attempts = (state.recoveryAttempts[kind] ?: 0) + 1\n        val consecutive = if (newEvidence) 0 else state.consecutiveRecoveryCyclesWithoutNewEvidence + 1''',
    '''        val attempts = (state.recoveryAttempts[kind] ?: 0) + 1\n        // Observation/page fingerprints can churn without helping the user's task. Only verified\n        // semantic progress resets this counter (see the verified-progress branch above).\n        val consecutive = state.consecutiveRecoveryCyclesWithoutNewEvidence + 1''',
)

# 2) Adaptive Free Mode: after repeated no-progress failures, stop replaying rigid App Graph routes
# and give the model explicit freedom to choose a materially different bounded strategy.
adaptive = "apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt"
replace_once(
    adaptive,
    '''        var providerRequests: Int = 0,\n        var pendingGateClass: OverlayGateClass? = null,\n        var checkpoint: CycloneTaskState? = null,''',
    '''        var providerRequests: Int = 0,\n        var pendingGateClass: OverlayGateClass? = null,\n        var checkpoint: CycloneTaskState? = null,\n        var adaptiveMode: String = "STRUCTURED",\n        var consecutiveNoProgressFailures: Int = 0,''',
)
replace_once(
    adaptive,
    '''                val graphAction = knownAppGraphAction(session.state.page, goal, session.graphAttempts)''',
    '''                val graphAction = if (session.adaptiveMode == "FREE") null\n                else knownAppGraphAction(session.state.page, goal, session.graphAttempts)''',
)
replace_once(
    adaptive,
    '''                session.providerRequests++\n                val agentContext = session.bridge.promptContext(goal)\n                val forcedVision = session.bridge.consumeForcedVision()''',
    '''                session.providerRequests++\n                val agentContext = session.bridge.promptContext(goal)\n                    .put("operatingMode", session.adaptiveMode)\n                    .put("noProgressFailures", session.consecutiveNoProgressFailures)\n                if (session.adaptiveMode == "FREE") {\n                    agentContext.put(\n                        "freeModeRule",\n                        "Structured recovery failed repeatedly. Choose a materially different bounded strategy using current evidence; do not replay failed routes. All GATE/policy boundaries remain mandatory.",\n                    )\n                }\n                val forcedVision = session.bridge.consumeForcedVision()''',
)
replace_once(
    adaptive,
    '''                onProgress(\n                    if (forcedVision) "Using silent visual evidence · AI request ${session.providerRequests}"\n                    else "Understanding ${session.state.page.title} · AI request ${session.providerRequests}"\n                )''',
    '''                onProgress(\n                    when {\n                        forcedVision -> "Using silent visual evidence · AI request ${session.providerRequests}"\n                        session.adaptiveMode == "FREE" -> "Adapting freely on ${session.state.page.title} · AI request ${session.providerRequests}"\n                        else -> "Understanding ${session.state.page.title} · AI request ${session.providerRequests}"\n                    }\n                )''',
)
replace_once(
    adaptive,
    '''                    detail = "Provider request ${session.providerRequests} · ${config.model.label} · page ${session.state.page.title}",''',
    '''                    detail = "Provider request ${session.providerRequests} · ${config.model.label} · mode ${session.adaptiveMode} · page ${session.state.page.title}",''',
)
replace_once(
    adaptive,
    '''            AgentTraceRuntime.event(context, session.traceId, "ACTION_REQUESTED", summary, code = action.tool)''',
    '''            AgentTraceRuntime.event(\n                context, session.traceId, "ACTION_REQUESTED", summary, code = action.tool,\n                detail = PageAgentProtocol.diagnosticActionDetail(action),\n            )''',
)
replace_once(
    adaptive,
    '''                if (envelope.androidExecutionOk) "Android accepted the action" else "Android rejected the action",\n                code = envelope.errorClass.name, ok = envelope.androidExecutionOk,\n            )''',
    '''                if (envelope.androidExecutionOk) "Android accepted the action" else "Android rejected the action",\n                code = envelope.errorClass.name, ok = envelope.androidExecutionOk,\n                detail = envelope.safeMessage,\n            )''',
)
replace_once(
    adaptive,
    '''            if (verified) {\n                session.successfulActions += "${action.tool}:${action.controlId.orEmpty()}@${state.page.pageKey.takeLast(10)}"\n            } else {\n                session.failedActions += "${action.tool}:${action.controlId.orEmpty()}:${envelope.errorClass.name}"\n            }''',
    '''            val madeProgress = verified && progress.classification == ProgressClassification.VERIFIED_PROGRESS\n            val previousMode = session.adaptiveMode\n            if (madeProgress) {\n                session.successfulActions += "${action.tool}:${action.controlId.orEmpty()}@${state.page.pageKey.takeLast(10)}"\n                session.consecutiveNoProgressFailures = 0\n                session.adaptiveMode = "STRUCTURED"\n            } else {\n                val failureCode = if (verified) "NO_VERIFIED_PROGRESS" else envelope.errorClass.name\n                session.failedActions += "${action.tool}:${action.controlId.orEmpty()}:$failureCode"\n                session.consecutiveNoProgressFailures += 1\n                if (session.consecutiveNoProgressFailures >= 2) session.adaptiveMode = "FREE"\n            }\n            if (previousMode != session.adaptiveMode) {\n                val entering = session.adaptiveMode == "FREE"\n                AgentTraceRuntime.event(\n                    context, session.traceId,\n                    if (entering) "FREE_MODE_ENTER" else "FREE_MODE_EXIT",\n                    if (entering) "Structured recovery stalled; Cyclone is trying a different strategy"\n                    else "Verified progress restored; returning to structured execution",\n                    code = if (entering) "adaptive.free.enter" else "adaptive.free.exit",\n                    ok = true,\n                    detail = "noProgressFailures=${session.consecutiveNoProgressFailures}",\n                )\n                onProgress(if (entering) "Trying a different way…" else "Progress verified · returning to the reliable route")\n            }''',
)
replace_once(
    adaptive,
    '''        if (verifiedProgress) {\n            session.bridge.markVerifiedProgress()\n            session.successfulActions += "phone.click:${graphAction.label}@${before.page.pageKey.takeLast(10)}"\n            if (before.page.pageKey != after.page.pageKey) announceNewPage(session.traceId, after, onProgress)\n        } else {\n            session.failedActions += "phone.click:${graphAction.label}:${envelope.errorClass.name}"\n            session.bridge.recover(session.bridge.causeFor(envelope), session.goal)\n        }''',
    '''        val previousMode = session.adaptiveMode\n        if (verifiedProgress) {\n            session.bridge.markVerifiedProgress()\n            session.successfulActions += "phone.click:${graphAction.label}@${before.page.pageKey.takeLast(10)}"\n            session.consecutiveNoProgressFailures = 0\n            session.adaptiveMode = "STRUCTURED"\n            if (before.page.pageKey != after.page.pageKey) announceNewPage(session.traceId, after, onProgress)\n        } else {\n            session.failedActions += "phone.click:${graphAction.label}:${envelope.errorClass.name}"\n            session.consecutiveNoProgressFailures += 1\n            if (session.consecutiveNoProgressFailures >= 2) session.adaptiveMode = "FREE"\n            session.bridge.recover(session.bridge.causeFor(envelope), session.goal)\n        }\n        if (previousMode != session.adaptiveMode) {\n            val entering = session.adaptiveMode == "FREE"\n            AgentTraceRuntime.event(\n                context, session.traceId,\n                if (entering) "FREE_MODE_ENTER" else "FREE_MODE_EXIT",\n                if (entering) "Known routes stopped verifying; Cyclone is trying a different strategy"\n                else "Verified progress restored; returning to structured execution",\n                code = if (entering) "adaptive.free.enter" else "adaptive.free.exit",\n                ok = true,\n                detail = "noProgressFailures=${session.consecutiveNoProgressFailures}",\n            )\n        }''',
)
replace_once(
    adaptive,
    '''            CycloneModelDirective.ACT -> decision.actions\n                .joinToString("|") { action -> "${action.tool}:${action.controlId.orEmpty()}" }\n                .takeIf { it.isNotBlank() }''',
    '''            CycloneModelDirective.ACT -> PageAgentProtocol.actionSignature(decision, pageKey)''',
)

# 3) Free Mode may use the already-existing, allowlisted Android ACTION_VIEW tool as a materially
# different browser/navigation strategy. Canonical execution still stays inside PhoneToolExecutor.
environment = "apps/mobile/app/src/main/java/com/cyclone/mobile/agent/tools/CycloneAgentEnvironment.kt"
replace_once(
    environment,
    '''            "phone.home",\n            "phone.open_app",\n        )''',
    '''            "phone.home",\n            "phone.open_app",\n            "phone.launch_intent",\n        )''',
)

print("Cyclone 3.9.2 runtime patch applied successfully")
