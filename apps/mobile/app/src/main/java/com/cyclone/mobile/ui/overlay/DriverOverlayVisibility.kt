package com.cyclone.mobile.ui.overlay

/** Desired faces survive temporary host-input yield, including updates that arrive during the stroke. */
internal class DriverOverlayVisibility {
    var panelWanted = false
        private set
    var dimWanted = false
        private set
    private var buttonWanted = true
    var yielding = false

    val buttonVisible get() = buttonWanted && !yielding
    val panelVisible get() = panelWanted && !yielding
    val dimVisible get() = dimWanted && !yielding

    fun show(panel: Boolean, dim: Boolean) {
        panelWanted = panel
        dimWanted = dim
        buttonWanted = !panel
    }

    fun showButton() { buttonWanted = true }
}
