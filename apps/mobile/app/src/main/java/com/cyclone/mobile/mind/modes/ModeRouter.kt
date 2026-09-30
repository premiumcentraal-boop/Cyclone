package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.mind.decide.Decider

/** Cyclone's modes (plan 42): the lowest one that can do a request takes it. */
enum class Mode { INSTANT, FLASH, MIND, ANSWER, IGNORE }

/** Settings → Speed. */
enum class Speed(val wire: String, val label: String) {
    /** The router: grammar, then one decision box, then the rules. */
    AUTO("auto", "Auto"),
    /** Instant for clear commands (the grammar), everything else the Mind. The default until the Lab passes. */
    COMMANDS("commands", "Instant for commands"),
    /** Every request is a Mind mission, as before alpha.77. */
    MIND("mind", "Always Mind");

    // Alpha 89: Auto is the default. JEV decides what the grammar doesn't settle; the phone model takes over what it earned.
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: AUTO }
}

/** Where a request goes, with what Instant needs or the short answer to say. */
data class Route(
    val mode: Mode,
    val why: String,
    val command: InstantCommand? = null,
    val answer: String? = null,
    /** Things a decision (or the owner) must choose between: two labels, two apps. */
    val candidates: List<String> = emptyList(),
    /** Alpha 89: who settled it, the decision in its typed shape, how long deciding took, and the phone model's shadow guess. */
    val by: Decider = Decider.RULES,
    val decision: com.cyclone.mobile.mind.decide.Guess? = null,
    val decideMs: Long = 0,
    val shadow: com.cyclone.mobile.mind.decide.Guess? = null,
)

/**
 * The phone model as the router uses it (alpha 89): its guess for every Auto request, and whether it may act on it.
 * It acts only for [earned] actions, only when sure, and not when this request is one of the audits still asked of JEV.
 */
class PhoneDecider(
    val model: com.cyclone.mobile.mind.decide.PhoneModel,
    val earned: Set<String>,
    val mayAct: Boolean,
    val audit: Boolean,
)

/** Facts the phone knows without a model, for short answers. */
data class LocalFacts(val time: String? = null, val date: String? = null, val batteryPercent: Int? = null, val charging: Boolean? = null)

/**
 * What a run carries when it moves up a mode (plan 42 §8), so the next mode continues and never redoes a move.
 */
data class RunBaton(
    val goal: String,
    val modes: List<Mode>,
    val done: List<String>,
    val reason: String,
    val candidates: List<String> = emptyList(),
) {
    /** The opening note the Flash or Mind run reads before anything else. */
    fun note(): String = buildString {
        append("This request started in ${modes.joinToString(" → ") { it.name.lowercase() }} mode")
        if (done.isNotEmpty()) append(", which already did: ${done.joinToString("; ")}")
        append(". It stopped because $reason.")
        if (candidates.isNotEmpty()) append(" Candidates it found: ${candidates.joinToString(", ")}.")
        append(" Continue from the screen as it is now; don't redo what was done.")
    }
}

/**
 * Plan 42 (M3): the router. Every request (Ask bar, Live voice, Drive, Glass, Command Center) comes here first.
 *
 * - **Stage 0, local (≈ 5 ms, no model):** a clear command from the grammar goes to Instant; a question the phone
 *   answers itself (the time, the date, the battery) goes to Answer.
 * - **Rules (code):** composing a message, money, deleting, accounts, several apps or a real question need the Mind;
 *   a second clause needs at least Flash.
 * - **Stage 1, Board 0 (one decision box):** only in Auto, when the grammar can't tell: route, intent and target in
 *   one call.
 * - **Unsure goes up, never down.**
 * Pure.
 */
