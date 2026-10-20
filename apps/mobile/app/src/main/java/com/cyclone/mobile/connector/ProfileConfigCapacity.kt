package com.cyclone.mobile.connector

/** Bounded per-installation quota, large enough for multiple full application inventories. */
internal object ProfileConfigCapacity {
    const val MAX_RECORDS = 8192

    fun check(existingRecords: Int, replacing: Boolean) {
        require(existingRecords >= 0)
        // Existing records remain writable even when an older installation is over quota.
        if (!replacing && existingRecords >= MAX_RECORDS) {
            throw ConnectorException("BAD_REQUEST", "At most $MAX_RECORDS config tuples per connector installation.")
        }
    }
}
