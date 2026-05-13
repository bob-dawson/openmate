# Sync Debugger 实现计划 v2（纯 JVM）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** 独立 Kotlin/JVM CLI 工具，通过 Bridge API 拉事件，复刻 EventReplayer 逻辑，输出每步 cache 状态和 change 详情。

**Architecture:** 完全独立项目 `D:\openmate\tools\sync-debugger`，零 Android 依赖。从 Android 项目复制核心逻辑（EventReplayer、DAO），DB 层用 JDBC SQLite 替代 Room。HTTP 用 Ktor。

**Tech Stack:** Kotlin 2.2.0, Ktor Client 3.1.3, SQLite JDBC 3.49.1.0, kotlinx-serialization 1.8.1, clikt 4.4.0

---

## File Structure

```
D:\openmate\tools\sync-debugger\
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/ (从 Android 项目复制)
└── src/main/kotlin/com/openmate/syncdebugger/
    ├── Main.kt                 # CLI + 主流程编排
    ├── BridgeClient.kt         # Ktor HTTP 客户端
    ├── OutputFormatter.kt      # 控制台 + JSON
    ├── model/
    │   ├── Entities.kt         # SessionMessageEntity, SyncStateEntity
    │   ├── Dtos.kt             # SyncEventDto, EventsResponseDto, InitResponseDto
    │   └── ReplayModels.kt     # ReplayEvent, ReplayChange
    ├── replayer/
    │   ├── EventReplayer.kt    # 从 Android 复制
    │   └── SessionMessageMapper.kt  # DTO→Entity 映射
    └── db/
        ├── JdbcDb.kt           # SQLite 连接 + 建表
        └── JdbcDao.kt          # DAO 的 JDBC 实现
```

---

### Task 1: 创建 Gradle 项目骨架

**Files:**
- Create: `D:\openmate\tools\sync-debugger\settings.gradle.kts`
- Create: `D:\openmate\tools\sync-debugger\build.gradle.kts`
- Create: `D:\openmate\tools\sync-debugger\src\main\kotlin\com\openmate\syncdebugger\Main.kt`

- [ ] **Step 1: 复制 Gradle wrapper**

```bash
# 从 Android 项目复制 gradle wrapper
New-Item -ItemType Directory -Force "D:\openmate\tools\sync-debugger\gradle\wrapper" | Out-Null
Copy-Item "D:\openmate\android\gradle\wrapper\gradle-wrapper.jar" "D:\openmate\tools\sync-debugger\gradle\wrapper\"
Copy-Item "D:\openmate\android\gradle\wrapper\gradle-wrapper.properties" "D:\openmate\tools\sync-debugger\gradle\wrapper\"
Copy-Item "D:\openmate\android\gradlew" "D:\openmate\tools\sync-debugger\"
Copy-Item "D:\openmate\android\gradlew.bat" "D:\openmate\tools\sync-debugger\"
```

- [ ] **Step 2: 创建 settings.gradle.kts**

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "sync-debugger"
```

- [ ] **Step 3: 创建 build.gradle.kts**

```kotlin
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

application {
    mainClass.set("com.openmate.syncdebugger.MainKt")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-cio:3.1.3")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<JavaExec> {
    standardInput = System.`in`
}
```

- [ ] **Step 4: 创建占位 Main.kt**

```kotlin
package com.openmate.syncdebugger

fun main(args: Array<String>) {
    println("sync-debugger v2")
}
```

- [ ] **Step 5: 验证编译和运行**

```bash
cd D:\openmate\tools\sync-debugger
.\gradlew.bat run --no-daemon 2>&1
```

预期：输出 `sync-debugger v2`

---

### Task 2: 复制数据模型

**Files:**
- Create: `src/main/kotlin/com/openmate/syncdebugger/model/Entities.kt`
- Create: `src/main/kotlin/com/openmate/syncdebugger/model/Dtos.kt`
- Create: `src/main/kotlin/com/openmate/syncdebugger/model/ReplayModels.kt`

- [ ] **Step 1: 创建 Entities.kt**

从 Android `SessionMessageEntity` 和 `SyncStateEntity` 复制，去掉 Room 注解：

```kotlin
package com.openmate.syncdebugger.model

data class SessionMessageEntity(
    val id: String,
    val sessionId: String,
    val type: String,
    val data: String,
    val timeCreated: Long,
    val timeUpdated: Long,
    val completedAt: Long? = null,
    val roundMark: Boolean = true,
)

