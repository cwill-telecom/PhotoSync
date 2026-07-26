package com.photosync.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.photosync.MainActivity
import com.photosync.R
import com.photosync.data.AppDatabase
import com.photosync.data.PreferencesManager
import com.photosync.monitor.MediaScanner
import com.photosync.monitor.NetworkMonitor
import com.photosync.monitor.PhotoContentObserver
import com.photosync.worker.ProcessQueueWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service that:
 *  1. Registers a ContentObserver to detect new photos
 *  2. Monitors network changes via NetworkMonitor
 *  3. Shows a persistent notification so the user knows it's running
 *  4. Triggers ProcessQueueWorker when Wi-Fi connects
 */
class SyncService : Service() {

    companion object {
        private const val TAG = "SyncService"
        const val CHANNEL_ID = "photosync_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.photosync.STOP"
        const val ACTION_SCAN = "com.photosync.SCAN"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var networkMonitor: NetworkMonitor
    private var contentObserver: PhotoContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        networkMonitor = NetworkMonitor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(0, 0)
        startForeground(NOTIFICATION_ID, notification)

        // Register ContentObserver
        registerContentObserver()

        // Monitor network changes
        scope.launch {
            networkMonitor.events.collect { event ->
                when (event) {
                    is NetworkMonitor.NetworkEvent.WifiConnected -> {
                        Log.i(TAG, "Wi-Fi connected, triggering upload")
                        triggerUpload()
                    }
                    is NetworkMonitor.NetworkEvent.OtherNetwork -> {
                        Log.d(TAG, "Other network connected")
                        triggerUpload()
                    }
                    is NetworkMonitor.NetworkEvent.Disconnected -> {
                        Log.d(TAG, "Disconnected")
                    }
                }
            }
        }
        networkMonitor.start()

        // Scan MediaStore for photos we may have missed (first run or manual rescan)
        if (intent?.action == ACTION_SCAN) {
            // Manual rescan: force full scan by passing timestamp=0
            scope.launch { performScan(forceFull = true) }
        } else {
            // Normal startup: incremental scan from last known timestamp
            scope.launch { performScan(forceFull = false) }
        }

        // Try upload immediately in case we're already on Wi-Fi
        triggerUpload()

        return START_STICKY
    }

    override fun onDestroy() {
        unregisterContentObserver()
        networkMonitor.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerContentObserver() {
        val db = AppDatabase.getInstance(this)
        contentObserver = PhotoContentObserver(
            android.os.Handler(mainLooper),
            contentResolver,
            db
        )
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true, // notifyForDescendants
            contentObserver!!
        )
        Log.i(TAG, "ContentObserver registered")
    }

    private fun unregisterContentObserver() {
        contentObserver?.let {
            contentResolver.unregisterContentObserver(it)
            Log.i(TAG, "ContentObserver unregistered")
        }
        contentObserver = null
    }

    private fun triggerUpload() {
        val work = OneTimeWorkRequestBuilder<ProcessQueueWorker>().build()
        WorkManager.getInstance(this).enqueue(work)
    }

    private fun buildNotification(queued: Int, completed: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(
                when {
                    queued > 0 -> getString(R.string.uploading_notification_text, queued)
                    completed > 0 -> getString(R.string.status_completed, completed)
                    else -> getString(R.string.sync_notification_text)
                }
            )
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_sync),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_sync_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private suspend fun performScan(forceFull: Boolean) {
        val db = AppDatabase.getInstance(this)
        val prefs = PreferencesManager(this)
        val scanner = MediaScanner(contentResolver, db)

        val lastTs = if (forceFull) 0L else prefs.getSettings().lastScanTimestamp
        val result = scanner.scan(lastTs)

        Log.i(TAG, "Scan result: found=${result.found}, new=${result.new}, skipped=${result.skipped}")

        if (result.new > 0) {
            // Update the scan timestamp to now so next scan is incremental
            prefs.setLastScanTimestamp(System.currentTimeMillis() / 1000)
            // Trigger an upload for the newly discovered photos
            triggerUpload()
        }
    }
}
