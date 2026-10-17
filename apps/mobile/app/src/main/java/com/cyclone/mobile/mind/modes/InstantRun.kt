package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.mind.pilot.Pilot

/**
 * The screen as Instant sees it: the app, the labels, and whether code says it is sensitive. [packageName] is the app in
 * the foreground, so an open can be proven (blank when the phone didn't say).
 */
data class InstantScreen(val app: String, val labels: List<String>, val sensitive: Boolean = false, val image: String? = null,
                         val packageName: String = "")
data class InstantMove(val ok: Boolean, val changed: Boolean, val note: String)
data class InstantContact(val name: String, val numbers: List<String>)

/** Why Instant stopped and where the run goes next. */
data class Promotion(val to: Mode, val reason: String, val candidates: List<String> = emptyList())

data class InstantOutcome(
    val done: Boolean,
    val lines: List<String>,
    val moves: Int,
    val promotion: Promotion? = null,
    /** Cancelled by the owner (the call window). Not a failure, not a promotion. */
    val cancelled: Boolean = false,
    /**
     * Alpha 143: the phone showed the result (the app in front, the screen changed, the tool said so). Only a verified
     * run teaches Replay and the phone model; a done that isn't verified says so instead of claiming success.
     */
    val verified: Boolean = false,
) {
    fun baton(goal: String): RunBaton? = promotion?.let { RunBaton(goal, listOf(Mode.INSTANT), lines, it.reason, it.candidates) }
}

/** The phone, as Instant moves it. Every move goes through Cyclone's executor on the phone. */
interface InstantHands {
    fun look(withImage: Boolean = false): InstantScreen?
    fun gesture(intent: InstantIntent, direction: String?): InstantMove
    fun tapLabel(label: String): InstantMove
    fun openApp(packageName: String): InstantMove
    fun camera(front: Boolean): InstantMove
    fun contacts(name: String): List<InstantContact>
    fun dial(number: String): InstantMove
    /** Shows "Calling Mam in 2 s — Cancel" (and says it in Live); true when the owner let it go ahead. */
    fun confirmWindow(text: String, ms: Long): Boolean
    fun timer(seconds: Int): InstantMove
    fun alarm(hour: Int, minute: Int): InstantMove
    fun flashlight(on: Boolean): InstantMove
    fun volume(up: Boolean): InstantMove
    fun media(action: String): InstantMove
    fun stopped(): Boolean
    /** Plan 52 run 6: zoom the screen with two fingers. */
    fun zoom(zoomIn: Boolean): InstantMove = InstantMove(false, false, "zooming isn't available here")
    /** Plan 52 run 6: press, carry and drop the control labelled [from] onto the one labelled [to]. */
    fun drag(from: String, to: String): InstantMove = InstantMove(false, false, "dragging isn't available here")
    /** Alpha 143: waits a moment for the phone to settle before the result is checked again. */
    fun pause(ms: Long) {}
}

/**
 * Plan 42 (M4): Instant mode. One command, done with Cyclone's tools, at most [MAX_MOVES] moves. The follow-up moves
 * (the camera's shutter, the dialer's call button) are found on the screen by name, or picked by one decision box.
 *
 * It never types, never composes, never sends, pays, deletes or posts (such a label promotes to the Mind, which asks
 * the owner), and never acts on a sensitive screen. Anything it can't finish promotes with what it did, so the next
 * mode continues.
 *
 * Alpha 143: a move that "worked" is not done until the phone shows it. An opened app must be the one in front, a tap
 * must change the screen; anything Instant can't prove goes up to Flash with what it did, never reported as done. Pure.
 */
object InstantRun {
    const val MAX_MOVES = 3
    const val CALL_WINDOW_MS = 2_000L
    /** How often, and how far apart, an open is checked before Instant gives it to Flash. */
    const val PROOF_LOOKS = 3
    const val PROOF_PAUSE_MS = 400L

