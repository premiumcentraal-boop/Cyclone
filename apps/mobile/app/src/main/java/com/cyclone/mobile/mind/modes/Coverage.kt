package com.cyclone.mobile.mind.modes

/**
 * Alpha 143: an Instant route must do the whole request, not the first half of it. "Open Instagram my profile" decided
 * as "open Instagram" opened the app and reported done on its home page.
 *
 * Models are asked because people don't speak in commands ("time for some insta", "take me to the home screen"), so
 * casual words are fine. What is not fine is a request that names a second place the one move doesn't reach: a place
 * inside an app ("profile", "orders", "chats"), another app on this phone, or another control on the screen. Such a
 * route goes up to Flash, which plans the whole request and checks it. The grammar's own matches are exact by
 * construction; this checks what a model decided (Board 0, Triage, the decision engine, the phone model, Replay). Pure.
 */
object Coverage {
    private val COMMON = setOf("please", "cyclone", "hey", "ok", "okay", "now", "alsjeblieft", "graag", "even", "nu", "can", "you",
        "could", "would", "kun", "kan", "je", "jij", "wil", "u", "the", "a", "an", "my", "mijn", "de", "het", "een", "to", "naar",
        "on", "op", "for", "voor", "me", "mij", "just", "app", "apps", "aub", "in", "of", "van")

    /** The words of each action itself: never a second place. */
    private val VOCABULARY: Map<InstantIntent, Set<String>> = mapOf(
        InstantIntent.OPEN_APP to setOf("open", "openen", "launch", "start", "go", "ga", "pull", "bring", "up", "fire", "show", "run"),
        InstantIntent.TAP to setOf("tap", "click", "press", "klik", "druk", "tik", "select", "hit", "choose", "kies", "tab", "icon",
            "button", "knop", "link", "option", "optie", "item", "tabblad"),
        InstantIntent.DRAG to setOf("drag", "sleep", "verplaats", "move", "onto", "into"),
        InstantIntent.BACK to setOf("back", "go", "ga", "terug", "previous", "vorige", "page", "screen", "pagina", "scherm"),
        InstantIntent.HOME to setOf("home", "go", "ga", "screen", "scherm", "homescreen", "startscherm", "beginscherm"),
        InstantIntent.SWIPE to setOf("swipe", "veeg", "up", "down", "left", "right", "page"),
        InstantIntent.SCROLL to setOf("scroll", "scrol", "up", "down", "page"),
    )

    /** Places inside an app, and pages of Settings: a second destination when the route doesn't name them itself. */
    val PLACES = setOf("profile", "profiel", "account", "settings", "instellingen", "order", "orders", "bestelling", "bestellingen",
        "cart", "winkelwagen", "basket", "mandje", "message", "messages", "berichten", "dm", "dms", "inbox", "feed", "story",
        "stories", "reel", "reels", "likes", "liked", "followers", "following", "volgers", "saved", "opgeslagen", "bookmarks",
        "favorites", "favourites", "favorieten", "history", "geschiedenis", "library", "bibliotheek", "playlist", "playlists",
        "afspeellijst", "afspeellijsten", "wallet", "payments", "betalingen", "subscriptions", "abonnementen", "explore",
        "ontdekken", "friends", "vrienden", "groups", "groepen", "posts", "contacts", "contacten", "calls", "oproepen", "chat",
        "chats", "status", "updates", "channels", "kanalen", "notifications", "meldingen", "activity", "activiteit", "downloads",
        "wishlist", "verlanglijst", "wifi", "bluetooth", "battery", "batterij", "display", "privacy", "security", "beveiliging")

    /**
     * The request's words that name a place [command] doesn't reach; empty when it covers the request (or is an action
     * this check leaves alone: the device's own switches, timers, calls, the camera and photos).
     */
    fun leftover(text: String, command: InstantCommand, world: GrammarWorld = GrammarWorld()): List<String> {
        val vocabulary = VOCABULARY[command.intent] ?: return emptyList()
        val targets = targetWords(command)
        val words = InstantGrammar.normalize(text).split(' ').filter { it.isNotBlank() && it !in COMMON && it !in vocabulary && !targets.covers(it) }
        val ownTarget = command.targetLabel?.let(InstantGrammar::normalize)
        val otherApps = world.apps.map { InstantGrammar.normalize(it.first) }.filter { it != ownTarget }
        val otherLabels = world.labels.map(InstantGrammar::normalize).filter { it != ownTarget && it != command.destination?.let(InstantGrammar::normalize) }
        return words.filter { w ->
            w in PLACES ||
                (w.length >= 4 && otherApps.any { app -> app == w || app.split(' ').any { it == w } }) ||
                (w.length >= 4 && otherLabels.any { it == w })
        }.distinct()
    }

    private class Targets(val words: List<String>, val joined: List<String>) {
        fun covers(w: String): Boolean = words.any { t -> t == w || (w.length >= 4 && InstantGrammar.similarity(w, t) >= InstantGrammar.MATCH) } ||
            // "insta" for Instagram, "you tube" said as two words for YouTube.
            (w.length >= 3 && joined.any { it.contains(w) })
    }

    private fun targetWords(command: InstantCommand): Targets {
        val names = listOfNotNull(command.targetLabel, command.destination) +
            (if (command.intent == InstantIntent.TAP || command.intent == InstantIntent.DRAG) listOfNotNull(command.target) else emptyList())
        val words = names.flatMap { InstantGrammar.normalize(it).split(' ') }.filter { it.isNotBlank() }.distinct()
        return Targets(words, names.map { InstantGrammar.normalize(it).replace(" ", "") })
    }

    /** [route] as it may go: an Instant route that leaves a named place undone goes to Flash instead. */
    fun check(route: Route, text: String, world: GrammarWorld): Route {
        val command = route.command ?: return route
        if (route.mode != Mode.INSTANT) return route
        val left = leftover(text, command, world)
        if (left.isEmpty()) return route
        return route.copy(mode = Mode.FLASH, command = null,
            why = "\"${left.joinToString(" ").take(60)}\" is more than ${ModeRouter.keyOf(command).replace('_', ' ')}" +
                (command.targetLabel?.let { " $it" }.orEmpty()))
    }
}
