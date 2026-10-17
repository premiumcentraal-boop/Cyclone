package com.cyclone.mobile.agent.contract

import org.json.JSONObject

/**
 * A JSON object as text to search, the same on the phone and in tests.
 *
 * Android's org.json escapes '/' as "\/" in toString() (JSON-java, which unit tests used, does not), so on the phone
 * `"${page.pageSummary}"` read `https:\/\/reddit.com\/` and the host pattern, which needs `/`, `?`, `#`, `:` or a space
 * after the host, never matched: "open reddit.com and log in" could not be confirmed done. Unescaping "\/" gives back
 * exactly what the page showed.
 */
internal fun JSONObject.searchText(): String = toString().replace("\\/", "/")
