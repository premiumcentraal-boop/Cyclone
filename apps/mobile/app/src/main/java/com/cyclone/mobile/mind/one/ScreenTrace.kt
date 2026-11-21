package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.mission.MindRedaction
import org.json.JSONArray
import org.json.JSONObject

/**
 * The screen the crew sees, as JSON, so a recorded run can be replayed on a computer. Pictures are left out: the
 * decisions a replay checks read the controls and the text. Labels and text go through the same scrubbing the Lab's
 * reports use, so a recording never holds a password, a code or an e-mail address in clear.
 */
object ScreenCodec {
    fun encode(screen: StepScreen): JSONObject = JSONObject()
        .put("packageName", screen.packageName)
        .put("appLabel", MindRedaction.scrubText(screen.appLabel))
        .put("pageKey", screen.pageKey)
        .put("fingerprint", screen.fingerprint)
        .put("identity", screen.identity)
        .put("weak", screen.weak)
        .put("sensitive", screen.sensitive)
        .put("texts", JSONArray(screen.texts.map { MindRedaction.scrubText(it) }))
        .put("controls", JSONArray(screen.controls.map { c ->
            JSONObject()
                .put("ref", c.ref)
                .put("label", MindRedaction.scrubText(c.label))
                .put("role", c.role)
                .put("editable", c.editable)
                .put("focused", c.focused)
                .put("value", c.value?.let { MindRedaction.scrubText(it) } ?: JSONObject.NULL)
        }))

    fun decode(json: JSONObject): StepScreen {
        val controls = json.getJSONArray("controls")
        val texts = json.getJSONArray("texts")
        return StepScreen(
            packageName = json.getString("packageName"),
            appLabel = json.optString("appLabel"),
            pageKey = json.optString("pageKey"),
            fingerprint = json.optString("fingerprint"),
            identity = json.optString("identity"),
            weak = json.optBoolean("weak"),
            sensitive = json.optBoolean("sensitive"),
            texts = (0 until texts.length()).map { texts.getString(it) },
            controls = (0 until controls.length()).map { i ->
                val c = controls.getJSONObject(i)
                StepControl(
                    ref = c.getString("ref"),
                    label = c.optString("label"),
                    role = c.optString("role"),
                    editable = c.optBoolean("editable"),
                    focused = c.optBoolean("focused"),
                    value = if (c.isNull("value")) null else c.optString("value"),
                )
            },
        )
    }
}

/**
 * Passes every look through unchanged and records a copy of what the phone showed. Recording is best effort: a sink that
 * fails never changes what the crew sees or decides.
 */
class RecordingEyes(private val inner: StepEyes, private val record: (JSONObject) -> Unit) : StepEyes {
    override fun look(image: Boolean): StepScreen? {
        val screen = inner.look(image)
        // A password, code or banking screen is never saved, whatever the owner has chosen for the words on other screens.
        if (screen != null && !screen.sensitive) {
            runCatching {
                record(JSONObject().put("kind", "screen").put("image", image).put("screen", ScreenCodec.encode(screen)))
            }
        }
        return screen
    }
}

/**
 * Where a Lab mission's screens are saved on the phone: the app's own external folder, which only adb can read. Words are
 * kept as they are, except secrets (passwords, codes), which are scrubbed by [ScreenCodec]. Nothing here leaves the phone
 * except by the owner's own adb pull into the local, git-ignored results folder. Owner missions never get a sink.
 */
object LabTrace {
    private val lock = Any()

    fun sinkFor(context: android.content.Context, missionId: String): (JSONObject) -> Unit {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = java.io.File(root, "lab-traces").apply { mkdirs() }
        val file = java.io.File(dir, "${safeName(missionId)}.jsonl")
        return { line -> synchronized(lock) { file.appendText(line.toString() + "\n") } }
    }

    internal fun safeName(missionId: String): String = missionId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
}
