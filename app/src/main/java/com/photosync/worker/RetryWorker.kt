package com.photosync.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.photosync.data.AppDatabase
import com.photosync.data.PreferencesManager
import com.photosync.network.UploadClient

/**
 * Periodic worker that retries FAILED uploads.
 * Runs every 30 minutes to catch any photos that
 * failed due to transient network issues.
 */
class RetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "RetryWorker"
        const val MAX_RETRIES = 5
    }

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager(applicationContext)
        val settings = prefs.getSettings()

        if (settings.serverUrl.isBlank()) return Result.success()

        val db = AppDatabase.getInstance(applicationContext)
        val dao = db.photoDao()
        val uploadClient = UploadClient(applicationContext.contentResolver, applicationContext.cacheDir)

        // Check reachability
        if (!uploadClient.isServerReachable(settings.serverUrl, settings.apiToken)) {
            return Result.success() // Don't retry the worker, wait for next cycle
        }

        // Get only FAILED entries that haven't exceeded max retries
        val failedEntries = dao.getByStatus(com.photosync.data.PhotoEntry.Status.FAILED)
            .filter { it.attempts < MAX_RETRIES }

        if (failedEntries.isEmpty()) return Result.success()

        Log.i(TAG, "Retrying ${failedEntries.size} failed photo(s)")

        var uploaded = 0
        for (entry in failedEntries) {
            dao.markStatus(entry.id, com.photosync.data.PhotoEntry.Status.UPLOADING)

            val result = uploadClient.uploadPhoto(entry, settings.serverUrl, settings.apiToken)
            if (result.success) {
                dao.markCompleted(entry.id)
                uploaded++
            } else {
                dao.markFailed(entry.id, com.photosync.data.PhotoEntry.Status.FAILED, result.message)
            }
        }

        Log.i(TAG, "Retry done: $uploaded recovered")
        return Result.success()
    }
}
