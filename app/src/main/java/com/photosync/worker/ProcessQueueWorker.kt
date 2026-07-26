package com.photosync.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.photosync.data.AppDatabase
import com.photosync.data.PreferencesManager
import com.photosync.network.UploadClient

/**
 * Processes all PENDING and FAILED photos in the queue.
 * Runs sequentially: uploads each photo one at a time.
 *
 * Triggered by:
 *  - ConnectivityReceiver when Wi-Fi/ethernet connects
 *  - Periodic worker every 15 minutes (fallback)
 *  - Explicitly from the UI "Sync Now" button
 */
class ProcessQueueWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "ProcessQueueWorker"
        const val MAX_RETRIES = 5
    }

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager(applicationContext)
        val settings = prefs.getSettings()

        if (settings.serverUrl.isBlank()) {
            Log.w(TAG, "Server URL not configured, skipping")
            return Result.success()
        }

        val db = AppDatabase.getInstance(applicationContext)
        val dao = db.photoDao()
        val uploadClient = UploadClient(applicationContext.contentResolver, applicationContext.cacheDir)

        // Check if server is reachable first
        if (!uploadClient.isServerReachable(settings.serverUrl, settings.apiToken)) {
            Log.d(TAG, "Server not reachable, will retry later")
            return Result.retry()
        }

        // Get all pending and failed entries (oldest first), skip ones that exceeded retries
        val entries = dao.getPendingAndFailed()
            .filter { it.attempts < MAX_RETRIES }
        if (entries.isEmpty()) {
            Log.d(TAG, "No photos to upload")
            return Result.success()
        }

        Log.i(TAG, "Processing ${entries.size} photo(s)")

        var anyFailed = false
        var uploaded = 0

        for (entry in entries) {
            // Mark as uploading
            dao.markStatus(entry.id, com.photosync.data.PhotoEntry.Status.UPLOADING)

            val result = uploadClient.uploadPhoto(entry, settings.serverUrl, settings.apiToken)

            if (result.success) {
                dao.markCompleted(entry.id)
                uploaded++
                Log.d(TAG, "Uploaded: ${entry.displayName}")
            } else {
                dao.markFailed(
                    entry.id,
                    com.photosync.data.PhotoEntry.Status.FAILED,
                    result.message
                )
                Log.w(TAG, "Failed: ${entry.displayName} - ${result.message}")

                // If server unreachable, stop processing — nothing else will work
                if (result.message.contains("unreachable", ignoreCase = true)) {
                    anyFailed = true
                    break
                }
                anyFailed = true
            }
        }

        // Prune completed entries older than 7 days
        val sevenDaysAgo = System.currentTimeMillis() / 1000 - 7 * 86400
        dao.pruneCompleted(sevenDaysAgo)

        Log.i(TAG, "Done: $uploaded uploaded, queue remaining: ${dao.pendingCount()}")

        return if (anyFailed && uploaded == 0) Result.retry() else Result.success()
    }
}
