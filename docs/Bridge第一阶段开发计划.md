# Bridge Agent 第一阶段开发计划

> 手机 App 直连 Bridge，Bridge 代理转发到 opencode + 监控/启停 opencode + 扩展文件目录 API + 文件 Web 服务

## 1. 第一阶段定位

第一阶段跳过 Cloud Relay Server，Android 客户端直接通过局域网连接 Bridge Agent。Bridge Agent 作为手机和 opencode 之间的代理层，提供：

1. **代理转发**：将客户端 HTTP/SSE 请求透传到 opencode
2. **opencode 进程管理**：监控 opencode 运行状态，按需启停
3. **扩展 API**：补充 opencode 缺失的文件目录操作能力（文件搜索、目录创建等）
4. **文件 Web 服务**：提供静态文件 HTTP 访问，Android 端自行渲染预览

```
第一阶段架构：

┌─────────────┐    HTTP/SSE     ┌──────────────┐    HTTP/SSE     ┌──────────────┐
│  Android    │◄───────────────►│  Bridge      │◄───────────────►│  opencode    │
│  Client     │   (LAN 直连)    │  Agent       │   (localhost)   │  serve       │
└─────────────┘                 └──────────────┘                 └──────────────┘
      │ 原始文件                       │
      └── GET /files/* ───────────────┤
                                     ├── 进程管理（启停/监控 opencode）
                                     ├── 代理转发（REST + SSE）
                                     ├── 扩展 API（文件目录操作）
                                     └── 文件 Web 服务（静态文件传输）
```

**与第二阶段的区别**：第二阶段引入 Cloud Relay，Bridge 和客户端都连接云端 WebSocket，实现跨网络通信。第一阶段是局域网直连，通信模型更简单。

---

## 2. 核心功能模块

### 2.1 代理转发

Bridge 监听一个 HTTP 端口（如 `:4097`），接收客户端请求，转发到本地 opencode（如 `:4098`）。

**REST 代理**：
- 客户端发到 Bridge 的 `/api/opencode/*` 请求，Bridge 去掉前缀后转发到 opencode
- 例：`GET http://bridge:4097/api/opencode/session` → `GET http://localhost:4098/session`
- 透传请求头、请求体、响应状态码、响应头

**SSE 代理**：
- 客户端连接 Bridge 的 `/api/opencode/global/event`，Bridge 同时连接 opencode 的 `/global/event`
- opencode SSE 事件逐行转发给客户端
- Bridge 断线重连 opencode SSE 时，客户端无感知（Bridge 内部处理重连）
- Bridge 需要维护 SSE 心跳：opencode 10 秒心跳超时则重连，同时向客户端维持自己的心跳

**代理转发的价值**（即使第一阶段客户端也能直连 opencode）：
- 统一入口：客户端只需知道 Bridge 地址，不需要分别知道 opencode 端口
- 进程管理：客户端通过 Bridge 启停 opencode，无需手动在电脑上操作
- 扩展 API：Bridge 在 opencode API 基础上增加文件操作等能力
- 为第二阶段铺路：客户端代码从直连 opencode 改为直连 Bridge，第二阶段只需改 Bridge 的上游连接方式

### 2.2 opencode 进程管理

**核心能力**：
- 检测 opencode 是否运行（进程检测 + 健康检查）
- 按需启动 opencode（`opencode serve --hostname 127.0.0.1 --port 4098`）
- 优雅停止 opencode（SIGTERM → 超时 SIGKILL）
- 监控 opencode 进程状态（running / stopped / crashed）
- opencode 崩溃后自动重启（可配置）

