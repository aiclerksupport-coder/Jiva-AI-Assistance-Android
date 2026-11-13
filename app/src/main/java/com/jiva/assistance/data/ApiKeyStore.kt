package com.jiva.assistance.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the user's own API key at rest. A client-side key cannot be protected from a compromised device. */
class ApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("jiva_secure", Context.MODE_PRIVATE)
    private val alias = "jiva_gemini_key"

    fun save(value: String) {
        require(value.isNotBlank())
        saveEncrypted("iv", "ciphertext", value)
    }

    fun read(): String = readEncrypted("iv", "ciphertext")

    fun savePicovoiceAccessKey(value: String) {
        require(value.isNotBlank()) { "Enter your Picovoice AccessKey." }
        saveEncrypted("picovoice_iv", "picovoice_ciphertext", value)
    }

    fun readPicovoiceAccessKey(): String =
        readEncrypted("picovoice_iv", "picovoice_ciphertext")

    fun clearPicovoiceAccessKey() {
        prefs.edit().remove("picovoice_iv").remove("picovoice_ciphertext").apply()
    }

    fun saveWakeWordModel(name: String, path: String) {
        require(name == "jiva" || name == "giva") { "Unsupported wake-word model slot." }
        require(path.endsWith(".ppn", ignoreCase = true)) { "Wake-word model must be a .ppn file." }
        prefs.edit().putString("wake_model_$name", path).apply()
    }

    fun readWakeWordModels(): List<String> =
        listOf("jiva", "giva").mapNotNull { name ->
            prefs.getString("wake_model_$name", null)
                ?.takeIf { java.io.File(it).isFile && it.endsWith(".ppn", ignoreCase = true) }
        }

    fun clearWakeWordModels() {
        prefs.edit().remove("wake_model_jiva").remove("wake_model_giva").apply()
    }

    fun isWakeServiceEnabled(): Boolean = prefs.getBoolean("wake_service_enabled", false)

    fun setWakeServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("wake_service_enabled", enabled).apply()
    }

    fun clear() {
        prefs.edit().remove("iv").remove("ciphertext").apply()
    }

    private fun saveEncrypted(ivKey: String, ciphertextKey: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(ciphertextKey, Base64.encodeToString(ciphertext, Base64.NO_WRAP)).apply()
    }

    private fun readEncrypted(ivKey: String, ciphertextKey: String): String {
        val iv = prefs.getString(ivKey, null) ?: return ""
        val encrypted = prefs.getString(ciphertextKey, null) ?: return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
