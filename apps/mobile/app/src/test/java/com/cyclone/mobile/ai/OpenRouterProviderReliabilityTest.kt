package com.cyclone.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenRouterProviderReliabilityTest {
    @Before
    fun clearCache() {
        OpenRouterQualificationCache.clearForTests()
    }

    @Test
    fun successfulResponseHasNoProviderFailure() {
        val result = OpenRouterProviderReliability.parseHttp(200, "{\"choices\":[]}")
        assertTrue(result.ok)
        assertNull(result.failure)
    }

    @Test
    fun authAndAccessFailuresAreTyped() {
        assertEquals(
            OpenRouterFailureCode.PROVIDER_AUTH_FAILED,
            OpenRouterProviderReliability.parseHttp(401, "{\"error\":{\"message\":\"invalid key\"}}").failure?.code,
        )
        assertEquals(
            OpenRouterFailureCode.MODEL_ACCESS_DENIED,
            OpenRouterProviderReliability.parseHttp(403, "{\"error\":{\"message\":\"model access denied\"}}").failure?.code,
        )
        assertEquals(
            OpenRouterFailureCode.MODEL_NOT_FOUND,
            OpenRouterProviderReliability.parseHttp(404, "{\"error\":{\"message\":\"model not found\"}}").failure?.code,
        )
    }

    @Test
    fun routingAndParameterFailuresRemainDistinct() {
        assertEquals(
            OpenRouterFailureCode.NO_PROVIDER_AVAILABLE,
            OpenRouterProviderReliability.parseHttp(400, "{\"error\":{\"message\":\"No available provider for this model\"}}").failure?.code,
        )
        assertEquals(
            OpenRouterFailureCode.ROUTING_CONSTRAINT_UNSATISFIED,
            OpenRouterProviderReliability.parseHttp(400, "{\"error\":{\"message\":\"data_collection routing preference cannot be satisfied\"}}").failure?.code,
        )
        assertEquals(
            OpenRouterFailureCode.PARAMETER_UNSUPPORTED,
            OpenRouterProviderReliability.parseHttp(400, "{\"error\":{\"message\":\"response_format is not supported\"}}").failure?.code,
        )
    }

    @Test
    fun rateLimitAndContextFailuresExposeRetrySemantics() {
        val rate = OpenRouterProviderReliability.parseHttp(429, "{\"error\":{\"message\":\"rate limited\"}}").failure!!
        assertEquals(OpenRouterFailureCode.RATE_LIMITED, rate.code)
        assertTrue(rate.retryable)

        val context = OpenRouterProviderReliability.parseHttp(400, "{\"error\":{\"message\":\"maximum context length exceeded\"}}").failure!!
        assertEquals(OpenRouterFailureCode.CONTEXT_LIMIT, context.code)
        assertFalse(context.retryable)
    }

    @Test
    fun providerMetadataAndRequestIdArePreservedWithoutSecrets() {
        val result = OpenRouterProviderReliability.parseHttp(
            503,
            "{\"error\":{\"message\":\"Authorization: Bearer super-secret-token failed\",\"metadata\":{\"provider_name\":\"ExampleProvider\"}}}",
            requestId = "req_123",
        ).failure!!
        assertEquals("ExampleProvider", result.provider)
        assertEquals("req_123", result.requestId)
        assertFalse(result.safeMessage.contains("super-secret-token"))
        assertTrue(result.safeMessage.contains("[REDACTED]"))
    }

    @Test
    fun qualificationCacheIsPerExactModelIdentity() {
        val normal = OpenRouterModelPresets.MUSE_SPARK_1_3.id
        val contributor = OpenRouterModelPresets.MUSE_SPARK_1_3_CONTRIBUTOR.id
        OpenRouterQualificationCache.markQualified(normal, nowMillis = 1_000)
        assertTrue(OpenRouterQualificationCache.isQualified(normal, nowMillis = 1_001))
        assertFalse(OpenRouterQualificationCache.isQualified(contributor, nowMillis = 1_001))
        assertFalse(OpenRouterQualificationCache.isQualified(normal, nowMillis = 1_000 + 6L * 60L * 60L * 1000L + 1))
    }
}
