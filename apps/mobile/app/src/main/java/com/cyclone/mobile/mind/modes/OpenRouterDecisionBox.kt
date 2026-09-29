package com.cyclone.mobile.mind.modes

import android.content.Context
import android.os.SystemClock
import com.cyclone.mobile.mind.pilot.FastMode
import com.cyclone.mobile.mind.pilot.FastModeSettings
import com.cyclone.mobile.mind.pilot.FastRoute
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Plan 42 (M1): one decision-box call over OpenRouter, on the same route and model as Fast mode (Settings → Model &
 * intelligence). A slow answer is no answer: the deadline is short and the caller then goes one mode up. Nothing is
 * logged; the key is sent only as the Authorization header.
 */
class OpenRouterDecisionBox(private val key: String, private val route: FastRoute, private val model: String) : DecisionBox {
    override fun ask(request: BoxRequest): BoxReply? {
        val started = SystemClock.elapsedRealtime()
        // The decision endpoint reads text only; a screenshot goes to the fast model.
        val useDecisions = route == FastRoute.DECISIONS && request.image == null
        val body = if (useDecisions) BoxWire.decisionsBody(model, request) else BoxWire.chatBody(chatModel(), request)
        val text = post(if (useDecisions) DECISIONS else CHAT, body) ?: return null
        val reply = if (useDecisions) BoxWire.parseDecisions(text, request) else BoxWire.parseChat(text, request)
        return reply?.copy(ms = SystemClock.elapsedRealtime() - started)
    }

    private fun chatModel(): String = if (route == FastRoute.DECISIONS) FastMode.DEFAULT_MODEL else model

    private fun post(url: String, body: JSONObject): String? = runCatching {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Modes")
            .post(body.toString().toRequestBody(JSON))
            .build()
        CLIENT.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
    }.getOrNull()

    companion object {
        private const val CHAT = "https://openrouter.ai/api/v1/chat/completions"
        private const val DECISIONS = "https://openrouter.ai/api/alpha/decisions"
        private val JSON = "application/json".toMediaType()
        private val CLIENT = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()

        /** The box for this phone, or null without an OpenRouter key (the router then uses only the grammar and rules). */
        fun of(context: Context, settings: FastModeSettings = FastMode.settings(context)): DecisionBox? {
            val key = com.cyclone.mobile.ai.OpenRouterSecretStore.read(context).takeIf { it.isNotBlank() } ?: return null
            return OpenRouterDecisionBox(key, settings.route, if (settings.route == FastRoute.DECISIONS) settings.decisionModel else settings.model)
        }
    }
}
