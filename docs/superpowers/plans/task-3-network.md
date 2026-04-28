# Task 3: Core Network Layer

## Goal

Build the HTTP + SSE client that talks directly to opencode server. Lives in `core/network`. Handles Basic auth, SSE parsing, and typed API calls.

## Package

`com.openmate.core.network`

## Key Components

### OpencodeApiClient

Retrofit-style API client (use OkHttp directly or Retrofit — executor's choice). Configured with:
- Base URL: `http://{address}:{port}`
- Optional Basic auth interceptor (adds `Authorization` header if password is set)
- JSON serialization: Kotlinx Serialization or Moshi

Exposes suspend functions matching the opencode API:

```
suspend fun listSessions(directory: String?, limit: Int?, start: Long?): List<SessionDto>
suspend fun getSession(id: String): SessionDto
suspend fun createSession(directory: String?): SessionDto
suspend fun deleteSession(id: String)
suspend fun getMessages(sessionID: String, limit: Int, before: String?): MessagesResponseDto
suspend fun sendMessageStream(sessionID: String, content: String): Flow<SseData>
```

**Important**: `POST /session/{id}/message` returns a streaming SSE response (same SSE format as `/global/event`). The `SseClient` or `OpencodeApiClient` must parse this response as an SSE stream, emitting `SseData` items as they arrive. This is how real-time message parts are delivered during a prompt.

Alternatively, use `POST /session/{id}/prompt_async` for fire-and-forget, then receive updates via the global SSE stream. The streaming message endpoint is preferred for MVP since it gives immediate feedback.
suspend fun sendPromptAsync(sessionID: String, content: String)
suspend fun abortSession(sessionID: String)
suspend fun listPermissions(): List<PermissionDto>
suspend fun replyPermission(requestID: String, reply: String, message: String?)
suspend fun listQuestions(): List<QuestionDto>
suspend fun replyQuestion(requestID: String, answers: List<List<String>>)
suspend fun rejectQuestion(requestID: String)
suspend fun healthCheck(): HealthDto
```

### SseClient

Dedicated SSE client using OkHttp's raw HTTP for long-lived connection to `/global/event`.

Key behaviors:
- Connects to `GET /global/event` with optional Basic auth
- Parses `data:` lines from SSE stream
- Emits parsed events as `Flow<SseData>`
- Handles heartbeat (10s interval from server)
- Auto-reconnect with exponential backoff on disconnect
- Exposes `connectionStatus: StateFlow<ConnectionStatus>`
- Can be stopped/started with new server config

### SseData

Parsed SSE event model:

| Field | Type |
|-------|------|
| type | String |
| payload | JsonObject (or String for raw) |
| directory | String |
| project | String? |
| workspace | String? |

### DTOs

Network-layer data transfer objects. Map to domain models via extension functions:
- `SessionDto` → `Session`
- `MessageDto` → `Message`
- `PermissionDto` → `PermissionRequest`
- `QuestionDto` → `QuestionRequest`
- etc.

Keep DTOs as separate classes from domain models — they reflect the JSON wire format, domain models reflect app needs.

### AuthInterceptor

OkHttp Interceptor that:
- Reads password from constructor param
- If non-null, adds `Authorization: Basic base64(:password)` to every request
- If null, does nothing

### NetworkModule

Hilt module providing:
- `OpencodeApiClient` (scoped to activity or custom scope)
- `SseClient` (scoped)
- OkHttp `Call.Factory` (with auth interceptor)

## Hilt Scoping

Both `OpencodeApiClient` and `SseClient` need to be re-created when switching server profiles. Use `@ActivityRetainedScoped` or a custom `@ServerScope` that's tied to the active server connection.

## Error Handling

- Wrap HTTP errors in typed exceptions: `ServerUnavailableException`, `AuthException`, `NotFoundException`
- SSE connection errors: emit to `connectionStatus` StateFlow, auto-reconnect
- Timeout: 30s for regular API calls, no timeout for SSE

## Files

| File | Purpose |
|------|---------|
| `OpencodeApiClient.kt` | HTTP API calls |
| `SseClient.kt` | SSE long-lived connection |
| `SseParser.kt` | Parse raw SSE text into SseData |
| `AuthInterceptor.kt` | Basic auth header injection |
| `dto/SessionDto.kt` | Session DTO + mapper |
| `dto/MessageDto.kt` | Message/Part DTOs + mappers |
| `dto/PermissionDto.kt` | Permission DTO + mapper |
| `dto/QuestionDto.kt` | Question DTO + mapper |
| `dto/HealthDto.kt` | Health response |
| `NetworkModule.kt` | Hilt DI |
| `NetworkExceptions.kt` | Typed network errors |

## Verification

1. `./gradlew :core:network:test` passes
2. Unit tests for: SseParser (with sample SSE text), AuthInterceptor (verify header), DTO mappers
3. Integration test: SseClient connects to a mock server (use OkHttp MockWebServer)
4. All tests run on JVM (no Android device needed)
