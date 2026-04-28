# Task 4: Core Database Layer

## Goal

Build the Room database layer in `core/database`. Stores sessions, messages, parts, and pending permissions/questions locally. Supports per-instance databases.

## Package

`com.openmate.core.database`

## Key Design: Per-Instance Databases

Each `ServerProfile` gets its own SQLite database file. This is achieved by:
- NOT using Hilt's default Room singleton
- Instead, provide a `DatabaseFactory` that creates/opens a Room DB by profile ID
- DB file naming: `instance_{profileId}.db`
- Use `SupportSQLiteOpenHelper` with custom `SQLiteOpenHelper` that targets the right file

### DatabaseFactory

```
class DatabaseFactory(context: Context) {
    fun create(profileId: String): AppDatabase
    fun delete(profileId: String)
}
```

Registered in Hilt as `@Singleton` — it's a factory, not the DB itself.

## Room Entities

### SessionEntity

| Column | Type | SQLite Type |
|--------|------|-------------|
| id | String | TEXT PK |
| title | String | TEXT |
| directory | String | TEXT |
| projectID | String | TEXT |
| workspaceID | String? | TEXT |
| parentID | String? | TEXT |
| createdAt | Long | INTEGER |
| updatedAt | Long | INTEGER |
| isCompacting | Boolean | INTEGER |
| isArchived | Boolean | INTEGER |

Indices: `directory`, `updatedAt`

### MessageEntity

| Column | Type | SQLite Type |
|--------|------|-------------|
| id | String | TEXT PK |
| sessionID | String | TEXT |
| role | String | TEXT |
| agent | String? | TEXT |
| createdAt | Long | INTEGER |

Index: `sessionID`, `createdAt`

### PartEntity

| Column | Type | SQLite Type |
|--------|------|-------------|
| id | String | TEXT PK |
| messageID | String | TEXT |
| sessionID | String | TEXT |
| type | String | TEXT (discriminator) |
| sequence | Int | INTEGER |
| text | String? | TEXT |
| toolCallID | String? | TEXT |
| toolName | String? | TEXT |
| toolState | String? | TEXT |
| toolArgs | String? | TEXT (JSON) |
| toolResult | String? | TEXT (JSON) |
| filePath | String? | TEXT |
| patch | String? | TEXT |
| agentName | String? | TEXT |
| summary | String? | TEXT |
| subtaskSessionID | String? | TEXT |
| stepType | String? | TEXT |

Index: `messageID`, `sequence`

### PermissionEntity

| Column | Type | SQLite Type |
|--------|------|-------------|
| id | String | TEXT PK |
| sessionID | String | TEXT |
| toolName | String | TEXT |
| input | String | TEXT |
| createdAt | Long | INTEGER |

### QuestionEntity

| Column | Type | SQLite Type |
|--------|------|-------------|
| id | String | TEXT PK |
| sessionID | String | TEXT |
| questions | String | TEXT (JSON) |
| createdAt | Long | INTEGER |

## DAOs

### SessionDao
```
@Query("SELECT * FROM SessionEntity WHERE directory = :dir ORDER BY updatedAt DESC")
fun observeByDirectory(dir: String?): Flow<List<SessionEntity>>

@Query("SELECT * FROM SessionEntity WHERE id = :id")
fun observeById(id: String): Flow<SessionEntity?>

@Insert(onConflict = REPLACE)
suspend fun upsert(session: SessionEntity)

@Insert(onConflict = REPLACE)
suspend fun upsertAll(sessions: List<SessionEntity>)

@Query("DELETE FROM SessionEntity WHERE id = :id")
suspend fun delete(id: String)
```

### MessageDao
```
@Query("SELECT * FROM MessageEntity WHERE sessionID = :sid ORDER BY createdAt DESC LIMIT :limit")
suspend fun getBySession(sid: String, limit: Int): List<MessageEntity>

@Query("SELECT * FROM MessageEntity WHERE sessionID = :sid ORDER BY createdAt DESC")
fun observeBySession(sid: String): Flow<List<MessageEntity>>

@Insert(onConflict = REPLACE)
suspend fun upsert(message: MessageEntity)

@Insert(onConflict = REPLACE)
suspend fun upsertAll(messages: List<MessageEntity>)

@Query("DELETE FROM MessageEntity WHERE id = :id")
suspend fun delete(id: String)
```

### PartDao
```
@Query("SELECT * FROM PartEntity WHERE messageID = :mid ORDER BY sequence")
suspend fun getByMessage(mid: String): List<PartEntity>

@Query("SELECT * FROM PartEntity WHERE messageID = :mid ORDER BY sequence")
fun observeByMessage(mid: String): Flow<List<PartEntity>>

@Insert(onConflict = REPLACE)
suspend fun upsert(part: PartEntity)

@Insert(onConflict = REPLACE)
suspend fun upsertAll(parts: List<PartEntity>)

@Query("DELETE FROM PartEntity WHERE id = :id")
suspend fun delete(id: String)
```

### PermissionDao
```
@Query("SELECT * FROM PermissionEntity ORDER BY createdAt")
fun observeAll(): Flow<List<PermissionEntity>>

@Insert(onConflict = REPLACE)
suspend fun upsert(permission: PermissionEntity)

@Query("DELETE FROM PermissionEntity WHERE id = :id")
suspend fun delete(id: String)
```

### QuestionDao
```
@Query("SELECT * FROM QuestionEntity ORDER BY createdAt")
fun observeAll(): Flow<List<QuestionEntity>>

@Insert(onConflict = REPLACE)
suspend fun upsert(question: QuestionEntity)

@Query("DELETE FROM QuestionEntity WHERE id = :id")
suspend fun delete(id: String)
```

## Mappers

Extension functions converting Entity ↔ Domain model:
- `SessionEntity.toDomain(): Session`
- `Session.toEntity(): SessionEntity`
- `MessageEntity.toDomain(parts: List<Part>): Message`
- etc.

## DatabaseModule

Hilt module providing:
- `DatabaseFactory` as `@Singleton`
- DAOs (retrieved from current active database via a `ActiveDatabaseProvider`)

### ActiveDatabaseProvider

```
class ActiveDatabaseProvider @Inject constructor(private val factory: DatabaseFactory) {
    private var currentDb: AppDatabase? = null
    private var currentProfileId: String? = null

    fun setActive(profileId: String): AppDatabase
    fun getActive(): AppDatabase
    fun clearActive()
}
```

## Files

| File | Purpose |
|------|---------|
| `AppDatabase.kt` | Room DB class |
| `DatabaseFactory.kt` | Per-instance DB factory |
| `ActiveDatabaseProvider.kt` | Manages active DB instance |
| `entity/SessionEntity.kt` | + mapper |
| `entity/MessageEntity.kt` | + mapper |
| `entity/PartEntity.kt` | + mapper |
| `entity/PermissionEntity.kt` | + mapper |
| `entity/QuestionEntity.kt` | + mapper |
| `dao/SessionDao.kt` | |
| `dao/MessageDao.kt` | |
| `dao/PartDao.kt` | |
| `dao/PermissionDao.kt` | |
| `dao/QuestionDao.kt` | |
| `DatabaseModule.kt` | Hilt DI |

## Verification

1. `./gradlew :core:database:test` passes
2. Android instrumented test: create DB, insert/query entities, verify mappers
3. Test `DatabaseFactory` creates separate files per profileId
4. Test `ActiveDatabaseProvider` switches between profiles
