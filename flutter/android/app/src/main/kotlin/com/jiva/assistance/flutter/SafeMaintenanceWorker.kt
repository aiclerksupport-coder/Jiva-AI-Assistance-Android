package com.jiva.assistance.flutter

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

/** Offline, idempotent app-private integrity check only; no Accessibility or mic. */
class SafeMaintenanceWorker(context: Context, params: WorkerParameters): Worker(context, params) {
    override fun doWork(): Result {
        return try {
        // Clean only stale, app-owned incomplete model imports. Never touch
        // the active model, user data, the secure journal or third-party apps.
        val staged = File(applicationContext.filesDir, "jiva_offline_model/staged.gguf")
        val staleAgeMs = 24L * 60L * 60L * 1000L
        if (staged.isFile && staged.lastModified() > 0 &&
            System.currentTimeMillis() - staged.lastModified() > staleAgeMs) {
            if (!staged.delete()) return Result.retry()
        }
        val directory = File(applicationContext.filesDir, "maintenance")
        directory.mkdirs()
        val state = File(directory, "last-ok.txt")
        val temp = File(directory, "last-ok.tmp")
        temp.writeText(System.currentTimeMillis().toString())
        if (!temp.renameTo(state)) {
            temp.copyTo(state, overwrite = true)
            temp.delete()
        }
        Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