**API 端点**：

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/bridge/status` | Bridge 状态（opencode 运行状态、版本等） |
| `POST` | `/api/bridge/opencode/start` | 启动 opencode |
| `POST` | `/api/bridge/opencode/stop` | 停止 opencode |
| `POST` | `/api/bridge/opencode/restart` | 重启 opencode |

**启动流程**：

```
1. Bridge 收到 start 请求
2. 检查 opencode 是否已在运行（GET /global/health）
3. 如未运行：spawn opencode serve
4. 等待就绪（轮询 /global/health，超时 60 秒）
5. 连接 SSE 流
6. 返回成功响应
```

**进程监控**：

```
- 监听 opencode 子进程 exit 事件
- exit 后：断开 SSE 连接，更新状态为 stopped
- 如 autoRestart=true：自动重新启动
- SSE 心跳超时（30 秒无事件）：视为 opencode 异常，尝试重启
```

**状态模型**：

```rust
enum OpencodeStatus {
    Stopped,
    Starting,
    Running,
    Stopping,
    Crashed,
}
```

### 2.3 扩展文件目录 API

opencode 的文件 API 有限制：只能浏览服务器工作目录下的文件，且缺少目录创建、文件搜索等能力。Bridge 在 opencode 之外直接操作文件系统，补充以下 API：

| 方法 | 路径 | 说明 | 对应待处理问题 |
|------|------|------|----------------|
| `GET` | `/api/bridge/fs/list` | 列出目录内容（支持任意路径） | 文件浏览器只能访问工作目录 |
| `GET` | `/api/bridge/fs/read` | 读取文件内容 | — |
| `POST` | `/api/bridge/fs/mkdir` | 创建目录（含中间目录） | 目录创建 |
| `POST` | `/api/bridge/fs/search` | 文件搜索（按名称/内容） | 文件搜索 |
| `GET` | `/api/bridge/fs/stat` | 获取文件/目录元信息 | — |
| `POST` | `/api/bridge/fs/write` | 写入文件 | — |

**API 详情**：

```
GET /api/bridge/fs/list?path=/home/user/project
→ { entries: [{ name, type: "file"|"directory", size, modified, permissions }] }

GET /api/bridge/fs/read?path=/home/user/project/src/main.ts
→ 文件内容（文本文件直接返回，二进制文件返回 base64）

POST /api/bridge/fs/mkdir
  { path: "/home/user/project/src/new-dir", recursive: true }
→ { success: true }

POST /api/bridge/fs/search
  { path: "/home/user/project", query: "TODO", type: "content"|"filename", maxResults: 50 }
→ { results: [{ path, line?, column?, snippet? }] }

GET /api/bridge/fs/stat?path=/home/user/project
→ { name, type, size, modified, permissions, isDirectory }

POST /api/bridge/fs/write
  { path: "/home/user/project/new-file.ts", content: "...", createDirs: true }
→ { success: true }
```

**安全约束**：
- Bridge 配置中设定允许访问的根目录白名单（`allowedPaths`）
- 所有文件操作路径必须解析为绝对路径后，验证在白名单内
- 拒绝访问白名单外的路径，返回 403
- 默认允许 opencode 的 `directory` 配置路径

### 2.4 文件 Web 服务

Bridge 内置一个轻量静态文件 HTTP 服务器，让 Android 客户端可以通过 HTTP 直接获取工作区文件的原始内容，由客户端自行决定如何渲染和预览。

**为什么需要文件 Web 服务**：
- **二进制文件**：图片、音频、视频等通过 JSON base64 传输效率低，直接 HTTP GET 获取原始字节更高效
- **大文件流式传输**：HTTP 原生支持 Range 请求和分块传输，客户端可按需加载
- **Android 端自主渲染**：Bridge 只负责传输原始文件，客户端根据文件类型选择 WebView / 图片查看器 / 代码高亮组件等渲染方式，更灵活
- **简洁的 Bridge**：Bridge 保持纯数据传输层的定位，不做渲染逻辑

**访问模式**：

```
文件原始内容：
  GET /files/{path}    → 直接返回文件原始字节，Content-Type 自动推断

目录列表：
  GET /files/{path}/   → 返回目录内容的 JSON（复用 fs.list 的结构）
