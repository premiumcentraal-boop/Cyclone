package com.cyclone.mobile.ui.overlay

internal object SheetDismissal {
    fun shouldDismiss(offsetPx: Float, heightPx: Float): Boolean =
        heightPx > 0 && offsetPx >= heightPx * .28f
}
