package com.photosync

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.photosync.receiver.BootReceiver
import com.photosync.service.SyncService
import com.photosync.worker.ProcessQueueWorker
import com.photosync.worker.RetryWorker
import java.util.concurrent.TimeUnit

class PhotoSyncApp : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "PhotoSyncApp"
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Initialize WorkManager with our config
        WorkManager.initialize(this, workManagerConfiguration)

        // Schedule periodic workers
        schedulePeriodicWorkers()

        // Start the foreground sync service
        startSyncService()
    }

    private fun schedulePeriodicWorkers() {
        // Process queue every 15 minutes (catches anything the observer missed)
        val periodicQueue = PeriodicWorkRequestBuilder<ProcessQueueWorker>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            BootReceiver.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicQueue
        )

        // Retry failed uploads every 30 minutes
        val periodicRetry = PeriodicWorkRequestBuilder<RetryWorker>(
            30, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "photosync_periodic_retry",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRetry
        )

        Log.i(TAG, "Periodic workers scheduled")
    }

    private fun startSyncService() {
        val intent = Intent(this, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.i(TAG, "SyncService started")
    }
}
