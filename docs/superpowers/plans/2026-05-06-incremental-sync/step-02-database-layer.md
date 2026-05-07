# 步骤 2：数据库层 — 新增 SyncState + SessionMessage + FullContent 表

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 Room 数据库中新增 3 张表，支撑事件溯源同步模型，同时保持与旧表（MessageEntity/PartEntity）共存。

**Architecture:** Room DB v9 → v10，fallbackToDestructiveMigration。新增表不影响旧表结构。所有新 DAO 通过 `ActiveDatabaseProvider.getActive()` 获取。

**Tech Stack:** Room, Kotlin, Hilt

---

## Files

- Create: `core/database/src/main/java/com/openmate/core/database/entity/SyncStateEntity.kt`
- Create: `core/database/src/main/java/com/openmate/core/database/entity/SessionMessageEntity.kt`
- Create: `core/database/src/main/java/com/openmate/core/database/entity/SessionMessageFullContentEntity.kt`
- Create: `core/database/src/main/java/com/openmate/core/database/dao/SyncStateDao.kt`
- Create: `core/database/src/main/java/com/openmate/core/database/dao/SessionMessageDao.kt`
- Modify: `core/database/src/main/java/com/openmate/core/database/AppDatabase.kt` (version bump + 新增 entities/DAOs)

---

## Task 1: Create SyncStateEntity

**Files:**
- Create: `core/database/src/main/java/com/openmate/core/database/entity/SyncStateEntity.kt`

- [ ] **Step 1: Create the entity**

记录每个 session 的同步进度（sessionId → lastSeq）。

```kotlin
package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "SyncStateEntity")
data class SyncStateEntity(
    @PrimaryKey val sessionID: String,
    val lastSeq: Int = 0,
    val lastSyncAt: Long = 0L,
)
```

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:database:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 2: Create SessionMessageEntity

**Files:**
- Create: `core/database/src/main/java/com/openmate/core/database/entity/SessionMessageEntity.kt`

- [ ] **Step 1: Create the entity**

新模型对应 opencode v2 SessionMessage。存储截断后的 data（JSON 字符串），回放时按截断规则处理。

核心设计决策：
- **单表而非多表**：SessionMessage 有 7 种类型（user/assistant/synthetic/shell/compaction/agent-switched/model-switched），差异大。用 `type` 鉴别 + `data` JSON 存储截断后内容，避免宽表或 7 张子表。
- **`data` 列存 JSON**：每种 type 的 data 结构不同，Room 无法直接映射到强类型列。用 JSON 字符串存储，读取时按 type 反序列化。
- **与旧 MessageEntity/PartEntity 共存**：过渡期两套数据并行，UI 选择数据源。

```kotlin
package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "SessionMessageEntity",
    indices = [
        Index("sessionID"),
        Index(value = ["sessionID", "createdAt"]),
    ],
)
data class SessionMessageEntity(
    @PrimaryKey val id: String,
    val sessionID: String,
    val type: String,
    val data: String,
    val createdAt: Long,
    val completedAt: Long? = null,
    val seq: Int = 0,
)
```

字段说明：
- `id`：消息 ID（来自事件回放产生的 SessionMessage.id）
- `sessionID`：所属会话
- `type`：消息类型鉴别（user/assistant/synthetic/shell/compaction/agent-switched/model-switched）
- `data`：截断后的 JSON 字符串，按 `truncation-rules.md` 处理
- `createdAt` / `completedAt`：时间戳（epoch 毫秒）
- `seq`：产生此消息的最后一个事件的 seq（用于增量同步断点续传）

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:database:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 3: Create SessionMessageFullContentEntity

**Files:**
- Create: `core/database/src/main/java/com/openmate/core/database/entity/SessionMessageFullContentEntity.kt`

- [ ] **Step 1: Create the entity**

三级回源缓存。用户点击"展开完整内容"时，从 `GET /session/:sessionID/message/:messageID` 回源获取未截断数据，缓存到此表。

```kotlin
package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "SessionMessageFullContentEntity")
data class SessionMessageFullContentEntity(
    @PrimaryKey val messageID: String,
    val sessionID: String,
    val data: String,
    val fetchedAt: Long,
)
```

- `messageID`：与 SessionMessageEntity.id 对应
- `data`：未截断的完整 JSON（来自旧模型 API 响应 `{ info, parts[] }`）
- `fetchedAt`：缓存时间，可用于 LRU 淘汰

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:database:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 4: Create SyncStateDao

**Files:**
- Create: `core/database/src/main/java/com/openmate/core/database/dao/SyncStateDao.kt`

- [ ] **Step 1: Create the DAO**

```kotlin
package com.openmate.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openmate.core.database.entity.SyncStateEntity

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM SyncStateEntity WHERE sessionID = :sessionID")
    suspend fun getBySession(sessionID: String): SyncStateEntity?

    @Query("SELECT lastSeq FROM SyncStateEntity WHERE sessionID = :sessionID")
    suspend fun getLastSeq(sessionID: String): Int?

    @Query("SELECT * FROM SyncStateEntity")
    suspend fun getAll(): List<SyncStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query("DELETE FROM SyncStateEntity WHERE sessionID = :sessionID")
    suspend fun delete(sessionID: String)
}
```

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:database:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 5: Create SessionMessageDao