object ModeRouter {
    private val MIND_WORDS = Regex("(?i)\\b(message|text|sms|whatsapp|email|e-mail|mail|write|tell|reply|answer|post|send|share|" +
        "comment|stuur|schrijf|zeg|vertel|antwoord|reageer|deel|buy|pay|order|book|transfer|betaal|koop|bestel|boek|delete|remove|" +
        "verwijder|wis|login|log in|sign in|sign up|password|wachtwoord|account|inloggen)\\b")
    private val QUESTION = Regex("(?i)^(what|why|how|who|when|where|which|is|are|do|does|can|wat|waarom|hoe|wie|wanneer|waar|welke|is|zijn|heb|kan)\\b")
    private val SECOND_CLAUSE = Regex("(?i)\\b(and then|then|after that|and also|en dan|daarna|en daarna|vervolgens)\\b|\\band\\b|\\ben\\b")
    private val TIME_Q = Regex("(?i)^(what time is it|what's the time|whats the time|what is the time|hoe laat is het|wat is de tijd)\\??$")
    private val DATE_Q = Regex("(?i)^(what day is it|what's the date|whats the date|what is the date|what day is it today|welke dag is het|wat is de datum|welke datum is het)\\??$")
    private val BATTERY_Q = Regex("(?i)^(how much battery( do i have| is left)?|what's my battery|whats my battery|battery( level| percentage)?|hoeveel batterij( heb ik)?|batterij)\\??$")

    /** Stage 0: a clear command or a local answer, or null. */
    fun stage0(text: String, world: GrammarWorld, facts: LocalFacts = LocalFacts()): Route? {
        localAnswer(text, facts)?.let { return Route(Mode.ANSWER, "the phone knows this", answer = it) }
        return when (val g = InstantGrammar.parse(text, world)) {
            is GrammarResult.Match -> Route(Mode.INSTANT, "a clear command", command = g.command)
            is GrammarResult.Ambiguous -> null // Board 0 or a higher mode decides between the candidates.
            GrammarResult.None -> null
        }
    }

    fun localAnswer(text: String, facts: LocalFacts): String? {
        val t = text.trim()
        return when {
            TIME_Q.matches(t) -> facts.time?.let { "It's $it." }
            DATE_Q.matches(t) -> facts.date?.let { "It's $it." }
            BATTERY_Q.matches(t) -> facts.batteryPercent?.let { "$it%" + if (facts.charging == true) ", charging." else "." }
            else -> null
        }
    }

    /** The rules: which requests must go to the Mind (or at least Flash), whatever a box says. */
    fun forced(text: String, world: GrammarWorld): Route? {
        val apps = world.apps.map { it.first }.filter { it.length >= 3 && InstantGrammar.normalize(text).contains(InstantGrammar.normalize(it)) }.distinct()
        return when {
            MIND_WORDS.containsMatchIn(text) -> Route(Mode.MIND, "it writes, pays, deletes or touches an account")
            apps.size >= 2 -> Route(Mode.MIND, "it spans several apps (${apps.joinToString()})")
            QUESTION.containsMatchIn(text.trim()) -> Route(Mode.MIND, "a question to answer")
            else -> null
        }
    }

    /** Board 0: one decision box with three questions. */
    fun board0(text: String, world: GrammarWorld): BoxRequest {
        val labels = world.labels.filter { it.isNotBlank() }.distinct().take(25)
        val apps = world.apps.map { it.first }.distinct()
            .sortedByDescending { app -> InstantGrammar.normalize(text).split(' ').maxOfOrNull { InstantGrammar.similarity(it, app) } ?: 0.0 }
            .take(10)
        val targets = (labels + apps).distinct() + "none"
        val context = buildString {
            appendLine("Request: ${text.take(300)}")
            if (labels.isNotEmpty()) appendLine("On screen: ${labels.joinToString(", ")}")
            if (apps.isNotEmpty()) appendLine("Apps that may be meant: ${apps.joinToString(", ")}")
        }
        return BoxRequest(
            "Route a request to a phone assistant: do it instantly with one tool, plan it quickly (flash), or think (mind).",
            context,
            listOf(
                BoxQuestion("route", "instant: one obvious action on this phone. flash: a few routine steps. mind: needs judgement, " +
                    "writing or a real answer. ignore: not meant for the assistant.", listOf("instant", "flash", "mind", "ignore")),
                BoxQuestion("intent", "For instant only: the one action.", INTENTS.keys.toList()),
                BoxQuestion("target", "For tap or open_app: which on-screen thing or app. Otherwise none.", targets),
            ),
        )
    }

