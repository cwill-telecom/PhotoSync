package com.photosync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    @Insert
    suspend fun insert(entry: PhotoEntry): Long

    @Insert
    suspend fun insertAll(entries: List<PhotoEntry>)

    @Update
    suspend fun update(entry: PhotoEntry)

    @Query("SELECT * FROM photos ORDER BY dateAdded DESC")
    fun getAllFlow(): Flow<List<PhotoEntry>>

    @Query("SELECT * FROM photos WHERE status = :status ORDER BY dateAdded ASC")
    suspend fun getByStatus(status: PhotoEntry.Status): List<PhotoEntry>

    @Query("SELECT COUNT(*) FROM photos WHERE status = :status")
    fun countByStatus(status: PhotoEntry.Status): Flow<Int>

    @Query("SELECT * FROM photos WHERE status = 'PENDING' OR status = 'FAILED' ORDER BY dateAdded ASC")
    suspend fun getPendingAndFailed(): List<PhotoEntry>

    @Query("SELECT * FROM photos WHERE contentUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): PhotoEntry?

    @Query("UPDATE photos SET status = :status, lastError = :error, attempts = attempts + 1 WHERE id = :id")
    suspend fun markFailed(id: Long, status: PhotoEntry.Status = PhotoEntry.Status.FAILED, error: String?)

    @Query("UPDATE photos SET status = :status, attempts = attempts + 1 WHERE id = :id")
    suspend fun markStatus(id: Long, status: PhotoEntry.Status)

    @Query("UPDATE photos SET status = 'COMPLETED' WHERE id = :id")
    suspend fun markCompleted(id: Long)

    @Query("DELETE FROM photos WHERE status = 'COMPLETED' AND dateAdded < :olderThan")
    suspend fun pruneCompleted(olderThan: Long)

    @Query("SELECT COUNT(*) FROM photos WHERE status = 'PENDING' OR status = 'UPLOADING' OR status = 'FAILED'")
    suspend fun pendingCount(): Int
}
