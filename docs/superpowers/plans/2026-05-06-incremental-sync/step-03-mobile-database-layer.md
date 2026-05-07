# Step 03: 移动端数据库层

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 用新的 SessionMessage 模型替换旧的 MessageEntity + PartEntity 表。新增 sync_state 和 session_message_full_content 表。

**Architecture:** 新模型中一条消息是一个 JSON 整体（SessionMessageEntity），不再是 message + parts 的拆分结构。DB version 递增 + fallbackToDestructiveMigration（用户卸载重装）。

**Tech Stack:** Kotlin, Room 2.7.2, kotlinx.serialization

**Design Doc:** `docs/superpowers/specs/2026-05-06-mobile-incremental-sync-design.md`

---

## File Structure

```
core/database/src/main/java/com/openmate/core/database/
├── AppDatabase.kt                — 版本升级，新增 entities + DAOs
├── entity/
│   ├── SyncStateEntity.kt        — 新增
│   ├── SessionMessageEntity.kt   — 新增（替代 MessageEntity + PartEntity）
│   └── SessionMessageFullContentEntity.kt — 新增（回源缓存）
├── dao/
│   ├── SyncStateDao.kt           — 新增
│   ├── SessionMessageDao.kt      — 新增（替代 MessageDao + PartDao）
│   └── SessionMessageFullContentDao.kt — 新增
```

旧的 MessageEntity、PartEntity、PartLiteEntity、MessageDao、PartDao 将删除。

---

## Task 1: 新增 Entity

**Files:**
- Create: `core/database/src/main/java/com/openmate/core/database/entity/SyncStateEntity.kt`
- Create: `core/database/src/main/java/com/openmate/core/database/entity/SessionMessageEntity.kt`
- Create: `core/database/src/main/java/com/openmate/core/database/entity/SessionMessageFullContentEntity.kt`

- [ ] **Step 1: 创建 SyncStateEntity**

```kotlin
package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val sessionId: String,
    val lastSeq: Long,
)
```

- [ ] **Step 2: 创建 SessionMessageEntity**

```kotlin
package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_message",
    indices = [
        Index("sessionId"),
        Index("sessionId", "type"),
        Index("timeCreated"),
    ],
)
data class SessionMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val type: String,
    val data: String,
    val timeCreated: Long,
    val timeUpdated: Long,
)
```

- [ ] **Step 3: 创建 SessionMessageFullContentEntity**

```kotlin
package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_message_full_content")
data class SessionMessageFullContentEntity(
    @PrimaryKey val messageId: String,
    val content: String,
    val fetchedAt: Long,
)
```

- [ ] **Step 4: Commit**

```
git add core/database/src/main/java/com/openmate/core/database/entity/SyncStateEntity.kt core/database/src/main/java/com/openmate/core/database/entity/SessionMessageEntity.kt core/database/src/main/java/com/openmate/core/database/entity/SessionMessageFullContentEntity.kt
git commit -m "feat(database): add sync state and session message entities"
```

---

## Task 2: 新增 DAO

**Files:**
- Create: `core/database/src/main/java/com/openmate/core/database/dao/SyncStateDao.kt`
- Create: `core/database/src/main/java/com/openmate/core/database/dao/SessionMessageDao.kt`
- Create: `core/database/src/main/java/com/openmate/core/database/dao/SessionMessageFullContentDao.kt`

- [ ] **Step 1: 创建 SyncStateDao**

```kotlin
package com.openmate.core.database.dao

import androidx.room.*
import com.openmate.core.database.entity.SyncStateEntity

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)
}
```

- [ ] **Step 2: 创建 SessionMessageDao**

