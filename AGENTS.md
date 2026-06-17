# AGENTS.md — OpenMate Workspace

## Repository

- **GitHub**: https://github.com/bob-dawson/openmate
- **License**: Apache 2.0
- **Release Notes**: https://github.com/bob-dawson/openmate/releases
- **Changelog**: [`CHANGELOG.md`](CHANGELOG.md)

## Project Structure

- **Android 客户端**: `D:\openmate\android` — 详见 [`android/AGENTS.md`](android/AGENTS.md)（架构、构建、约定、API 等）
- **Bridge Agent**: `D:\openmate\opencode-bridge` — 详见 [`opencode-bridge/AGENTS.md`](opencode-bridge/AGENTS.md)（Rust 代理/进程管理/文件服务，端口 4097）
- **Relay Gateway**: `D:\openmate\server\relay-gateway` — 详见 [`server/relay-gateway/AGENTS.md`](server/relay-gateway/AGENTS.md)（Rust 中继网关，部署在 `gateway.clawmate.net`，端口 6200）
- **opencode 源码**: `D:\github\opencode` — 排查服务端事件流、`prompt_async`、`session.next.*` 相关逻辑时优先查看该仓库及其 `AGENTS.md`
- **调试脚本**: `D:\openmate\scripts\`
- **sync-debugger**: `D:\openmate\tools\sync-debugger` — 详见 [`tools/sync-debugger/AGENTS.md`](tools/sync-debugger/AGENTS.md)（纯 Kotlin/JVM CLI，重放 EventReplayer 增量同步排查 bug）
- **GradleMcp**: `D:\openmate\GradleMcp\GradleMcp` — .NET 10 Windows 服务，提供 Gradle 构建能力，端口 5099

## Key Paths

- **opencode DB**: `C:\Users\bob_d\.local\share\opencode\opencode.db` — SQLite，包含 event/session_message 等表，Bridge sync API 直接读此 DB
- **opencode state**: `C:\Users\bob_d\.local\state\opencode\` — frecency, model, prompt-history 等
- **opencode config**: `C:\Users\bob_d\.config\opencode\`
- **查询 opencode DB 示例**: `sqlite3 "C:\Users\bob_d\.local\share\opencode\opencode.db" "SELECT seq, type FROM event WHERE aggregate_id='ses_xxx' ORDER BY seq LIMIT 10"`

## Communication

- 默认使用中文与用户交流，除非用户明确要求使用其他语言。

## 讨论与实施纪律

- **任何技术讨论阶段，禁止直接修改代码。**
- 讨论完成后，必须向用户明确说明准备修改哪些文件、做什么改动，并请求用户确认。
- **用户确认之后才能开始修改代码。**
- 调查问题（读代码、查数据库、搜索）不受此限制。

## Sync Constraints

- 移动端因网络与系统调度特性，无法假设 SSE 连接始终稳定。
- 任何状态同步或数据展示都不能只依赖 SSE 获取数据，否则在断连、后台切换或重连期间可能丢失信息。
- 需要实时性时，可使用 SSE 提升更新速度，但必须配合轮询与增量同步等现有补偿机制保证最终一致性与信息完整性。

### 3. Bridge 测试 Token 生成

```powershell
# 从 bridge.db 读取密钥 + 插入测试设备 + 生成 HMAC token
# 输出保存在 scripts/.bridge_token
python D:\openmate\scripts\get_bridge_token.py --show

# 通过网关测试（替换 token 变量）：
$token = (Get-Content D:\openmate\scripts\.bridge_token).Trim()
curl -s https://gateway.clawmate.net/api/bridge/status -H "X-Instance-Id: <instance_id>" -H "Authorization: Bearer $token"
```

## Debug & Analysis Tools

### 0. opencode 事件数据库
opencode 的事件存储在 SQLite 数据库中，Bridge 直接读这个 DB 提供 sync API。

**DB 路径**: `C:\Users\bob_d\.local\share\opencode\opencode.db`

```powershell
# 查询特定会话的事件（如 step.failed、message.part.updated 等）
python -c "
import sqlite3, json
db = sqlite3.connect(r'C:\Users\bob_d\.local\share\opencode\opencode.db')
rows = db.execute('SELECT seq, type, data FROM event WHERE aggregate_id = ? AND seq > ? ORDER BY seq', ['SESSION_ID', START_SEQ]).fetchall()
for seq, typ, data_str in rows:
    print(f'seq={seq} type={typ}')
