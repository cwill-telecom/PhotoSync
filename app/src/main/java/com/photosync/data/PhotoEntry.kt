package com.photosync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photos")
data class PhotoEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** MediaStore content URI string, e.g. content://media/external/images/media/123 */
    val contentUri: String,
    /** Display name for the photo file */
    val displayName: String,
    /** Unix timestamp (seconds) when the photo was taken / detected */
    val dateAdded: Long,
    /** File size in bytes, -1 if unknown */
    val sizeBytes: Long = -1,
    /** Current upload status */
    val status: Status = Status.PENDING,
    /** Number of upload attempts */
    val attempts: Int = 0,
    /** Error message from the last failed attempt */
    val lastError: String? = null
) {
    enum class Status {
        PENDING,
        UPLOADING,
        COMPLETED,
        FAILED
    }
}
