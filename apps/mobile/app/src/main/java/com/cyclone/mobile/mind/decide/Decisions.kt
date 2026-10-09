package com.cyclone.mobile.mind.decide

import android.content.Context
import com.cyclone.mobile.decisions.DecisionBreaker
import com.cyclone.mobile.decisions.DecisionsHttp
import com.cyclone.mobile.decisions.DecisionsResult
import com.cyclone.mobile.decisions.DecisionsWire
import com.cyclone.mobile.mind.modes.BoxReply
import com.cyclone.mobile.mind.modes.BoxRequest
import com.cyclone.mobile.mind.modes.BoxWire
import com.cyclone.mobile.mind.modes.DecisionBox
import org.json.JSONObject
import java.io.File

/**
 * Who answers Cyclone's typed decisions (plans 41, 42 and 58): the modes router's decision box, the Pilot's decision
 * route and the watch. One port, one setting, so the provider changes in one place.
 *
 * - [JEV]: TypeSafe's decision model. **Text only:** it never gets a screenshot, so none is taken for it.
 * - [LUNA]: GPT-6 Luna Decisions (OpenAI's Decisions API on OpenRouter, public beta since 2026-10-06). Reads text and
 *   images.
 *
 * Both go to OpenRouter's Decisions endpoint with the same request shape (`decisions/DecisionsWire`). Plan 58: the
 * provider is a setting (Settings → Speed → Advanced, default [DEFAULT]), so switching to Luna after the Lab's numbers
 * is a flip, not a build. While one provider decides, the other can answer the same questions beside it (the watch,
 * `DecisionWatch`), never acting.
 */
enum class DecisionProvider(
    val label: String,
    val model: String,
    val endpoint: String?,
    /** Can it read a screenshot? A provider without vision never receives one. */
    val vision: Boolean,
    /** False when it must never be picked. */
    val live: Boolean,
    /** Its name in settings and in the stats. */
    val wire: String,
) {
    JEV("JEV (TypeSafe)", "~typesafe/jev-latest", DecisionsWire.ENDPOINT, vision = false, live = true, wire = "jev"),
    LUNA("GPT-6 Luna Decisions", "openai/gpt-6-luna-decisions", DecisionsWire.ENDPOINT, vision = true, live = true, wire = "luna");

    val usable: Boolean get() = live && endpoint != null && model.isNotBlank()

    companion object { fun of(wire: String?): DecisionProvider? = entries.firstOrNull { it.wire == wire } }
}

object Decisions {
    /** The provider until the owner (or the Lab's gate) picks another. */
    val DEFAULT: DecisionProvider = DecisionProvider.JEV

    @Volatile private var chosen: DecisionProvider = DEFAULT
    @Volatile private var dir: File? = null
    private val breakers = DecisionProvider.entries.associateWith { DecisionBreaker() }

    /** The provider that decides: the chosen one when usable, otherwise [DEFAULT]. */
    fun active(): DecisionProvider = chosen.takeIf { it.usable } ?: DEFAULT

    /** Re-reads the setting (cheap: one preference). Every entry point with a context calls it. */
    fun refresh(context: Context): DecisionProvider {
        dir = File(context.applicationContext.filesDir, "decide")
        chosen = com.cyclone.mobile.mind.modes.CycloneModes.settings(context).provider
        return active()
    }

    /** The provider that answers beside [active] in the watch, or null. */
    fun watching(): DecisionProvider? = DecisionProvider.entries.firstOrNull { it != active() && it.usable }

    fun breaker(provider: DecisionProvider): DecisionBreaker = breakers.getValue(provider)

    /** The decision box for this phone, or null without an OpenRouter key (the router then uses only grammar and rules). */
    fun box(context: Context): DecisionBox? {
        val provider = refresh(context)
        val key = com.cyclone.mobile.ai.OpenRouterSecretStore.read(context).takeIf { it.isNotBlank() } ?: return null
        val settings = com.cyclone.mobile.mind.modes.CycloneModes.settings(context)
        return ProviderDecisionBox(key, provider, ROUTING_DEADLINE_MS, board = "board0", zdr = settings.strictPrivacy)
    }

    /** Plan 58: the Triage board on the active provider, when it is switched on; otherwise null. */
    fun triage(context: Context): com.cyclone.mobile.mind.modes.TypedBox? {
        val settings = com.cyclone.mobile.mind.modes.CycloneModes.settings(context)
        if (settings.triage != com.cyclone.mobile.mind.modes.StageSwitch.ON) return null
        val key = com.cyclone.mobile.ai.OpenRouterSecretStore.read(context).takeIf { it.isNotBlank() } ?: return null
        return ProviderTypedBox(key, refresh(context), TRIAGE_DEADLINE_MS, "triage", settings.strictPrivacy)
    }