db.close()
"
```

关键表：`event`（id, aggregate_id, seq, type, data）、`event_sequence`（aggregate_id, seq）、`session_message`

### 1. Session API Analysis (opencode REST API)
`D:\openmate\scripts\session_tool.py` — 通过 opencode REST API 查询服务端原始数据。

**前提**: opencode serve 运行在 `http://127.0.0.1:4098`

```powershell
# 列出所有顶层会话（不含子会话）
python D:\openmate\scripts\session_tool.py list

# 含子会话
python D:\openmate\scripts\session_tool.py list --all

# 按标题关键词搜索
python D:\openmate\scripts\session_tool.py find "权限"

# 查看会话消息摘要（每条消息的 parts 按 type 简要输出）
python D:\openmate\scripts\session_tool.py parts <sessionID> --limit 20

# 导出完整 JSON（含所有 parts 原始数据，用于分析具体 part 字段值）
python D:\openmate\scripts\session_tool.py export <sessionID> --limit 10
python D:\openmate\scripts\session_tool.py export <sessionID> -o D:\tmp\debug.json
```

### 2. Android Room DB Analysis (设备数据库)
`D:\openmate\scripts\analyze_android_db.py` — 用 sqlite3 分析从安卓设备拉取的 Room 数据库。

**用途**: 确认数据是否正确写入 DB，排查同步/映射问题。**当 API 返回的数据正确但 UI 显示不对时，必须检查 DB。**

#### 拉取数据库（1 步）

```powershell
# 自动停止 app → 检测 DB 文件名 → 复制 3 个文件到 D:\openmate\temp_db_dir\
python D:\openmate\scripts\pull_android_db.py
```

手动拉取（备用）：

```powershell
# 1. 停止 app 让 WAL merge
adb shell am force-stop com.openmate

# 2. 复制 DB 文件（需同时复制 .db / .db-wal / .db-shm 三个文件）
#    DB 文件名格式: instance_{profileId}（不含扩展名）
#    先用 adb shell "run-as com.openmate ls /data/user/0/com.openmate/databases/" 查看实际文件名
New-Item -ItemType Directory -Force D:\openmate\temp_db_dir | Out-Null
$dbFile = "instance_xxxxx"  # 替换为实际文件名
adb exec-out run-as com.openmate cat /data/user/0/com.openmate/databases/$dbFile > D:\openmate\temp_db_dir\instance.db
adb exec-out run-as com.openmate cat /data/user/0/com.openmate/databases/${dbFile}-wal > D:\openmate\temp_db_dir\instance.db-wal
adb exec-out run-as com.openmate cat /data/user/0/com.openmate/databases/${dbFile}-shm > D:\openmate\temp_db_dir\instance.db-shm
```

#### 分析数据库

```powershell
# 默认概览：表列表 + session_message 各类型计数 + 同步状态
python D:\openmate\scripts\analyze_android_db.py D:\openmate\temp_db_dir\instance.db

# 自定义 SQL 查询
python D:\openmate\scripts\analyze_android_db.py D:\openmate\temp_db_dir\instance.db --sql "SELECT * FROM session_message WHERE type='tool' LIMIT 5"
```

#### 常用检查 SQL

```sql
-- Part 类型分布
SELECT type, count(*) FROM session_message GROUP BY type;

-- 特定会话的消息（按时间排序）
SELECT id, type, timeCreated, substr(data,1,80) FROM session_message WHERE sessionId='ses_xxx' ORDER BY timeCreated;

-- 同步状态
SELECT * FROM sync_state;

-- Tool 类型详情（data 是 JSON）
SELECT id, substr(data,1,120) FROM session_message WHERE type='tool' AND sessionId='ses_xxx';

-- Session 列表
SELECT id, title, status FROM SessionEntity ORDER BY updatedAt DESC;
```

### 典型调试流程

渲染问题时，按此顺序排查，定位数据丢失发生在哪一层：

```
1. session_tool.py export            → API 原始数据对不对？
2. analyze_android_db.py             → DB 里存的数据对不对？
3. SessionMessagePartDto.toDomain()  → JSON→Domain 映射是否缺少 type 分支？
4. SessionMessageEntity (data JSON)  → Domain→Entity 字段是否完整？
5. SessionMessagePartRenderer        → DisplayItem 渲染逻辑是否正确？
```

