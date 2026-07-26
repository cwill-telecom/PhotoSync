package com.photosync.monitor

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.photosync.data.AppDatabase
import com.photosync.data.PhotoEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans MediaStore for photos the ContentObserver may have missed
 * (taken before the app was installed, or while the app was killed).
 *
 * Uses an incremental approach: first run scans the most recent 200 photos;
 * subsequent runs only scan photos added after the last scan timestamp.
 */
class MediaScanner(
    private val contentResolver: ContentResolver,
    private val database: AppDatabase
) {
    companion object {
        private const val TAG = "MediaScanner"
        private const val INITIAL_SCAN_LIMIT = 200
        private const val MAX_SCAN_LIMIT = 500

        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
    }

    data class ScanResult(
        val found: Int,
        val new: Int,
        val skipped: Int
    )

    /**
     * Scan for photos added after [lastScanTimestamp].
     * If lastScanTimestamp is 0 or negative, performs an initial scan
     * of the most recent [INITIAL_SCAN_LIMIT] photos.
     */
    suspend fun scan(lastScanTimestamp: Long): ScanResult = withContext(Dispatchers.IO) {
        val dao = database.photoDao()
        val isInitialScan = lastScanTimestamp <= 0

        val selection: String?
        val selectionArgs: Array<String>?
        val sortOrder: String
        val limit: Int

        if (isInitialScan) {
            selection = null
            selectionArgs = null
            sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            limit = INITIAL_SCAN_LIMIT
        } else {
            selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
            selectionArgs = arrayOf(lastScanTimestamp.toString())
            sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"
            limit = MAX_SCAN_LIMIT
        }

        var found = 0
        var new = 0
        var skipped = 0
        val maxTimestamp = lastScanTimestamp

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            PROJECTION,
            selection,
            selectionArgs,
            "$sortOrder LIMIT $limit"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                found++
                val id = cursor.getLong(idCol)
                val dateAdded = cursor.getLong(dateCol)

                // Track the highest timestamp seen
                // (var for lambda, so use array trick)

                val contentUri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id.toString()
                ).toString()

                // Skip if already in the queue
                if (dao.getByUri(contentUri) != null) {
                    skipped++
                    continue
                }

                val displayName = cursor.getString(nameCol)
                val size = cursor.getLong(sizeCol)

                dao.insert(
                    PhotoEntry(
                        contentUri = contentUri,
                        displayName = displayName,
                        dateAdded = dateAdded,
                        sizeBytes = size
                    )
                )
                new++
            }
        }

        Log.i(TAG, "Scan complete: found=$found, new=$new, skipped=$skipped, " +
                "initial=${isInitialScan}")

        ScanResult(found, new, skipped)
    }
}
