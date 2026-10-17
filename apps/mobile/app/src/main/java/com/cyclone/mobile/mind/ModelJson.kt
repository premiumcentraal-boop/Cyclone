package com.cyclone.mobile.mind

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON written by a model, read the way Cyclone means it: an explicit `null` is the same as leaving the key out.
 *
 * Models often send optional fields as `null` (strict tool schemas even require it). On Android, `optString(key)` on a
 * JSON null returns the text "null" (AOSP's `JSON.toString(JSONObject.NULL)`), even with a fallback; the desktop
 * JSON-java library that unit tests used returns "". So `{"app":"WhatsApp","package":null}` read a package called
 * "null" on the phone while every test passed. Everything parsed from model output goes through [parse] (or
 * [withoutNulls]), so a null never reaches an `optString`. Pure.
 */
object ModelJson {
    /** Parses [text] as a JSON object and drops every JSON null in it. Throws like `JSONObject(text)` on bad JSON. */
    fun parse(text: String): JSONObject = withoutNulls(JSONObject(text))

    /** A copy of [json] with every JSON null removed, at any depth (array elements that are null are dropped). */
    fun withoutNulls(json: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            clean(json.opt(key))?.let { out.put(key, it) }
        }
        return out
    }

    fun withoutNulls(array: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until array.length()) clean(array.opt(i))?.let { out.put(it) }
        return out
    }

    private fun clean(value: Any?): Any? = when {
        value == null || value == JSONObject.NULL -> null
        value is JSONObject -> withoutNulls(value)
        value is JSONArray -> withoutNulls(value)
        else -> value
    }
}