data class SyncStateEntity(
    val sessionId: String,
    val lastSeq: Long,
)
```

- [ ] **Step 2: 创建 Dtos.kt**

从 Android `SyncDto.kt` 复制，去掉 Android 相关导入。只保留 sync 相关的 DTO：

```kotlin
package com.openmate.syncdebugger.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class EventsResponseDto(
    val events: List<SyncEventDto> = emptyList(),
    @SerialName("maxSeq") val maxSeq: Long? = null,
)

@Serializable
data class SyncEventDto(
    val id: String = "",
    @SerialName("aggregateId") val aggregateId: String = "",
    val seq: Long = 0,
    val type: String = "",
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class InitResponseDto(
    val messages: List<SyncMessageDto> = emptyList(),
    @SerialName("maxSeq") val maxSeq: Long? = null,
)

@Serializable
data class SyncMessageDto(
    val id: String = "",
    @SerialName("sessionId") val sessionId: String = "",
    val type: String = "",
    @SerialName("timeCreated") val timeCreated: Long = 0,
    @SerialName("timeUpdated") val timeUpdated: Long = 0,
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SessionsResponseDto(
    val sessions: List<SyncSessionDto> = emptyList(),
)

@Serializable
data class SyncSessionDto(
    val id: String = "",
    val title: String = "",
    @SerialName("maxSeq") val maxSeq: Long? = null,
)
```

- [ ] **Step 3: 创建 ReplayModels.kt**

从 Android `EventReplayer.kt` 提取 ReplayEvent 和 ReplayChange：

```kotlin
package com.openmate.syncdebugger.model

import kotlinx.serialization.json.JsonObject

data class ReplayEvent(
    val id: String,
    val type: String,
    val data: JsonObject,
)

sealed class ReplayChange {
    data class Insert(val entity: SessionMessageEntity) : ReplayChange()
    data class Update(
        val id: String,
        val type: String,
        val data: JsonObject,
        val timeUpdated: Long,
        val completedAt: Long? = null,
        val roundMark: Boolean? = null,
    ) : ReplayChange()
}
```

- [ ] **Step 4: 编译验证**

```bash
cd D:\openmate\tools\sync-debugger
.\gradlew.bat compileKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD"
```

---

### Task 3: 实现 JDBC DB 层

**Files:**
- Create: `src/main/kotlin/com/openmate/syncdebugger/db/JdbcDb.kt`
- Create: `src/main/kotlin/com/openmate/syncdebugger/db/JdbcDao.kt`

- [ ] **Step 1: 创建 JdbcDb.kt**

SQLite 连接管理 + 建表（模拟 Room 的 schema）：

```kotlin
package com.openmate.syncdebugger.db

import java.sql.Connection
import java.sql.DriverManager

class JdbcDb(private val dbPath: String) : AutoCloseable {
    val connection: Connection

    init {
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        connection.autoCommit = false
        createTables()
    }

    private fun createTables() {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS session_message (
                    id TEXT PRIMARY KEY,
                    sessionId TEXT NOT NULL,
                    type TEXT NOT NULL,
                    data TEXT NOT NULL,
                    timeCreated INTEGER NOT NULL,
                    timeUpdated INTEGER NOT NULL,
                    completedAt INTEGER,
                    roundMark INTEGER NOT NULL DEFAULT 1
                )
            """)
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_session_message_sessionId ON session_message(sessionId)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_session_message_sessionId_type ON session_message(sessionId, type)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_session_message_timeCreated ON session_message(timeCreated)")
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sync_state (
                    sessionId TEXT PRIMARY KEY,
                    lastSeq INTEGER NOT NULL
                )
            """)
        }
        connection.commit()
    }

    fun transaction(block: () -> Unit) {
        try {
            block()
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        }
    }

    override fun close() {
        connection.close()
    }
}
```

- [ ] **Step 2: 创建 JdbcDao.kt**

实现 Android 端 SessionMessageDao 和 SyncStateDao 的查询，用 JDBC：

```kotlin
package com.openmate.syncdebugger.db

import com.openmate.syncdebugger.model.SessionMessageEntity
import com.openmate.syncdebugger.model.SyncStateEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull

class JdbcDao(private val db: JdbcDb) {

    private val json = Json { ignoreUnknownKeys = true }

