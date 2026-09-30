package com.cyclone.mobile.mind.modes

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Settings → Speed (plan 42), stored on this phone only. */
data class ModeSettings(
    val speed: Speed = Speed.COMMANDS,
    /** Live voice: seconds the mic stays open after a quick action, for a follow-up or the rest of a sentence. */
    val keepListeningSeconds: Int = 8,
    /** Live voice: a quick action that worked is confirmed with a sound and a buzz, not words. */
    val silentSuccess: Boolean = true,
)

/** What happened to a request, for the surface that asked (Live voice speaks it; the Ask bar shows it). */
data class ModeResult(
    val mode: Mode,
    /** Short words for the owner, or null when a sound is enough (silent success). */
    val say: String?,
    val ok: Boolean,
    /** The run went on as a Flash or Mind mission. */
    val promoted: Boolean = false,
    /** Alpha.78: what the run did, stage by stage with its time ("route instant swipe 2 ms", "swipe 640 ms ok"). */
    val steps: List<String> = emptyList(),
)

/**
 * Plan 42 (M3): the one entry for requests. The router picks the lowest mode that can do it; Instant runs here; Flash
 * and Mind are Mind missions (Flash is a quick one that plans for the Pilot), started with the baton when Instant
 * handed over. Only [handle] starts a mission for a request; surfaces never call MindMissions.start themselves.
 */
object CycloneModes {
    private const val PREFS = "cyclone_modes"

    fun settings(context: Context): ModeSettings {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ModeSettings(Speed.of(p.getString("speed", null)), p.getInt("keep_listening_s", 8).coerceIn(0, 20),
            p.getBoolean("silent_success", true))
    }

    fun save(context: Context, settings: ModeSettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("speed", settings.speed.wire).putInt("keep_listening_s", settings.keepListeningSeconds.coerceIn(0, 20))
            .putBoolean("silent_success", settings.silentSuccess).apply()
    }

    @Volatile private var current: Running? = null

    private class Running(val id: String) : OverlayChromeRuntime.MissionHooks {
        @Volatile var stopped = false
        override fun stop() { stopped = true }
        override fun ownerText(text: String): Boolean {
            if (!CANCEL.matches(text.trim())) return false
            stopped = true
            return true
        }
    }

    /** True while an Instant action runs. */
    fun busy(): Boolean = current != null

    /** Stops a running Instant action (a new request, the owner's stop, "no" in Live). */
    fun cancel() { current?.stopped = true }

    /**
     * Routes [request] and carries it out. Returns at once; the work runs on its own thread and [done] is called with
     * the result. A request while a mission runs goes to that mission, as before.
     */
    fun handle(context: Context, request: String, attachment: com.cyclone.mobile.ui.overlay.TaskAttachment? = null,
               voice: Boolean = false, done: (ModeResult) -> Unit = {}) {
        val app = context.applicationContext
        if (MindMissions.live.value != null) {
            if (!MindMissions.start(app, request, attachment)) MindMissions.offer(app, request)
            done(ModeResult(Mode.MIND, null, true, promoted = true))
            return
        }
        cancel()
        val settings = settings(app)
        // An attachment is for reading, so it always goes to the Mind.
        if (attachment != null || settings.speed == Speed.MIND) {
            mission(app, request, attachment, Mode.MIND, null, done)
            return
        }
        Thread({ run(app, request, settings, voice, done) }, "cyclone-modes").start()
    }

