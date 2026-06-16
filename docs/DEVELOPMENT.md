# OpenMate Development Guide

A native Android client for opencode. Connect to your PC's opencode instance over LAN/Tailscale to manage workspaces, browse sessions, send messages, and respond to permission requests and questions.

## Architecture

```
┌──────────────┐          LAN / Tailscale          ┌─────────────────────┐
│  Android App │  ──── HTTP + SSE ────  :4097 ────▶│  OpenMate Bridge    │
│  (OpenMate)  │          Bearer Token              │  (Rust, port 4097)  │
│              │                                  │    ├─ auth (HMAC)    │
│              │                                  │    ├─ proxy ────────▶│ opencode serve
│              │                                  │    ├─ process mgr    │  (port 4096)
│              │                                  │    ├─ fs API         │
│              │                                  │    └─ SSE proxy      │
└──────────────┘                                  └─────────────────────┘
```

**Bridge-only mode**: The Android client only connects to the Bridge (port 4097), never directly to opencode. The Bridge acts as a reverse proxy + process manager + file server + authentication gateway, forwarding all REST/SSE requests to opencode (port 4096) and providing extended filesystem APIs. The connection verifies Bridge status; non-Bridge endpoints return `NOT_BRIDGE`.

## Getting Started

### 1. Build the Bridge Agent

**Windows:**

```powershell
cd opencode-bridge
cargo build --release

# Run in foreground
.\target\release\openmate.exe

# Or install as a system service (auto-start on boot)
.\target\release\openmate.exe install
# To uninstall:
# .\target\release\openmate.exe uninstall
```

**Linux:**

```bash
cd opencode-bridge
cargo build --release

# Run in foreground
./target/release/openmate

# Or install as a systemd service (auto-start on boot)
sudo ./target/release/openmate install
# To uninstall:
# sudo ./target/release/openmate uninstall
```

The Bridge automatically starts `opencode serve` (default port 4096) on launch. Make sure `opencode` is in your PATH.

