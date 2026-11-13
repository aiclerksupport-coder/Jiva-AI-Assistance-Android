package com.jiva.assistance.data

import kotlinx.coroutines.delay
import java.io.IOException

class GeminiApiException(
    val statusCode: Int,
    message: String,
) : IOException(message) {
    val retryable: Boolean
        get() = statusCode == 408 || statusCode == 429 || statusCode in 500..599
}

object GeminiRetryPolicy {
    const val MAX_ATTEMPTS = 3

    fun shouldRetry(error: Throwable): Boolean =
        error is GeminiApiException && error.retryable ||
            error is IOException && error !is GeminiApiException

    suspend fun <T> execute(
        onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
        operation: suspend () -> T,
    ): T {
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return operation()
            } catch (error: Exception) {
                if (attempt == MAX_ATTEMPTS || !shouldRetry(error)) throw error
                onRetry(attempt, error)
                delay(1_000L shl (attempt - 1))
            }
        }
        error("Unreachable retry state.")
    }
}
