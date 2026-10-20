package com.cyclone.mobile.mind.lab

import android.content.Context
import com.cyclone.mobile.decisions.DecisionsResult
import com.cyclone.mobile.decisions.DecisionsWire
import com.cyclone.mobile.mind.decide.DecisionProvider
import com.cyclone.mobile.mind.decide.Decisions
import com.cyclone.mobile.mind.modes.Triage
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plan 58 §11.3 (alpha.123): the decisions Lab on the phone. Sends the golden set through one provider's Triage board,
 * four at a time, on the fixed synthetic phone ([GoldenSet.WORLD]), and keeps the report. Nothing on the phone is read
 * or moved: it only asks questions about labelled sentences. Started from Settings → Speed; the report reaches Glass
 * with the decision numbers.
 */
object DecisionLab {
    private const val FILE = "lab.json"
    private const val PARALLEL = 4
    private val running = AtomicBoolean(false)

    fun busy(): Boolean = running.get()

    /** Starts a run for [provider]; false when one is already running or there is no key. [done] gets the report. */
    fun start(context: Context, provider: DecisionProvider, done: (JSONObject?) -> Unit = {}): Boolean {
        val key = com.cyclone.mobile.ai.OpenRouterSecretStore.read(context).takeIf { it.isNotBlank() } ?: return false
        if (!running.compareAndSet(false, true)) return false
        val app = context.applicationContext
        Decisions.refresh(app)
        Thread({
            val report = runCatching { run(app, key, provider) }.getOrNull()
            running.set(false)
            done(report)
        }, "cyclone-decision-lab").start()
        return true
    }

    /**
     * Plan 75 (alpha 145): every usable provider on the same golden set, one after the other, then the comparison the
     * step board depends on: how often the two agree, how often they're right when they do, and who is right when they
     * split. False when a run is already going or there is no key. [done] gets the comparison.
     */
    fun startAll(context: Context, done: (JSONObject?) -> Unit = {}): Boolean {
        val key = com.cyclone.mobile.ai.OpenRouterSecretStore.read(context).takeIf { it.isNotBlank() } ?: return false
        val providers = DecisionProvider.entries.filter { it.usable }
        if (providers.size < 2 || !running.compareAndSet(false, true)) return false
        val app = context.applicationContext
        Decisions.refresh(app)
        Thread({
            val report = runCatching {
                val runs = providers.map { p -> p to samples(app, key, p) }
                runs.forEach { (p, list) -> save(app, p.wire, DecisionLabReport.build(p.wire, p.model, System.currentTimeMillis(), list)) }
                DecisionLabReport.pair(runs[0].first.wire, runs[0].second, runs[1].first.wire, runs[1].second, System.currentTimeMillis())
                    .also { save(app, BOTH, it) }
            }.getOrNull()
            running.set(false)
            done(report)
        }, "cyclone-decision-lab").start()
        return true
    }

    private fun run(context: Context, key: String, provider: DecisionProvider): JSONObject {
        val report = DecisionLabReport.build(provider.wire, provider.model, System.currentTimeMillis(), samples(context, key, provider))
        save(context, provider.wire, report)
        return report
    }

    private fun samples(context: Context, key: String, provider: DecisionProvider): List<LabSample> {
        val requests = GoldenSet.parse(context.assets.open(GoldenSet.ASSET).bufferedReader().use { it.readText() })
            .filter { it.tag !in GoldenSet.ON_PHONE_TAGS && it.rung != "answer" }
        val pool = Executors.newFixedThreadPool(PARALLEL)
        return try {
            pool.invokeAll(requests.map { r -> Callable { ask(key, provider, r) } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun ask(key: String, provider: DecisionProvider, request: GoldenRequest): LabSample {
        val world = GoldenSet.WORLD
        val questions = Triage.questions(world, request.text)
        val body = runCatching { DecisionsWire.body(provider.model, Triage.state(request.text, world), questions) }.getOrNull()
            ?: return LabSample(request, null, 0, "rejected")
        return when (val result = Decisions.call(key, provider, body, "Cyclone Lab", "lab", Decisions.WATCH_DEADLINE_MS)) {
            null -> LabSample(request, null, 0, "skipped")
            is DecisionsResult.Failed -> LabSample(request, null, result.ms, result.failure.wire)
            is DecisionsResult.Ok -> {
                val reply = DecisionsWire.parse(result.body, questions) ?: return LabSample(request, null, result.ms, "unreadable")
                val reading = Triage.read(reply)
                val rung = Triage.route(reading, request.text, world, BAR).mode.name.lowercase()
                val cost = runCatching { JSONObject(result.body).optJSONObject("usage")?.optDouble("cost", Double.NaN)?.takeIf { !it.isNaN() } }.getOrNull()
                LabSample(request, rung, result.ms, null, cost, reading.capability?.takeIf { it != "none" }, reading.capabilityConfidence)
            }
        }
    }

    /** The last report per provider ({"jev": {...}, "openai": {...}}), and the last comparison of both ("both"). */
    fun reports(context: Context): JSONObject = runCatching {
        JSONObject(file(context).takeIf { it.isFile }?.readText() ?: "{}")
    }.getOrDefault(JSONObject())

    private fun save(context: Context, name: String, report: JSONObject) = synchronized(this) {
        runCatching {
            val all = reports(context).put(name, report)
            val target = file(context)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(all.toString())
            temp.renameTo(target)
        }
    }

    private fun file(context: Context) = File(File(context.applicationContext.filesDir, "decide"), FILE)

    const val BOTH = "both"

    /** The Lab scores at the router's careful bar. */
    private const val BAR = 0.9
}
