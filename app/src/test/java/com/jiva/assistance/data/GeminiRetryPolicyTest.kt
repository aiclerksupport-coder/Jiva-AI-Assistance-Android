package com.jiva.assistance.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GeminiRetryPolicyTest {
    @Test
    fun retriesTransientNetworkAndServerFailuresOnly() {
        assertTrue(GeminiRetryPolicy.shouldRetry(IOException("connection lost")))
        assertTrue(GeminiRetryPolicy.shouldRetry(GeminiApiException(429, "rate limited")))
        assertTrue(GeminiRetryPolicy.shouldRetry(GeminiApiException(503, "unavailable")))
        assertFalse(GeminiRetryPolicy.shouldRetry(GeminiApiException(401, "unauthorized")))
        assertFalse(GeminiRetryPolicy.shouldRetry(IllegalArgumentException("bad input")))
    }
}