    private val INTENTS = linkedMapOf(
        "swipe_up" to (InstantIntent.SWIPE to "up"), "swipe_down" to (InstantIntent.SWIPE to "down"),
        "swipe_left" to (InstantIntent.SWIPE to "left"), "swipe_right" to (InstantIntent.SWIPE to "right"),
        "scroll_up" to (InstantIntent.SCROLL to "up"), "scroll_down" to (InstantIntent.SCROLL to "down"),
        "back" to (InstantIntent.BACK to null), "home" to (InstantIntent.HOME to null), "recents" to (InstantIntent.RECENTS to null),
        "tap" to (InstantIntent.TAP to null), "open_app" to (InstantIntent.OPEN_APP to null), "camera" to (InstantIntent.CAMERA to null),
        "photo" to (InstantIntent.PHOTO to null), "selfie" to (InstantIntent.SELFIE to null),
        "flashlight_on" to (InstantIntent.FLASHLIGHT to "on"), "flashlight_off" to (InstantIntent.FLASHLIGHT to "off"),
        "volume_up" to (InstantIntent.VOLUME to "up"), "volume_down" to (InstantIntent.VOLUME to "down"),
        "media_play_pause" to (InstantIntent.MEDIA to "play_pause"), "media_next" to (InstantIntent.MEDIA to "next"),
        "none" to null,
    )

    /** Board 0's answers under the rules: Instant only when everything it needs is sure. Unsure goes up. */
    fun fromBoard(reply: BoxReply?, text: String, world: GrammarWorld, bar: Double): Route {
        if (reply == null) return Route(Mode.FLASH, "the router's decision box gave no answer")
        return when (reply.sure("route", bar)) {
            "ignore" -> Route(Mode.IGNORE, "not meant for Cyclone")
            "mind" -> Route(Mode.MIND, "the router judged it needs thinking")
            "instant" -> {
                val (intent, direction) = reply.sure("intent", bar)?.let { INTENTS[it] } ?: return Route(Mode.FLASH, "unsure which action")
                when (intent) {
                    InstantIntent.TAP -> {
                        val label = reply.sure("target", bar)?.takeIf { it in world.labels } ?: return Route(Mode.FLASH, "unsure which control")
                        Route(Mode.INSTANT, "the router picked it", InstantCommand(intent, text, target = label, targetLabel = label))
                    }
                    InstantIntent.OPEN_APP -> {
                        val app = reply.sure("target", bar)?.let { t -> world.apps.firstOrNull { it.first == t } } ?: return Route(Mode.FLASH, "unsure which app")
                        Route(Mode.INSTANT, "the router picked it", InstantCommand(intent, text, target = app.second, targetLabel = app.first))
                    }
                    else -> Route(Mode.INSTANT, "the router picked it", InstantCommand(intent, text, direction = direction))
                }
            }
            else -> Route(Mode.FLASH, "a few routine steps" + if (reply.choice("route") != "flash") " (the router was unsure)" else "")
        }
    }