**Files:**
- Create: `core/database/src/main/java/com/openmate/core/database/dao/SessionMessageDao.kt`

- [ ] **Step 1: Create the DAO**

```kotlin
package com.openmate.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openmate.core.database.entity.SessionMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionMessageDao {
    @Query("SELECT * FROM SessionMessageEntity WHERE sessionID = :sessionID ORDER BY createdAt ASC")
    fun observeBySession(sessionID: String): Flow<List<SessionMessageEntity>>

    @Query("SELECT * FROM SessionMessageEntity WHERE id = :id")
    suspend fun getById(id: String): SessionMessageEntity?

    @Query("SELECT MAX(seq) FROM SessionMessageEntity WHERE sessionID = :sessionID")
    suspend fun getMaxSeq(sessionID: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: SessionMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<SessionMessageEntity>)

    @Query("DELETE FROM SessionMessageEntity WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM SessionMessageEntity WHERE sessionID = :sessionID")
    suspend fun deleteBySession(sessionID: String)
}
```

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:database:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 6: Update AppDatabase

**Files:**
- Modify: `core/database/src/main/java/com/openmate/core/database/AppDatabase.kt`

- [ ] **Step 1: Add new entities and DAOs**

更新内容：
1. 新增 3 个 entity 到 `entities` 数组
2. 新增 2 个 abstract DAO 方法
3. version 9 → 10

```kotlin
@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        PartEntity::class,
        PermissionEntity::class,
        QuestionEntity::class,
        TodoEntity::class,
        SyncStateEntity::class,
        SessionMessageEntity::class,
        SessionMessageFullContentEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun partDao(): PartDao
    abstract fun permissionDao(): PermissionDao
    abstract fun questionDao(): QuestionDao
    abstract fun todoDao(): TodoDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun sessionMessageDao(): SessionMessageDao
}
```

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :core:database:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Task 7: Add FullContent DAO query methods

**Files:**
- Create: `core/database/src/main/java/com/openmate/core/database/dao/SessionMessageFullContentDao.kt`
- Modify: `core/database/src/main/java/com/openmate/core/database/AppDatabase.kt`

- [ ] **Step 1: Create the DAO**

```kotlin
package com.openmate.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openmate.core.database.entity.SessionMessageFullContentEntity

@Dao
interface SessionMessageFullContentDao {
    @Query("SELECT * FROM SessionMessageFullContentEntity WHERE messageID = :messageID")
    suspend fun getById(messageID: String): SessionMessageFullContentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(content: SessionMessageFullContentEntity)

    @Query("DELETE FROM SessionMessageFullContentEntity WHERE messageID = :messageID")
    suspend fun delete(messageID: String)

    @Query("DELETE FROM SessionMessageFullContentEntity WHERE fetchedAt < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)
}
```

- [ ] **Step 2: Add to AppDatabase**

在 AppDatabase 中新增：
```kotlin
abstract fun sessionMessageFullContentDao(): SessionMessageFullContentDao
```

- [ ] **Step 3: Build and verify**

Run: `.\gradlew.bat :core:database:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`

Expected: No errors

---

## Design Notes

### 为什么 SessionMessageEntity 用 JSON data 列而非强类型列？

1. **类型差异大**：SessionMessage 有 7 种类型，字段几乎不重叠（assistant 有 content[]/tokens/cost，user 有 text/files，shell 有 command/output）。拆成强类型列会导致大量 nullable 列（类似当前 PartEntity 的问题但更严重）。
2. **截断后结构可变**：截断规则会修改 data 内容（如裁剪 bash output、跳过 snapshot），强类型列无法表达"部分字段被截断"的语义。
3. **Room 无法直接映射联合类型**：opencode 的 `SessionMessage` 是 tagged union，Room 没有 discriminated union 支持。
4. **查询模式简单**：UI 按会话加载所有消息 → 按 type 分派渲染，不需要按 data 内部字段做 SQL 查询。

### 为什么保留旧表？

过渡期需要旧表可用：
1. 旧锚点同步作为回退
2. 验证新数据与旧数据一致性
3. UI 逐步迁移，不能一刀切

### DB version 升级策略

当前使用 `fallbackToDestructiveMigration`，升级 version 会清空所有表数据。这是可接受的，因为：
- 移动端数据可从服务端重新同步
- 过渡期用户数据无持久化需求
- 避免 migration boilerplate

---

## Verification

- [ ] `.\gradlew.bat :core:database:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"` — zero errors
- [ ] 3 张新表 + 3 个新 DAO 在 AppDatabase 中注册
- [ ] schema export 生成正确的 JSON schema（检查 `core/database/schemas/` 目录）
