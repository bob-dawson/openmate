# Bridge Windows GUI 设计文档

> 日期: 2026-05-19
> 状态: 已确认，待实施
> Mockup: `docs/bridge-windows-gui-mockup.html`

## 目标

将 Bridge 从 Console 应用改造为 Windows 桌面应用：无控制台窗口、系统托盘驻留、内嵌 Web 管理页面。

## 架构

### 运行模式

```
openmate.exe (Windows 子系统, 无控制台)
├── 有子命令 (approve/install/uninstall/service/reset-token)
│   └── 纯 CLI 行为（与现有完全一致）
└── 无子命令 → GUI 模式:
    ├── 启动 axum 服务器
    ├── 创建系统托盘图标
    └── 进入 Win32 消息循环
```

### 子系统切换

- `#![windows_subsystem = "windows"]` 通过条件编译：`cfg(all(windows, not(test)))`
- 从终端调用子命令时 stdout 正常输出到调用方终端

### 启动参数

| 参数 | 行为 |
|------|------|
| 无参数 | GUI 模式，自动打开浏览器 |
| `--tray` | GUI 模式，不打开浏览器（用于开机自启） |
| 子命令 | CLI 行为不变 |

### 单实例运行

- 使用 Windows Named Mutex `Global\OpenMateBridge` 检测
- 重复启动时：`POST http://localhost:4097/api/bridge/open-ui` 通知运行实例打开浏览器，当前进程退出
- 新增 API：`POST /api/bridge/open-ui`（仅 localhost）→ 调用 `open::that()` 打开浏览器

## 系统托盘

### 实现

- windows-rs 直接调用 Win32 `Shell_NotifyIcon` API
- 主线程运行 Win32 消息循环
- tokio runtime 在独立线程池运行 axum + 异步逻辑
- 通过 mpsc channel 桥接托盘事件到 async 世界

### 托盘菜单

```
OpenMate Bridge (图标: 绿点=Running, 红点=Stopped)
├── 🟢 opencode: Running       ← 状态显示，不可点击
├── ─────────────
├── 打开管理页面               ← 浏览器打开 /ui/
├── ─────────────
├── ✔ 开机自动启动             ← 勾选/切换
└── 退出                       ← graceful shutdown
```

### 开机自启

