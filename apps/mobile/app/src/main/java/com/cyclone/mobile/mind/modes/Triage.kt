package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.decisions.DPart
import com.cyclone.mobile.decisions.DQuestion
import com.cyclone.mobile.decisions.DReply
import com.cyclone.mobile.mind.decide.Guess

/**
 * Plan 58 §5 (alpha.123): the Triage board. One Decisions call asks every independent question about a request at
 * once: how hard it is (a score), which action it means (a choice), the risk flags (yes/no), whether it needs the screen,
 * whether it is for later, and the slot plan for short chains. Code combines the answers in [route]; the model never
 * decides alone what is safe, and unsure always goes up.
 *
 * In alpha.123 Triage ships in shadow: it is asked beside the acting decision and recorded, never acted on, until the
 * Lab gate passes. Pure.
 */
object Triage {
    /** The step kinds of the slot plan (plan 58 §5B.1). */
    val STEP_KINDS: List<String> = listOf("open_app", "go_to", "find", "tap", "open_item", "back", "toggle", "read", "none")
    const val MAX_STEPS = 4

    /** The risk flags that put an approval in front of a move. Their bar is low on purpose: missing one is expensive. */
    val RISKS: List<String> = listOf("sends_or_posts", "money", "destroys", "account")
    const val RISK_BAR = 0.3

    private val DIFFICULTY = DQuestion.Score(
        "How much work is this request for a phone assistant?",
        listOf(
            "One obvious action on this phone: open an app or the camera, a gesture, the volume, the flashlight, media keys.",
            "A few routine steps in one app, with nothing to write and nothing to choose.",
            "Several apps, or reading the screen and choosing between things.",
            "Writing words, judgement, personal context (which account, which person), or a long task.",
        ),
    )

    private fun flag(instructions: String, yes: String, no: String) = DQuestion.Noul(instructions, yes, no)

    /** Every Triage question, all independent of each other, so one call answers them all. */
    fun questions(world: GrammarWorld, text: String): Map<String, DQuestion> = linkedMapOf<String, DQuestion>().apply {
        put("difficulty", DIFFICULTY)
        put("capability", DQuestion.Choice("If one single action on the phone does the whole request, which one? Otherwise none.",
            ModeRouter.INTENTS.keys.filter { it != "none" }.associateWith { ACTION_GUIDANCE[it].orEmpty() } + ("none" to
                "No single action does it: it needs several steps, writing, an answer, or it is not a phone action.")))
        put("target", DQuestion.Choice("For tap or open_app: which on-screen thing or app. Otherwise none.", targets(world, text).associateWith { "" }))
        put("is_for_cyclone", flag("Is this meant for the phone assistant?",
            "An instruction or a question for the assistant.", "Chatter, thanks, or words said to someone else."))
        put("answer_only", flag("Does it only ask for information, with nothing to do on the phone?",
            "It asks a question or wants to know something.", "It asks for something to be done on the phone."))
        put("sends_or_posts", flag("Does it send, post, share, reply to or call anyone?",
            "Something leaves the phone to another person or a public place.", "Nothing is sent to anyone."))
        put("money", flag("Does it buy, pay, order, book or transfer money?", "Money or a purchase is involved.", "No money is involved."))
        put("destroys", flag("Does it delete, remove, uninstall, reset or clear anything?", "Something would be deleted or lost.", "Nothing is deleted."))
        put("account", flag("Does it sign in, sign out, or change an account, a password or security setting?",
            "An account, a sign-in or a password is involved.", "No account or security change."))
        put("writes_text", flag("Must the assistant compose words (a message, a post, a note, a search it must word)?",
            "The assistant has to write text.", "Nothing has to be written, or the exact words are given."))
        put("multi_app", flag("Does it need more than one app?", "Two or more apps are needed.", "One app, or none, is enough."))
        put("needs_screen", flag("Does it refer to what is on the screen now (this, that, the blue one)?",
            "It points at something on the screen.", "It can be understood without seeing the screen."))
        put("later", flag("Is it for a later time or on a condition?",
            "It says when or on what condition (in 10 minutes, tonight, when I get home).", "It is for now."))
        for (i in 1..MAX_STEPS) put("step_$i", DQuestion.Choice(
            "Split the request into its steps on the phone. Which kind is step $i? none when there is no step $i.",
            STEP_KINDS.associateWith { STEP_GUIDANCE[it].orEmpty() }))
    }

