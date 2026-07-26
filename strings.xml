package com.photosync.monitor

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.photosync.data.AppDatabase
import com.photosync.data.PhotoEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Observes MediaStore.Images for new photos.
 * When a new image is inserted, adds it to the Room queue.
 */
class PhotoContentObserver(
    handler: Handler,
    private val contentResolver: ContentResolver,
    private val database: AppDatabase
) : ContentObserver(handler) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = database.photoDao()

    companion object {
        private const val TAG = "PhotoContentObserver"

        // Projection: what columns we need to read
        val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )

        const val SORT_ORDER = "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 1"
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        if (uri == null) return

        Log.d(TAG, "MediaStore change detected: $uri")

        scope.launch {
            try {
                val cursor = contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    PROJECTION,
                    null, null,
                    SORT_ORDER
                )

                cursor?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        val displayName = it.getString(
                            it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        )
                        val dateAdded = it.getLong(
                            it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                        )
                        val size = it.getLong(
                            it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                        )

                        val contentUri = Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id.toString()
                        ).toString()

                        // Skip if already queued
                        val existing = dao.getByUri(contentUri)
                        if (existing != null) {
                            Log.d(TAG, "Photo already queued: $displayName")
                            return@launch
                        }

                        val entry = PhotoEntry(
                            contentUri = contentUri,
                            displayName = displayName,
                            dateAdded = dateAdded,
                            sizeBytes = size
                        )
                        dao.insert(entry)
                        Log.i(TAG, "Queued new photo: $displayName (id=$id)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling new photo", e)
            }
        }
    }
}
