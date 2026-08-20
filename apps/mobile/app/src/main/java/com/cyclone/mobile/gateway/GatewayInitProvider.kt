package com.cyclone.mobile.gateway

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Process bootstrap only. It starts no listener unless the user has explicitly enabled PC Gateway.
 * This lets the localabstract listener recover when Android recreates Cyclone for Accessibility.
 */
class GatewayInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(GatewayRuntime::startIfEnabled)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
