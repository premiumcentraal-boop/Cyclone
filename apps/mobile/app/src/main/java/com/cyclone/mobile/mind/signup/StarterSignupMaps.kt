package com.cyclone.mobile.mind.signup

import android.content.Context
import org.json.JSONObject

/** Packaged field definitions for Account Setup; separate from maps learned on this phone. */
object StarterSignupMaps {
    fun load(context: Context, packageName: String): SignupMap? =
        resolve(packageName) { context.applicationContext.assets.open(it).bufferedReader().use { reader -> reader.readText() } }

    internal fun resolve(packageName: String, readAsset: (String) -> String): SignupMap? {
        if (packageName != "com.instagram.android") return null
        return runCatching { SignupMap.fromJson(JSONObject(readAsset("signup/instagram.json"))) }
            .getOrNull()?.takeIf { it.packageName == packageName && it.complete && it.finalLabel != null }
    }
}
