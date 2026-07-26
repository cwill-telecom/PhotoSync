package com.photosync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.photosync.worker.ProcessQueueWorker

/**
 * Manifest-registered receiver for CONNECTIVITY_CHANGE.
 * On Android 7+ this only fires while the app is running (foreground/background).
 * Serves as a secondary trigger alongside the NetworkCallback in NetworkMonitor.
 */
class ConnectivityReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ConnectivityReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(network) ?: return

        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        if (isWifi || isEthernet) {
            Log.i(TAG, "Network available (wifi=$isWifi, ethernet=$isEthernet), triggering queue")
            val work = OneTimeWorkRequestBuilder<ProcessQueueWorker>().build()
            WorkManager.getInstance(context).enqueue(work)
        }
    }
}
