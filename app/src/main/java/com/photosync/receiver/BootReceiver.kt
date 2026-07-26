package com.photosync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.photosync.worker.ProcessQueueWorker
import com.photosync.worker.RetryWorker
import java.util.concurrent.TimeUnit

/**
 * Re-schedules workers after device reboot.
 * The Application class handles ContentObserver re-registration.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val PERIODIC_WORK_NAME = "photosync_periodic_queue"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed, scheduling workers")

        // Periodic queue processor: every 15 minutes
        val periodicWork = PeriodicWorkRequestBuilder<ProcessQueueWorker>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWork
        )

        // Periodic retry for failed uploads: every 30 minutes
        val retryWork = PeriodicWorkRequestBuilder<RetryWorker>(
            30, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "photosync_periodic_retry",
            ExistingPeriodicWorkPolicy.UPDATE,
            retryWork
        )
    }
}
