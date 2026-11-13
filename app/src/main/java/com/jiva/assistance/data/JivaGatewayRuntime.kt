package com.jiva.assistance.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.jiva.assistance.BuildConfig
import kotlinx.coroutines.tasks.await

/**
 * Production Gemini calls require authenticated Firebase user + genuine
 * Play Integrity App Check. Legacy BYOK remains development-only.
 *
 * The API key here is Firebase's public client identifier, NOT Gemini's
 * server-side secret. All values are supplied at build time.
 */
object JivaGatewayRuntime {
    val required: Boolean get() = !BuildConfig.DEBUG || BuildConfig.JIVA_REQUIRE_GATEWAY

    @Volatile private var app: FirebaseApp? = null

    private fun firebaseApp(context: Context): FirebaseApp {
        app?.let { return it }
        synchronized(this) {
            app?.let { return it }
            val url = BuildConfig.JIVA_GATEWAY_URL
            check(url.startsWith("https://") && url.isNotBlank()) {
                "Configure an HTTPS Jiva gateway for release builds."
            }
            check(BuildConfig.JIVA_FIREBASE_PROJECT_ID.isNotBlank() &&
                BuildConfig.JIVA_FIREBASE_APP_ID.isNotBlank() &&
                BuildConfig.JIVA_FIREBASE_WEB_API_KEY.isNotBlank()) {
                "Configure public Firebase project/app identifiers for release."
            }
            val existing = FirebaseApp.getApps(context)
                .firstOrNull { it.name == "jiva-gateway" }
            val instance = existing ?: FirebaseApp.initializeApp(
                context.applicationContext,
                FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.JIVA_FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.JIVA_FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.JIVA_FIREBASE_WEB_API_KEY)
                    .build(),
                "jiva-gateway",
            )
            FirebaseAppCheck.getInstance(instance).installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance(),
            )
            app = instance
            return instance
        }
    }

    fun client(context: Context): JivaGatewayClient {
        val firebase = firebaseApp(context)
        return JivaGatewayClient(BuildConfig.JIVA_GATEWAY_URL) {
            val user = FirebaseAuth.getInstance(firebase).currentUser
                ?: error("Sign in to your verified Jiva account before using cloud AI.")
            check(!user.isAnonymous && user.isEmailVerified) { "A verified Jiva user account is required." }
            val id = user.getIdToken(false).await().token
            val appCheck = FirebaseAppCheck.getInstance(firebase)
                .getAppCheckToken(false).await().token
            check(!id.isNullOrBlank() && appCheck.isNotBlank()) {
                "Firebase identity or App Check token is missing."
            }
            JivaGatewayTokens(id, appCheck)
        }
    }

    suspend fun signIn(context: Context, email: String, password: String) {
        require(email.isNotBlank() && password.isNotBlank()) {
            "Email and password are required."
        }
        val firebase = firebaseApp(context)
        FirebaseAuth.getInstance(firebase)
            .signInWithEmailAndPassword(email.trim(), password).await()
        val user = FirebaseAuth.getInstance(firebase).currentUser
        check(user != null && !user.isAnonymous) { "Verified Jiva login required." }
        if (!user.isEmailVerified) {
            user.sendEmailVerification().await()
            throw IllegalStateException("Verify your email address, then sign in again.")
        }
    }

    fun signOut(context: Context) {
        FirebaseAuth.getInstance(firebaseApp(context)).signOut()
    }
}
