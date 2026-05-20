# Bridge Windows GUI 实施计划 — 模块级大纲

> 设计文档: `docs/superpowers/specs/2026-05-19-bridge-windows-gui-design.md`
> UI Mockup: `docs/bridge-windows-gui-mockup.html`

## 模块概览

共 6 个模块，按依赖顺序排列。每个模块产出可编译、可测试的增量。

```
01-BridgeDb ──→ 03-Token+Auth ──→ 04-Management APIs ──→ 05-Web UI
02-LogCapture ──┘                                          │
                                                           ↓
                                              06-Windows Desktop (最后集成)
```

---

### 模块 01: BridgeDb — Bridge 专用数据库

**目标**: 创建 Bridge 自己的 SQLite 数据库，管理 paired_devices 表

**为什么需要**: 现有 SyncDb 只读 opencode 的 DB。Bridge 需要自己的可写存储来跟踪已配对设备。

**步骤**:
1. 新建 `src/bridge_db.rs`，DB 文件 `~/.opencode/bridge.db`
2. 实现 `paired_devices` 表建表 + CRUD
3. 单元测试

**产出文件**: `src/bridge_db.rs`（新建），`src/lib.rs`（加 mod 声明）

---

### 模块 02: LogCapture — 日志捕获系统

**目标**: 自定义 tracing Layer，拦截日志写入环形缓冲区

**为什么需要**: Windows 子系统无控制台，需要通过 Web UI 查看日志。

**步骤**:
1. 新建 `src/log_capture.rs`，定义 LogEntry + LogBuffer
2. 实现 tracing Layer，格式化事件写入环形缓冲区
3. 同时写文件到 `~/.opencode/bridge.log`
4. 单元测试

**产出文件**: `src/log_capture.rs`（新建）

---

### 模块 03: Token + Auth 变更

**目标**: Token 增加 device_id 字段，认证中间件查询 paired_devices

**依赖**: 模块 01（BridgeDb）

**步骤**:
1. 修改 `src/auth/token.rs`：generate 接收 device_id，validate 返回 device_id
2. 修改 `src/auth/pair.rs`：confirm 时生成 device_id 并写入 BridgeDb
3. 修改 `src/auth/middleware.rs`：验证 token 后查询 paired_devices 存在性，更新 last_seen
4. 修改 `src/state.rs`：AppState 增加 BridgeDb 实例
5. 更新现有单元/集成测试

**产出文件**: 修改 `auth/token.rs`、`auth/pair.rs`、`auth/middleware.rs`、`state.rs`

---

### 模块 04: Management APIs — 管理 API 端点

**目标**: 新增所有 Web UI 需要的后端 API

**依赖**: 模块 01、02、03

**步骤**:
1. 设备管理 API：`GET/DELETE /api/bridge/devices`，`PUT /api/bridge/devices/{id}/name`
2. 日志 API：`GET /api/bridge/logs`（查询），`GET /api/bridge/logs/stream`（SSE）
3. 网络 API：`GET /api/bridge/network/interfaces`（枚举网卡）
4. QR 码 API：`GET /api/bridge/qrcode?ip=`（生成 SVG）
5. 自启动 API：`GET/POST /api/bridge/autostart`
6. Open-UI API：`POST /api/bridge/open-ui`
7. APK 下载：`GET /download/openmate.apk`
8. 修改 `src/server.rs` 注册新路由，修改 `middleware.rs` 更新路径白名单
9. 集成测试

**产出文件**: 新建 `src/api/` 目录（devices.rs, logs.rs, network.rs, qrcode.rs, autostart.rs），修改 `server.rs`、`middleware.rs`

---

### 模块 05: Web UI — 管理页面静态 HTML

**目标**: 实现完整的管理页面 UI

**依赖**: 模块 04（API 端点）

**步骤**:
1. 创建 `src/ui/` 目录，放入 HTML/JS 文件，通过 `include_str!` 嵌入
2. `server.rs` 新增 `/ui/*` 路由
3. 概览页：状态卡片 + 待配对列表 + 设备列表（JS fetch API）
4. 扫码连接页：网卡选择 + QR 码 + 下载
5. 日志页：SSE 实时 + 过滤 + 搜索
6. 设置页：自启开关 + 重置密钥
7. 下载页 `/ui/download`：移动端友好的扫码落地页

**产出文件**: `src/ui/` 目录（HTML/JS 文件），修改 `server.rs`

---

### 模块 06: Windows Desktop — 子系统切换 + 托盘 + 集成

**目标**: 从 Console 应用变为 Windows 桌面应用

**依赖**: 模块 01-05 全部完成

**步骤**:
1. 新建 `src/tray.rs`：windows-rs 调用 Shell_NotifyIcon，菜单，Win32 消息循环
2. 修改 `src/main.rs`：`#![windows_subsystem = "windows"]` 条件编译，`--tray` 参数，GUI 模式入口
3. 实现单实例检测（Named Mutex）+ 重复启动时 POST open-ui
4. 托盘菜单：打开管理页、opencode 状态、开机自启切换、退出
5. 实现开机自启快捷方式操作
6. 首次/手动启动时自动打开浏览器
7. Graceful shutdown 整合
8. `Cargo.toml` 新增依赖（windows, qrcode, open）
9. 端到端手动验证

**产出文件**: 新建 `src/tray.rs`，修改 `main.rs`、`Cargo.toml`

---

## 实施顺序

```
模块 01 (BridgeDb)        ── 可独立开发和测试
模块 02 (LogCapture)      ── 可与 01 并行
模块 03 (Token+Auth)      ── 依赖 01
模块 04 (Management APIs) ── 依赖 01, 02, 03
模块 05 (Web UI)          ── 依赖 04
模块 06 (Windows Desktop) ── 依赖全部，最后集成
```

每个步骤的详细实现文档见同目录下 `01-bridgedb.md` ~ `06-windows-desktop.md`。