```

**MIME 类型处理**：

| 文件类型 | Content-Type | 说明 |
|---------|-------------|------|
| 图片 (png/jpg/gif/svg/webp) | `image/png` 等 | WebView/图片组件直接加载 |
| 视频/音频 (mp4/mp3/...) | `video/mp4` 等 | MediaPlayer/ExoPlayer 直接加载 |
| PDF | `application/pdf` | WebView 内置 PDF 渲染 |
| 文本/代码 | `text/plain; charset=utf-8` | 客户端获取内容后自行高亮渲染 |
| Markdown | `text/plain; charset=utf-8` | 客户端获取内容后自行 Markdown 渲染 |
| 二进制文件 | `application/octet-stream` | 提供下载 |

**Android 客户端集成**：

- 文件浏览器中点击文件 → 根据文件类型选择预览方式：
  - 图片/视频/音频/PDF → WebView 或原生组件直接加载 `http://bridge:4097/files/{path}`
  - 代码文件 → 获取文本内容后用客户端高亮组件渲染
  - Markdown → 获取文本内容后用客户端 Markdown 渲染组件渲染
  - 其他 → 提供下载

**安全约束**（同 §2.3，共享 PathGuard）：
- `/files/*` 路径同样受 `allowedPaths` 白名单约束
- 防止路径遍历：`/files/../../etc/passwd` → 403
- 默认只能访问 opencode 工作目录及其子目录

---

## 3. 技术方案

### 3.1 技术栈：Rust

选择 Rust 的核心理由：

1. **单文件发布**：`cargo build --release` 产出单个静态链接二进制，用户下载即用，无需安装任何运行时
2. **跨平台编译**：`cross` 工具链一行命令交叉编译 Linux/macOS/Windows + ARM/x64
3. **低资源占用**：常驻内存 5-15 MB，无 GC 停顿，适合长期运行的服务
4. **文件搜索性能**：可集成 `ignore` crate（ripgrep 核心库），文件搜索性能碾压其他方案
5. **编译期安全**：重构和修改时编译器帮忙检查，长期维护更可靠

| 选项 | 选型 | 理由 |
|------|------|------|
| HTTP 框架 | **axum** | Tokio 生态，成熟稳定，SSE/stream 支持好 |
| 异步运行时 | **tokio** | Rust 异步标准，多任务调度 |
| HTTP 客户端 | **reqwest** | 代理转发 opencode 请求 |
| 进程管理 | **tokio::process** | spawn + 信号 + stdout 监听 |
| 文件搜索 | **ignore** crate | ripgrep 核心，高性能文件遍历 + gitignore 感知 |
| 内容搜索 | **grep-regex** + **grep-searcher** | ripgrep 生态，正则内容搜索 |
| 序列化 | **serde** + **serde_json** | Rust 标配，零开销 JSON |
| 配置文件 | **toml** crate | TOML 格式，serde 原生支持 |
| MIME 推断 | **infer** | 基于文件魔数，准确度高 |
| 路径安全 | **path-clean** + 自定义校验 | 规范化路径 + 白名单校验 |
| 日志 | **tracing** | 结构化日志，tokio 生态标配 |
| 错误处理 | **anyhow** (应用层) + **thiserror** (库层) | Rust 最佳实践 |

### 3.2 项目结构

```
opencode-bridge/
├── src/
│   ├── main.rs                   # 入口，解析配置，启动服务
│   ├── config.rs                  # TOML 配置加载与校验
│   ├── state.rs                   # AppState（共享状态：opencode 状态、SSE 连接等）
│   ├── proxy/
│   │   ├── mod.rs
│   │   ├── rest.rs                # REST 请求代理
│   │   └── sse.rs                 # SSE 流代理
│   ├── process/
│   │   ├── mod.rs
│   │   └── opencode_manager.rs    # opencode 进程管理
│   ├── fs/
│   │   ├── mod.rs
│   │   ├── router.rs              # 文件系统 API 路由
│   │   ├── operations.rs          # 文件操作实现
│   │   ├── search.rs              # 文件搜索（ignore + grep）
│   │   └── path_guard.rs          # 路径安全校验
│   ├── files/
│   │   ├── mod.rs
│   │   └── router.rs              # 文件 Web 服务路由（静态文件传输）
│   ├── bridge/
│   │   ├── mod.rs
│   │   └── router.rs              # Bridge 管理 API 路由
│   └── error.rs                   # 统一错误类型
├── Cargo.toml
├── bridge.toml                    # 配置文件
└── Cross.toml                     # 跨平台编译配置（可选）
```