    private fun run(context: Context, request: String, settings: ModeSettings, voice: Boolean, reply: (ModeResult) -> Unit) {
        val running = Running("instant-${UUID.randomUUID()}")
        current = running
        OverlayChromeRuntime.attachMission(running)
        // Alpha.78: every run is timed and logged to the run history, stage by stage.
        val trace = ModesTrace(context, request, voice)
        val done: (ModeResult) -> Unit = { result -> trace.finish(result); reply(result.copy(steps = trace.steps)) }
        // Voice keeps the screen (alpha.78): its own panel shows the work and speaks the result, so the Ask chrome stays out.
        val chrome = !voice
        val device = com.cyclone.mobile.mind.mission.AndroidMindDevice(context)
        var confirmWindow: (String, Long) -> Boolean = { _, _ -> false }
        val hands = AndroidInstantHands(context, request, device, { text, ms -> confirmWindow(text, ms) }, { running.stopped })
        var handedOver = false
        try {
            val fast = com.cyclone.mobile.mind.pilot.FastMode.settings(context)
            // Alpha.78: decisions go to the one decision provider (JEV, text only, until OpenAI Decisions is live).
            val box = if (settings.speed == Speed.AUTO) com.cyclone.mobile.mind.decide.Decisions.box(context) else null
            val blocker = device.blocker()
            // The screen is read only when the command needs its labels ("tap Pokémon GO") or Auto may ask a box; a
            // gesture or an app to open reads it once, right before the move.
            val screen = if (blocker == null && (box != null || InstantGrammar.needsScreen(request))) trace.time("read the screen") { hands.look() } else null
            val world = GrammarWorld(screen?.labels.orEmpty(), device.apps().map { it.label to it.packageName })
            val route = trace.time("route") { ModeRouter.route(request, world, facts(context), settings.speed, box, fast.sureness.bar) }
            trace.step("routed to ${route.mode.name.lowercase()}" + (route.command?.let { " (${it.intent.name.lowercase()}${it.direction?.let { d -> " $d" }.orEmpty()})" }.orEmpty()) + ": ${route.why}")
            when (route.mode) {
                Mode.IGNORE -> done(ModeResult(Mode.IGNORE, null, true))
                Mode.ANSWER -> {
                    if (chrome) {
                        OverlayChromeRuntime.missionWorking(running.id, route.answer)
                        OverlayChromeRuntime.missionFinished(running.id, true, route.answer.orEmpty())
                    }
                    done(ModeResult(Mode.ANSWER, route.answer, true))
                }
                Mode.FLASH, Mode.MIND -> { handedOver = true; mission(context, request, null, route.mode, null, done) }
                Mode.INSTANT -> {
                    val command = route.command ?: return mission(context, request, null, Mode.FLASH, null, done).also { handedOver = true }
                    blocker?.let { why ->
                        handedOver = true
                        trace.step("handed to the Mind: $why")
                        return mission(context, request, null, Mode.MIND, RunBaton(request, listOf(Mode.INSTANT), emptyList(), why), done)
                    }
                    if (chrome) OverlayChromeRuntime.missionWorking(running.id, InstantCopy.working(command))
                    confirmWindow = { text, ms -> window(running, text, ms, chrome) }
                    val outcome = trace.time(InstantCopy.working(command)) { InstantRun.run(command, hands, box, fast.sureness.bar) }
                    val promotion = outcome.promotion
                    when {
                        // Stopped by the owner (Stop, "no", or a newer request): nothing more happens.
                        running.stopped && !outcome.cancelled -> {
                            trace.step("stopped by the owner")
                            if (chrome) OverlayChromeRuntime.missionFinished(running.id, false, "Stopped")
                            done(ModeResult(Mode.INSTANT, null, true))
                        }
                        promotion != null -> {
                            handedOver = true
                            trace.step("handed up to ${promotion.to.name.lowercase()}: ${promotion.reason}")
                            mission(context, request, null, promotion.to, outcome.baton(request), done)
                        }
                        outcome.cancelled -> {
                            if (chrome) OverlayChromeRuntime.missionFinished(running.id, true, "Cancelled")
                            done(ModeResult(Mode.INSTANT, "Cancelled.", true))
                        }
                        else -> {
                            if (chrome) OverlayChromeRuntime.missionFinished(running.id, true, InstantCopy.done(outcome))
                            done(ModeResult(Mode.INSTANT, if (voice && settings.silentSuccess) null else InstantCopy.done(outcome), true))
                        }
                    }
                }
            }
        } catch (error: Exception) {
            trace.step("error: ${error.javaClass.simpleName}")
            // Never lose a request: anything unexpected goes to the Mind.
            if (!handedOver) {
                handedOver = true
                mission(context, request, null, Mode.MIND, null, done)
            }
        } finally {
            OverlayChromeRuntime.detachMission(running)
            if (current === running) current = null
        }
    }

    private fun mission(context: Context, request: String, attachment: com.cyclone.mobile.ui.overlay.TaskAttachment?, mode: Mode,
                        baton: RunBaton?, done: (ModeResult) -> Unit) {
        current?.let { OverlayChromeRuntime.detachMission(it) }
        val started = MindMissions.start(context, request, attachment, handover = baton?.note(), flash = mode == Mode.FLASH)
        if (!started) MindMissions.offer(context, request)
        done(ModeResult(if (mode == Mode.FLASH) Mode.FLASH else Mode.MIND, null, true, promoted = true))
    }

    /** "Calling Mam in 2 s": the owner can stop it (the Stop button or "no"); silence lets it go ahead. */
    private fun window(running: Running, text: String, ms: Long, chrome: Boolean): Boolean {
        if (chrome) OverlayChromeRuntime.missionWorking(running.id, "$text in ${ms / 1000} s. Tap Stop to cancel.")
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (running.stopped) return false
            Thread.sleep(50)
        }
        return !running.stopped
    }

    private fun facts(context: Context): LocalFacts {
        val now = Date()
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return LocalFacts(
            time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
            date = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now),
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    private val CANCEL = Regex("(?i)^(no|stop|cancel|wait|nee|stop maar|annuleer|wacht)[.!]?$")
}
