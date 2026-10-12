package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.market.MarketRules

/** First-party details cards only. The separate Run/Share controls never carry this tag. */
internal object SkillDetailsNavigation {
    const val TAG_PREFIX = "cyclone_skill_details:"
    fun tag(id: String) = TAG_PREFIX + id

    fun labels(packageName: String?, activation: UiNodeSnapshot): List<String>? {
        if (packageName != "com.cyclone.mobile" || !activation.clickable || activation.editable) return null
        if (!activation.resourceId.startsWith(TAG_PREFIX)) return null
        if (!MarketRules.LISTING_ID.matches(activation.resourceId.removePrefix(TAG_PREFIX))) return null
        return listOf("Open skill details")
    }
}