### 3.3 配置文件

```toml
# bridge.toml

[bridge]
port = 4097
hostname = "0.0.0.0"         # 0.0.0.0 允许局域网访问

[opencode]
binary = "opencode"           # 可执行文件路径
hostname = "127.0.0.1"
port = 4098
directory = ""                # 默认工作目录
auto_start = true             # Bridge 启动时自动启动 opencode
auto_restart = true           # opencode 崩溃后自动重启

[fs]
allowed_paths = []            # 允许访问的路径白名单，空则自动使用 opencode.directory
```

配置文件搜索路径（按优先级）：
1. 命令行参数 `--config <path>`
2. 当前目录 `./bridge.toml`
3. `~/.opencode/bridge.toml`

### 3.4 SSE 代理实现要点

```
客户端连接 Bridge SSE:
  1. Bridge 收到 /api/opencode/global/event 请求
  2. Bridge 检查是否已有到 opencode 的 SSE 连接
     - 有：复用现有连接，将新客户端加入订阅者列表
     - 无：建立到 opencode 的 SSE 连接
  3. opencode SSE 事件到达 → 逐行转发给所有订阅的客户端
  4. 客户端断开 → 从订阅者列表移除
  5. 所有客户端断开 → 关闭到 opencode 的 SSE 连接（可选，或保持复用）
  6. opencode SSE 断线 → Bridge 自动重连 → 重连成功后继续转发
```

**注意**：第一阶段客户端只有一个，SSE 代理实现可以简化为 1:1 转发（一个客户端对应一个到 opencode 的 SSE 连接），无需多路复用。第二阶段再优化为共享连接。

**Rust SSE 代理关键实现**：

```rust
// axum SSE 响应：桥接 reqwest 的 SSE 流
async fn sse_proxy(State(state): State<AppState>) -> Sse<impl Stream<Item = Result<Event, Infallible>>> {
    let stream = reqwest::get(format!("http://{}/global/event", state.opencode_url))
        .await
        .unwrap()
        .bytes_stream()
        .map(|chunk| {
            // 解析 SSE 行，逐条转发
            Event::default().data(chunk.unwrap())
        });
    Sse::new(stream).keep_alive(KeepAlive::default())
}
```

### 3.5 Android 客户端适配

客户端从直连 opencode 改为连接 Bridge，改动最小化：

**当前**：`ServerProfile.address:port` → 直连 opencode（`http://address:4098`）
**改为**：`ServerProfile.address:port` → 连接 Bridge（`http://address:4097`）

具体改动：

1. **ServerProfile**：新增 `type` 字段区分直连 opencode / 连接 Bridge
2. **ConnectionManager**：根据 `type` 决定 baseUrl 和 SSE 路径
3. **OpencodeApiClient**：连接 Bridge 时，REST 路径加 `/api/opencode` 前缀
4. **SseClient**：连接 Bridge 时，SSE 路径为 `/api/opencode/global/event`
5. **新增 BridgeApiClient**：调用 `/api/bridge/*` 管理接口（启停 opencode、文件操作等）
6. **实例列表页**：Bridge 类型实例显示 opencode 运行状态，支持远程启停
7. **文件预览**：根据文件类型选择渲染方式，图片/PDF/视频直接加载 `/files/*` URL，文本文件获取内容后客户端渲染

---

## 4. 开发步骤

### Step 1：项目脚手架

