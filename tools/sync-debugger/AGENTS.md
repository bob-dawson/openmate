# AGENTS.md — sync-debugger

纯 Kotlin/JVM CLI 工具，通过 Bridge API 拉取事件并重放 EventReplayer 增量同步逻辑，用于排查移动端同步 bug（如子任务中止后 metadata 丢失）。

## 构建

```powershell
.\gradlew.bat shadowJar
# 产出: build/libs/sync-debugger-all.jar
```

## 用法

```powershell
$jar = "build\libs\sync-debugger-all.jar"

# 列出 sessions
java -jar $jar --token @D:\openmate\scripts\.bridge_token --list

# 全量快照 + 增量同步（首次）
java -jar $jar --token @D:\openmate\scripts\.bridge_token --session <SES_ID> --init 30 --db debug.db

# 增量同步（从 DB cursor 继续，可重复执行）
java -jar $jar --token @D:\openmate\scripts\.bridge_token --session <SES_ID> --db debug.db

# 指定起始 seq（覆盖 DB cursor）
java -jar $jar --token <TOKEN> --session <SES_ID> --after-seq 100 --db debug.db

# 仅输出 JSON 文件
java -jar $jar --token <TOKEN> --session <SES_ID> --db debug.db --json-only --output result.json
```

## 参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `--session` | Session ID | 必填（或 `--list`） |
| `--init <N>` | 全量快照加载（指定 limit） | 无 |
| `--after-seq` | 增量起始 seq（覆盖 DB cursor） | DB 中 sync_state.lastSeq |
| `--db` | SQLite 持久化文件路径 | `sync-debug.db` |
| `--bridge` | Bridge URL | `http://127.0.0.1:4097` |
| `--token` | Bearer token（`@path` 读文件） | 无 |
| `--list` | 列出所有 sessions | — |
| `--output` | JSON 输出文件 | `sync-result.json` |
| `--json-only` | 只输出 JSON，不打印控制台 | false |

## 分页机制

Bridge events API 默认 `limit=100`。工具自动分页循环，与 Android 端逻辑一致：
- `while(true)` 循环请求，每批 100 条事件
- 每批 replay → coalesce → 写 DB → 更新 cursor
- `events.isEmpty()` 时停止
- 控制台输出标注 batch 编号 `[B2]`

## 架构

```
src/main/kotlin/com/openmate/syncdebugger/
├── Main.kt              # CLI 入口 + 主流程（分页循环、coalesce、DB 写入）
├── BridgeClient.kt      # Ktor HTTP + Bearer auth（init/events/sessions）
├── OutputFormatter.kt   # 控制台输出 + JSON 模型（StepResult/SyncResult/Summary）
├── db/
│   ├── JdbcDb.kt        # SQLite 连接 + 建表
│   └── JdbcDao.kt       # JDBC 实现 DAO（含 getAssistantByToolCallId）
├── model/
│   ├── Dtos.kt          # Bridge API 响应 DTO
│   ├── Entities.kt      # 本地持久化实体
│   └── ReplayModels.kt  # ReplayEvent / ReplayChange
└── replayer/
    ├── EventReplayer.kt       # 从 Android 复制，加 getCacheState()
    └── SessionMessageMapper.kt # 从 Android 复制
```

## 与 Android 端的关系

- `replayer/EventReplayer.kt` 和 `replayer/SessionMessageMapper.kt` 从 `android/core/data/` 复制（改包名）
- `db/JdbcDao.kt` 对应 Android 端的 `SessionMessageDao`，用 JDBC SQLite 替代 Room
- EventReplayer 新增 `getCacheState(): Triple<String?, String?, Int>` 暴露内部 cache 状态
- **同步修复时需双向同步代码**：Android 端改 EventReplayer 后需同步到此工具

## 输出格式

### 控制台

```
[seq=42] message.part.updated | cache=assistant..a1b2c3d4(tools=3) | UPDATE tool msg_abc123
[seq=43] session.updated | cache=assistant..a1b2c3d4(tools=3) | SKIP (noChange)
=== Summary ===
Session: ses_xxx
Events: 24 (batches=1)
Changes: 14
Skipped: 10
Skip reasons: {noChange=10}
```

### JSON

`SyncResult` 包含 `steps[]`（每事件的 seq/type/cache/change/skip/batch）和 `summary`。

## 已知问题

- SLF4J 无 provider 警告无害，可忽略
- Bridge token 路径：`D:\openmate\scripts\.bridge_token`（128 字符 hex）