    /** The state: the request, then what the phone can see and offer (app words only). */
    fun state(text: String, world: GrammarWorld): List<DPart> = listOf(
        DPart.Text("Triage one request to a phone assistant that operates this Android phone."),
        DPart.Text(BoxWire.clean(buildString {
            appendLine("Request: ${text.take(300)}")
            val labels = world.labels.filter { it.isNotBlank() }.distinct().take(25)
            if (labels.isNotEmpty()) appendLine("On screen: ${labels.joinToString(", ")}")
            val apps = rankedApps(world, text)
            if (apps.isNotEmpty()) appendLine("Apps that may be meant: ${apps.joinToString(", ")}")
        }.trimEnd())),
    )

    private fun rankedApps(world: GrammarWorld, text: String): List<String> {
        val words = InstantGrammar.normalize(text).split(' ')
        return world.apps.map { it.first }.distinct()
            .sortedByDescending { app -> words.maxOfOrNull { InstantGrammar.similarity(it, app) } ?: 0.0 }.take(10)
    }

    fun targets(world: GrammarWorld, text: String): List<String> =
        (world.labels.filter { it.isNotBlank() }.distinct().take(25) + rankedApps(world, text)).distinct().filter { it != "none" } + "none"

    /** The answers in one shape, for routing, the watch record and the Lab. */
    data class Reading(
        val difficulty: Double?,
        val difficultyConfidence: Double,
        val capability: String?,
        val capabilityConfidence: Double,
        val target: String?,
        val targetConfidence: Double,
        val flags: Map<String, Double>,
        val steps: List<String>,
        val refused: Set<String>,
    ) {
        fun flag(name: String): Double = flags[name] ?: 0.0
        val risky: Boolean get() = RISKS.any { flag(it) >= RISK_BAR }
        /** Flags at or above their bar, for the record. */
        fun raised(): List<String> = flags.filter { (k, v) -> v >= if (k in RISKS) RISK_BAR else 0.5 }.keys.sorted()
    }

    fun read(reply: DReply): Reading {
        val d = reply.score("difficulty")
        val cap = reply.choice("capability")
        val target = reply.choice("target")
        val flags = FLAGS.mapNotNull { name -> reply.noul(name)?.let { name to it.yes } }.toMap()
        val steps = (1..MAX_STEPS).map { reply.choice("step_$it")?.choice }.takeWhile { it != null && it != "none" }.filterNotNull()
        return Reading(d?.score, d?.confidence ?: 0.0, cap?.choice, cap?.confidence ?: 0.0, target?.choice?.takeIf { it != "none" },
            target?.confidence ?: 0.0, flags, steps, reply.answers.filterValues { it == com.cyclone.mobile.decisions.DAnswer.Refusal }.keys)
    }

    private val FLAGS = listOf("is_for_cyclone", "answer_only", "writes_text", "multi_app", "needs_screen", "later") + RISKS

