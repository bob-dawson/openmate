# Sync Debugger — 纯 Kotlin/JVM 同步排查工具（v2）

## 目的

排查 Android 端增量同步问题的命令行工具。通过 Bridge API 拉事件，复刻 EventReplayer 逻辑真实重现同步流程。输出每步 cache 状态和 change 详情。

## 方案：复制核心代码

完全独立的 Kotlin/JVM 项目，不依赖 Android 项目。从 Android 复制核心逻辑，DB 层用 JDBC 替代 Room。

**项目位置**：`D:\openmate\tools\sync-debugger`（独立于 `D:\openmate\android`）

## 架构

```
tools/sync-debugger/
├── build.gradle.kts          # 纯 JVM，Kotlin + Ktor + JDBC + clikt
├── settings.gradle.kts
├── gradle/                   # Gradle wrapper
└── src/main/kotlin/
    └── com/openmate/syncdebugger/
        ├── Main.kt              # CLI 入口 + 主流程
        ├── BridgeClient.kt      # Ktor HTTP，调 Bridge API
        ├── model/               # 数据模型（从 Android 复制，去掉 Android 依赖）
        │   ├── Entities.kt      # SessionMessageEntity, SyncStateEntity (普通 data class)
        │   ├── Dtos.kt          # SyncEventDto, EventsResponseDto 等 (kotlinx.serialization)
        │   └── ReplayModels.kt  # ReplayEvent, ReplayChange
        ├── replayer/
        │   ├── EventReplayer.kt # 从 Android 复制，核心逻辑不变
        │   └── SessionMessageMapper.kt  # DTO→Entity 映射
        ├── db/
        │   ├── JdbcDb.kt        # JDBC SQLite 连接管理
        │   └── JdbcDao.kt       # DAO 的 JDBC 实现（upsert, query）
        └── OutputFormatter.kt   # 控制台 + JSON 输出
```

## 依赖

```
org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2
io.ktor:ktor-client-core:3.1.3
io.ktor:ktor-client-cio:3.1.3
org.xerial:sqlite-jdbc:3.49.1.0
com.github.ajalt.clikt:clikt:4.4.0
org.slf4j:slf4j-simple:2.0.16
```

纯 JVM，零 Android 依赖。

## 数据流

```
Bridge API → 事件 → EventReplayer.replay() → Change → JDBC 写入本地 SQLite
                        ↑
                  JdbcDao (查本地 SQLite)
```

## CLI

```bash
# 全量同步
java -jar sync-debugger.jar --session ses_xxx

# 增量同步
java -jar sync-debugger.jar --session ses_xxx --after-seq 9900

# 指定 DB
java -jar sync-debugger.jar --session ses_xxx --db debug.db

# 指定 Bridge
java -jar sync-debugger.jar --session ses_xxx --bridge http://192.168.1.100:4097
```

## 实现步骤

1. 创建独立 Gradle 项目（build.gradle.kts + settings.gradle.kts + wrapper）
2. 复制数据模型（Entities, DTOs, ReplayModels）
3. 实现 JDBC DB 层（建表 + DAO）
4. 复制 EventReplayer（去掉 Android import）
5. 复制 SessionMessageMapper
6. 实现 BridgeClient（Ktor HTTP）
7. 实现 OutputFormatter
8. 实现 Main.kt 主流程
9. 编译 + 端到端测试
