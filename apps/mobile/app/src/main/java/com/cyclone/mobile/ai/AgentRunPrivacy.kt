package com.cyclone.mobile.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AgentRunSanitizer {
    private const val REDACTED = "[REDACTED]"
    private const val PRIVATE = "[OMITTED_PRIVATE_REASONING]"
    private const val BINARY = "[OMITTED_BINARY]"

    private val sensitiveKey = Regex(
        "(?i)(password|passwd|passcode|secret|token|api.?key|openrouter|otp|one.?time|verification.?code|cvv|card.?number|pin|authorization|cookie|clipboard.?text|clipboard.?value)",
    )
    private val privateReasoningKey = Regex("(?i)(chain.?of.?thought|private.?reasoning|hidden.?reasoning|reasoning.?content|thoughts|cot)")
    private val imagePayloadKey = Regex("(?i)(png.?base64|image.?base64|base64|image.?bytes|data.?uri|screenshot.?bytes)")
    private val secretAssignment = Regex(
        "(?i)(password|passwd|passcode|secret|token|api[_ -]?key|otp|one[- ]?time(?: code)?|verification(?: code)?|pin)\\s*(?:is|[:=])\\s*[\\\"']?[^,;\\s\\\"'}]+",
    )
    private val bearer = Regex("(?i)bearer\\s+[a-z0-9._~+/-]{8,}")
    private val openRouterKey = Regex("(?i)sk-or-v1-[a-z0-9_-]{12,}")
    private val genericProviderKey = Regex("(?i)\\bsk-[a-z0-9_-]{16,}")
    private val longBase64 = Regex("[A-Za-z0-9+/]{180,}={0,2}")
    private val paymentCard = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
    private val privateReasoningLine = Regex("(?im)(chain.?of.?thought|private.?reasoning|hidden.?reasoning|reasoning.?content)\\s*[:=]\\s*.*$")

    fun sanitizeRecord(record: AgentRunRecord): AgentRunRecord = record.copy(
        goal = cleanText(record.goal).take(1000),
        model = cleanText(record.model).take(240),
        summary = cleanText(record.summary).take(3000),
        finalClassification = cleanText(record.finalClassification).take(160),
        events = record.events.takeLast(800).map(::sanitizeEvent),
    )

    fun sanitizeEvent(event: AgentRunEvent): AgentRunEvent = event.copy(
        message = cleanText(event.message).take(1200),
        tool = event.tool?.let(::cleanText)?.take(120),
        payload = sanitizeObject(event.payload, event.tool, "payload"),
    )

    fun sanitizeObject(source: JSONObject, tool: String? = null, path: String = "payload"): JSONObject {
        val out = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val raw = source.opt(key)
            val childPath = "$path.$key"
            out.put(key, when {
                privateReasoningKey.containsMatchIn(key) -> PRIVATE
                imagePayloadKey.matches(key) -> BINARY
                sensitiveKey.containsMatchIn(key) -> REDACTED
                shouldRedactTypedArgument(tool, key, path) -> REDACTED
                else -> sanitizeValue(raw, tool, childPath)
            })
        }
        return out
    }

    fun cleanText(value: String): String = value
        .replace(privateReasoningLine, PRIVATE)
        .replace(secretAssignment) { match -> "${match.groupValues[1]}=$REDACTED" }
        .replace(bearer, "Bearer $REDACTED")
        .replace(openRouterKey, REDACTED)
        .replace(genericProviderKey, REDACTED)
        .replace(paymentCard, "[PAYMENT_REDACTED]")
        .replace(longBase64, BINARY)
        .replace(Regex("(?s)\\\"(?:pngBase64|imageBase64|base64)\\\"\\s*:\\s*\\\".*?\\\""), "\"base64\":\"$BINARY\"")
        .take(16_000)

    private fun sanitizeValue(value: Any?, tool: String?, path: String): Any = when (value) {
        null, JSONObject.NULL -> JSONObject.NULL
        is JSONObject -> sanitizeObject(value, tool, path)
        is JSONArray -> JSONArray().also { out ->
            val limit = minOf(value.length(), 300)
            for (index in 0 until limit) out.put(sanitizeValue(value.opt(index), tool, "$path[$index]"))
        }
        is String -> cleanText(value)
        else -> value
    }

    private fun shouldRedactTypedArgument(tool: String?, key: String, path: String): Boolean {
        val shortTool = tool.orEmpty().removePrefix("phone.")
        val argumentPath = path.contains("argument", ignoreCase = true) ||
            path.contains("param", ignoreCase = true) || path.contains("safeArguments", ignoreCase = true)
        if (shortTool in setOf("type", "replace_text") && argumentPath && key.lowercase() in setOf("text", "value", "content")) return true
        if (shortTool == "set_clipboard" && key.lowercase() in setOf("text", "value", "content", "clipboard")) return true
        return false
    }
}

object AgentRunLogExporter {
    fun metadataJson(
        record: AgentRunRecord,
        cycloneVersion: String,
        versionCode: Long,
        buildIdentifier: String,
        generatedAtMs: Long = System.currentTimeMillis(),
    ): JSONObject = JSONObject()
        .put("schema", AgentRunSchema.RUN_SCHEMA)
        .put("cycloneVersion", AgentRunSanitizer.cleanText(cycloneVersion))
        .put("versionCode", versionCode)
        .put("buildIdentifier", AgentRunSanitizer.cleanText(buildIdentifier))
        .put("taskSessionId", record.id)
        .put("generatedAtMs", generatedAtMs)
        .put("screenshotsIncluded", false)
        .put("privateReasoningIncluded", false)
        .put("contents", JSONArray(listOf("run.json", "timeline.txt", "metadata.json")))

    fun writeZip(record: AgentRunRecord, metadata: JSONObject, output: OutputStream) {
        val safe = AgentRunSanitizer.sanitizeRecord(record)
        val runJson = AgentRunCodec.toJson(safe)
            .put("exportPolicy", JSONObject()
                .put("screenshotsIncluded", false)
                .put("hiddenChainOfThoughtIncluded", false)
                .put("sensitiveValuesRedacted", true))
        val safeMetadata = AgentRunSanitizer.sanitizeObject(metadata)
        ZipOutputStream(output).use { zip ->
            write(zip, "run.json", runJson.toString(2))
            write(zip, "timeline.txt", timeline(safe))
            write(zip, "metadata.json", safeMetadata.toString(2))
        }
    }

    fun archiveBytes(record: AgentRunRecord, metadata: JSONObject): ByteArray = ByteArrayOutputStream().use { out ->
        writeZip(record, metadata, out)
        out.toByteArray()
    }

    fun timeline(record: AgentRunRecord): String = buildString {
        record.events.sortedWith(compareBy<AgentRunEvent> { it.sequence }.thenBy { it.timestampMs }).forEach { event ->
            val clock = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(event.timestampMs))
            append(clock).append("  ").append(AgentRunTimeline.title(event))
            event.message.takeIf { it.isNotBlank() }?.let { append(" — ").append(AgentRunSanitizer.cleanText(it)) }
            event.payload.optString(AgentRunSchema.Payload.ERROR_CLASS).takeIf(String::isNotBlank)?.let { append(" [").append(it.take(120)).append(']') }
            append('\n')
        }
    }

    private fun write(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
