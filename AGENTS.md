# AGENTS.md — OpenMate Workspace

## Debug & Analysis Tools

### 1. Session API Analysis (opencode REST API)
`D:\openmate\scripts\session_tool.py` — 通过 opencode REST API 查询服务端原始数据。

**前提**: opencode serve 运行在 `http://127.0.0.1:4096`

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
# 默认概览：表列表 + PartEntity 各类型计数 + file parts + synthetic text 残留
python D:\openmate\scripts\analyze_android_db.py D:\openmate\temp_db_dir\instance.db

# 自定义 SQL 查询
python D:\openmate\scripts\analyze_android_db.py D:\openmate\temp_db_dir\instance.db --sql "SELECT * FROM PartEntity WHERE type='file'"
```

#### 常用检查 SQL

```sql
-- Part 类型分布
SELECT type, count(*) FROM PartEntity GROUP BY type;

-- File parts 详情
SELECT id, mime, filename, length(url) FROM PartEntity WHERE type='file';

-- 检查 synthetic text 是否被正确过滤（应返回 0 行）
SELECT id, substr(text,1,60) FROM PartEntity WHERE type='text' AND text LIKE '%Called the%';

-- 特定会话的消息
SELECT id, role, createdAt FROM MessageEntity WHERE sessionID='ses_xxx' ORDER BY createdAt;

-- 特定消息的 parts
SELECT type, sequence, substr(text,1,40), toolName, mime, filename FROM PartEntity WHERE messageID='msg_xxx' ORDER BY sequence;
```

### 典型调试流程

渲染问题时，按此顺序排查，定位数据丢失发生在哪一层：

```
1. session_tool.py export  → API 原始数据对不对？
2. analyze_android_db.py   → DB 里存的数据对不对？
3. PartDto.toDomain()      → JSON→Domain 映射是否缺少 type 分支？
4. Part.toEntity()         → Domain→Entity 字段是否完整？
5. PartRenderer            → DisplayItem 渲染逻辑是否正确？
```

**重要**: 每次 app 重新安装（DB version 变更 + fallbackToDestructiveMigration）后，需要重新进入会话触发同步才能看到数据。
