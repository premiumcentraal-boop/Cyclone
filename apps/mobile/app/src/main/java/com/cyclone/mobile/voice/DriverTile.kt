package com.cyclone.mobile.voice

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile "Cyclone Drive" (plan 32): Driver mode on or off from the shade, before you set off. */
class DriverTile : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        DriverMode.setEnabled(this, !DriverMode.enabled(this))
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = DriverMode.enabled(this)
        tile.label = "Cyclone Drive"
        tile.subtitle = if (on) "On" else "Off"
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.contentDescription = if (on) "Cyclone Drive is on" else "Cyclone Drive is off"
        tile.updateTile()
    }
}