    /**
     * The whole decision (alpha 89):
     * 1. Speed → Always Mind.
     * 2. Stage 0: the grammar and the phone's own answers (no model, about 5 ms).
     * 3. The rules: writing, money, deleting, accounts, several apps, a real question → the Mind; a second clause → Flash.
     * 4. The phone model, for an action it has earned and is sure of (a few ms, works offline).
     * 5. JEV (the decisions provider) for everything else, within its deadline.
     * Unsure goes up, never down. The phone model's guess rides along on every Auto request as its shadow, so its
     * agreement with JEV is measured all the time.
     */
    fun route(text: String, world: GrammarWorld, facts: LocalFacts, speed: Speed, box: DecisionBox?, bar: Double,
              phone: PhoneDecider? = null): Route {
        if (speed == Speed.MIND) return Route(Mode.MIND, "Speed is set to Always Mind", by = Decider.SETTING, decision = guessOf(Mode.MIND))
        stage0(text, world, facts)?.let { r ->
            return r.copy(by = if (r.mode == Mode.ANSWER) Decider.ANSWER else Decider.GRAMMAR, decision = guessOf(r.mode, r.command))
        }
        forced(text, world)?.let { return it.copy(by = Decider.RULES, decision = guessOf(it.mode)) }
        if (speed == Speed.COMMANDS) return Route(Mode.MIND, "not a clear command", by = Decider.SETTING, decision = guessOf(Mode.MIND))
        if (SECOND_CLAUSE.containsMatchIn(text)) return Route(Mode.FLASH, "more than one step", by = Decider.RULES, decision = guessOf(Mode.FLASH))
        val candidates = world.labels + world.apps.map { it.first }
        val started = System.nanoTime()
        val shadow = phone?.model?.guess(text, candidates)
        val phoneMs = (System.nanoTime() - started) / 1_000_000
        val earnedGuess = if (phone != null && shadow != null && phone.mayAct && shadow.mode == "instant" && shadow.intent in phone.earned &&
            shadow.confidence >= com.cyclone.mobile.mind.decide.Earning.SURE) shadow else null
        // Offline (no box) the earned guess always acts; online, an audit request still goes to JEV to keep agreement measured.
        if (earnedGuess != null && (box == null || phone?.audit != true)) {
            fromGuess(earnedGuess, text, world)?.let { return it.copy(by = Decider.PHONE, decision = earnedGuess, decideMs = phoneMs) }
        }
        if (box == null) return Route(Mode.MIND, "no decision provider is reachable", by = Decider.FALLBACK, decision = guessOf(Mode.MIND), shadow = shadow)
        val reply = box.ask(board0(text, world))
        val route = fromBoard(reply, text, world, bar)
        return route.copy(by = Decider.DECISIONS, decision = reply?.let { decisionOf(it) } ?: guessOf(route.mode), decideMs = reply?.ms ?: 0, shadow = shadow)
    }

    /** The typed answers of a decision box as one decision. */
    fun decisionOf(reply: BoxReply): com.cyclone.mobile.mind.decide.Guess {
        val mode = reply.choice("route") ?: "flash"
        val intent = if (mode == "instant") reply.choice("intent") ?: "none" else "none"
        val target = if (mode == "instant") reply.choice("target")?.takeIf { it != "none" } else null
        val confidence = if (mode == "instant") minOf(reply.confidence("route"), reply.confidence("intent")) else reply.confidence("route")
        return com.cyclone.mobile.mind.decide.Guess(mode, intent, target, confidence)
    }

    /** A route (and its command) in the decision shape, for the lessons. */
    fun guessOf(mode: Mode, command: InstantCommand? = null): com.cyclone.mobile.mind.decide.Guess =
        com.cyclone.mobile.mind.decide.Guess(mode.name.lowercase(), command?.let { keyOf(it) } ?: "none", command?.targetLabel, 1.0)

    /** The action key of a command ("swipe_up", "open_app"), or its intent's name for actions only the grammar reads. */
    fun keyOf(command: InstantCommand): String =
        INTENTS.entries.firstOrNull { (_, v) -> v?.first == command.intent && v.second == command.direction }?.key
            ?: INTENTS.entries.firstOrNull { (_, v) -> v?.first == command.intent && v.second == null }?.key
            ?: command.intent.name.lowercase()

    /** The phone model's guess as a route, when its target is really on this phone. */
    fun fromGuess(g: com.cyclone.mobile.mind.decide.Guess, text: String, world: GrammarWorld): Route? {
        val (intent, direction) = INTENTS[g.intent] ?: return null
        return when (intent) {
            InstantIntent.TAP -> g.target?.takeIf { it in world.labels }?.let {
                Route(Mode.INSTANT, "the phone model knows this one", InstantCommand(intent, text, target = it, targetLabel = it))
            }
            InstantIntent.OPEN_APP -> world.apps.firstOrNull { it.first == g.target }?.let {
                Route(Mode.INSTANT, "the phone model knows this one", InstantCommand(intent, text, target = it.second, targetLabel = it.first))
            }
            else -> Route(Mode.INSTANT, "the phone model knows this one", InstantCommand(intent, text, direction = direction))
        }
    }
}
