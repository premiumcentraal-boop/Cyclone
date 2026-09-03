package com.cyclone.mobile.agent.tools

import android.content.Context
import com.cyclone.mobile.agent.contract.*
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

interface CycloneCompoundAgentToolsApi {
    fun descriptors(): JSONArray
    fun call(toolName: String, args: JSONObject = JSONObject()): JSONObject
    fun openApp(name: String): JSONObject
    fun understandPage(goal: String = ""): JSONObject
    fun recall(goal: String): JSONObject
    fun search(queries: List<String>, goal: String = ""): JSONObject
    fun inspect(elementIds: List<String>): JSONObject
    fun visualContext(goal: String = ""): JSONObject
    fun click(elementId: String, goal: String = ""): JSONObject
    fun longPress(elementId: String, goal: String = ""): JSONObject
    fun type(elementId: String, value: String, goal: String = ""): JSONObject
    fun replaceText(elementId: String, value: String, goal: String = ""): JSONObject
    fun scroll(direction: String = "forward", elementId: String? = null, goal: String = ""): JSONObject
    fun back(goal: String = ""): JSONObject
    fun home(goal: String = ""): JSONObject
    fun runSkill(skillId: String, goal: String = ""): JSONObject
}

/**
 * Provider-neutral high-bandwidth toolbox. It composes the existing CycloneAgentEnvironment;
 * no Android action, selector, accessibility or policy authority is duplicated here.
 */
