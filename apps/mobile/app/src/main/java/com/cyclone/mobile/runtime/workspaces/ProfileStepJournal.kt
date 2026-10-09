package com.cyclone.mobile.runtime.workspaces

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Plan 57 W1: every privileged profile step, as it happened: which fixed command, how it ended, how long it took and
 * Android's own words. It is what the debug file is made of, so a failure can be fixed from facts. Text is redacted
 * before it is kept; nothing here is ever sent anywhere by itself.
 */
data class ProfileStep(
    val atMs: Long,
    val operation: String,
    /** The fixed command shape that ran (never caller text). */
    val command: String,
    val exitCode: Int?,
    val ms: Long,
    val output: String,
    /** How Cyclone read the answer: a failure kind, or null for success. */
    val verdict: String?,
) {
    fun toJson(): JSONObject = JSONObject().put("at", atMs).put("op", operation).put("command", command)
        .put("exit", exitCode ?: JSONObject.NULL).put("ms", ms).put("output", output).put("verdict", verdict ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = ProfileStep(
            o.optLong("at"), o.optString("op"), o.optString("command"),
            if (o.isNull("exit")) null else o.optInt("exit"), o.optLong("ms"), o.optString("output"),
            if (o.isNull("verdict")) null else o.optString("verdict"),
        )
    }
}

/** Removes what must never leave the phone in a debug file. Pure. */
object ProfileDebugRedaction {
    private val longToken = Regex("[A-Za-z0-9+/=_-]{40,}")
    private val namedSecret = Regex("(?i)\\b(key|token|secret|password|passcode|pin|otp|nonce|bundle|cookie|session|auth)(\"?\\s*[:=]\\s*\"?)([^\\s\",}]+)")

    fun text(raw: String, limit: Int = 8_192): String {
        var text = raw.take(limit * 2)
        text = com.cyclone.mobile.mind.mission.MindRedaction.scrub(text)
        text = namedSecret.replace(text) { m -> m.groupValues[1] + m.groupValues[2] + "[hidden]" }
        text = longToken.replace(text, "[long value hidden]")
        return text.take(limit)
    }
}

object ProfileStepJournal {
    const val LIMIT = 200
    const val OUTPUT_LIMIT = 8_192

    private val steps = ArrayDeque<ProfileStep>()
    @Volatile private var file: File? = null
    private var loaded = false

    /** Where the journal is kept. Called once at start; steps recorded before it are kept in memory. */
    @Synchronized fun attach(context: Context) {
        val dir = File(context.noBackupFilesDir, "profile-debug")
        if (!dir.isDirectory) dir.mkdirs()
        file = File(dir, "steps.json")
        if (!loaded) {
            val earlier = runCatching { decode(AtomicFile(file!!).openRead().use { it.readBytes().toString(Charsets.UTF_8) }) }
                .getOrDefault(emptyList())
            val now = steps.toList()
            steps.clear()
            (earlier + now).takeLast(LIMIT).forEach(steps::addLast)
            loaded = true
        }
    }

    @Synchronized fun record(operation: String, command: String, exitCode: Int?, ms: Long, output: String, verdict: String?,
                             atMs: Long = System.currentTimeMillis()) {
        add(steps, ProfileStep(atMs, operation, ProfileDebugRedaction.text(command, 400), exitCode, ms,
            ProfileDebugRedaction.text(output, OUTPUT_LIMIT), verdict))
        save()
    }

    @Synchronized fun snapshot(): List<ProfileStep> = steps.toList()

    /** Pure: keeps the newest [LIMIT]. */
    internal fun add(ring: ArrayDeque<ProfileStep>, step: ProfileStep) {
        ring.addLast(step)
        while (ring.size > LIMIT) ring.removeFirst()
    }

    internal fun encode(list: List<ProfileStep>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    internal fun decode(text: String): List<ProfileStep> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).map { ProfileStep.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save() {
        val target = file ?: return
        runCatching {
            val atomic = AtomicFile(target)
            val out = atomic.startWrite()
            try { out.write(encode(steps.toList()).toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
            catch (e: Exception) { atomic.failWrite(out); throw e }
        }
    }
}
