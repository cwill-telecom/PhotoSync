package com.photosync.network

import android.content.ContentResolver
import android.net.Uri
import com.photosync.data.PhotoEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class UploadResult(
    val success: Boolean,
    val message: String = ""
)

class UploadClient(
    private val contentResolver: ContentResolver,
    private val cacheDir: File
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Quick check if the server is reachable.
     * Returns true if the server responds (any 2xx or 4xx).
     */
    suspend fun isServerReachable(serverUrl: String, token: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/health")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                response.close()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Upload a single photo to the server.
     * Copies the content URI to a temp file first (needed for OkHttp multipart).
     */
    suspend fun uploadPhoto(
        entry: PhotoEntry,
        serverUrl: String,
        token: String
    ): UploadResult = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            val uri = Uri.parse(entry.contentUri)

            // Copy content URI to a temp file for multipart upload
            tempFile = File(cacheDir, "upload_${entry.id}_${entry.displayName}")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext UploadResult(false, "Cannot open photo URI")

            if (tempFile.length() == 0L) {
                return@withContext UploadResult(false, "Photo file is empty")
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    entry.displayName,
                    tempFile.asRequestBody("image/*".toMediaType())
                )
                .addFormDataPart("date_added", entry.dateAdded.toString())
                .addFormDataPart("display_name", entry.displayName)
                .build()

            val request = Request.Builder()
                .url("$serverUrl/upload")
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            if (response.isSuccessful) {
                UploadResult(true, body)
            } else {
                UploadResult(false, "HTTP ${response.code}: $body")
            }
        } catch (e: java.net.ConnectException) {
            UploadResult(false, "Server unreachable: ${e.message}")
        } catch (e: java.net.SocketTimeoutException) {
            UploadResult(false, "Connection timed out: ${e.message}")
        } catch (e: Exception) {
            UploadResult(false, "Upload error: ${e.message}")
        } finally {
            tempFile?.delete()
        }
    }
}
