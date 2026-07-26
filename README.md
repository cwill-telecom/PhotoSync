# PhotoSync

Android app that automatically transfers photos to your PC the moment you take them. Queues offline and uploads when you reconnect to home Wi-Fi.

## How it works

1. Android ContentObserver watches MediaStore for new photos
2. Photo gets added to a local Room database queue immediately
3. If the PC server is reachable: uploads via HTTP multipart right away
4. If away from home: stays queued, uploads automatically when Wi-Fi reconnects
5. A Python Flask server on the PC receives, authenticates, and saves each photo

```
Phone (any network)                  Home Wi-Fi                        Your PC
─────────────────                    ──────────                        ───────
Take photo ──> Queue                 Reconnect ──> ProcessQueueWorker  Flask :8765
                    │                     ▲              │                 │
                    │    PENDING          │              │   POST /upload  │
                    └─────────────────────┘              └────────────────>│
                                                                          │
                                                             ./photos/YYYY/MM/
```

## Quick Start

### 1. PC — Start the receiver

```bash
cd server
pip install -r requirements.txt
python receiver.py --token my-secret-token --port 8765
```

Photos are saved to `./photos/YYYY/MM/` by default. Use `--dir C:\Users\username` to change.

Find your PC's local IP:
- **Windows:** `ipconfig` — look for the IPv4 address under your Wi-Fi adapter
- **Linux:** `ip addr show` or `hostname -I`

**Windows firewall:** the first time you run the server, Windows will prompt to allow Python through the firewall. Allow it on private networks.

### 2. Android — Build and install

Open `C:\Users\username\PhotoSync` in Android Studio (recommended). It handles the Gradle wrapper, SDK download, and builds automatically.

Or build from CLI:
```bash
gradle wrapper
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

Install the APK on your phone via ADB or direct download.

### 3. Configure the app

Open PhotoSync, tap the gear icon, and set:

| Setting | Example | Required |
|---------|---------|----------|
| Server URL | `http://192.168.1.100:8765` | Yes |
| API Token | `my-secret-token` | Yes |
| Home SSID | `MyHomeWiFi` | No |

Grant photo access when prompted. The foreground service starts immediately — the status bar shows "PhotoSync: Watching for new photos."

### 4. Test

Take a photo. Within seconds it appears in the queue on the app's dashboard. If your PC server is running and reachable, it uploads automatically. Tap **Sync Now** to force it.

## Configuration

### Server (receiver.py)

| Flag | Env var | Default | Description |
|------|---------|---------|-------------|
| `--token` | `PHOTOSYNC_TOKEN` | *(required)* | Shared secret for API auth |
| `--port` | — | `8765` | HTTP listen port |
| `--dir` | `PHOTOSYNC_DIR` | `./photos` | Where to save received photos |
| `--host` | — | `0.0.0.0` | Bind address |
| `--debug` | — | off | Enable Flask debug mode |

### Android app

Settings are in the app UI (tap gear icon). The foreground service can be paused/resumed from the main dashboard.

## Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│ ANDROID APP                                                        │
│                                                                    │
│  PhotoContentObserver ──> Room DB (photos table)                   │
│         │                      │                                   │
│         │              ┌───────┴────────┐                          │
│         │              │ PENDING/FAILED │                          │
│         │              └───────┬────────┘                          │
│         │                      │                                   │
│  NetworkMonitor ──> triggers ProcessQueueWorker                    │
│  (Wi-Fi connect)         │                                         │
│                          │  OkHttp multipart POST                  │
│                          │  to server/upload                       │
│                          ▼                                         │
│                   UploadClient                                     │
│                                                                    │
│  Fallbacks:                                                        │
│  - ConnectivityReceiver (manifest, connectivity changes)           │
│  - Periodic ProcessQueueWorker (every 15 min)                      │
│  - Periodic RetryWorker (every 30 min, up to 5 attempts)           │
│  - BootReceiver (re-schedules after reboot)                        │
└────────────────────────────────────────────────────────────────────┘
```

### Upload flow

```
New photo taken
       │
       ▼
ContentObserver fires ──> INSERT into Room (PENDING)
       │
       ├── Server reachable? ──Yes──> upload ──> COMPLETED
       │
       └── No ──> stays PENDING
                        │
              Wi-Fi reconnects
                        │
                        ▼
              NetworkMonitor triggers ProcessQueueWorker
                        │
                        ├── Upload OK ──> COMPLETED
                        ├── Server down ──> FAILED (retry later)
                        └── 5 failures ──> stays FAILED (manual retry)
```

## API Endpoints

Both endpoints require `Authorization: Bearer <token>` header.

### `GET /health`
Server reachability check. Returns:
```json
{"status": "ok", "photos_dir": "/path/to/photos", "server_time": "2024-..."}
```

### `POST /upload`
Multipart form upload. Fields:
- `file` — the photo (required)
- `date_added` — Unix timestamp (optional, used for directory naming)
- `display_name` — original filename (optional)

Returns:
```json
{"status": "ok", "filename": "IMG_1234.jpg", "path": "/photos/2024/07/IMG_1234.jpg", "size_bytes": 2345678}
```

### `GET /photos`
List received photos (debugging). Returns last 500.

## Security

- HTTP only — meant for local network. Do not expose to the internet.
- Bearer token with constant-time comparison on every request
- Sanitized filenames prevent path traversal attacks
- Android cleartext traffic allowed via `network_security_config.xml`

If you need remote access, put the server behind nginx/Caddy with TLS and rate limiting.

## Troubleshooting

**Photos not detected?**
- Check that PhotoSync has the "Photos & media" permission (Android Settings → Apps → PhotoSync → Permissions)
- On Android 13+, ensure notifications are allowed for the foreground service

**Server unreachable?**
- Verify the PC's IP hasn't changed (use a static DHCP lease on your router)
- Check Windows Firewall: allow Python on private networks
- Test from phone browser: open `http://<pc-ip>:8765/health` — should get a 401 (not a timeout)

**Uploads slow or fail?**
- Large photos over slow Wi-Fi may hit the 60s upload timeout — the worker will retry
- Check PC disk space in the photos directory

**App killed by Android?**
- Disable battery optimization for PhotoSync: Android Settings → Apps → PhotoSync → Battery → Unrestricted
- The periodic workers (every 15 min) act as a safety net

## Requirements

| Platform | Minimum |
|----------|---------|
| Android | 8.0 (API 26) |
| PC | Python 3.9+, Flask ≥ 3.0 |