    private val SHUTTER = Regex("(?i)\\b(shutter|take (a )?(photo|picture)|capture|photo button|camera button|sluiter|foto maken|neem (een )?foto|opname)\\b")
    private val CALL_BUTTON = Regex("(?i)^(call|dial|voice call|bellen|bel|audio call)$|\\bcall button\\b|\\bbelknop\\b")

    fun run(command: InstantCommand, hands: InstantHands, box: DecisionBox? = null, bar: Double = 0.9): InstantOutcome {
        val lines = mutableListOf<String>()
        var moves = 0
        fun done(verified: Boolean = true) = InstantOutcome(true, lines.toList(), moves, verified = verified)
        fun promote(to: Mode, reason: String, candidates: List<String> = emptyList()) =
            InstantOutcome(false, lines.toList(), moves, Promotion(to, reason, candidates))
        fun step(move: InstantMove, line: String): Boolean {
            moves++
            if (move.ok) lines += line
            return move.ok
        }
        if (hands.stopped()) return promote(Mode.MIND, "the owner stopped it")

        return when (command.intent) {
            InstantIntent.SWIPE, InstantIntent.SCROLL, InstantIntent.BACK, InstantIntent.HOME, InstantIntent.RECENTS -> {
                val move = hands.gesture(command.intent, command.direction)
                if (!step(move, describe(command))) return promote(Mode.FLASH, "the ${command.intent.name.lowercase()} didn't happen: ${move.note}")
                // Nothing moved (the end of a list, Home on the home screen): done, said plainly, never counted as proof.
                if (!move.changed) lines[lines.size - 1] = "${describe(command)}, but nothing on the screen moved"
                done(verified = move.changed)
            }
            InstantIntent.TIMER -> if (step(hands.timer(command.seconds ?: 0), "set a timer")) done() else promote(Mode.MIND, "the timer couldn't be set")
            InstantIntent.ALARM -> if (step(hands.alarm(command.hour ?: -1, command.minute ?: -1), "set an alarm")) done() else promote(Mode.MIND, "the alarm couldn't be set")
            InstantIntent.FLASHLIGHT -> if (step(hands.flashlight(command.direction == "on"), "turned the flashlight ${command.direction}")) done()
                else promote(Mode.MIND, "the flashlight couldn't be switched")
            InstantIntent.VOLUME -> if (step(hands.volume(command.direction == "up"), "turned the volume ${command.direction}")) done()
                else promote(Mode.MIND, "the volume couldn't be changed")
            InstantIntent.MEDIA -> if (step(hands.media(command.direction ?: "play_pause"), "media: ${command.direction}")) done()
                else promote(Mode.MIND, "the media key didn't work")
            InstantIntent.OPEN_APP -> {
                val pkg = command.target.orEmpty()
                val name = command.targetLabel ?: pkg
                val move = hands.openApp(pkg)
                if (!step(move, "opened $name")) return promote(Mode.FLASH, "the app didn't open: ${move.note}")
                when (val front = inFront(hands, pkg)) {
                    null -> done()
                    else -> {
                        lines[lines.size - 1] = "asked the phone to open $name"
                        promote(Mode.FLASH, "$name was asked to open, but the phone shows $front")
                    }
                }
            }
            InstantIntent.CAMERA -> {
                val move = hands.camera(front = false)
                if (!step(move, "opened the camera")) promote(Mode.FLASH, "the camera didn't open")
                else if (!move.changed) promote(Mode.FLASH, "the camera was asked to open, but the screen didn't change")
                else done()
            }
            InstantIntent.TAP -> tap(command.target.orEmpty(), hands, lines, { moves++ }, ::promote) ?: done()
            InstantIntent.PHOTO, InstantIntent.SELFIE -> {
                val selfie = command.intent == InstantIntent.SELFIE
                if (!step(hands.camera(front = selfie), if (selfie) "opened the front camera" else "opened the camera")) return promote(Mode.FLASH, "the camera didn't open")
                val shutter = find(hands, box, bar, SHUTTER, "Which control takes the photo (the shutter)?") ?: return promote(Mode.FLASH, "couldn't find the camera's shutter")
                if (moves >= MAX_MOVES) return promote(Mode.FLASH, "too many moves")
                val move = hands.tapLabel(shutter)
                if (!step(move, "took the photo")) promote(Mode.FLASH, "the shutter didn't work: ${move.note}")
                else if (!move.changed) promote(Mode.FLASH, "the shutter was pressed, but nothing on the screen showed a photo was taken")
                else done()
            }
            InstantIntent.CALL -> call(command, hands, box, bar, lines, { moves++ }, ::promote) ?: done()
            InstantIntent.ZOOM -> {
                val move = hands.zoom(command.direction == "in")
                if (!step(move, describe(command))) promote(Mode.FLASH, "the zoom didn't happen: ${move.note}")
                else if (!move.changed) promote(Mode.FLASH, "the zoom didn't change the screen")
                else done()
            }
            InstantIntent.DRAG -> drag(command, hands, lines, { moves++ }, ::promote) ?: done()
        }
    }