- 添加：在 `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\` 创建快捷方式
- 快捷方式目标：`openmate.exe --tray`（静默启动，不开浏览器）
- 移除：删除该快捷方式
- 状态检测：检查快捷方式是否存在
- 切换入口：托盘菜单 + Web 设置页面

### 退出流程

1. 停止 opencode 进程
2. 关闭 axum 服务器 (graceful)
3. 销毁托盘图标
4. 进程退出

## Web 管理 UI

### 技术方案

- 静态 HTML/JS 通过 `include_str!` 编译期嵌入二进制
- axum 新增 `/ui/*` 路由提供静态页面服务
- 深色主题，单页应用，左侧导航

### 页面结构（4 个 Tab）

#### 概览

- opencode 状态卡片（Running/Stopped, PID, 运行时长）
- opencode 版本卡片（当前版本 + 最新版本 + 升级按钮）
- 已配对设备数量
- Bridge 版本 + 端口
- 待批准配对请求列表（PIN + 来源 IP + 倒计时 + 批准/拒绝按钮）
- 已配对设备列表（设备名 + IP + 配对时间 + 最后活跃 + 改名/删除按钮）

#### 扫码连接

- 网卡选择下拉（自动枚举本机 IPv4 非 loopback 接口）
- QR 码（服务端 Rust qrcode crate 生成 SVG）
- QR 内容：`http://<选中IP>:4097/ui/download`
- Bridge 地址复制按钮
- APK 下载按钮（检测 exe 同目录 `apk/` 文件夹）
- 使用步骤说明

#### 日志

- 实时日志查看器（SSE 推送）
- Level 过滤（INFO/WARN/ERROR）
- 搜索
- 暂停/继续
- 下载日志

#### 设置

- 开机自启开关
- 重置密钥（危险操作）

### 信息页 `/ui/download`（扫码目标，移动端友好）

- Bridge 地址 + 复制按钮
- APK 下载链接
- 连接步骤说明

## 设备管理数据模型

### 新增 paired_devices 表

复用现有 SQLite sync_db：

```sql
CREATE TABLE paired_devices (
    device_id   TEXT PRIMARY KEY,
    ip          TEXT NOT NULL,
    name        TEXT,
    user_agent  TEXT,
    paired_at   TEXT NOT NULL,
    last_seen   TEXT
);
```

### Token 结构变更

- Token payload 新增 `device_id` 字段
- 认证中间件验证链：HMAC 签名 → 提取 device_id → 查询 paired_devices → 存在则放行并更新 last_seen
- 不存在则 401（设备已被删除）
- **不向后兼容**：旧 token 全部失效，所有设备需重新配对

### 配对流程变更

confirm 时：生成 device_id → INSERT paired_devices → 签发含 device_id 的 token

### 设备操作

- 列表：`SELECT * FROM paired_devices ORDER BY last_seen DESC`
- 改名：`UPDATE paired_devices SET name = ? WHERE device_id = ?`
- 删除：`DELETE FROM paired_devices WHERE device_id = ?`（该设备下次请求自动 401）

## 日志系统

### 捕获

- 自定义 `tracing::Layer`，拦截格式化后的日志事件
- 写入环形缓冲区 `Arc<Mutex<VecDeque<LogEntry>>>`，容量 2000 条
- 同时写文件到 `~/.opencode/bridge.log`（兜底）
- `LogEntry { timestamp: String, level: String, target: String, message: String }`

### API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/bridge/logs?level=&search=&offset=&limit=` | 获取日志（JSON，支持分页和过滤） |
| GET | `/api/bridge/logs/stream` | SSE 实时推送新日志 |

### Web UI

- SSE 实时滚动显示
- Level 过滤按钮
- 搜索输入框
- 暂停/继续按钮
- 下载日志按钮

## QR 码

### LAN IP 检测

- API：`GET /api/bridge/network/interfaces`
- 枚举本机网卡，过滤 IPv4 + 非 loopback + 状态 up
- 返回 `[{name, ip}]`

### QR 码生成

- API：`GET /api/bridge/qrcode?ip=<ip>` → SVG image
- 使用 Rust `qrcode` crate 服务端生成
- QR 内容：`http://<ip>:4097/ui/download`

## APK 托管

- Bridge 启动时检测 exe 同目录下 `apk/` 文件夹
- 存在则提供下载，不存在则下载按钮隐藏
- API：`GET /download/openmate.apk` → 文件下载
- 管理页可上传更新 APK

## 新增 API 汇总

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/bridge/open-ui` | 通知打开浏览器 | localhost |
| GET | `/api/bridge/network/interfaces` | 枚举网卡 IP | localhost |
| GET | `/api/bridge/qrcode?ip=` | 生成 QR 码 SVG | localhost |
| GET | `/api/bridge/logs` | 获取日志 | localhost |
| GET | `/api/bridge/logs/stream` | SSE 日志流 | localhost |
| GET | `/ui/` | 管理页面 | localhost |
| GET | `/ui/download` | 扫码信息页 | 公开 |
| GET | `/download/openmate.apk` | APK 下载 | 公开 |
| POST | `/api/bridge/autostart` | 设置开机自启 | localhost |
| GET | `/api/bridge/autostart` | 查询自启状态 | localhost |

## 新增 Cargo 依赖

| crate | 用途 |
|-------|------|
| `windows` | Win32 Shell_NotifyIcon + Named Mutex |
| `qrcode` | QR 码生成 |
| `open` | 打开浏览器 |

## 源码变更预览

```
新增文件:
  src/tray.rs              # 托盘图标 + Win32 消息循环
  src/ui/                  # Web UI 静态文件 (编译期嵌入)
  src/log_capture.rs       # tracing Layer + 环形缓冲区
  src/network.rs           # 网卡 IP 枚举

修改文件:
  src/main.rs              # 子系统切换 + GUI 模式入口
  src/auth/pair.rs         # confirm 时写入 paired_devices
  src/auth/middleware.rs   # 验证 device_id 存在性
  src/auth/token.rs        # Token payload 增加 device_id
  src/server.rs            # 新增 /ui/* /logs 等路由
  src/state.rs             # AppState 增加日志缓冲区
  Cargo.toml               # 新增依赖
```
