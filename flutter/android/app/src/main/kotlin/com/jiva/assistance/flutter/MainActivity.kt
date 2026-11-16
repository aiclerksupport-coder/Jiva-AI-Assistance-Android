package com.jiva.assistance.flutter

import android.os.Bundle
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.TimeUnit

class MainActivity : FlutterFragmentActivity() {
    private val workName = "jiva_safe_private_maintenance_v1"
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger,
            "jiva/safe_background_scheduler").setMethodCallHandler { call, result ->
            try {
                val manager = WorkManager.getInstance(applicationContext)
                when (call.method) {
                    "enableMaintenance" -> {
                        val request = PeriodicWorkRequestBuilder<SafeMaintenanceWorker>(
                            24, TimeUnit.HOURS)
                            .setConstraints(Constraints.Builder().build())
                            .build()
                        manager.enqueueUniquePeriodicWork(workName,
                            ExistingPeriodicWorkPolicy.KEEP, request)
                        result.success(true)
                    }
                    "disableMaintenance" -> {
                        manager.cancelUniqueWork(workName)
                        result.success(true)
                    }
                    "maintenanceStatus" -> {
                        result.success(mapOf("enabledConfigurationOnly" to true,
                            "note" to "OS controls actual execution time"))
                    }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("SAFE_WORK_ERROR", "Maintenance scheduler failed", null)
            }
        }
    }
}
