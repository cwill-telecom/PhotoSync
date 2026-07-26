package com.photosync.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "photosync_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_API_TOKEN = stringPreferencesKey("api_token")
        val KEY_HOME_SSID = stringPreferencesKey("home_ssid")
        val KEY_SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val KEY_AUTO_SYNC = booleanPreferencesKey("auto_sync")
        val KEY_LAST_SCAN_TS = longPreferencesKey("last_scan_ts")

        const val DEFAULT_SERVER_URL = ""
        const val DEFAULT_API_TOKEN = ""
        const val DEFAULT_HOME_SSID = ""
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL
    }

    val apiToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_API_TOKEN] ?: DEFAULT_API_TOKEN
    }

    val homeSsid: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HOME_SSID] ?: DEFAULT_HOME_SSID
    }

    val setupComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SETUP_COMPLETE] ?: false
    }

    val autoSync: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_SYNC] ?: true
    }

    val lastScanTimestamp: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_SCAN_TS] ?: 0L
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = url }
    }

    suspend fun setApiToken(token: String) {
        context.dataStore.edit { it[KEY_API_TOKEN] = token }
    }

    suspend fun setHomeSsid(ssid: String) {
        context.dataStore.edit { it[KEY_HOME_SSID] = ssid }
    }

    suspend fun setSetupComplete(complete: Boolean) {
        context.dataStore.edit { it[KEY_SETUP_COMPLETE] = complete }
    }

    suspend fun setAutoSync(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_SYNC] = enabled }
    }

    suspend fun setLastScanTimestamp(ts: Long) {
        context.dataStore.edit { it[KEY_LAST_SCAN_TS] = ts }
    }

    /** Get all settings in one suspend call */
    suspend fun getSettings(): Settings {
        val prefs = context.dataStore.data
        return prefs.first().let { p ->
            Settings(
                serverUrl = p[KEY_SERVER_URL] ?: DEFAULT_SERVER_URL,
                apiToken = p[KEY_API_TOKEN] ?: DEFAULT_API_TOKEN,
                homeSsid = p[KEY_HOME_SSID] ?: DEFAULT_HOME_SSID,
                setupComplete = p[KEY_SETUP_COMPLETE] ?: false,
                autoSync = p[KEY_AUTO_SYNC] ?: true,
                lastScanTimestamp = p[KEY_LAST_SCAN_TS] ?: 0L
            )
        }
    }

    data class Settings(
        val serverUrl: String = "",
        val apiToken: String = "",
        val homeSsid: String = "",
        val setupComplete: Boolean = false,
        val autoSync: Boolean = true,
        val lastScanTimestamp: Long = 0L
    )
}
