# Task 5: Core Data Layer (Repository Implementations)

## Goal

Implement the repository interfaces from `core/domain` using `core/network` (API calls) and `core/database` (local cache). Lives in `core/data`.

## Package

`com.openmate.core.data`

## Architecture Pattern

**Single source of truth**: Database is the source of truth for UI. Network fetches update the database, and UI observes database flows.

### Flow for each operation:
1. **Fetch from network** → **Store in DB** → **DB Flow emits to UI**
2. **SSE event arrives** → **Parse & store in DB** → **DB Flow emits to UI**

## Repository Implementations

### ServerProfileRepositoryImpl

Implements `ServerProfileRepository`. Stores profiles in SharedPreferences or DataStore (simple key-value, not per-instance DB).

- Serialize profiles to JSON, store as string set
- `getAll()`: deserialize from storage
- `save()`: add/update profile, serialize back
- `delete()`: remove profile, also call `DatabaseFactory.delete(profileId)`

### SessionRepositoryImpl

```
class SessionRepositoryImpl @Inject constructor(
    private val api: OpencodeApiClient,
    private val sessionDao: SessionDao,   // from ActiveDatabaseProvider
    private val messageDao: MessageDao,
    private val partDao: PartDao,
) : SessionRepository
```

- `getSessions()`: fetch from API → upsert into DB → return from DB
- `observeSessions()`: return `sessionDao.observeByDirectory().map { it.toDomain() }`
- `createSession()`: call API → upsert result into DB
- `deleteSession()`: call API → delete from DB
- SSE event handling: when `session.created`/`session.updated`/`session.deleted` events arrive, update DB accordingly

### MessageRepositoryImpl

```
class MessageRepositoryImpl @Inject constructor(
    private val api: OpencodeApiClient,
    private val messageDao: MessageDao,
    private val partDao: PartDao,
) : MessageRepository
```

- `getMessages()`: fetch from API → upsert messages + parts into DB → return from DB
- `sendMessage()`: call `api.sendMessageStream()` → for each SSE event in the stream:
  - `message.updated` → upsert message in DB
  - `message.part.updated` → upsert part in DB
  - `message.part.delta` → update text in existing part in DB
  - Return the flow of Parts as they arrive
- `observeMessages()`: return `messageDao.observeBySession().map { it.toDomain() }`

### PermissionRepositoryImpl

```
class PermissionRepositoryImpl @Inject constructor(
    private val api: OpencodeApiClient,
    private val permissionDao: PermissionDao,
) : PermissionRepository
```

- `getPending()`: fetch from API → upsert → return from DB
- `reply()`: call API → delete from DB
- `observePending()`: `permissionDao.observeAll().map { it.toDomain() }`
- SSE `permission.asked` → upsert into DB
- SSE `permission.replied` → delete from DB

### QuestionRepositoryImpl

```
class QuestionRepositoryImpl @Inject constructor(
    private val api: OpencodeApiClient,
    private val questionDao: QuestionDao,
) : QuestionRepository
```

- Same pattern as Permission
- SSE `question.asked` → upsert
- SSE `question.replied`/`question.rejected` → delete

### SseEventRepositoryImpl

```
class SseEventRepositoryImpl @Inject constructor(
    private val sseClient: SseClient,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val permissionRepository: PermissionRepository,
    private val questionRepository: QuestionRepository,
) : SseEventRepository
```

- `connect()`: delegates to `sseClient.connect()`, but also subscribes to the event flow and dispatches events to the appropriate repository
- Event dispatching: when SSE event arrives, route based on event type:
  - `session.*` → SessionRepository (upsert/delete)
  - `message.*` → MessageRepository (upsert/delete)
  - `permission.*` → PermissionRepository (upsert/delete)
  - `question.*` → QuestionRepository (upsert/delete)
  - `server.connected` → update connection status
  - `server.heartbeat` → update last heartbeat time
- `disconnect()`: delegates to `sseClient.disconnect()`
- `observeConnectionStatus()`: delegates to `sseClient.connectionStatus`

## EventDispatcher

Helper class that routes SSE events to the right repository. Keeps `SseEventRepositoryImpl` clean.

```
class EventDispatcher @Inject constructor(
    private val sessionHandler: SessionEventHandler,
    private val messageHandler: MessageEventHandler,
    private val permissionHandler: PermissionEventHandler,
    private val questionHandler: QuestionEventHandler,
) {
    fun dispatch(event: SseData)
}
```

## SseSyncManager

Handles the initial data sync when connecting to a server:
1. Call `healthCheck()` to verify connectivity
2. Fetch session list via `SessionRepository.getSessions()`
3. For each session, fetch recent messages via `MessageRepository.getMessages(sessionID, limit=80)`
4. Connect SSE stream for real-time updates

## DataModule

Hilt module providing all repository implementations bound to their interfaces.

## Files

| File | Purpose |
|------|---------|
| `ServerProfileRepositoryImpl.kt` | |
| `SessionRepositoryImpl.kt` | |
| `MessageRepositoryImpl.kt` | |
| `PermissionRepositoryImpl.kt` | |
| `QuestionRepositoryImpl.kt` | |
| `SseEventRepositoryImpl.kt` | |
| `EventDispatcher.kt` | SSE event routing |
| `sse/SessionEventHandler.kt` | Handle session.* events |
| `sse/MessageEventHandler.kt` | Handle message.* events |
| `sse/PermissionEventHandler.kt` | Handle permission.* events |
| `sse/QuestionEventHandler.kt` | Handle question.* events |
| `SseSyncManager.kt` | Initial sync on connect |
| `DataModule.kt` | Hilt DI |

## Verification

1. `./gradlew :core:data:test` passes
2. Unit tests using fakes for `OpencodeApiClient` and DAOs
3. Test EventDispatcher routes events correctly
4. Test SseSyncManager initial sync sequence
5. Test repository "fetch → store → observe" pattern
