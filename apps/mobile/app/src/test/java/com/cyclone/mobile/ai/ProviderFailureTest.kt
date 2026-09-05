package com.cyclone.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderFailureTest {
    @Test fun providerFailuresHaveActionableSafeMessages() {
        assertEquals("provider.authentication", ProviderFailure.code(401))
        assertEquals("provider.credit", ProviderFailure.code(402))
        assertEquals("provider.rate_limit", ProviderFailure.code(429))
        assertEquals("provider.timeout_or_network", ProviderFailure.code(0))
        listOf(0, 401, 402, 403, 408, 429, 500, 504).forEach {
            assertNotNull(ProviderFailure.message(ProviderFailure.code(it)))
        }
        assertNull(ProviderFailure.message("untrusted provider content"))
    }
}
