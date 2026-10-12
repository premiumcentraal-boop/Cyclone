package com.cyclone.mobile.mind.decide

import android.content.Context
import com.cyclone.mobile.decisions.DecisionsResult
import com.cyclone.mobile.decisions.DecisionsWire
import com.cyclone.mobile.mind.MindMemory
import com.cyclone.mobile.mind.modes.GrammarWorld
import com.cyclone.mobile.mind.modes.ModeSettings
import com.cyclone.mobile.mind.modes.Route
import com.cyclone.mobile.mind.modes.StageSwitch
import com.cyclone.mobile.mind.modes.Triage
import com.cyclone.mobile.mind.modes.WatchBoard
import java.io.File

/**
 * Plan 58 (alpha.123): the watch. After a request is routed, the provider that is not deciding (GPT-6 Luna Decisions
 * while JEV decides) answers the same Board 0, and Triage while it is in shadow, on its own thread. Nothing it says is
 * acted on; it is recorded so the Lab gate can compare. One call per request, skipped while that provider's breaker is
 * open, never for a request that looks like it carries a secret.
 */
object DecisionWatch {
    private const val FILE = "watch.jsonl"
    private val lock = Any()

    fun watch(context: Context, text: String, world: GrammarWorld, route: Route, settings: ModeSettings, bar: Double) {
        if (MindMemory.looksSecret(text)) return
        val active = Decisions.refresh(context)
        val comparing = settings.watch && Decisions.watching() != null
        val provider = (if (comparing) Decisions.watching() else null)
            ?: active.takeIf { settings.triage == StageSwitch.SHADOW } ?: return
        val routedByBoard0 = route.by == Decider.DECISIONS && settings.triage != StageSwitch.ON
        val questions = WatchBoard.questions(text, world, comparing && provider != active, routedByBoard0, settings.triage)
        if (questions.isEmpty()) return
        val key = com.cyclone.mobile.ai.OpenRouterSecretStore.read(context).takeIf { it.isNotBlank() } ?: return
        val app = context.applicationContext
        Thread({
            runCatching {
                val atMs = System.currentTimeMillis()
                val body = DecisionsWire.body(provider.model, Triage.state(text, world), questions, zdr = settings.strictPrivacy)
                val result = Decisions.call(key, provider, body, "Cyclone Watch", "watch", Decisions.WATCH_DEADLINE_MS)
                val reply = (result as? DecisionsResult.Ok)?.let { DecisionsWire.parse(it.body, questions) }
                val failure = (result as? DecisionsResult.Failed)?.failure?.wire ?: if (result is DecisionsResult.Ok && reply == null) "unreadable" else null
                save(app, WatchBoard.record(atMs, provider.wire, result?.ms ?: 0, route, reply, failure, text, world, bar, questions))
            }
        }, "cyclone-decision-watch").start()
    }

    fun records(context: Context): List<WatchRecord> = synchronized(lock) {
        WatchLog.decode(file(context).takeIf { it.isFile }?.readText())
    }

    fun forget(context: Context) = synchronized(lock) { runCatching { file(context).delete() } }

    private fun save(context: Context, record: WatchRecord) = synchronized(lock) {
        runCatching {
            val target = file(context)
            target.parentFile?.mkdirs()
            val kept = WatchLog.decode(target.takeIf { it.isFile }?.readText()) + record
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(WatchLog.encode(kept))
            temp.renameTo(target)
        }
    }

    private fun file(context: Context) = File(File(context.applicationContext.filesDir, "decide"), FILE)
}