class CycloneCompoundAgentTools internal constructor(
    private val runtime: CompoundAgentRuntimePort,
) : CycloneCompoundAgentToolsApi {
    constructor(context: Context) : this(AndroidCompoundAgentRuntimePort(context.applicationContext))

    private val mutationLock = Any()

    override fun descriptors(): JSONArray = CompoundAgentToolCatalog.descriptorsJson()

    override fun call(toolName: String, args: JSONObject): JSONObject = when (toolName) {
        "open_app" -> openApp(args.optString("name"))
        "understand_page" -> understandPage(args.optString("goal"))
        "recall" -> recall(args.optString("goal"))
        "search" -> search(strings(args.optJSONArray("queries")), args.optString("goal"))
        "inspect" -> inspect(strings(args.optJSONArray("elementIds")))
        "visual_context" -> visualContext(args.optString("goal"))
        "click" -> click(args.optString("elementId"), args.optString("goal"))
        "long_press" -> longPress(args.optString("elementId"), args.optString("goal"))
        "type" -> type(args.optString("elementId"), args.optString("value"), args.optString("goal"))
        "replace_text" -> replaceText(args.optString("elementId"), args.optString("value"), args.optString("goal"))
        "scroll" -> scroll(args.optString("direction", "forward"), args.optString("elementId").takeIf(String::isNotBlank), args.optString("goal"))
        "back" -> back(args.optString("goal"))
        "home" -> home(args.optString("goal"))
        "run_skill" -> runSkill(args.optString("skillId"), args.optString("goal"))
        else -> errorJson(AgentFailureClass.CAPABILITY_UNAVAILABLE, AgentFailureLayer.CAPABILITY, false, "Unknown compound tool: $toolName")
    }

    override fun openApp(name: String): JSONObject = synchronized(mutationLock) {
        if (name.isBlank()) return@synchronized invalid("App name is required.")
        val inventory = runtime.installedApps(refresh = true)
        return@synchronized when (val resolution = CompoundAppResolver.resolve(name, inventory)) {
            is AppResolution.NotFound -> JSONObject()
                .put("success", false)
                .put("errorClass", AgentFailureClass.TARGET_NOT_FOUND.name)
                .put("reasonCode", "APP_NOT_FOUND")
                .put("query", name.take(160))
                .put("candidates", appMatchesJson(resolution.candidates))
            is AppResolution.Ambiguous -> JSONObject()
                .put("success", false)
                .put("errorClass", AgentFailureClass.TARGET_NOT_FOUND.name)
                .put("reasonCode", "AMBIGUOUS_APP")
                .put("query", name.take(160))
                .put("candidates", appMatchesJson(resolution.candidates))
            is AppResolution.Resolved -> {
                val app = resolution.match.app
                val pre = runtime.observe("Open ${app.label}")
                if (pre.failure != null) return@synchronized failureJson(pre.failure)
                val envelope = runtime.act(
                    "phone.open_app",
                    JSONObject().put("package", app.packageName),
                    "Open ${app.label}",
                )
                val packageVerified = envelope.verification.passed && envelope.after?.packageName == app.packageName
                actionJson(envelope)
                    .put("success", packageVerified && envelope.errorClass == AgentFailureClass.NONE)
                    .put("resolvedApp", app.label)
                    .put("package", app.packageName)
                    .put("activity", app.launcherActivity ?: JSONObject.NULL)
                    .put("currentPackage", envelope.after?.packageName ?: JSONObject.NULL)
                    .put("packageVerified", packageVerified)
                    .put("matchScore", resolution.match.score)
            }
        }
    }

    override fun understandPage(goal: String): JSONObject {
        val observed = runtime.observe(goal)
        val page = observed.page ?: return failureJson(observed.failure ?: genericObservationFailure())
        val brain = runtime.brainRecall(goal)
        val routes = runtime.knownRoutes(goal)
        val apps = CompoundAppResolver.rank(goal, runtime.installedApps(refresh = true)).take(APP_MATCH_LIMIT)
        val actionHistory = runtime.history()
        val previous = actionHistory.firstOrNull()
        val previousVerified = actionHistory.firstOrNull { it.verification.passed }
        val rankedControls = page.controls.take(PAGE_CONTROL_LIMIT)
        val supplementals = page.controls.filter { it.source == "semantic_supplement" }.take(SUPPLEMENTAL_LIMIT)
        val verifiedSkills = runtime.verifiedSkills(goal).take(SKILL_HINT_LIMIT)

        return JSONObject()
            .put("success", true)
            .put("goal", goal.take(300))
            .put("observationId", page.observationId)
            .put("generation", page.generation)
            .put("package", page.packageName)
            .put("activity", page.activity ?: JSONObject.NULL)
            .put("pageKey", page.pageKey)
            .put("identity", JSONObject()
                .put("structuralKey", page.structuralKey)
                .put("contentKey", page.contentKey)
                .put("accessibilityFingerprint", page.accessibilityFingerprint))
            .put("pageSummary", boundedObject(page.pageSummary, OBJECT_PREVIEW_LIMIT))
            .put("pageText", boundedPageText(page.pageText))
            .put("controls", jsonArray(rankedControls.map(::candidateJson)))
            .put("supplementalControls", jsonArray(supplementals.map(::candidateJson)))
            .put("brainRecall", advisory(brain.evidence))
            .put("knownRoutes", advisory(routes.evidence))
            .put("installedAppMatches", appMatchesJson(apps))
            .put("verifiedSkillHints", jsonArray(verifiedSkills.map(::skillSummaryJson)))
            .put("previousAction", previous?.let(::previousActionJson) ?: JSONObject.NULL)
            .put("previousVerifiedAction", previousVerified?.let(::previousActionJson) ?: JSONObject.NULL)
            .put("screenshot", JSONObject()
                .put("eligible", true)
                .put("structuredFirst", true)
                .put("tool", "visual_context")
                .put("reason", if (rankedControls.isEmpty()) "No semantic controls were ranked." else "Use only when structured evidence is insufficient."))
            .put("rawAccessibilityIncluded", false)
    }

    override fun recall(goal: String): JSONObject {
        if (goal.isBlank()) return invalid("Recall goal is required.")
        val brain = runtime.brainRecall(goal)
        val routes = runtime.knownRoutes(goal)
        val apps = CompoundAppResolver.rank(goal, runtime.installedApps(refresh = true)).take(APP_MATCH_LIMIT)
        val skills = runtime.verifiedSkills(goal).take(RECALL_SKILL_LIMIT)
        val failures = runtime.history().filter { it.errorClass != AgentFailureClass.NONE }.take(PREVIOUS_FAILURE_LIMIT)
        return JSONObject()
            .put("success", true)
            .put("goal", goal.take(300))
            .put("authority", "ADVISORY_ONLY")
            .put("brain", advisory(brain.evidence))
            .put("appGraph", advisory(routes.evidence))
            .put("installedAppMatches", appMatchesJson(apps))
            .put("verifiedSkills", jsonArray(skills.map(::skillSummaryJson)))
            .put("previousFailures", jsonArray(failures.map(::previousActionJson)))
            .put("mayAuthorizeAction", false)
    }

    override fun search(queries: List<String>, goal: String): JSONObject {
        val clean = queries.map(String::trim).filter(String::isNotBlank).distinct().take(MAX_BATCH)
        if (clean.isEmpty()) return invalid("At least one search query is required.")
        var observationId: String? = null
        var generation: Long? = null
        val merged = linkedMapOf<String, MergedCandidate>()
        val perQuery = JSONArray()
        for (query in clean) {
            val result = runtime.search(query, goal.ifBlank { query })
            result.failure?.let { return failureJson(it) }
            val id = result.observationId ?: return failureJson(genericObservationFailure())
            val gen = result.generation ?: return failureJson(genericObservationFailure())
            if (observationId == null) {
                observationId = id
                generation = gen
            } else if (observationId != id || generation != gen) {
                return errorJson(AgentFailureClass.STALE_OBSERVATION, AgentFailureLayer.OBSERVATION, true, "Batch search observation changed; run search again.")
            }
            val results = JSONArray()
            result.candidates.take(SEARCH_PER_QUERY_LIMIT).forEach { candidate ->
                results.put(candidateJson(candidate))
                val current = merged[candidate.elementId]
                if (current == null) merged[candidate.elementId] = MergedCandidate(candidate, mutableSetOf(query))
                else {
                    current.queries += query
                    if (candidate.relevance > current.candidate.relevance) current.candidate = candidate
                }
            }
            perQuery.put(JSONObject().put("query", query).put("candidates", results))
        }
        val ranked = merged.values.sortedWith(compareByDescending<MergedCandidate> { it.candidate.relevance }.thenBy { it.candidate.label }).take(SEARCH_MERGED_LIMIT)
        return JSONObject()
            .put("success", true)
            .put("observationId", observationId)
            .put("generation", generation)
            .put("queries", jsonArray(clean))
            .put("perQuery", perQuery)
            .put("candidates", jsonArray(ranked.map { item -> candidateJson(item.candidate).put("matchedQueries", jsonArray(item.queries.sorted())) }))
    }

    override fun inspect(elementIds: List<String>): JSONObject {
        val ids = elementIds.map(String::trim).filter(String::isNotBlank).distinct().take(MAX_BATCH)
        if (ids.isEmpty()) return invalid("At least one elementId is required.")
        var observationId: String? = null
        var generation: Long? = null
        val inspected = JSONArray()
        for (id in ids) {
            val result = runtime.inspect(id)
            val failure = result.failure
            if (failure != null) {
                return failureJson(failure).put("failedElementId", id)
            }
            if (observationId == null) {
                observationId = result.observationId
                generation = result.generation
            } else if (observationId != result.observationId || generation != result.generation) {
                return errorJson(AgentFailureClass.STALE_OBSERVATION, AgentFailureLayer.OBSERVATION, true, "Batch inspect observation changed; inspect again.")
            }
            inspected.put(inspectJson(result))
        }
        return JSONObject()
            .put("success", true)
            .put("observationId", observationId ?: JSONObject.NULL)
            .put("generation", generation ?: JSONObject.NULL)
            .put("elements", inspected)
    }

    override fun visualContext(goal: String): JSONObject {
        val page = understandPage(goal)
        if (!page.optBoolean("success")) return page
        val evidence = runtime.screenshotEvidence(goal)
        evidence.failure?.let { return failureJson(it).put("page", page) }
        val annotations = JSONArray()
        val controls = page.optJSONArray("controls") ?: JSONArray()
        for (index in 0 until minOf(controls.length(), VISUAL_ANNOTATION_LIMIT)) {
            val control = controls.optJSONObject(index) ?: continue
            val bounds = control.optJSONObject("bounds") ?: continue
            annotations.put(JSONObject()
                .put("elementId", control.optString("elementId"))
                .put("label", control.optString("label"))
                .put("role", control.optString("role"))
                .put("bounds", JSONObject(bounds.toString())))
        }
        return JSONObject()
            .put("success", true)
            .put("observationId", page.optString("observationId"))
            .put("generation", page.optLong("generation"))
            .put("page", page)
            .put("screenshot", evidence.toJson())
            .put("visualDimensions", JSONObject().put("width", evidence.width ?: JSONObject.NULL).put("height", evidence.height ?: JSONObject.NULL))
            .put("semanticOverlay", annotations)
            .put("base64Included", false)
            .put("screenshotProvesSuccess", false)
    }

    override fun click(elementId: String, goal: String): JSONObject = mutateElement("phone.click", elementId, goal)
    override fun longPress(elementId: String, goal: String): JSONObject = mutateElement("phone.long_press", elementId, goal)

    override fun type(elementId: String, value: String, goal: String): JSONObject = synchronized(mutationLock) {
        if (elementId.isBlank()) return@synchronized invalid("elementId is required.")
        if (value.length > 4096) return@synchronized invalid("value exceeds the bounded type length.")
        actionJson(runtime.act("phone.type", JSONObject().put("elementId", elementId).put("value", value).put("user_authorized", true), goal.ifBlank { "Type into current field" }))
    }

    override fun replaceText(elementId: String, value: String, goal: String): JSONObject = synchronized(mutationLock) {
        if (elementId.isBlank()) return@synchronized invalid("elementId is required.")
        if (value.length > 4096) return@synchronized invalid("value exceeds the bounded type length.")
        actionJson(runtime.act("phone.replace_text", JSONObject().put("elementId", elementId).put("value", value).put("user_authorized", true), goal.ifBlank { "Replace text in current field" }))
    }

    override fun scroll(direction: String, elementId: String?, goal: String): JSONObject = synchronized(mutationLock) {
        if (direction !in setOf("forward", "backward")) return@synchronized invalid("direction must be forward or backward.")
        val params = JSONObject().put("direction", direction)
        elementId?.takeIf(String::isNotBlank)?.let { params.put("elementId", it) }
        actionJson(runtime.act("phone.scroll", params, goal.ifBlank { "Scroll $direction" }))
    }

    override fun back(goal: String): JSONObject = synchronized(mutationLock) {
        actionJson(runtime.act("phone.back", JSONObject(), goal.ifBlank { "Go back" }))
    }

    override fun home(goal: String): JSONObject = synchronized(mutationLock) {
        actionJson(runtime.act("phone.home", JSONObject(), goal.ifBlank { "Go home" }))
    }

    override fun runSkill(skillId: String, goal: String): JSONObject = synchronized(mutationLock) {
        if (skillId.isBlank()) return@synchronized invalid("skillId is required.")
        val skill = runtime.verifiedSkills(goal).firstOrNull { it.skillId == skillId }
            ?: return@synchronized errorJson(AgentFailureClass.CAPABILITY_UNAVAILABLE, AgentFailureLayer.LEARNING, false, "Skill is not a currently verified/reviewable route.").put("skillId", skillId)
        val stepResults = JSONArray()
        for ((index, step) in skill.steps.withIndex()) {
            val pre = runtime.observe(goal.ifBlank { skill.label })
            pre.failure?.let {
                return@synchronized failureJson(it).put("skillId", skill.skillId).put("failedStep", index).put("steps", stepResults)
            }
            val executable = resolveSkillStep(step, goal.ifBlank { skill.label })
                ?: return@synchronized errorJson(AgentFailureClass.TARGET_NOT_FOUND, AgentFailureLayer.OBSERVATION, true, "Verified skill target could not be re-located on the fresh observation.")
                    .put("skillId", skill.skillId).put("failedStep", index).put("steps", stepResults)
            val envelope = runtime.act(executable.first, executable.second, goal.ifBlank { step.label })
            val json = actionJson(envelope).put("step", index).put("label", step.label)
            stepResults.put(json)
            if (!envelope.verification.passed) {
                return@synchronized JSONObject()
                    .put("success", false)
                    .put("skillId", skill.skillId)
                    .put("failedStep", index)
                    .put("errorClass", envelope.errorClass.name)
                    .put("verification", envelope.verification.status.name)
                    .put("steps", stepResults)
                    .put("stopped", true)
            }
        }
        JSONObject()
            .put("success", true)
            .put("skillId", skill.skillId)
            .put("label", skill.label)
            .put("confidence", skill.confidence)
            .put("source", skill.source)
            .put("verifiedEveryTransition", true)
            .put("steps", stepResults)
    }

    private fun resolveSkillStep(step: CompoundVerifiedSkillStep, goal: String): Pair<String, JSONObject>? {
        if (step.tool !in VERIFIED_SKILL_RUNNABLE_TOOLS) return null
        if (step.tool in setOf("phone.type", "phone.replace_text")) return null
        if (step.tool in setOf("phone.home", "phone.back")) return step.tool to JSONObject()
        if (step.tool == "phone.open_app") {
            val pkg = step.params.optString("package")
            return if (pkg.isBlank()) null else step.tool to JSONObject().put("package", pkg)
        }
        if (step.tool == "phone.scroll") {
            return step.tool to JSONObject().put("direction", step.params.optString("direction", "forward"))
        }
        val query = selectorQuery(step.params).ifBlank { step.label }
        val found = runtime.search(query, goal)
        if (found.failure != null) return null
        val candidate = found.candidates.firstOrNull() ?: return null
        return step.tool to JSONObject().put("elementId", candidate.elementId)
    }

    private fun mutateElement(tool: String, elementId: String, goal: String): JSONObject = synchronized(mutationLock) {
        if (elementId.isBlank()) return@synchronized invalid("elementId is required.")
        actionJson(runtime.act(tool, JSONObject().put("elementId", elementId), goal.ifBlank { tool.removePrefix("phone.").replace('_', ' ') }))
    }

    private fun actionJson(envelope: AgentActionEnvelope): JSONObject {
        val success = envelope.verification.passed && envelope.errorClass == AgentFailureClass.NONE
        return JSONObject()
            .put("success", success)
            .put("tool", envelope.tool.removePrefix("phone."))
            .put("goal", envelope.goal.take(300))
            .put("execution", JSONObject()
                .put("androidExecutionOk", envelope.androidExecutionOk)
                .put("executorReportedOk", envelope.executorReportedOk))
            .put("verification", JSONObject()
                .put("status", envelope.verification.status.name)
                .put("passed", envelope.verification.passed)
                .put("basis", envelope.verification.basis ?: JSONObject.NULL)
                .put("semanticSuccessClaimed", envelope.semanticSuccessClaimed))
            .put("failure", JSONObject()
                .put("errorClass", envelope.errorClass.name)
                .put("failureLayer", envelope.failureLayer.name)
                .put("retryable", envelope.retryable)
                .put("message", envelope.safeMessage ?: JSONObject.NULL))
            .put("before", compactPage(envelope.before))
            .put("after", compactPage(envelope.after))
            .put("delta", JSONObject()
                .put("pageChanged", envelope.delta.pageChanged)
                .put("packageChanged", envelope.delta.packageChanged)
                .put("accessibilityChanged", envelope.delta.accessibilityChanged)
                .put("semanticStateChanges", jsonArray(envelope.delta.semanticStateChanges))
                .put("goalLabelAppeared", envelope.delta.goalLabelAppeared)
                .put("summary", envelope.delta.summary))
            .put("observation", JSONObject()
                .put("beforeId", envelope.beforeObservationId ?: JSONObject.NULL)
                .put("afterId", envelope.afterObservationId ?: JSONObject.NULL)
                .put("generation", envelope.observationGeneration ?: JSONObject.NULL)
                .put("requiresFreshTargetAfterMutation", true))
            .put("learning", JSONObject()
                .put("recorded", envelope.learning.recorded)
                .put("reason", envelope.learning.reason))
            .put("typedPlaintextReturned", false)
    }

    private fun candidateJson(candidate: AgentElementCandidate): JSONObject {
        val e = candidate.evidence
        return JSONObject()
            .put("elementId", candidate.elementId)
            .put("observationId", candidate.observationId)
            .put("label", candidate.label.take(180))
            .put("semanticName", candidate.semanticName.take(140))
            .put("role", candidate.role)
            .put("source", candidate.source)
            .put("relevance", candidate.relevance)
            .put("selected", e.optBoolean("selected"))
            .put("checked", e.optBoolean("checked"))
            .put("focused", e.optBoolean("focused"))
            .put("editable", e.optBoolean("editable"))
            .put("clickable", e.optBoolean("clickable"))
            .put("scrollable", e.optBoolean("scrollable"))
            .put("enabled", e.optBoolean("enabled", true))
            .put("bounds", e.optJSONObject("bounds")?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .put("actions", copyArray(e.optJSONArray("androidActions") ?: e.optJSONArray("actions")))
            .put("expectedEffect", e.opt("expectedEffect") ?: JSONObject.NULL)
    }

    private fun inspectJson(result: AgentInspectResult): JSONObject {
        val e = result.evidence ?: JSONObject()
        return JSONObject()
            .put("elementId", result.elementId)
            .put("observationId", result.observationId ?: JSONObject.NULL)
            .put("generation", result.generation ?: JSONObject.NULL)
            .put("label", e.optString("label").take(180))
            .put("semanticName", e.optString("semanticName").take(140))
            .put("role", e.optString("role"))
            .put("selected", e.optBoolean("selected"))
            .put("checked", e.optBoolean("checked"))
            .put("focused", e.optBoolean("focused"))
            .put("editable", e.optBoolean("editable"))
            .put("clickable", e.optBoolean("clickable"))
            .put("scrollable", e.optBoolean("scrollable"))
            .put("enabled", e.optBoolean("enabled", true))
            .put("bounds", e.optJSONObject("bounds")?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .put("actions", copyArray(e.optJSONArray("androidActions") ?: e.optJSONArray("actions")))
            .put("expectedEffect", e.opt("expectedEffect") ?: JSONObject.NULL)
    }

    private fun previousActionJson(envelope: AgentActionEnvelope): JSONObject = JSONObject()
        .put("tool", envelope.tool.removePrefix("phone."))
        .put("goal", envelope.goal.take(200))
        .put("verification", envelope.verification.status.name)
        .put("verificationBasis", envelope.verification.basis ?: JSONObject.NULL)
        .put("errorClass", envelope.errorClass.name)
        .put("failureLayer", envelope.failureLayer.name)
        .put("retryable", envelope.retryable)
        .put("semanticSuccessClaimed", envelope.semanticSuccessClaimed)
        .put("delta", envelope.delta.summary.take(300))

    private fun skillSummaryJson(skill: CompoundVerifiedSkill): JSONObject = JSONObject()
        .put("skillId", skill.skillId)
        .put("label", skill.label.take(180))
        .put("confidence", skill.confidence)
        .put("source", skill.source)
        .put("goalHints", skill.goalHints.take(220))
        .put("stepCount", skill.steps.size)
        .put("steps", jsonArray(skill.steps.take(8).map { JSONObject().put("tool", it.tool.removePrefix("phone.")).put("label", it.label.take(160)) }))
        .put("authority", "ADVISORY_UNTIL_RUN_SKILL")

    private fun compactPage(page: AgentPageCard?): Any = page?.let {
        JSONObject()
            .put("observationId", it.observationId)
            .put("generation", it.generation)
            .put("package", it.packageName)
            .put("activity", it.activity ?: JSONObject.NULL)
            .put("pageKey", it.pageKey)
            .put("structuralKey", it.structuralKey)
            .put("contentKey", it.contentKey)
            .put("accessibilityFingerprint", it.accessibilityFingerprint)
    } ?: JSONObject.NULL

    private fun advisory(value: JSONObject?): JSONObject = JSONObject()
        .put("authority", "ADVISORY_ONLY")
        .put("evidence", boundedObject(value ?: JSONObject(), KNOWLEDGE_PREVIEW_LIMIT))
        .put("mayAuthorizeAction", false)

    private fun boundedPageText(value: JSONObject): JSONObject {
        val out = JSONObject()
        value.optString("text").takeIf(String::isNotBlank)?.let { out.put("text", it.take(PAGE_TEXT_CHAR_LIMIT)) }
        if (value.has("lineCount")) out.put("lineCount", value.optInt("lineCount"))
        val source = value.optJSONArray("lines") ?: JSONArray()
        val lines = JSONArray()
        for (i in 0 until minOf(source.length(), PAGE_TEXT_LINE_LIMIT)) {
            val item = source.opt(i)
            when (item) {
                is JSONObject -> lines.put(boundedObject(item, 500))
                null, JSONObject.NULL -> Unit
                else -> lines.put(item.toString().take(500))
            }
        }
        out.put("lines", lines)
        out.put("bounded", true)
        return out
    }

    private fun boundedObject(value: JSONObject, maxChars: Int): JSONObject {
        val raw = value.toString()
        if (raw.length <= maxChars) return JSONObject(raw)
        return JSONObject().put("bounded", true).put("preview", raw.take(maxChars)).put("originalChars", raw.length)
    }

    private fun selectorQuery(params: JSONObject): String {
        val selector = params.optJSONObject("selector") ?: params
        return listOf("text", "contentDescription", "fuzzyText", "resourceId", "textContains", "contentDescriptionContains")
            .mapNotNull { selector.optString(it).takeIf(String::isNotBlank) }
            .firstOrNull().orEmpty().substringAfterLast('/').replace('_', ' ')
    }

    private fun appMatchesJson(matches: List<CompoundAppMatch>): JSONArray = jsonArray(matches.map { match ->
        JSONObject()
            .put("label", match.app.label)
            .put("package", match.app.packageName)
            .put("activity", match.app.launcherActivity ?: JSONObject.NULL)
            .put("score", match.score)
            .put("openSuccessCount", match.app.openSuccessCount)
            .put("openFailureCount", match.app.openFailureCount)
    })

    private fun failureJson(failure: AgentFailure): JSONObject = JSONObject()
        .put("success", false)
        .put("errorClass", failure.errorClass.name)
        .put("failureLayer", failure.failureLayer.name)
        .put("retryable", failure.retryable)
        .put("message", failure.message)
        .put("reasonCode", failure.reasonCode ?: JSONObject.NULL)

    private fun errorJson(errorClass: AgentFailureClass, layer: AgentFailureLayer, retryable: Boolean, message: String): JSONObject =
        failureJson(AgentFailure(errorClass, layer, retryable, message))

    private fun invalid(message: String) = errorJson(AgentFailureClass.INVALID_REQUEST, AgentFailureLayer.INPUT, false, message)
    private fun genericObservationFailure() = AgentFailure(AgentFailureClass.EXECUTION_FAILED, AgentFailureLayer.OBSERVATION, true, "Fresh observation was unavailable.")

    private fun strings(array: JSONArray?): List<String> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) array.optString(i).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun copyArray(array: JSONArray?): JSONArray = array?.let { JSONArray(it.toString()) } ?: JSONArray()

    private data class MergedCandidate(var candidate: AgentElementCandidate, val queries: MutableSet<String>)

    private companion object {
        const val MAX_BATCH = 8
        const val PAGE_CONTROL_LIMIT = 24
        const val SUPPLEMENTAL_LIMIT = 8
        const val SEARCH_PER_QUERY_LIMIT = 10
        const val SEARCH_MERGED_LIMIT = 24
        const val APP_MATCH_LIMIT = 6
        const val SKILL_HINT_LIMIT = 6
        const val RECALL_SKILL_LIMIT = 10
        const val PREVIOUS_FAILURE_LIMIT = 5
        const val VISUAL_ANNOTATION_LIMIT = 18
        const val PAGE_TEXT_CHAR_LIMIT = 4_000
        const val PAGE_TEXT_LINE_LIMIT = 20
        const val OBJECT_PREVIEW_LIMIT = 4_000
        const val KNOWLEDGE_PREVIEW_LIMIT = 6_000
    }
}

internal sealed interface AppResolution {
    data class Resolved(val match: CompoundAppMatch) : AppResolution
    data class Ambiguous(val candidates: List<CompoundAppMatch>) : AppResolution
    data class NotFound(val candidates: List<CompoundAppMatch>) : AppResolution
}

internal object CompoundAppResolver {
    fun resolve(query: String, apps: List<CompoundInstalledApp>): AppResolution {
        val ranked = rank(query, apps)
        if (ranked.isEmpty() || ranked.first().score < 0.56) return AppResolution.NotFound(ranked.take(5))
        val top = ranked.first()
        val exacts = ranked.filter { normalize(it.app.label) == normalize(query) }
        if (exacts.size == 1) return AppResolution.Resolved(exacts.first())
        if (exacts.size > 1) return AppResolution.Ambiguous(exacts.take(5))
        val second = ranked.getOrNull(1)
        if (second != null && top.score >= 0.74 && second.score >= 0.70 && top.score - second.score < 0.05) {
            return AppResolution.Ambiguous(ranked.take(5))
        }
        return if (top.score >= 0.70) AppResolution.Resolved(top) else AppResolution.NotFound(ranked.take(5))
    }

    fun rank(query: String, apps: List<CompoundInstalledApp>): List<CompoundAppMatch> {
        val q = normalize(query)
        if (q.isBlank()) return emptyList()
        return apps.mapNotNull { app -> score(q, app)?.let { CompoundAppMatch(app, it) } }
            .sortedWith(compareByDescending<CompoundAppMatch> { it.score }.thenBy { it.app.label.lowercase(Locale.US) }.thenBy { it.app.packageName })
    }

    private fun score(q: String, app: CompoundInstalledApp): Double? {
        val label = normalize(app.label)
        val pkg = normalize(app.packageName.replace('.', ' '))
        if (q == label) return 1.0
        if (q == pkg) return 0.96
        if (label.startsWith(q)) return 0.91
        if (label.contains(q)) return 0.86
        if (pkg.contains(q)) return 0.78
        val qTokens = q.split(' ').filter(String::isNotBlank).distinct()
        if (qTokens.isEmpty()) return null
        val corpus = "$label $pkg"
        val matched = qTokens.count(corpus::contains)
        if (matched == 0) return null
        val ratio = matched.toDouble() / qTokens.size
        return (0.52 + ratio * 0.26).coerceAtMost(0.82)
    }

    private fun normalize(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private val VERIFIED_SKILL_RUNNABLE_TOOLS = setOf("phone.open_app", "phone.click", "phone.long_press", "phone.scroll", "phone.back", "phone.home")

internal object CompoundSkillPolicy {
    fun isRunnableVerifiedEvidence(
        source: String,
        successCount: Int,
        failureCount: Int,
        confidence: Double,
        tool: String,
    ): Boolean = source.contains("VERIFIED_ROUTE") &&
        successCount > failureCount &&
        confidence >= 0.65 &&
        tool in VERIFIED_SKILL_RUNNABLE_TOOLS
}

internal interface CompoundAgentRuntimePort {
    fun observe(goal: String): AgentObservationResult
    fun search(query: String, goal: String): AgentSearchResult
    fun inspect(elementId: String): AgentInspectResult
    fun act(tool: String, params: JSONObject, goal: String): AgentActionEnvelope
    fun history(): List<AgentActionEnvelope>
    fun brainRecall(goal: String): AgentKnowledgeResult
    fun knownRoutes(goal: String): AgentKnowledgeResult
    fun screenshotEvidence(goal: String): CompoundScreenshotEvidence
    fun installedApps(refresh: Boolean): List<CompoundInstalledApp>
    fun verifiedSkills(goal: String): List<CompoundVerifiedSkill>
}

private class AndroidCompoundAgentRuntimePort(
    private val context: Context,
) : CompoundAgentRuntimePort {
    private val environment = CycloneAgentEnvironment(context)

    override fun observe(goal: String) = environment.observe(goal)
    override fun search(query: String, goal: String) = environment.search(query, goal)
    override fun inspect(elementId: String) = environment.inspect(elementId)
    override fun act(tool: String, params: JSONObject, goal: String) = environment.act(tool, params, goal)
    override fun history() = environment.history()
    override fun brainRecall(goal: String) = environment.brainRecall(goal)
    override fun knownRoutes(goal: String) = environment.knownRoutes(goal)

    override fun screenshotEvidence(goal: String): CompoundScreenshotEvidence {
        val result = environment.screenshot(goal)
        val sha = result.filePath?.let { path -> runCatching { sha256(File(path)) }.getOrNull() }
        return CompoundScreenshotEvidence(
            observationId = result.observationId,
            reference = result.filePath,
            sha256 = sha,
            width = result.width,
            height = result.height,
            timestampMs = result.timestampMs,
            failure = result.failure,
        )
    }

    override fun installedApps(refresh: Boolean): List<CompoundInstalledApp> {
        AdaptiveBrainRuntime.initialize(context)
        if (refresh) AdaptiveBrainRuntime.store.refreshAppInventory()
        return AdaptiveBrainRuntime.store.listApps().mapNotNull { app ->
            val launchable = context.packageManager.getLaunchIntentForPackage(app.packageName) != null
            if (!launchable) null else CompoundInstalledApp(
                label = app.label,
                packageName = app.packageName,
                launcherActivity = app.launcherActivity,
                openSuccessCount = app.openSuccessCount,
                openFailureCount = app.openFailureCount,
            )
        }
    }

    override fun verifiedSkills(goal: String): List<CompoundVerifiedSkill> {
        AdaptiveBrainRuntime.initialize(context)
        val micros = AdaptiveBrainRuntime.store.listMicroSkills(500)
            .filter { skill -> CompoundSkillPolicy.isRunnableVerifiedEvidence(
                source = skill.source,
                successCount = skill.successCount,
                failureCount = skill.failureCount,
                confidence = skill.confidence,
                tool = skill.tool,
            ) }
            .associateBy { it.signature }
        val singles = micros.values.map { skill ->
            CompoundVerifiedSkill(
                skillId = "micro:${skill.signature}",
                label = skill.name,
                confidence = skill.confidence,
                source = skill.source,
                goalHints = skill.goalHints,
                steps = listOf(CompoundVerifiedSkillStep(skill.tool, safeParams(skill.paramsJson), skill.name)),
            )
        }
        val paths = AdaptiveBrainRuntime.store.listPaths(200).mapNotNull { path ->
            if (path.successCount <= path.failureCount || path.confidence < 0.70 || path.skillSignatures.isEmpty()) return@mapNotNull null
            val steps = path.skillSignatures.mapNotNull(micros::get)
            if (steps.size != path.skillSignatures.size) return@mapNotNull null
            CompoundVerifiedSkill(
                skillId = "path:${path.signature}",
                label = path.goalKey.ifBlank { "Verified learned path" },
                confidence = path.confidence,
                source = "VERIFIED_PATH",
                goalHints = path.goalKey,
                steps = steps.map { CompoundVerifiedSkillStep(it.tool, safeParams(it.paramsJson), it.name) },
            )
        }
        val q = goal.lowercase(Locale.US)
        return (paths + singles).sortedWith(compareByDescending<CompoundVerifiedSkill> {
            if (q.isNotBlank() && (it.label + " " + it.goalHints).lowercase(Locale.US).contains(q)) 1 else 0
        }.thenByDescending { it.confidence }.thenBy { it.label })
    }

    private fun safeParams(raw: String): JSONObject = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