- [ ] 初始化 Rust 项目（`cargo init opencode-bridge`）
- [ ] 配置 Cargo.toml 依赖（axum, tokio, serde, reqwest, toml, tracing 等）
- [ ] 实现 TOML 配置加载与校验
- [ ] 实现 AppState（共享状态）
- [ ] Bridge HTTP 服务启动（监听端口、基础路由）
- [ ] 健康检查端点 `GET /api/bridge/status`
- [ ] 验证：`cargo run` 启动，curl 访问 status 端点

### Step 2：REST 代理

- [ ] 实现通用 REST 代理（axum → reqwest 转发）
- [ ] 路径映射：`/api/opencode/*` → opencode `/*`
- [ ] 透传请求方法、请求头、请求体
- [ ] 透传响应状态码、响应头、响应体
- [ ] 错误处理：opencode 不可达时返回明确错误
- [ ] 验证：用 curl 测试所有关键 API 的代理转发

### Step 3：SSE 代理

- [ ] 实现 SSE 代理端点 `/api/opencode/global/event`
- [ ] Bridge 连接 opencode SSE 流（reqwest streaming）
- [ ] 逐行转发 SSE 事件给客户端（axum SSE response）
- [ ] Bridge 生成自己的心跳
- [ ] opencode SSE 断线时 Bridge 自动重连
- [ ] 客户端断开时清理资源
- [ ] 验证：Android 客户端连接 Bridge SSE，确认事件正常接收

### Step 4：opencode 进程管理

- [ ] 实现 OpencodeManager（tokio::process::Command）
- [ ] 启动 opencode（spawn + 等待就绪）
- [ ] 停止 opencode（SIGTERM → 超时 SIGKILL，Windows 用 taskkill）
- [ ] 进程状态监控（exit 事件、健康检查轮询）
- [ ] 崩溃自动重启
- [ ] Bridge 启动时 autoStart 逻辑
- [ ] API 端点：start / stop / restart / status
- [ ] 验证：通过 API 启停 opencode，确认状态正确

### Step 5：扩展文件目录 API

- [ ] 实现 PathGuard（路径白名单校验，处理符号链接和 `..` 遍历）
- [ ] 实现 fs.list（目录列表，std::fs::read_dir）
- [ ] 实现 fs.read（文件读取，文本直接返回，二进制 base64）
- [ ] 实现 fs.mkdir（目录创建，支持 recursive）
- [ ] 实现 fs.search（文件搜索：ignore crate 遍历 + grep-regex 内容搜索）
- [ ] 实现 fs.stat（文件元信息，std::fs::metadata）
- [ ] 实现 fs.write（文件写入）
- [ ] API 路由注册
- [ ] 验证：通过 curl 测试各文件操作 API

### Step 6：文件 Web 服务

- [ ] 实现 `/files/*` 路由，PathGuard 复用 §2.3 的白名单校验
- [ ] 实现 MIME 类型推断（infer crate）
- [ ] 实现原始文件访问（tokio::fs 直接流式返回文件字节 + 正确 Content-Type）
- [ ] 实现目录路径的 JSON 响应（复用 fs.list）
- [ ] 验证：浏览器访问 `/files/` 确认文件下载、目录列表

### Step 7：Android 客户端适配

- [ ] ServerProfile 新增 `type` 字段（DIRECT / BRIDGE）
- [ ] ConnectionManager 支持 Bridge 连接模式
- [ ] OpencodeApiClient 适配 `/api/opencode` 前缀
- [ ] SseClient 适配 Bridge SSE 路径
- [ ] 新增 BridgeApiClient（进程管理 + 文件操作）
- [ ] 实例列表页：Bridge 实例显示 opencode 状态 + 启停按钮
- [ ] 文件浏览器接入 Bridge 文件 API（可浏览任意白名单内路径）
- [ ] 文件预览：图片/PDF/视频直接加载 `/files/*` URL，文本/Markdown 客户端渲染
- [ ] 端到端验证：手机通过 Bridge 完整操作 opencode

