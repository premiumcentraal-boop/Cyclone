package com.cyclone.mobile.connector

import org.junit.Assert.*
import org.junit.Test

class ProfileConfigCapacityTest {
    @Test fun full327AppInventoryDoesNotHitTheFormer256Limit() {
        repeat(327) { ProfileConfigCapacity.check(it, replacing = false) }
    }

    @Test fun twenty327AppProfilesFitOneCompanionInstallation() {
        repeat(20 * 327) { ProfileConfigCapacity.check(it, replacing = false) }
    }

    @Test fun quotaRemainsBoundedAndRetainsTheExistingErrorContract() {
        ProfileConfigCapacity.check(ProfileConfigCapacity.MAX_RECORDS - 1, replacing = false)
        try {
            ProfileConfigCapacity.check(ProfileConfigCapacity.MAX_RECORDS, replacing = false)
            fail("Quota must remain enforced")
        } catch (error: ConnectorException) {
            assertEquals("BAD_REQUEST", error.code)
            assertEquals("At most 8192 config tuples per connector installation.", error.message)
        }
    }

    @Test fun updatesRemainAllowedAtOrAboveTheQuota() {
        ProfileConfigCapacity.check(ProfileConfigCapacity.MAX_RECORDS, replacing = true)
        ProfileConfigCapacity.check(ProfileConfigCapacity.MAX_RECORDS + 1, replacing = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedNegativeCountIsRejected() {
        ProfileConfigCapacity.check(-1, replacing = false)
    }
}
