package com.cyclone.mobile.decisions

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 58 (alpha.123): the one wire for OpenRouter's Decisions API (`POST /api/alpha/decisions`), shared by the modes
 * router, the Pilot and Drive's JEV watch. Built to the documented contract, not guessed:
 *
 * - **Request:** `model`, `state` (an array: plain strings for text, `{"type":"image_url","image_url":{"url":"data:…"}}`
 *   for images, top level only), `questions` (name → typed question). A `noul` question's `criteria` has the keys
 *   `"true"` and `"false"`; a `choice` question's `criteria` maps each option to its guidance; a `score` question's
 *   `criteria` is an ordered array, lowest first.
 * - **Answer:** `answers.<name>` with `type`: `noul` (the probability of yes), `choice` (`choice`, `confidence`,
 *   `probabilities`), `score` (`score`, `confidence`, `probabilities`), or `refusal` (no answer; never a "no").
 *
 * Read exactly: an answer outside its question's options, of the wrong type, or missing is no answer. Pure.
 */
sealed interface DQuestion {
    val instructions: String

    /** A yes/no question; the answer is the probability of yes. */
    data class Noul(override val instructions: String, val whenTrue: String, val whenFalse: String) : DQuestion

    /** Pick one option. [options] maps each option to its guidance, in order; a blank guidance repeats the option. */
    data class Choice(override val instructions: String, val options: Map<String, String>) : DQuestion {
        init {
            require(options.isNotEmpty()) { "a choice needs options" }
            require(options.keys.none { it.isBlank() }) { "blank option" }
        }
    }

    /** An ordered rubric, lowest level first; the answer is the expected level (0 … levels-1). */
    data class Score(override val instructions: String, val levels: List<String>) : DQuestion {
        init { require(levels.isNotEmpty()) { "a score needs levels" } }
    }

    companion object {
        /** A choice whose options need no guidance beyond their names. */
        fun choice(instructions: String, options: List<String>): Choice = Choice(instructions, options.associateWith { "" })
    }
}

/** One item of the `state` array. */
sealed interface DPart {
    data class Text(val text: String) : DPart

    /** A base64 data URL (`data:image/png|jpeg|webp;base64,…`); remote URLs are not fetched by the API. */
    data class Image(val dataUrl: String, val detail: String? = null) : DPart {
        init { require(DecisionsWire.isImageDataUrl(dataUrl)) { "an image must be a png, jpeg or webp data URL" } }
    }
}

sealed interface DAnswer {
    data class Noul(val yes: Double) : DAnswer
    data class Choice(val choice: String, val confidence: Double, val probabilities: Map<String, Double>) : DAnswer {
        /** The second most likely option and its probability, or null. */
        fun runnerUp(): Pair<String, Double>? = probabilities.entries.filter { it.key != choice }.maxByOrNull { it.value }?.toPair()
        /** How far the chosen option leads the next one (1.0 when nothing else had any weight). */
        fun margin(): Double = (probabilities[choice] ?: confidence) - (runnerUp()?.second ?: 0.0)
    }
    data class Score(val score: Double, val confidence: Double, val probabilities: Map<Int, Double>) : DAnswer
    /** The model declined this question. It is not a "no": the caller treats it as no answer. */
    data object Refusal : DAnswer
}

data class DReply(
    val answers: Map<String, DAnswer>,
    val model: String? = null,
    val provider: String? = null,
    val inputTokens: Int? = null,
    val cost: Double? = null,
) {
    fun choice(name: String): DAnswer.Choice? = answers[name] as? DAnswer.Choice
    fun noul(name: String): DAnswer.Noul? = answers[name] as? DAnswer.Noul
    fun score(name: String): DAnswer.Score? = answers[name] as? DAnswer.Score
    fun refused(name: String): Boolean = answers[name] == DAnswer.Refusal
}

object DecisionsWire {
    const val ENDPOINT = "https://openrouter.ai/api/alpha/decisions"
    /** Documented limits: 200 questions per request; up to 128 images for GPT-6 Luna Decisions. */
    const val MAX_QUESTIONS = 200
    const val MAX_IMAGES = 128

    private val NAME = Regex("[a-z][a-z0-9_]{0,63}")
    private val IMAGE = Regex("^data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$")

    fun isImageDataUrl(url: String): Boolean = IMAGE.matches(url)