### Step 8：打包与部署

- [ ] `cargo build --release` 验证产出单文件二进制
- [ ] 跨平台编译配置（Cross.toml）：Linux x64/ARM64, macOS, Windows
- [ ] GitHub Actions CI：自动构建多平台 release
- [ ] Windows 服务注册脚本（NSSM）
- [ ] Linux systemd service 模板
- [ ] 文档：安装、配置、启动说明

---

## 5. 与设计文档的映射

| 设计文档章节 | 第一阶段实现 | 第二阶段扩展 |
|-------------|-------------|-------------|
| §4.1 Bridge 定位 | 代理 + 进程管理 + 文件扩展 | 新增 Cloud Relay WebSocket 连接 |
| §4.2 生命周期 | autoStart + 手动启停 | 新增 Cloud Relay 注册/心跳 |
| §4.3 进程管理 | OpencodeManager 完整实现 | 同 |
| §4.4 配置文件 | bridge.toml | 新增 serverUrl (Cloud Relay) |
| §4.6 请求代理映射 | REST 代理透传 | 通过 Cloud Relay WebSocket 转发 |
| §3.3 WebSocket 协议 | 不涉及 | 第二阶段实现 |
| §7 认证流程 | 不涉及（局域网信任） | 第二阶段实现 GitHub OAuth |

**第一阶段不做**：
- Cloud Relay WebSocket 连接
- GitHub OAuth 认证
- 推送通知（FCM）
- 多实例管理（云端）
- 消息分片 / 大数据优化

---

## 6. 验证标准

1. **代理转发**：客户端通过 Bridge 能完成所有 opencode 操作（创建会话、发消息、查看消息、确认权限、回答问题）
2. **SSE 实时性**：通过 Bridge 收到的 SSE 事件与直连 opencode 一致，延迟 < 100ms
3. **进程管理**：能通过 API 启停 opencode，崩溃后自动恢复，状态准确
4. **文件操作**：能浏览白名单内任意目录、创建目录、搜索文件、读写文件
5. **文件 Web 服务**：能通过 HTTP 获取工作区文件的原始内容，MIME 类型正确
6. **端到端**：手机通过 Bridge 完成完整编码会话流程（创建会话 → 发送消息 → 实时接收回复 → 确认权限 → 查看 diff → 浏览工作区文件）
7. **单文件部署**：`cargo build --release` 产出独立二进制，无外部依赖，可直接运行

---

## 7. 风险与注意事项

1. **SSE 代理复杂性**：axum 的 SSE 实现需要将 reqwest 的流式响应桥接到 axum 的 Sse response，需要仔细处理背压和生命周期
2. **进程管理跨平台**：Windows 上没有 SIGTERM，`tokio::process::Child::kill()` 会强制终止。Windows 上应使用 `taskkill /pid` 或 Ctrl-C event 实现优雅停止
3. **文件操作安全**：PathGuard 必须正确处理符号链接、`..` 路径遍历、路径编码等攻击向量，`/files/*` 和 `/api/bridge/fs/*` 共用同一套校验逻辑
4. **opencode 端口冲突**：Bridge 和 opencode 各占一个端口，确保不冲突。Bridge 默认 4097，opencode 默认 4098
5. **opencode 启动耗时**：首次启动可能较慢（加载 MCP、索引等），启动超时应设为 60 秒
6. **与现有 Android 客户端兼容**：第一阶段应保留直连 opencode 的能力（`type: DIRECT`），Bridge 模式作为新增选项，不影响现有功能
7. **大文件传输**：文件 Web 服务需要处理大文件，应使用流式传输（tokio::fs 分块读取），避免一次性加载到内存。可考虑对文件大小设置上限（如 100MB）
8. **Rust 编译时间**：首次编译较慢（~2min），开发时可用 `cargo check` 快速检查，或启用 mold/lld 链接器加速