    /**
     * One decision call: a short deadline (a slow answer is no answer and the caller goes one mode up), nothing logged
     * but the call's numbers, the key only in the Authorization header. While the provider's breaker is open no call
     * is made at all.
     */
    fun call(key: String, provider: DecisionProvider, body: JSONObject, title: String, board: String,
             deadlineMs: Long = 6_000): DecisionsResult? {
        val url = provider.endpoint ?: return null
        val breaker = breaker(provider)
        if (breaker.open()) return null
        val result = DecisionsHttp.post(key, body, title, deadlineMs, url)
        breaker.record(result)
        log(CallRecord.of(System.currentTimeMillis(), provider.wire, board, result))
        return result
    }

    /** The call log's numbers for the stats (counts, times, failures and cost; no request text). */
    fun calls(): List<CallRecord> = synchronized(this) { CallLog.decode(dir?.let { File(it, CALLS) }?.takeIf { it.isFile }?.readText()) }

    /** The log is written off the caller's thread: a decision on the routing path never waits for the disk. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "cyclone-decision-log").apply { isDaemon = true } }

    private fun log(record: CallRecord) {
        val folder = dir ?: return
        runCatching { writer.execute { write(folder, record) } }
    }

    private fun write(folder: File, record: CallRecord) {
        synchronized(this) {
            runCatching {
                folder.mkdirs()
                val file = File(folder, CALLS)
                val kept = CallLog.decode(file.takeIf { it.isFile }?.readText()) + record
                val temp = File(folder, "$CALLS.tmp")
                temp.writeText(CallLog.encode(kept))
                temp.renameTo(file)
            }
        }
    }

    /**
     * Alpha 89: how long routing waits for the decision. A slower answer is no answer: the request goes one mode up
     * (Flash), so a slow network never stalls a command.
     */
    const val ROUTING_DEADLINE_MS = 2_500L

    /** Plan 58 §4: Triage must answer within 1.2 s, or the request goes one rung up. */
    const val TRIAGE_DEADLINE_MS = 1_200L

    /** The watch never makes anyone wait, so it may take longer: it measures, it doesn't decide. */
    const val WATCH_DEADLINE_MS = 6_000L

    private const val CALLS = "calls.jsonl"
}

/** The decision box over a [DecisionProvider]. A screenshot is dropped for a provider without vision. */
class ProviderDecisionBox(
    private val key: String,
    private val provider: DecisionProvider,
    private val deadlineMs: Long = 6_000,
    private val board: String = "box",
    private val zdr: Boolean = false,
) : DecisionBox {
    override val sees: Boolean get() = provider.vision

    override fun ask(request: BoxRequest): BoxReply? {
        val body = runCatching { body(provider, request, zdr) }.getOrNull() ?: return null
        // The router's Board 0 and Instant's follow-up questions are counted apart.
        val name = if (request.questions.any { it.id == "route" }) board else "instant"
        val result = Decisions.call(key, provider, body, "Cyclone Modes", name, deadlineMs) as? DecisionsResult.Ok ?: return null
        return BoxWire.parseDecisions(result.body, request)?.copy(ms = result.ms)
    }

    companion object {
        /** The request as [provider] gets it: the decisions shape, and never an image it can't read. Pure. */
        fun body(provider: DecisionProvider, request: BoxRequest, zdr: Boolean = false): JSONObject =
            BoxWire.decisionsBody(provider.model, if (provider.vision) request else request.copy(image = null), zdr)
    }
}

/** Plan 58: typed questions (Triage) over a [DecisionProvider]. Images go only to a provider with vision. */
class ProviderTypedBox(
    private val key: String,
    private val provider: DecisionProvider,
    private val deadlineMs: Long,
    private val board: String,
    private val zdr: Boolean = false,
) : com.cyclone.mobile.mind.modes.TypedBox {
    override fun ask(state: List<com.cyclone.mobile.decisions.DPart>,
                     questions: Map<String, com.cyclone.mobile.decisions.DQuestion>): com.cyclone.mobile.mind.modes.TypedAnswer? {
        val parts = if (provider.vision) state else state.filterNot { it is com.cyclone.mobile.decisions.DPart.Image }
        val body = runCatching { DecisionsWire.body(provider.model, parts, questions, zdr = zdr) }.getOrNull() ?: return null
        val result = Decisions.call(key, provider, body, "Cyclone Modes", board, deadlineMs) as? DecisionsResult.Ok ?: return null
        val reply = DecisionsWire.parse(result.body, questions) ?: return null
        return com.cyclone.mobile.mind.modes.TypedAnswer(reply, result.ms)
    }
}