    private fun tap(label: String, hands: InstantHands, lines: MutableList<String>, count: () -> Unit,
                    promote: (Mode, String, List<String>) -> InstantOutcome): InstantOutcome? {
        val screen = hands.look() ?: return promote(Mode.FLASH, "the screen couldn't be read", emptyList())
        if (screen.sensitive) return promote(Mode.MIND, "this screen shows a password, code or card field, or the app is kept out of quick actions", emptyList())
        // Code decides what can't be undone; the Mind asks the owner for those.
        if (Pilot.irreversible(label)) return promote(Mode.MIND, "\"$label\" can't be undone, so it needs your approval", emptyList())
        if (screen.labels.none { it == label }) return promote(Mode.FLASH, "\"$label\" is no longer on the screen", emptyList())
        count()
        val move = hands.tapLabel(label)
        if (!move.ok) return promote(Mode.FLASH, "the tap on \"$label\" didn't happen: ${move.note}", emptyList())
        lines += "tapped \"$label\""
        // A tap that changed nothing did nothing the owner can see: Flash looks at why.
        if (!move.changed) return promote(Mode.FLASH, "the tap on \"$label\" changed nothing on the screen", emptyList())
        return null
    }

    /** Plan 52 run 6: a drop that can't be undone (Trash, Delete) goes to the Mind, which asks the owner. */
    private fun drag(command: InstantCommand, hands: InstantHands, lines: MutableList<String>, count: () -> Unit,
                     promote: (Mode, String, List<String>) -> InstantOutcome): InstantOutcome? {
        val from = command.target.orEmpty()
        val to = command.destination.orEmpty()
        if (from.isBlank() || to.isBlank()) return promote(Mode.FLASH, "the drag needs something to move and a place to drop it", emptyList())
        val screen = hands.look() ?: return promote(Mode.FLASH, "the screen couldn't be read", emptyList())
        if (screen.sensitive) return promote(Mode.MIND, "this screen shows a password, code or card field, or the app is kept out of quick actions", emptyList())
        if (Pilot.irreversible(to) || Pilot.irreversible(from) || Regex("(?i)\\b(trash|bin|prullenbak)\\b").containsMatchIn(to)) {
            return promote(Mode.MIND, "dropping on \"$to\" can't be undone, so it needs your approval", emptyList())
        }
        if (screen.labels.none { it == from } || screen.labels.none { it == to }) {
            return promote(Mode.FLASH, "\"$from\" or \"$to\" is no longer on the screen", emptyList())
        }
        count()
        val move = hands.drag(from, to)
        if (!move.ok) return promote(Mode.FLASH, "the drag didn't happen: ${move.note}", emptyList())
        lines += "moved \"$from\" to \"$to\""
        if (!move.changed) return promote(Mode.FLASH, "the drag of \"$from\" changed nothing on the screen", emptyList())
        return null
    }

