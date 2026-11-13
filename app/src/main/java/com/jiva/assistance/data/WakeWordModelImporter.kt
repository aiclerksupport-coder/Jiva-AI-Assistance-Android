package com.jiva.assistance.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

object WakeWordModelImporter {
    private const val MAX_MODEL_BYTES = 10L * 1024 * 1024

    fun import(context: Context, uri: Uri, name: String): String {
        require(name == "jiva" || name == "giva") { "Unsupported wake-word model slot." }
        val displayName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
        require(displayName.endsWith(".ppn", ignoreCase = true)) {
            "Choose a Picovoice .ppn wake-word model."
        }
        val directory = File(context.filesDir, "wakewords").apply { mkdirs() }
        val target = File(directory, "$name.ppn")
        val temporary = File(directory, "$name.ppn.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not read the selected wake-word model.")
            input.use { source ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_MODEL_BYTES) { "Wake-word model is too large (10 MB limit)." }
                        output.write(buffer, 0, read)
                    }
                    require(total > 0) { "The selected wake-word model is empty." }
                }
            }
            if (target.exists() && !target.delete()) throw IOException("Could not replace the previous wake-word model.")
            if (!temporary.renameTo(target)) throw IOException("Could not save the selected wake-word model.")
            return target.absolutePath
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }
}