    fun getById(id: String): SessionMessageEntity? {
        val sql = "SELECT * FROM session_message WHERE id = ?"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return readEntity(rs)
            }
        }
    }

    fun getLatestIncompleteAssistant(sessionId: String): SessionMessageEntity? {
        val sql = "SELECT * FROM session_message WHERE sessionId = ? AND type = 'assistant' AND roundMark = 0 AND completedAt IS NULL ORDER BY timeCreated DESC LIMIT 1"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return readEntity(rs)
            }
        }
    }

    fun getLatestIncompleteCompaction(sessionId: String): SessionMessageEntity? {
        val sql = "SELECT * FROM session_message WHERE sessionId = ? AND type = 'compaction' AND completedAt IS NULL ORDER BY timeCreated DESC LIMIT 1"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return readEntity(rs)
            }
        }
    }

    fun getAssistantByToolCallId(sessionId: String, callID: String): SessionMessageEntity? {
        val sql = "SELECT * FROM session_message WHERE sessionId = ? AND type = 'assistant' AND data LIKE '%' || ? || '%' ORDER BY timeCreated DESC LIMIT 1"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, callID)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return readEntity(rs)
            }
        }
    }

    fun upsert(entity: SessionMessageEntity) {
        val sql = """INSERT OR REPLACE INTO session_message (id, sessionId, type, data, timeCreated, timeUpdated, completedAt, roundMark)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, entity.id)
            ps.setString(2, entity.sessionId)
            ps.setString(3, entity.type)
            ps.setString(4, entity.data)
            ps.setLong(5, entity.timeCreated)
            ps.setLong(6, entity.timeUpdated)
            entity.completedAt?.let { ps.setLong(7, it) } ?: ps.setNull(7, java.sql.Types.INTEGER)
            ps.setInt(8, if (entity.roundMark) 1 else 0)
            ps.executeUpdate()
        }
    }

    fun deleteBySession(sessionId: String) {
        db.connection.prepareStatement("DELETE FROM session_message WHERE sessionId = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeUpdate()
        }
    }

    fun getSyncState(sessionId: String): SyncStateEntity? {
        val sql = "SELECT * FROM sync_state WHERE sessionId = ?"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return SyncStateEntity(rs.getString("sessionId"), rs.getLong("lastSeq"))
            }
        }
    }

    fun upsertSyncState(entity: SyncStateEntity) {
        val sql = "INSERT OR REPLACE INTO sync_state (sessionId, lastSeq) VALUES (?, ?)"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, entity.sessionId)
            ps.setLong(2, entity.lastSeq)
            ps.executeUpdate()
        }
    }

    private fun readEntity(rs: java.sql.ResultSet): SessionMessageEntity {
        val completedAt = rs.getLong("completedAt")
        return SessionMessageEntity(
            id = rs.getString("id"),
            sessionId = rs.getString("sessionId"),
            type = rs.getString("type"),
            data = rs.getString("data"),
            timeCreated = rs.getLong("timeCreated"),
            timeUpdated = rs.getLong("timeUpdated"),
            completedAt = if (rs.wasNull()) null else completedAt,
            roundMark = rs.getInt("roundMark") != 0,
        )
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd D:\openmate\tools\sync-debugger
.\gradlew.bat compileKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD"
```

---

### Task 4: 复制 EventReplayer + SessionMessageMapper

**Files:**
- Create: `src/main/kotlin/com/openmate/syncdebugger/replayer/EventReplayer.kt`
- Create: `src/main/kotlin/com/openmate/syncdebugger/replayer/SessionMessageMapper.kt`

- [ ] **Step 1: 复制 EventReplayer.kt**

从 `D:\openmate\android\core\data\src\main\java\com\openmate\core\data\sync\EventReplayer.kt` 完整复制，修改：
- package 改为 `com.openmate.syncdebugger.replayer`
- import `com.openmate.core.database.entity.SessionMessageEntity` 改为 `com.openmate.syncdebugger.model.SessionMessageEntity`
- 去掉所有 `import com.openmate.core.data.sync.*`
- 替换为 `import com.openmate.syncdebugger.model.*`

同时添加 `getCacheState()` 方法（返回 Triple，用于输出 cache 状态）。

- [ ] **Step 2: 复制 SessionMessageMapper.kt**

从 `D:\openmate\android\core\data\src\main\java\com\openmate\core\data\sync\SessionMessageMapper.kt` 复制，修改：
- package 改为 `com.openmate.syncdebugger.replayer`
- import 改为 `com.openmate.syncdebugger.model.*`

- [ ] **Step 3: 编译验证**

```bash
cd D:\openmate\tools\sync-debugger
.\gradlew.bat compileKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD"
```

---

### Task 5: 实现 BridgeClient + OutputFormatter

**Files:**
- Create: `src/main/kotlin/com/openmate/syncdebugger/BridgeClient.kt`
- Create: `src/main/kotlin/com/openmate/syncdebugger/OutputFormatter.kt`

- [ ] **Step 1: 创建 BridgeClient.kt**

Ktor HTTP 客户端：

```kotlin
package com.openmate.syncdebugger

import com.openmate.syncdebugger.model.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.Json

class BridgeClient(private val baseUrl: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient(CIO)

    suspend fun getEvents(sessionId: String, afterSeq: Long): EventsResponseDto {
        val body = client.get("$baseUrl/api/bridge/sync/session/$sessionId/events?afterSeq=$afterSeq").bodyAsText()
        return json.decodeFromString<EventsResponseDto>(body)
    }

    suspend fun getInit(sessionId: String, limit: Int = 30): InitResponseDto {
        val body = client.get("$baseUrl/api/bridge/sync/session/$sessionId/init?limit=$limit").bodyAsText()
        return json.decodeFromString<InitResponseDto>(body)
    }

    suspend fun getSessions(): SessionsResponseDto {
        val body = client.get("$baseUrl/api/bridge/sync/sessions").bodyAsText()
        return json.decodeFromString<SessionsResponseDto>(body)
    }

    fun close() = client.close()
}
```

- [ ] **Step 2: 创建 OutputFormatter.kt**

```kotlin
package com.openmate.syncdebugger

import kotlinx.serialization.Serializable

@Serializable
data class StepResult(
    val seq: Long,
    val eventType: String,
    val eventId: String,
    val cacheState: CacheSnapshot? = null,
    val changeType: String? = null,
    val changeDetail: String? = null,
    val skipReason: String? = null,
)

@Serializable
data class CacheSnapshot(
    val id: String? = null,
    val type: String? = null,
    val toolCount: Int = 0,
)

@Serializable
data class SyncResult(
    val sessionId: String,
    val afterSeq: Long,
    val totalEvents: Int,
    val steps: List<StepResult>,
    val summary: Summary,
)

@Serializable
data class Summary(
    val totalChanges: Int,
    val skippedEvents: Int,
    val skipReasons: Map<String, Int>,
)

class OutputFormatter {
    fun formatStepConsole(step: StepResult): String {
        val cacheStr = step.cacheState?.let { c ->
            val id = c.id?.takeLast(8) ?: "null"
            "${c.type ?: "?"}..${id}(tools=${c.toolCount})"
        } ?: "null"
        val changeStr = step.changeType?.let { "$it ${step.changeDetail?.takeLast(8) ?: ""}" } ?: "SKIP"
        val skipStr = step.skipReason?.let { " ($it)" } ?: ""
        return "[seq=${step.seq}] ${step.eventType} | cache=$cacheStr | $changeStr$skipStr"
    }

    fun formatSummaryConsole(result: SyncResult): String {
        return """
            |=== Summary ===
            |Session: ${result.sessionId}
            |Events: ${result.totalEvents}
            |Changes: ${result.summary.totalChanges}
            |Skipped: ${result.summary.skippedEvents}
            |Skip reasons: ${result.summary.skipReasons}
        """.trimMargin()
    }
}
```

- [ ] **Step 3: 编译验证**

---

### Task 6: 实现 Main.kt 主流程

**Files:**
- Modify: `src/main/kotlin/com/openmate/syncdebugger/Main.kt`

主流程：
1. 解析 CLI 参数
2. 初始化 JDBC DB
3. 如果 `--init`：加载快照到 DB
4. 构建 DbLoader（从 JDBC DB 查）
5. 逐事件 replay 并收集输出
6. 将 Change 写入 DB（coalesce + apply）
7. 输出控制台 + JSON

需要从 Android `SessionMessageRepositoryImpl.incrementalSync()` 复刻：
- DbLoader lambda
- Coalesce 逻辑
- Apply 逻辑（Update 时读 existing → merge → upsert）

- [ ] **Step 1: 实现 Main.kt**

- [ ] **Step 2: 编译验证**

```bash
cd D:\openmate\tools\sync-debugger
.\gradlew.bat compileKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD"
```

---

### Task 7: 端到端测试

- [ ] **Step 1: 确保 Bridge 运行**

```bash
curl -s http://127.0.0.1:4097/api/bridge/status
```

- [ ] **Step 2: 获取一个 session ID**

```bash
cd D:\openmate\tools\sync-debugger
.\gradlew.bat run --args="--bridge http://127.0.0.1:4097 --list" --no-daemon 2>&1
```

- [ ] **Step 3: 运行全量同步**

```bash
.\gradlew.bat run --args="--session <SESSION_ID> --init 30" --no-daemon 2>&1
```

- [ ] **Step 4: 运行增量同步**

```bash
.\gradlew.bat run --args="--session <SESSION_ID>" --no-daemon 2>&1
```

- [ ] **Step 5: 检查 JSON 输出**
