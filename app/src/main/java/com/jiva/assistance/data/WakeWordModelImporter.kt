package com.jiva.assistance.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
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

        // AtomicFile preserves the last complete model if the incoming file is
        // truncated, too large, or the device loses power during replacement.
        val directory = File(context.filesDir, "wakewords").apply {
            check(exists() || mkdirs()) { "Could not create wake-word model directory." }
        }
        val target = File(directory, "$name.ppn")
        val atomic = AtomicFile(target)
        context.contentResolver.openInputStream(uri)?.use { source ->
            var output: java.io.FileOutputStream? = null
            try {
                output = atomic.startWrite()
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
                atomic.finishWrite(output)
                output = null
            } catch (error: Exception) {
                if (output != null) atomic.failWrite(output)
                throw error
            }
        } ?: throw IOException("Could not read the selected wake-word model.")
        return target.absolutePath
    }
}