**Configuration**: All settings are stored in a SQLite database (`~/.openmate/bridge.db`) and managed via the admin web UI at `http://127.0.0.1:4097/ui/`. No config files needed. See [README.md](../README.md#configuration) for the full list of configurable options.

### 2. Build the Android Client

```bash
cd android
.\gradlew.bat assembleDebug --no-daemon
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Or open the `android/` directory in Android Studio and build/run directly.

### 3. Pairing & Connection

First-time connection to the Bridge requires PIN-based pairing:

1. **Android**: Open the app → Add instance → Enter Bridge address (e.g. `100.71.116.3`) and port (`4097`) → Tap Save
2. **Android**: The app automatically initiates a pairing request and displays a 6-digit PIN
3. **PC**: Approve the PIN on the machine running the Bridge:

```powershell
openmate.exe approve 123456
```

4. **Android**: Tap Confirm → Pairing complete, auto-connects

After successful pairing, the token is saved on the device — no re-pairing needed for future connections. If a token is invalidated (e.g. Bridge key reset), the app prompts for re-pairing.

Reset the Bridge secret key (invalidates all paired device tokens):

```powershell
openmate.exe reset-token
```

## Features

### Android Client

| Feature | Description |
|---------|-------------|
| Instance management | Add/edit/delete Bridge instances, test connections |
| PIN pairing | First-time pairing via PIN code authentication |
| Workspace list | Browse all sessions grouped by directory, with search |
| Session list | Independent session browsing, create/delete/rename |
| Chat detail | Send messages, stream responses, render 12 part types |
| Permission response | Allow / always allow / deny permission requests |
| Question response | Select and submit answers or reject questions |
| TODO list | Session task progress (in-progress / pending / completed) |
| Model selection | Select AI model and provider |
| Skill selection | Select available skills |
| File browser | Browse workspace directories, view/download files |
| Markdown rendering | compose-markdown library for code block rendering |
| Binary file download | Download → cache → open via system Intent |
| Message attachments | Attach files/gallery images to messages |
| Session operations | Abort, compact/summarize, fork |
| SSE real-time push | Session/message/permission/question/TODO events |
| Settings | Notification toggles, auto-allow rules, cache management |

### Bridge Agent

| Feature | Description |
|---------|-------------|
| Reverse proxy | All unmatched routes forwarded to opencode, Authorization header stripped |
| Authentication | HMAC-SHA256 token + PIN pairing, IP binding, rate limiting |
| Process management | Auto-start/stop/restart opencode, crash detection + auto-recovery |
| System service | Windows (Win32 Service) / Linux (systemd), auto-start on boot |
| SSE proxy | mpsc channel + ReceiverStream, auto-reconnect |
| Filesystem API | Directory listing, file read/write, mkdir, search, upload/download |
| Static file serving | `/files/{*path}` route, MIME detection |
| Path whitelist | PathGuard validates all FS operations (`allowed_paths=[]` = allow all) |

## Bridge CLI Commands

| Command | Description |
|---------|-------------|
| `openmate` | Run in foreground |
| `openmate install` | Install as system service and start |
| `openmate uninstall` | Uninstall system service |
| `openmate service` | Run in service mode (called by the system) |
| `openmate approve <pin>` | Approve a pairing PIN |
| `openmate reset-token` | Reset secret key, invalidating all tokens |

## Bridge API

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/bridge/status` | Public | Bridge version + opencode status + auth_enabled |
| POST | `/api/bridge/pair/request` | Public | Request pairing PIN |
| POST | `/api/bridge/pair/approve` | Localhost only | Approve PIN |
| POST | `/api/bridge/pair/confirm` | Public | Confirm pairing, receive token |
| POST | `/api/bridge/opencode/start` | Bearer | Start opencode |
| POST | `/api/bridge/opencode/stop` | Bearer | Stop opencode |
| POST | `/api/bridge/opencode/restart` | Bearer | Restart opencode |
| GET | `/api/bridge/fs/roots` | Bearer | Filesystem root directories |
| GET | `/api/bridge/fs/list?path=` | Bearer | Directory listing |
| GET | `/api/bridge/fs/stat?path=` | Bearer | File/directory metadata |
| GET | `/api/bridge/fs/read?path=` | Bearer | Read file (text / binary base64) |
| GET | `/api/bridge/fs/download?path=` | Bearer | Stream download file |
| PUT | `/api/bridge/fs/upload?path=` | Bearer | Upload file (max 100MB) |
| POST | `/api/bridge/fs/write` | Bearer | Write file |
| POST | `/api/bridge/fs/mkdir` | Bearer | Create directory |
| POST | `/api/bridge/fs/search` | Bearer | Search files |
| GET | `/files/{*path}` | Bearer | Static file serving |
| GET | `/api/opencode/global/event` | Bearer | SSE proxy |
| ANY | Other | Bearer | Forwarded to opencode |

### Authentication Flow

```
Android                      Bridge (port 4097)                    PC
  │                              │                                  │
  │  POST /pair/request ────────▶│  Generate PIN, bind to IP        │
  │  ◀─────── { pin: "123456" } │                                  │
  │                              │                                  │
  │   Display PIN "123456"       │  openmate approve 123456 ◀───────│
  │                              │  Mark PIN as approved             │
  │                              │                                  │
  │  POST /pair/confirm ────────▶│  Verify PIN + IP, generate token │
  │  ◀─────── { token: "..." }  │                                  │
  │                              │                                  │
  │  Subsequent requests         │                                  │
  │  carry Bearer token ────────▶│  Verify token → forward to opencode
```

## Android Tech Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin 2.2.0 |
| UI | Jetpack Compose + Material 3 (dark theme) |
| Architecture | MVVM + Hilt DI + Room + OkHttp |
| Build | AGP 8.11.0 / KSP 2.2.0-2.0.2 / Compose BOM 2025.07.00 |
| SDK | minSdk 26 / targetSdk 36 |
| Navigation | Navigation Compose (single Activity) |
| Network | OkHttp (REST + SSE long-poll) + Bearer Token auth |
| Database | Room v9 (per-instance SQLite) |
| Token storage | DataStore\<Preferences\> |
| Markdown | compose-markdown |
| i18n | Standard Android string resources (English default + Chinese) |

### Module Structure

```
android/
  app/                → OpenMateApp, MainActivity, NavHost, ConnectionManager
  core/
    common/           → Result<T>, Flow extensions, AppDispatchers
    domain/           → 12 domain models (12 Part subtypes), 7 repository interfaces
    data/             → 7 repository impls + EventDispatcher + SSE event handlers
    database/         → Room v9, 6 entities, 6 DAOs, ActiveDatabaseProvider
    network/          → OpencodeApiClient, SseClient, TokenStore, BearerTokenInterceptor
    ui/               → Dark theme (opencode palette), MessageBubble, StreamingText
  feature/
    instance/         → Instance list/add, pairing PIN dialog
    session/          → Workspace/session/chat/file browser
    settings/         → Settings page
```

### Data Flow

```
REST: OpencodeApiClient (+ Bearer token) → DTOs → toDomain() → Domain → toEntity() → Room → Flow → ViewModel → UI
SSE:  SseClient (+ Bearer token) → SseParser → SseData → EventDispatcher → EventHandler → Room → Flow → UI
```

## Bridge Tech Stack

| Category | Technology |
|----------|------------|
| Language | Rust (edition 2024) |
| Web | Axum 0.8 + Tower HTTP 0.6 (CORS + tracing) |
| HTTP | Reqwest 0.12 (stream + json) |
| Async | Tokio 1 (full), tokio-stream, tokio-util |
| Auth | HMAC-SHA256 (hmac + sha2) + random key (getrandom) |
| Service | Windows: windows-service crate / Linux: systemd |
| Config | SQLite (`~/.openmate/bridge.db`) + admin UI (`/ui/`) |
| CLI | Clap 4 (derive) |

### Bridge Source Structure

```
opencode-bridge/src/
  main.rs              # CLI entry point
  server.rs            # axum server startup + graceful shutdown
  service_windows.rs   # Windows service (install/uninstall/run)
  service_linux.rs     # Linux systemd service (install/uninstall)
  auth/
    key.rs             # SecretKey generation/loading/persistence
    token.rs           # HMAC-SHA256 token generation/verification
    pair.rs            # PIN pairing (request/approve/confirm)
    middleware.rs      # Auth middleware (public/localhost/bearer)
  bridge/router.rs     # Bridge API handlers
  process/             # opencode process management
  proxy/               # REST + SSE proxy
  fs/                  # Filesystem API
  files/               # Static file serving
```

## Build & Deploy

### Development Build

```bash
# Bridge
cd opencode-bridge
cargo build --release
# Output: target/release/openmate.exe

# Android
cd android
.\gradlew.bat assembleDebug --no-daemon
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### One-click Deploy

```powershell
# Stop Bridge → copy binary → restart → install APK to device
scripts\deploy.bat
```

## Project Structure

```
openmate/
  android/               → Android client (Kotlin)
  opencode-bridge/       → Bridge Agent (Rust)
  scripts/               → Debug/deploy scripts
    deploy.bat           → Deploy script
    session_tool.py      → opencode REST API query tool
    pull_android_db.py   → Pull Room DB from device
    analyze_android_db.py → Analyze Room DB
  docs/                  → Design documents
  AGENTS.md              → Workspace-level guidance
  android/AGENTS.md      → Android architecture/conventions/API reference
  opencode-bridge/AGENTS.md → Bridge architecture/conventions
```

## License

Open-source project. See [README.md](../README.md) for details.