```kotlin
package com.openmate.core.database.dao

import androidx.room.*
import com.openmate.core.database.entity.SessionMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionMessageDao {
    @Query("SELECT * FROM session_message WHERE sessionId = :sessionId ORDER BY timeCreated ASC")
    fun observeBySession(sessionId: String): Flow<List<SessionMessageEntity>>

    @Query("SELECT * FROM session_message WHERE sessionId = :sessionId ORDER BY timeCreated DESC LIMIT :limit")
    suspend fun getBySessionDesc(sessionId: String, limit: Int): List<SessionMessageEntity>

    @Query("SELECT * FROM session_message WHERE id = :id")
    suspend fun getById(id: String): SessionMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SessionMessageEntity>)

    @Query("DELETE FROM session_message WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM session_message WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT COUNT(*) FROM session_message WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    @Transaction
    suspend fun replaceAllForSession(sessionId: String, messages: List<SessionMessageEntity>) {
        deleteBySession(sessionId)
        upsertAll(messages)
    }
}
```

- [ ] **Step 3: 创建 SessionMessageFullContentDao**

```kotlin
package com.openmate.core.database.dao

import androidx.room.*
import com.openmate.core.database.entity.SessionMessageFullContentEntity

@Dao
interface SessionMessageFullContentDao {
    @Query("SELECT * FROM session_message_full_content WHERE messageId = :messageId")
    suspend fun get(messageId: String): SessionMessageFullContentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionMessageFullContentEntity)

    @Query("DELETE FROM session_message_full_content WHERE messageId = :messageId")
    suspend fun delete(messageId: String)
}
```

- [ ] **Step 4: Commit**

```
git add core/database/src/main/java/com/openmate/core/database/dao/SyncStateDao.kt core/database/src/main/java/com/openmate/core/database/dao/SessionMessageDao.kt core/database/src/main/java/com/openmate/core/database/dao/SessionMessageFullContentDao.kt
git commit -m "feat(database): add sync DAOs"
```

---

## Task 3: 更新 AppDatabase

**Files:**
- Modify: `core/database/src/main/java/com/openmate/core/database/AppDatabase.kt`

- [ ] **Step 1: 版本号 +1，注册新 entities/DAOs，删除旧的**

```kotlin
@Database(
    entities = [
        SessionEntity::class,
        // 删除: MessageEntity::class, PartEntity::class, PermissionEntity::class, QuestionEntity::class, TodoEntity::class
        // 新增:
        SyncStateEntity::class,
        SessionMessageEntity::class,
        SessionMessageFullContentEntity::class,
    ],
    version = 10,  // 从 9 升到 10
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    // 删除: abstract fun messageDao(): MessageDao
    // 删除: abstract fun partDao(): PartDao
    // 删除: abstract fun permissionDao(): PermissionDao
    // 删除: abstract fun questionDao(): QuestionDao
    // 删除: abstract fun todoDao(): TodoDao
    // 新增:
    abstract fun syncStateDao(): SyncStateDao
    abstract fun sessionMessageDao(): SessionMessageDao
    abstract fun sessionMessageFullContentDao(): SessionMessageFullContentDao
}
```

注意：Permission/Question/Todo 暂时也删除——这些功能将通过新的同步机制或独立 API 获取。如需保留可后续加回。

- [ ] **Step 2: 删除旧的 Entity 和 DAO 文件**

删除以下文件：
- `entity/MessageEntity.kt`
- `entity/PartEntity.kt`
- `entity/PartLiteEntity.kt`
- `entity/PermissionEntity.kt`
- `entity/QuestionEntity.kt`
- `entity/TodoEntity.kt`
- `dao/MessageDao.kt`
- `dao/PartDao.kt`
- `dao/PermissionDao.kt`
- `dao/QuestionDao.kt`
- `dao/TodoDao.kt`

- [ ] **Step 3: 验证编译**

Run: `./gradlew :core:database:compileDebugKotlin`
Expected: 编译成功（可能有其他模块引用旧 DAO 的编译错误，在后续步骤修复）

- [ ] **Step 4: Commit**

```
git add -A core/database/
git commit -m "feat(database): replace old entities with session message model, bump to v10"
```
