package com.photosync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.photosync.data.AppDatabase
import com.photosync.data.PhotoEntry
import com.photosync.data.PreferencesManager
import com.photosync.service.SyncService
import com.photosync.worker.ProcessQueueWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                PhotoSyncApp()
            }
        }
    }
}

@Composable
fun PhotoSyncApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesManager(context) }
    val db = remember { AppDatabase.getInstance(context) }

    val serverUrl by prefs.serverUrl.collectAsStateWithLifecycle("")
    val apiToken by prefs.apiToken.collectAsStateWithLifecycle("")
    val homeSsid by prefs.homeSsid.collectAsStateWithLifecycle("")
    val autoSync by prefs.autoSync.collectAsStateWithLifecycle(true)

    val allPhotos by db.photoDao().getAllFlow().collectAsStateWithLifecycle(emptyList())
    val pendingCount by db.photoDao().countByStatus(PhotoEntry.Status.PENDING).collectAsStateWithLifecycle(0)
    val uploadingCount by db.photoDao().countByStatus(PhotoEntry.Status.UPLOADING).collectAsStateWithLifecycle(0)
    val completedCount by db.photoDao().countByStatus(PhotoEntry.Status.COMPLETED).collectAsStateWithLifecycle(0)
    val failedCount by db.photoDao().countByStatus(PhotoEntry.Status.FAILED).collectAsStateWithLifecycle(0)

    // Permission handling
    var hasMediaPerm by remember { mutableStateOf(checkMediaPermission(context)) }

    val mediaPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasMediaPerm = grants.values.all { it }
    }

    var showSettings by remember { mutableStateOf(false) }
    var editUrl by remember(serverUrl) { mutableStateOf(serverUrl) }
    var editToken by remember(apiToken) { mutableStateOf(apiToken) }
    var editSsid by remember(homeSsid) { mutableStateOf(homeSsid) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PhotoSync") },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Permission banner
            if (!hasMediaPerm) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Photo access required",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                mediaPermLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
                            } else {
                                mediaPermLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                            }
                        }) {
                            Text("Grant")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Status dashboard
            Text("Queue Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusChip("Pending", pendingCount, MaterialTheme.colorScheme.secondaryContainer)
                StatusChip("Uploading", uploadingCount, MaterialTheme.colorScheme.tertiaryContainer)
                StatusChip("Done", completedCount, MaterialTheme.colorScheme.primaryContainer)
                StatusChip("Failed", failedCount, MaterialTheme.colorScheme.errorContainer)
            }

            Spacer(Modifier.height(16.dp))

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val enable = !autoSync
                            prefs.setAutoSync(enable)
                            val svcIntent = Intent(context, SyncService::class.java)
                            if (enable) {
                                context.startForegroundService(svcIntent)
                            } else {
                                svcIntent.action = SyncService.ACTION_STOP
                                context.startService(svcIntent)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (autoSync) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (autoSync) "Pause" else "Start")
                }

                Button(
                    onClick = {
                        val work = OneTimeWorkRequestBuilder<ProcessQueueWorker>().build()
                        WorkManager.getInstance(context).enqueue(work)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = serverUrl.isNotBlank()
                ) {
                    Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sync Now")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Rescan button — scans MediaStore for photos not yet in the queue
            OutlinedButton(
                onClick = {
                    val scanIntent = Intent(context, SyncService::class.java).apply {
                        action = SyncService.ACTION_SCAN
                    }
                    context.startService(scanIntent)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = serverUrl.isNotBlank()
            ) {
                Icon(Icons.Default.ImageSearch, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Rescan Photo Folder")
            }

            Spacer(Modifier.height(16.dp))

            // Settings panel
            if (showSettings) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Server Settings", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = editUrl,
                            onValueChange = { editUrl = it },
                            label = { Text("Server URL") },
                            placeholder = { Text("http://192.168.1.100:8765") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = editToken,
                            onValueChange = { editToken = it },
                            label = { Text("API Token") },
                            placeholder = { Text("shared-secret") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = editSsid,
                            onValueChange = { editSsid = it },
                            label = { Text("Home SSID (optional)") },
                            placeholder = { Text("MyHomeWiFi") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    prefs.setServerUrl(editUrl)
                                    prefs.setApiToken(editToken)
                                    prefs.setHomeSsid(editSsid)
                                    prefs.setSetupComplete(true)
                                    showSettings = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save Settings")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Server reachability hint
            if (serverUrl.isBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Configure your server URL in Settings to start syncing photos to your computer.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // Recent photos list
            Text("Recent Photos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))

            if (allPhotos.isEmpty()) {
                Text(
                    "No photos synced yet. Take a picture and it will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(allPhotos.take(50)) { photo ->
                        PhotoRow(photo)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusChip(label: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Surface(
        color = color,
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("$count", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun PhotoRow(photo: PhotoEntry) {
    val icon = when (photo.status) {
        PhotoEntry.Status.COMPLETED -> Icons.Default.CheckCircle
        PhotoEntry.Status.PENDING -> Icons.Default.HourglassEmpty
        PhotoEntry.Status.UPLOADING -> Icons.Default.CloudUpload
        PhotoEntry.Status.FAILED -> Icons.Default.Error
    }
    val tint = when (photo.status) {
        PhotoEntry.Status.COMPLETED -> MaterialTheme.colorScheme.primary
        PhotoEntry.Status.PENDING -> MaterialTheme.colorScheme.secondary
        PhotoEntry.Status.UPLOADING -> MaterialTheme.colorScheme.tertiary
        PhotoEntry.Status.FAILED -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            photo.displayName,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        if (photo.status == PhotoEntry.Status.FAILED && photo.lastError != null) {
            Text(
                photo.lastError,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1
            )
        }
    }
}

private fun checkMediaPermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }
}
