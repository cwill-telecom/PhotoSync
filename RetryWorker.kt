package com.photosync.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Monitors network connectivity and emits events when Wi-Fi state changes.
 * On API 29+ the SSID requires ACCESS_FINE_LOCATION; we fall back to
 * just detecting Wi-Fi connectivity and let the upload logic check if
 * the configured server is reachable.
 */
class NetworkMonitor(context: Context) {

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _events = Channel<NetworkEvent>(Channel.CONFLATED)
    val events: Flow<NetworkEvent> = _events.receiveAsFlow()

    sealed class NetworkEvent {
        /** Wi-Fi connected (no SSID guarantee on API 29+) */
        data class WifiConnected(val ssid: String?) : NetworkEvent()
        /** Any network disconnected */
        data object Disconnected : NetworkEvent()
        /** Other network type (mobile, ethernet) */
        data object OtherNetwork : NetworkEvent()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val ssid = getCurrentSsid()
                Log.i(TAG, "Wi-Fi connected, SSID: $ssid")
                _events.trySend(NetworkEvent.WifiConnected(ssid))
            } else {
                _events.trySend(NetworkEvent.OtherNetwork)
            }
        }

        override fun onLost(network: Network) {
            Log.i(TAG, "Network lost")
            _events.trySend(NetworkEvent.Disconnected)
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    /** Best-effort SSID. Returns null if location not granted / API >= 29 without location. */
    fun getCurrentSsid(): String? {
        return try {
            val info = wifiManager.connectionInfo
            val ssid = info.ssid
            if (ssid == "<unknown ssid>" || ssid.isBlank()) null
            else ssid.removeSurrounding("\"")
        } catch (e: Exception) {
            null
        }
    }

    /** Quick check: are we currently on Wi-Fi? */
    fun isOnWifi(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