**重要**: 每次 app 重新安装（DB version 变更 + fallbackToDestructiveMigration）后，需要重新进入会话触发同步才能看到数据。

## GradleMcp HTTP API

GradleMcp（端口 5099）同时提供 MCP 协议（`/mcp`）和 REST HTTP 接口。由于 opencode MCP 远程连接空闲后可能断连且不会自动重连，推荐使用 HTTP 接口作为可靠替代。

**注意**: Gradle daemon 与 opencode shell 不兼容，Android 构建必须通过 GradleMcp HTTP 接口调用（端口 5099），不能直接使用 `gradlew`。sync-debugger 构建同理。

### 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/gradle/run` | 执行 Gradle 构建 |
| POST | `/api/gradle/stop` | 停止当前构建 |
| GET | `/api/gradle/status` | 查询构建状态 |

### 请求/响应

```powershell
# 运行 Gradle
Invoke-RestMethod -Uri "http://localhost:5099/api/gradle/run" -Method Post -ContentType "application/json" -Body '{"args":[":app:assembleDebug"],"cwd":"D:\\openmate"}'

# 请求体字段：
#   args       string[]  必需，Gradle 参数
#   cwd        string?   可选，起始目录（默认当前目录）
#   timeoutMs  int?      可选，超时毫秒数（默认 600000）

# run 响应（结构化 JSON）：
# {"ok":true,"exitCode":0,"timedOut":false,"idleTimedOut":false,"durationMs":12345,"stdoutTail":"...","stderrTail":"...","combinedTail":"...","errorMessage":null}
# 编译错误在 stderrTail/combinedTail 中，ok=false 且 exitCode!=0 表示构建失败

# 停止构建
Invoke-RestMethod -Uri "http://localhost:5099/api/gradle/stop" -Method Post

# stop 响应：
# {"stopped":true,"hadRunningTask":true,"message":"requested termination of the current Gradle process tree"}

# 查询状态
Invoke-RestMethod -Uri "http://localhost:5099/api/gradle/status"
# 返回: {"running":false} 或 {"running":true,"invocation":{"id":"...","startedAt":"...","workingDirectory":"...","running":true,"pid":12345}}
```

## 编写复杂实现计划的规范

遇到复杂实现计划时，**不要尝试写一份大而全的文档**。按以下步骤进行：

1. 先总结需要修改哪几个模块，每个模块进行哪几个步骤（模块级大纲）
2. 为每个步骤单独写一个实现文档（步骤级详情）

这样可以避免一次性编写过长文档导致遗漏或结构混乱。

## 重构纪律

重大重构（替换数据模型、重写组件、模块级改造）**必须**遵循以下流程，防止功能丢失：

### 1. 重构前：编写功能基线文档

在动手改代码之前，先梳理当前已实现的功能清单，写入文档（如 `docs/refactor-baseline.md`），内容至少包括：

- **功能列表**：每个页面/模块的完整功能项（含交互细节，如折叠/展开/排序/隐藏规则）
- **数据流**：哪些 Repository 有真实实现 vs 空实现，API 调用链路
- **UI 行为**：自动滚动策略、状态显示规则、模态/内联方式等
- **已知约束**：性能优化、时钟同步方案、轮询间隔等

### 2. 重构中：逐项迁移对照

每完成一个模块的重构，对照基线文档逐项确认：

- ✅ 功能已迁移
- ⚠️ 功能变更（说明原因）
- ❌ 功能缺失（必须补回或经用户确认删除）

### 3. 重构后：对照验收

重构完成后，用基线文档做验收清单，确保没有遗漏。特别关注：

- **Repository 实现**：是否从有实现变成了空实现（本次 TODO 仓库的教训）
- **UI 交互规则**：折叠/展开、排序、显示/隐藏条件是否一致
- **数据持久化**：Entity/DAO 是否完整注册到 AppDatabase
- **DB 版本**：schema 变更后必须递增 version number

### 教训案例

2026-05-10 SessionMessageRenderer 重构：替换 MessageEntity+PartEntity 数据模型时，TodoRepositoryImpl、SmartAutoScroll 等周边组件的实现被重置为空壳/默认值，导致 TODO 列表消失、滚动行为回退。根本原因是没有在重构前记录功能基线，重构后未对照验收。
