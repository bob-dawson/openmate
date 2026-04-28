# AGENTS.md — OpenMate Android

## Project Overview
OpenMate is an Android native client for [opencode](https://opencode.ai) (the open-source AI coding agent). It connects to a running `opencode serve` instance over LAN/Tailscale.

- **Repository**: `D:\openmate`
- **Android project root**: `D:\openmate\android`
- **OpenCode source (local)**: `D:\github\opencode`
- **Design doc**: `D:\openmate\OpenMate设计.md`

## Architecture
- **Language**: Kotlin 2.2.0
- **UI**: Jetpack Compose + Material 3 (dark theme, opencode color palette)
- **Architecture**: MVVM + Hilt DI + Room + OkHttp
- **Build**: AGP 8.11.0, KSP 2.2.0-2.0.2, Compose BOM 2025.07.00
- **Multi-module structure**:
  ```
  app/                          # Hilt Application, ConnectionManager, NavHost, MainActivity
  core/common/                  # Utilities (time formatting, etc.)
  core/domain/                  # Pure Kotlin models + repository interfaces
  core/data/                    # Repository implementations, SSE event handlers/dispatcher
  core/database/                # Room entities, DAOs, ActiveDatabaseProvider (per-instance DB)
  core/network/                 # OpencodeApiClient, SseClient, SseParser, DTOs
  core/ui/                      # Shared UI components (TopBar, EmptyStateView)
  feature/instance/             # Server profile list, add/edit instance
  feature/session/              # Session list, session detail (chat), message parts
  feature/settings/             # App settings
  ```

## Build & Run
- **IDE**: Android Studio (user compiles in IDE, NOT CLI — don't run `assembleDebug` unless asked)
- **SDK**: `C:\Users\bob_d\AppData\Local\Android\Sdk` (in `local.properties`)
- **Min SDK**: 26, **Target SDK**: 36

## Key Conventions
- **No mocking libraries** — use hand-crafted fakes/test doubles
- **Google Truth** for test assertions
- **No code comments** unless explicitly requested
- **Error handling**: Log via `android.util.Log` + expose `errorMessage: StateFlow<String?>` to UI via Snackbar
- **Never commit unless explicitly asked**

## OpenCode Server API Reference

The OpenCode server (`opencode serve --port <port>`) is a Hono (Node.js) HTTP server. Routes are in `packages/opencode/src/server/routes/`.

### Base URL & Endpoints
All instance-scoped routes are under the base URL (e.g., `http://192.168.x.x:4096`):

| Method | Path | Description |
|--------|------|-------------|
| GET | `/global/health` | Health check: `{"healthy": true, "version": "..."}` |
| GET | `/event` | SSE event stream (see SSE format below) |
| GET | `/session` | List sessions → `Session.Info[]` |
| GET | `/session/:sessionID` | Get session → `Session.Info` |
| POST | `/session` | Create session (body: optional `{title, parentID, workspaceID, permission}`) |
| DELETE | `/session/:sessionID` | Delete session |
| PATCH | `/session/:sessionID` | Update session (body: `{title?, permission?, time?}`) |
| POST | `/session/:sessionID/abort` | Abort running session |
| GET | `/session/:sessionID/message` | List messages with parts → `MessageV2.WithParts[]` |
| POST | `/session/:sessionID/message` | Send prompt (streaming SSE response) |
| POST | `/session/:sessionID/prompt_async` | Async prompt (returns 204 immediately) |
| POST | `/session/:sessionID/fork` | Fork session at a message |
| GET | `/permission` | List pending permissions |
| POST | `/permission/:requestID/reply` | Reply to permission request |
| GET | `/question` | List pending questions |
| POST | `/question/:requestID/reply` | Reply to question |
| POST | `/question/:requestID/reject` | Reject question |

### Session.Info JSON Format
```json
{
  "id": "ses_xxx",
  "slug": "session-slug",
  "projectID": "prj_xxx",
  "workspaceID": null,
  "directory": "/path/to/project",
  "parentID": null,
  "title": "Session Title",
  "version": "v2",
  "time": {
    "created": 1745800000000,
    "updated": 1745800000000,
    "compacting": null,
    "archived": null
  },
  "summary": null,
  "share": null,
  "revert": null,
  "permission": null
}
```
- `time.created`/`time.updated` are **epoch milliseconds** (NOT ISO strings)
- `time.compacting`/`time.archived` are `Long?` — non-null means active

### MessageV2 (User | Assistant) — Discriminated Union on `role`

**User message** (`role: "user"`):
```json
{
  "id": "msg_xxx",
  "sessionID": "ses_xxx",
  "role": "user",
  "time": { "created": 1745800000000 },
  "agent": "code",
  "model": { "providerID": "anthropic", "modelID": "claude-sonnet-4-20250514" },
  "format": { "type": "text" }
}
```

**Assistant message** (`role: "assistant"`):
```json
{
  "id": "msg_xxx",
  "sessionID": "ses_xxx",
  "role": "assistant",
  "time": { "created": 1745800000000, "completed": null },
  "parentID": "msg_parent",
  "modelID": "claude-sonnet-4-20250514",
  "providerID": "anthropic",
  "mode": "code",
  "agent": "code",
  "path": { "cwd": "/project", "root": "/project" },
  "cost": 0.003,
  "tokens": { "total": 1500, "input": 1000, "output": 500, "reasoning": 200, "cache": { "read": 100, "write": 50 } },
  "error": null
}
```

### Part Types (discriminated union on `type`)
Parts have base fields: `{id, sessionID, messageID, type}`

| type | Key fields |
|------|-----------|
| `text` | `text: String`, optional `time: {start, end}` |
| `reasoning` | `text: String`, `time: {start, end}` |
| `tool` | `callID, tool, state: {status: pending|running|completed|error, ...}` |
| `file` | `mime, url, filename?` |
| `agent` | `name` |
| `step-start` | `snapshot?` |
| `step-finish` | `reason, cost, tokens` |
| `snapshot` | `snapshot` |
| `patch` | `hash, files[]` |
| `subtask` | `prompt, description, agent` |
| `retry` | `attempt, error, time: {created}` |
| `compaction` | `auto, overflow?` |

### SSE Event Format
SSE events are sent as `data: <JSON>` lines. Format:
```
data: {"type":"<event-type>","properties":{...}}
```

**Event types from server**:
- `server.connected` — initial connection established
- `server.heartbeat` — sent every 10 seconds
- `server.instance.disposed` — server shutting down
- `session.created`, `session.updated`, `session.deleted` — session lifecycle (SyncEvent)
- `message.updated`, `message.removed` — message lifecycle (SyncEvent)
- `message.part.updated`, `message.part.removed`, `message.part.delta` — part-level updates
- `session.error` — error in session processing
- `session.diff` — diff produced
- `session.status` — status change (idle/running/etc)
- `permission.replied` — permission response
- `question.*` — question events

The `message.part.delta` BusEvent has format:
```json
{
  "type": "message.part.delta",
  "properties": {
    "sessionID": "...",
    "messageID": "...",
    "partID": "...",
    "delta": "streamed text chunk"
  }
}
```

### Health Check
`GET /global/health` → `{"healthy": true, "version": "1.x.x"}`

## Network Configuration
- Global cleartext HTTP enabled for Phase 1 LAN (`network_security_config.xml`)
- OkHttpClient: `connectTimeout(30s)`, `readTimeout(0)` (infinite for SSE)
- SSE heartbeat: client monitors for 30s no-data timeout, auto-reconnects with exponential backoff (1s → 30s max)

## Data Flow
```
API (OpencodeApiClient) → DTOs → toDomain() → Domain Models → toEntity() → Room DB → Flow → ViewModel → Compose UI
SSE (SseClient) → SseParser → SseData → EventDispatcher → {Session,Message,Permission,Question}EventHandler → Room DB updates → Flow
```

## Key Implementation Details
- `ActiveDatabaseProvider`: each `ServerProfile` gets its own Room SQLite database (file named by profile ID)
- `ConnectionManager` (app module, `@Singleton`): manages SSE connect/disconnect lifecycle, updates `apiClient.baseUrl` on connect
- `OpencodeApiClient.baseUrl` is a mutable `var` — set dynamically when connecting to an instance
- `SseClient`: OkHttp-based SSE with heartbeat monitoring and exponential backoff reconnect
- Session list uses Room `observeAll()` Flow + initial `refresh()` from API
- All ViewModel error handling: `Log.e(TAG, ...) + _errorMessage StateFlow + Snackbar in UI`

## SSE Parser Note
The SSE data format is **NOT** wrapped in `{"directory":"...","payload":{...}}`. The actual format is:
```
data: {"type":"event.type","properties":{...}}
```
The `SseData` class has fields `type: String` and `properties: JsonObject`.

## Message API Note
`GET /session/:sessionID/message` returns a flat array of `MessageV2.WithParts` (each item has `info` + `parts[]`), NOT a wrapped `{messages: [...]}` object. The current `MessageDto` needs to be updated to match this format when implementing session detail.

## TODO / Known Issues
- `MessageDto` / `PartDto` don't match actual API response format (needs rewrite for MessageV2 structure)
- SSE event handlers (`SessionEventHandler`, etc.) are stub open classes — real SSE→DB sync logic not yet implemented
- `sendMessageStream` in `OpencodeApiClient` uses SSE parsing but the prompt endpoint streams differently (needs verification)
- User's Tailscale IPs: 100.74.x.x and 100.71.x.x
- `opencode serve --hostname 0.0.0.0 --port 4096` is the server command (必须加 `--hostname 0.0.0.0`，否则只监听 127.0.0.1，局域网无法连接)