    private fun call(command: InstantCommand, hands: InstantHands, box: DecisionBox?, bar: Double, lines: MutableList<String>,
                     count: () -> Unit, promote: (Mode, String, List<String>) -> InstantOutcome): InstantOutcome? {
        val name = command.target.orEmpty()
        val found = hands.contacts(name).filter { it.numbers.isNotEmpty() }
        when {
            found.isEmpty() -> return promote(Mode.FLASH, "no contact with a number matches \"$name\"", emptyList())
            found.size > 1 -> return promote(Mode.FLASH, "more than one contact matches \"$name\"; ask the owner which one", found.map { it.name }.take(6))
        }
        val who = found.single()
        if (!hands.confirmWindow("Calling ${who.name}", CALL_WINDOW_MS)) {
            lines += "the owner cancelled the call to ${who.name}"
            return InstantOutcome(true, lines.toList(), 0, cancelled = true)
        }
        count()
        if (!hands.dial(who.numbers.first()).ok) return promote(Mode.FLASH, "the dialer didn't open", emptyList())
        lines += "opened the dialer for ${who.name}"
        val button = find(hands, box, bar, CALL_BUTTON, "Which control starts the call?") ?: return promote(Mode.FLASH, "couldn't find the call button", emptyList())
        count()
        val move = hands.tapLabel(button)
        if (!move.ok) return promote(Mode.FLASH, "the call button didn't work: ${move.note}", emptyList())
        lines += "called ${who.name}"
        if (!move.changed) return promote(Mode.FLASH, "the call button was pressed, but the screen didn't change", emptyList())
        return null
    }

    /**
     * Alpha 143: null when [pkg] is the app in front, else what the phone shows instead. It looks again a few times,
     * since an app can take a moment to come up; a screen that can't be read, or doesn't say its app, is no proof.
     */
    private fun inFront(hands: InstantHands, pkg: String): String? {
        var shown = "a screen that couldn't be read"
        repeat(PROOF_LOOKS) { attempt ->
            if (attempt > 0) hands.pause(PROOF_PAUSE_MS)
            val screen = hands.look()
            when {
                screen == null -> Unit
                screen.packageName.isBlank() -> shown = "a screen that doesn't say which app it is"
                screen.packageName.equals(pkg, ignoreCase = true) -> return null
                else -> shown = screen.app.ifBlank { screen.packageName }
            }
        }
        return shown
    }

    /**
     * A follow-up control: by name on the screen first (unique), otherwise one decision box with the screenshot. Null
     * when neither is sure.
     */
    private fun find(hands: InstantHands, box: DecisionBox?, bar: Double, pattern: Regex, question: String): String? {
        repeat(2) { attempt ->
            // A screenshot only for a box that can read one (JEV can't): taking it costs time for nothing.
            val screen = hands.look(withImage = box?.sees == true) ?: return null
            if (screen.sensitive) return null
            screen.labels.filter { pattern.containsMatchIn(it) }.distinct().singleOrNull()?.let { return it }
            if (box != null && screen.labels.isNotEmpty()) {
                val choices = screen.labels.distinct().take(40) + "none"
                val reply = box.ask(BoxRequest("Pick the one control a phone assistant should press next.",
                    "Screen: ${screen.app}\nControls: ${screen.labels.distinct().take(40).joinToString(", ")}",
                    listOf(BoxQuestion("next", question, choices)), screen.image))
                reply?.sure("next", bar)?.takeIf { it != "none" && !Pilot.irreversible(it) }?.let { return it }
            }
        }
        return null
    }

    private fun describe(command: InstantCommand): String = when (command.intent) {
        InstantIntent.SWIPE -> "swiped ${command.direction}"
        InstantIntent.SCROLL -> "scrolled ${command.direction}"
        InstantIntent.BACK -> "pressed Back"
        InstantIntent.HOME -> "went Home"
        InstantIntent.RECENTS -> "opened recent apps"
        InstantIntent.ZOOM -> "zoomed ${command.direction}"
        else -> command.intent.name.lowercase()
    }
}