    /**
     * Plan 58 §5: the answers combined into a route. Instant only when the request is easy, the action is sure, nothing
     * is risky and one app is enough; writing, judgement or "later" go to the Mind; unsure goes up, never down.
     */
    fun route(r: Reading, text: String, world: GrammarWorld, bar: Double): Route {
        if (r.flags["is_for_cyclone"]?.let { it <= 0.2 } == true) return Route(Mode.IGNORE, "triage: not meant for Cyclone")
        val difficulty = r.difficulty ?: return Route(Mode.FLASH, "triage gave no difficulty")
        // A wide spread is unsure: it counts as harder.
        val effective = difficulty + if (r.difficultyConfidence < 0.6) 0.5 else 0.0
        if (r.flag("answer_only") >= 0.8 && (r.capability == null || r.capability == "none")) return Route(Mode.MIND, "triage: a question to answer")
        if (r.flag("writes_text") >= 0.5) return Route(Mode.MIND, "triage: it needs words written")
        if (effective >= 2.5) return Route(Mode.MIND, "triage: it needs judgement or personal context")
        if (r.flag("later") >= 0.5) return Route(Mode.MIND, "triage: it is for later or on a condition")
        if (effective < 0.6 && !r.risky && r.flag("multi_app") < 0.5 && r.capability != null && r.capability != "none" &&
            r.capabilityConfidence >= bar) {
            instant(r, text, world, bar)?.let { return it }
        }
        if (effective < 1.6) return Route(Mode.FLASH, "triage: a few routine steps" + if (r.risky) " (a risky move waits for approval)" else "")
        return Route(Mode.MIND, "triage: too much for a short plan")
    }

    private fun instant(r: Reading, text: String, world: GrammarWorld, bar: Double): Route? {
        val (intent, direction) = ModeRouter.INTENTS[r.capability] ?: return null
        return when (intent) {
            InstantIntent.TAP -> r.target?.takeIf { it in world.labels && r.targetConfidence >= bar }
                ?.let { Route(Mode.INSTANT, "triage picked it", InstantCommand(intent, text, target = it, targetLabel = it)) }
            InstantIntent.OPEN_APP -> world.apps.firstOrNull { it.first == r.target }?.takeIf { r.targetConfidence >= bar }
                ?.let { Route(Mode.INSTANT, "triage picked it", InstantCommand(intent, text, target = it.second, targetLabel = it.first)) }
            else -> Route(Mode.INSTANT, "triage picked it", InstantCommand(intent, text, direction = direction))
        }
    }

    /** The reading in the lessons' decision shape. */
    fun guessOf(route: Route, r: Reading): Guess = Guess(route.mode.name.lowercase(),
        if (route.mode == Mode.INSTANT) r.capability ?: "none" else "none",
        if (route.mode == Mode.INSTANT) route.command?.targetLabel else null,
        if (route.mode == Mode.INSTANT) minOf(r.capabilityConfidence, r.difficultyConfidence) else r.difficultyConfidence)

    private val ACTION_GUIDANCE = mapOf(
        "swipe_up" to "Swipe up on the screen.", "swipe_down" to "Swipe down on the screen.",
        "swipe_left" to "Swipe left.", "swipe_right" to "Swipe right.",
        "scroll_up" to "Scroll up.", "scroll_down" to "Scroll down.",
        "back" to "Go back.", "home" to "Go to the home screen.", "recents" to "Show the recent apps.",
        "tap" to "Tap one thing that is on the screen now.", "open_app" to "Open one installed app.",
        "camera" to "Open the camera.", "photo" to "Take a photo.", "selfie" to "Take a selfie.",
        "flashlight_on" to "Turn the flashlight on.", "flashlight_off" to "Turn the flashlight off.",
        "volume_up" to "Louder.", "volume_down" to "Quieter.",
        "media_play_pause" to "Play or pause what is playing.", "media_next" to "Next track.",
        "zoom_in" to "Zoom in on the screen.", "zoom_out" to "Zoom out on the screen.",
    )

    private val STEP_GUIDANCE = mapOf(
        "open_app" to "Open an app.", "go_to" to "Go to a place inside the app (a tab, a page, the inbox, settings).",
        "find" to "Scroll or look until something is visible.", "tap" to "Tap a named control.",
        "open_item" to "Open an item of a list (the first message, the latest email).", "back" to "Go back.",
        "toggle" to "Switch a setting on or off.", "read" to "Read something out or tell what is there.",
        "none" to "There is no such step.",
    )
}