    /**
     * The request body. [zdr] asks OpenRouter for zero-data-retention providers only; [sortByLatency] asks for the
     * fastest provider.
     */
    fun body(
        model: String,
        state: List<DPart>,
        questions: Map<String, DQuestion>,
        sessionId: String? = null,
        zdr: Boolean = false,
        sortByLatency: Boolean = true,
    ): JSONObject {
        require(model.isNotBlank()) { "model" }
        require(questions.isNotEmpty() && questions.size <= MAX_QUESTIONS) { "1 to $MAX_QUESTIONS questions" }
        require(questions.keys.all { NAME.matches(it) }) { "question names are lower_snake_case" }
        require(state.count { it is DPart.Image } <= MAX_IMAGES) { "too many images" }
        val stateJson = JSONArray()
        state.forEach { part ->
            when (part) {
                is DPart.Text -> if (part.text.isNotBlank()) stateJson.put(part.text)
                is DPart.Image -> stateJson.put(JSONObject().put("type", "image_url").put("image_url",
                    JSONObject().put("url", part.dataUrl).also { u -> part.detail?.let { u.put("detail", it) } }))
            }
        }
        require(stateJson.length() > 0) { "state is empty" }
        val qs = JSONObject()
        questions.forEach { (name, q) -> qs.put(name, question(q)) }
        val body = JSONObject().put("model", model).put("state", stateJson).put("questions", qs)
        if (sessionId != null) body.put("session_id", sessionId.take(256))
        if (zdr || sortByLatency) body.put("provider", JSONObject().also { p ->
            if (sortByLatency) p.put("sort", "latency")
            if (zdr) p.put("zdr", true)
        })
        return body
    }

    private fun question(q: DQuestion): JSONObject = when (q) {
        is DQuestion.Noul -> JSONObject().put("type", "noul").put("instructions", q.instructions)
            .put("criteria", JSONObject().put("true", q.whenTrue).put("false", q.whenFalse))
        is DQuestion.Choice -> JSONObject().put("type", "choice").put("instructions", q.instructions)
            .put("criteria", JSONObject().also { c -> q.options.forEach { (option, guidance) -> c.put(option, guidance.ifBlank { option }) } })
        is DQuestion.Score -> JSONObject().put("type", "score").put("instructions", q.instructions)
            .put("criteria", JSONArray(q.levels))
    }

    /** The answers to [questions], or null when the body is not a decisions answer at all. */
    fun parse(body: String?, questions: Map<String, DQuestion>): DReply? {
        val json = runCatching { JSONObject(body ?: return null) }.getOrNull() ?: return null
        val answers = json.optJSONObject("answers") ?: return null
        val read = questions.mapNotNull { (name, q) -> answers.optJSONObject(name)?.let { a -> answer(a, q)?.let { name to it } } }.toMap()
        val usage = json.optJSONObject("usage")
        return DReply(
            answers = read,
            model = json.optString("model").takeIf { it.isNotBlank() },
            provider = json.optString("provider").takeIf { it.isNotBlank() },
            inputTokens = usage?.optInt("input_tokens", -1)?.takeIf { it >= 0 },
            cost = usage?.optDouble("cost", Double.NaN)?.takeIf { !it.isNaN() },
        )
    }

    private fun answer(a: JSONObject, q: DQuestion): DAnswer? {
        val type = a.optString("type")
        if (type == "refusal") return DAnswer.Refusal
        return when (q) {
            is DQuestion.Noul -> if (type != "noul") null else a.unit("noul")?.let { DAnswer.Noul(it) }
            is DQuestion.Choice -> {
                if (type != "choice") return null
                val choice = a.optString("choice").takeIf { it in q.options.keys } ?: return null
                val probabilities = a.optJSONObject("probabilities")?.let { p ->
                    q.options.keys.mapNotNull { k -> p.optDouble(k, Double.NaN).takeIf { !it.isNaN() }?.let { k to it.coerceIn(0.0, 1.0) } }.toMap()
                }.orEmpty()
                val confidence = a.unit("confidence") ?: probabilities[choice] ?: return null
                DAnswer.Choice(choice, confidence, probabilities)
            }
            is DQuestion.Score -> {
                if (type != "score") return null
                val score = a.optDouble("score", Double.NaN).takeIf { !it.isNaN() && it >= 0.0 && it <= q.levels.lastIndex } ?: return null
                val probabilities = a.optJSONObject("probabilities")?.let { p ->
                    q.levels.indices.mapNotNull { i -> p.optDouble(i.toString(), Double.NaN).takeIf { !it.isNaN() }?.let { i to it.coerceIn(0.0, 1.0) } }.toMap()
                }.orEmpty()
                DAnswer.Score(score, a.unit("confidence") ?: 0.0, probabilities)
            }
        }
    }

    private fun JSONObject.unit(key: String): Double? = optDouble(key, Double.NaN).takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0)
}
