package com.cyclone.mobile.decisions

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InterruptedIOException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Why a decisions call gave no answer, for the stats and the breaker. */
enum class DecisionsFailure(val wire: String) {
    /** 400/404/413: the request was wrong. A bug on our side, never retried. */
    REJECTED("rejected"),
    /** 401/402/403: the key, the credit or the account. */
    KEY("key"),
    /** 429: too many requests. */
    RATE_LIMITED("rate_limited"),
    /** 5xx, 524, 529: the provider. */
    SERVER("server"),
    /** The deadline passed. */
    TIMEOUT("timeout"),
    /** No connection. */
    OFFLINE("offline");

    companion object {
        fun ofStatus(code: Int): DecisionsFailure = when (code) {
            401, 402, 403 -> KEY
            429 -> RATE_LIMITED
            in 500..599 -> SERVER
            else -> REJECTED
        }
    }
}

sealed interface DecisionsResult {
    val ms: Long
    data class Ok(val body: String, override val ms: Long) : DecisionsResult
    data class Failed(val failure: DecisionsFailure, val status: Int?, override val ms: Long) : DecisionsResult
}

/**
 * The one HTTP call for decisions (plan 58): a hard deadline per call, one shared warm connection, the key only in the
 * Authorization header, and nothing logged (no body, no key). A failure says why, so the breaker and the stats can
 * tell a wrong request from a slow network.
 */
object DecisionsHttp {
    private val JSON = "application/json".toMediaType()
    private val CLIENT: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
    }

    fun post(key: String, body: JSONObject, title: String, deadlineMs: Long, url: String = DecisionsWire.ENDPOINT,
             clock: () -> Long = System::nanoTime): DecisionsResult {
        val started = clock()
        fun ms() = (clock() - started) / 1_000_000
        return try {
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $key")
                .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
                .header("X-Title", title)
                .post(body.toString().toRequestBody(JSON))
                .build()
            val client = CLIENT.newBuilder().callTimeout(deadlineMs.coerceIn(200, 30_000), TimeUnit.MILLISECONDS).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (response.isSuccessful && text != null) DecisionsResult.Ok(text, ms())
                else DecisionsResult.Failed(DecisionsFailure.ofStatus(response.code), response.code, ms())
            }
        } catch (_: InterruptedIOException) {
            DecisionsResult.Failed(DecisionsFailure.TIMEOUT, null, ms())
        } catch (_: IOException) {
            DecisionsResult.Failed(DecisionsFailure.OFFLINE, null, ms())
        } catch (_: IllegalArgumentException) {
            DecisionsResult.Failed(DecisionsFailure.REJECTED, null, ms())
        }
    }
}

/**
 * Plan 58 §11.1 rule 5: three failures within a minute, or one 429, pause decision calls for two minutes. While paused
 * routing uses the grammar, the rules and the phone model, and anything else goes one rung up. A wrong request (4xx) or
 * a missing key counts too: calling again won't fix it. Thread-safe. Pure apart from the clock it is given.
 */
class DecisionBreaker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val failures: Int = 3,
    private val windowMs: Long = 60_000,
    private val pauseMs: Long = 120_000,
) {
    private val recent = ArrayDeque<Long>()
    private var pausedUntil = 0L
    private var lastReason: DecisionsFailure? = null

    @Synchronized fun open(): Boolean = clock() < pausedUntil

    /** Why the breaker last paused, while it is paused. */
    @Synchronized fun reason(): DecisionsFailure? = if (open()) lastReason else null

    @Synchronized fun record(result: DecisionsResult) {
        val now = clock()
        when (result) {
            is DecisionsResult.Ok -> recent.clear()
            is DecisionsResult.Failed -> {
                recent.addLast(now)
                while (recent.isNotEmpty() && now - recent.first() > windowMs) recent.removeFirst()
                if (result.failure == DecisionsFailure.RATE_LIMITED || recent.size >= failures) {
                    pausedUntil = now + pauseMs
                    lastReason = result.failure
                    recent.clear()
                }
            }
        }
    }
}
