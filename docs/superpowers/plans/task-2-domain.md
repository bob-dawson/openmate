# Task 2: Core Domain Models & Repository Interfaces

## Goal

Define all domain models and repository interfaces in `core/domain`. These are pure Kotlin — no Android framework, no Room, no network types. Other modules depend on this.

## Package

`com.openmate.core.domain`

## Domain Models

### ServerProfile
Represents a saved connection profile (address + auth).

| Field | Type | Description |
|-------|------|-------------|
| id | String (UUID) | Unique ID |
| name | String | User-given name ("Work Desktop") |
| address | String | Host or IP (e.g., `100.64.0.1`) |
| port | Int | Port number (default 4096) |
| password | String? | Server password (nullable = no auth) |
| createdAt | Long | Epoch millis |
| lastConnectedAt | Long? | Epoch millis |

### Session
Mirrors opencode `Session.Info`.

| Field | Type | Description |
|-------|------|-------------|
| id | String | Session ID |
| title | String | Session title |
| directory | String | Project directory |
| projectID | String | Project ID |
| workspaceID | String? | Workspace ID |
| parentID | String? | Parent session (fork) |
| createdAt | Long | Epoch millis |
| updatedAt | Long | Epoch millis |
| isCompacting | Boolean | Currently compacting? |
| isArchived | Boolean | Archived? |
| status | SessionStatus? | Running status (null if idle) |

### SessionStatus
Enum: `IDLE`, `BUSY`, `COMPACTING`

### Message
Mirrors opencode `MessageV2`.

| Field | Type | Description |
|-------|------|-------------|
| id | String | Message ID |
| sessionID | String | Parent session |
| role | MessageRole | USER or ASSISTANT |
| agent | String? | Agent name |
| createdAt | Long | Epoch millis |
| parts | List\<Part\> | Ordered message parts |

### MessageRole
Enum: `USER`, `ASSISTANT`

### Part
Sealed interface representing a message part. Subtypes:

| Subtype | Key Fields |
|---------|------------|
| TextPart | text: String |
| ToolInvocationPart | toolCallID, toolName, state (CALL/PARTIAL/RESULT), args (String?), result (String?) |
| StepStartPart | stepType: String |
| StepFinishPart | stepType: String |
| ReasoningPart | text: String |
| FilePart | filePath, content (String?) |
| SnapshotPart | filePath, content (String?) |
| PatchPart | filePath, patch: String |
| AgentPart | agentName: String |
| CompactionPart | summary: String |
| SubtaskPart | sessionID: String |

### PermissionRequest

| Field | Type | Description |
|-------|------|-------------|
| id | String | Request ID |
| sessionID | String | Parent session |
| toolName | String | Tool requesting permission |
| input | String | Tool input (JSON string) |
| createdAt | Long | Epoch millis |

### PermissionReply
Enum: `ALLOW`, `DENY`

### QuestionRequest

| Field | Type | Description |
|-------|------|-------------|
| id | String | Request ID |
| sessionID | String | Parent session |
| questions | List\<QuestionItem\> | Question items |

### QuestionItem

| Field | Type | Description |
|-------|------|-------------|
| label | String | Question text |
| options | List\<String\> | Available choices |

### ConnectionStatus
Enum: `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `ERROR`

### SseEvent
Represents a parsed SSE event from opencode.

| Field | Type | Description |
|-------|------|-------------|
| type | String | Event type (e.g., "message.part.delta") |
| payload | String | Raw JSON payload string |
| directory | String | Project directory |

## Repository Interfaces

### ServerProfileRepository
```
suspend fun getAll(): List<ServerProfile>
suspend fun getById(id: String): ServerProfile?
suspend fun save(profile: ServerProfile)
suspend fun delete(id: String)
```

### SessionRepository
```
suspend fun getSessions(directory: String?, limit: Int?, start: Long?): List<Session>
suspend fun getSession(id: String): Session?
suspend fun createSession(directory: String?): Session
suspend fun deleteSession(id: String)
fun observeSessions(directory: String?): Flow<List<Session>>
fun observeSession(id: String): Flow<Session?>
```

### MessageRepository
```
suspend fun getMessages(sessionID: String, limit: Int, before: String?): List<Message>
suspend fun sendMessage(sessionID: String, content: String): Flow<Part>
fun observeMessages(sessionID: String): Flow<List<Message>>
```

### PermissionRepository
```
suspend fun getPending(): List<PermissionRequest>
suspend fun reply(requestID: String, reply: PermissionReply, message: String?)
fun observePending(): Flow<List<PermissionRequest>>
```

### QuestionRepository
```
suspend fun getPending(): List<QuestionRequest>
suspend fun reply(requestID: String, answers: List<List<String>>)
suspend fun reject(requestID: String)
fun observePending(): Flow<List<QuestionRequest>>
```

### SseEventRepository
```
fun connect(address: String, port: Int, password: String?): Flow<SseEvent>
fun disconnect()
fun observeConnectionStatus(): Flow<ConnectionStatus>
```

## Verification

1. `./gradlew :core:domain:test` passes
2. All models are data classes or sealed interfaces
3. All repository interfaces are pure Kotlin (no Android imports)
4. Tests: verify model construction, equality, serialization boundaries
