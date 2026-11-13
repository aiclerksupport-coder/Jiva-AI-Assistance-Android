package com.jiva.assistance.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Device-owned system prompt; Jiva NEVER reads/stores the owner's screen PIN.
 * No Firebase, cloud identity, separate Jiva PIN or cached unlock session.
 */
class OwnerKeyAuthorization(private val activity: FragmentActivity) {
    suspend fun authenticate(action: String): Boolean = suspendCancellableCoroutine { continuation ->
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            // Android 8-10 does not support STRONG | DEVICE_CREDENTIAL together.
            // AndroidX provides the compatible device-credential fallback.
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        if (BiometricManager.from(activity).canAuthenticate(authenticators) !=
            BiometricManager.BIOMETRIC_SUCCESS) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(false)
                }
                // A failed fingerprint scan is not success. Android handles
                // retry/lockout and may offer the device credential instead.
            },
        )
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Jiva owner verification")
            .setSubtitle("Confirm with your phone fingerprint or screen lock")
            .setDescription("Required to $action your Gemini API key.")
            .setAllowedAuthenticators(authenticators)
            .build()
        try {
            prompt.authenticate(info)
        } catch (_: Exception) {
            if (continuation.isActive) continuation.resume(false)
        }
    }
}
