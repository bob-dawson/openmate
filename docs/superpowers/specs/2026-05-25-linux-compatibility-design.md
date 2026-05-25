# Linux 兼容性设计 — OpenMate Bridge

日期: 2026-05-25

## 目标

让 Bridge 在 Linux 上完整运行，支持有桌面和无桌面两种场景，对齐 Windows 管理页体验。

## 现状分析

### 编译阻断 (Critical)

| 文件 | 问题 |
|------|------|
| `src/api/autostart.rs` | 无条件引用 `std::os::windows::process::CommandExt`，Linux 编译失败 |
| `src/api/mod.rs:6,33-34` | 无条件包含 autostart 模块和路由 |

### 功能缺失 (High)

| 文件 | 问题 |
|------|------|
| `src/tray.rs:409-416` | `spawn_tray_thread()` 返回错误，无 Linux 托盘 |
| `src/main.rs:170-173` | `is_already_running()` 返回 false，无单实例保护 |
| `src/gateway/client.rs:58-73` | TCP keepalive 未配置，WebSocket 长连接可能静默断开 |

### 无桌面场景核心问题

当前配对流程依赖浏览器（localhost-only 管理页批准 PIN），无桌面 Linux 无法打开浏览器，用户无法完成配对。

### 已适配 (无需改动)

- `src/browser.rs` — `open::that()` 已支持 Linux (xdg-open)
- `src/config.rs` — PATH 分隔符和 binary 搜索已适配
- `src/process/opencode_manager.rs` — `pkill` 替代 `taskkill` 已实现
- `src/fs/operations.rs` — 根目录 `/` 已适配
- `src/service_linux.rs` — systemd 安装/卸载已实现

## 设计

### 1. 自启动：统一 systemd system service

Linux 下统一用 systemd system service 管理自启动（`/etc/systemd/system/openmate.service`），不使用 `--user` 或 XDG autostart。

**为什么不用 systemd --user**：WSL 2.5.x+ 存在 `systemctl --user` 竞态条件 bug（`/run/user/UID/bus` 缺失），在多个发行版上不可靠。

**为什么不用 XDG autostart**：XDG 仅在用户登录桌面后才触发，Bridge 作为后台服务应在开机时自动运行，无需等待用户登录。

**实现**：
- `is_enabled()`: `systemctl is-enabled openmate.service`
- `set_enabled(true)`: `systemctl enable openmate.service`（需 sudo）
- `set_enabled(false)`: `systemctl disable openmate.service`（需 sudo）

与现有 `service_linux.rs` 的 `install` 命令一致，`install` 本身就需要 sudo 写 unit 文件。

### 2. autostart 模块重构

将 `src/api/autostart.rs` 拆分为平台实现：

```
src/api/autostart/
├── mod.rs              # 公共 trait + cfg 重导出
├── windows.rs          # 现有 Windows 实现（原样迁移）
└── linux.rs            # 新建 Linux 实现 (systemd system service)
```

**公共接口** (`mod.rs`):

```rust
pub trait AutostartManager {
    fn is_enabled() -> bool;
    fn set_enabled(enabled: bool) -> anyhow::Result<()>;
    fn mode() -> &'static str;  // "windows" | "systemd" | "unavailable"
}

#[cfg(target_os = "windows")]
mod windows;
#[cfg(target_os = "linux")]
mod linux;

#[cfg(target_os = "windows")]
pub use windows::WindowsAutostart as Autostart;
#[cfg(target_os = "linux")]
pub use linux::LinuxAutostart as Autostart;
```

**Linux 实现** (`linux.rs`):

```rust
pub struct LinuxAutostart;

impl AutostartManager for LinuxAutostart {
    fn mode() -> &'static str { "systemd" }

    fn is_enabled() -> bool {
        // systemctl is-enabled openmate.service
    }

    fn set_enabled(enabled: bool) -> anyhow::Result<()> {
        // sudo systemctl enable/disable openmate.service
    }
}
```

**路由注册** (`src/api/mod.rs`):

```rust
.route("/api/bridge/autostart", get(autostart::get_autostart))
.route("/api/bridge/autostart", post(autostart::set_autostart))
```

### 3. 无桌面配对：终端二维码

#### 问题

无桌面 Linux 无法打开浏览器，用户无法完成扫码配对。

#### 方案：自定义协议 QR 码 + 终端 ASCII 输出

**QR 内容格式**（自定义协议，最短路径）：

```
openmate:iid=<32hex>;st=<64hex>
```

约 55 字符，终端 QR 码 Version 2-3（约 51-57 列 Unicode），非常小且清晰。

**参数说明**：
- `iid` — instance_id（16 字节 hex = 32 字符），网关用来定位 Bridge
- `st` — scan_token（32 字节 hex = 64 字符），一次性配对凭证

**不需要编码进 QR 的信息**：
- `name` — 配对成功后从 Bridge API 获取
- `address/port` — 通过网关配对时不需要 LAN 地址
- 下载链接 — 手机已安装 app 的场景不需要

**配对流程**：

```
1. Bridge 启动（无桌面模式）
2. 终端输出 ASCII 二维码 + 辅助信息：

   ████████████████████████████
   ██ ▄▄▄▄▄ █▀█ █▀▀▀█ ▄▄▄▄▄ ██
   ██ █   █ █▀▀▀█▄▀▄▄█ █   █ ██
   ██ █▄▄▄█ █▄▀ █▀▀ ▀█ █▄▄▄█ ██
   ██▄▄▄▄▄▄▄█▄▀▄█▄█▄█▄▄▄▄▄▄██

     Bridge: myserver (192.168.1.100:4097)
     Gateway: gateway.clawmate.net
     Scan the QR code to pair your device
     Or run: openmate approve <pin>

3. 手机扫码 → 解析 openmate: 协议
4. 通过网关 gateway.clawmate.net 发起 scan-confirm（iid + st）
5. 配对成功 → 获得 token + device_id
6. 用 token 调 Bridge API 获取 name 等补充信息
```

**Bridge 侧实现**：

`src/main.rs` 无桌面启动时，调用新模块输出终端二维码：

```rust
fn print_terminal_qr(state: &AppState) {
    let iid = &state.config.gateway.instance_id;
    let st = /* 当前 scan_token 或生成新的 */;
    let qr_data = format!("openmate:iid={};st={}", iid, st);

    let code = qrcode::QrCode::new(&qr_data).unwrap();
    let string = code.render::<qrcode::render::unicode::Dense1x2>()
        .quiet_zone(false)
        .module_dimensions(2, 1)
        .build();

    println!("\n{}", string);
    println!("  Bridge: {} ({}:{})", hostname, ip, port);
    println!("  Gateway: gateway.clawmate.net");
    println!("  Scan the QR code to pair your device");
    println!("  Or run: openmate approve <pin>\n");
}
```

**Android 侧改动**：

`QrScanViewModel.parseQrUrl()` 增加自定义协议解析：

```kotlin
private fun parseQrUrl(url: String): ParsedQrUrl? {
    if (url.startsWith("openmate:")) {
        return parseCustomProtocol(url)
    }
    // 现有 HTTP URL 解析逻辑不变
    ...
}

private fun parseCustomProtocol(url: String): ParsedQrUrl? {
    // openmate:iid=<32hex>;st=<64hex>
    val params = url.removePrefix("openmate:").split(";")
    var iid = ""
    var st = ""
    for (param in params) {
        val (key, value) = param.split("=", limit = 2)
        when (key) {
            "iid" -> iid = value
            "st" -> st = value
        }
    }
    if (st.isBlank()) return null
    return ParsedQrUrl(
        name = "",          // 配对成功后从 Bridge API 获取
        address = "",       // 通过网关，不需要 LAN 地址
        port = 0,
        scanToken = st,
        instanceId = iid,
    )
}
```

配对成功后，Android 调 Bridge `/api/bridge/status` 获取 name、port 等信息填充 ServerProfile。

**安全性**：
- `scan_token` 有 120 秒有效期（已有 `SCAN_TOKEN_TTL_SECS`）
- QR 码仅在终端显示，物理接触服务器才能看到
- 配对流程无需打开管理页批准（scan-confirm 是公开路径，与当前 LAN 扫码逻辑一致）
- 已有 `approve <pin>` CLI 命令作为备选

**有桌面场景**：检测到 `DISPLAY`/`WAYLAND_DISPLAY` 时，打开浏览器显示管理页（与 Windows 行为一致），不输出终端二维码。终端二维码仅限无桌面场景。

### 4. 管理页远程访问安全模型

#### 问题

Linux 无桌面场景，管理页 `/ui/` 限制 localhost-only，用户无法从其他机器访问。如果开放远程访问，安全性如何保证？

#### 方案：已配对设备 token 授权

**当前模型**：
- `/ui/` localhost-only
- 管理操作 localhost-only
- 配对设备 token 仅用于代理转发（opencode API）

**改为**：
- `/ui/` 允许两种访问方式：
  1. localhost 直接访问（不变）
  2. 已配对设备的 Bearer token 访问（新增）
- 管理操作 API 同样允许已配对设备 token 访问

**安全保证**：
- 未配对设备无法访问管理页（没有 token）
- 配对行为必须在 Bridge 机器本地完成（终端二维码 or `approve` CLI）
- 配对 = 已授权设备，信任级别等同于本机操作
- `pair/approve` 仍限 localhost-only（PIN 批准不开放远程）

**中间件改动** (`src/auth/middleware.rs`):

```rust
// /ui/ 和 LOCALHOST_ONLY_PATHS 的访问条件：
// 1. localhost → 放行（不变）
// 2. 已配对设备的 Bearer token → 放行（新增）
// 3. 其他 → 拒绝
```

### 5. Linux 系统托盘

**依赖**: `ksni = "0.3"` (cfg 门控，仅 Linux 编译)

```toml
[target.'cfg(target_os = "linux")'.dependencies]
ksni = "0.3"
```

**实现位置**: `src/tray.rs` 增加 `#[cfg(target_os = "linux")]` 块

**Tray 结构**:

```rust
#[cfg(target_os = "linux")]
struct BridgeTray {
    port: u16,
    autostart_enabled: bool,
    tx: mpsc::Sender<TrayEvent>,
}

#[cfg(target_os = "linux")]
impl ksni::Tray for BridgeTray {
    fn id(&self) -> String { "openmate-bridge".into() }
    fn title(&self) -> String { "OpenMate Bridge".into() }
    fn tool_tip(&self) -> ksni::ToolTip {
        ksni::ToolTip {
            title: "OpenMate Bridge".into(),
            description: format!("端口: {}", self.port).into(),
            ..Default::default()
        }
    }
    fn activate(&self, _x: i32, _y: i32) {
        let _ = self.tx.send(TrayEvent::OpenUi);
    }
    fn menu(&self) -> Vec<ksni::MenuItem<Self>> {
        vec![
            ksni::StandardItem {
                label: "打开管理页面".into(),
                activate: Box::new(|tray: &mut BridgeTray| {
                    let _ = tray.tx.send(TrayEvent::OpenUi);
                }),
                ..Default::default()
            },
            ksni::Separator,
            ksni::CheckmarkItem {
                label: "开机自动启动".into(),
                checked: self.autostart_enabled,
                activate: Box::new(|tray: &mut BridgeTray| {
                    tray.autostart_enabled = !tray.autostart_enabled;
                    let _ = tray.tx.send(TrayEvent::ToggleAutostart);
                }),
                ..Default::default()
            },
            ksni::Separator,
            ksni::StandardItem {
                label: "退出".into(),
                activate: Box::new(|tray: &mut BridgeTray| {
                    let _ = tray.tx.send(TrayEvent::Quit);
                }),
                ..Default::default()
            },
        ]
    }
}
```

**spawn_tray_thread 实现**:

```rust
#[cfg(target_os = "linux")]
pub fn spawn_tray_thread(port: u16, tx: mpsc::Sender<TrayEvent>) -> anyhow::Result<()> {
    let tray = BridgeTray { port, autostart_enabled: false, tx };
    let handle = ksni::Handle::new(tray);
    
    std::thread::spawn(move || {
        if let Err(e) = handle.run_blocking() {
            tracing::error!("Tray error: {}", e);
        }
    });
    Ok(())
}
```

**图标处理**: 使用 `icon_pixmap` 字段直接传像素数据（内嵌 PNG），避免依赖系统图标路径。

**无桌面环境降级**: ksni 依赖 D-Bus session bus。无桌面环境时优雅降级：
- 检查 `DBUS_SESSION_BUS_ADDRESS` 环境变量
- 不存在时 log warning 并跳过托盘初始化
- Bridge 正常运行，通过终端二维码配对 + web 管理页操作

### 6. 单实例保护

用 abstract Unix domain socket 替代 Windows Mutex：

```rust
#[cfg(target_os = "linux")]
fn is_already_running() -> bool {
    use std::os::unix::net::UnixListener;
    match UnixListener::bind("\0openmate-bridge") {
        Ok(_) => false,
        Err(_) => true,
    }
}
```

优势：
- abstract socket 不依赖文件系统，进程退出后自动释放
- 无需清理逻辑
- 与 Windows Mutex 语义一致

### 7. Gateway TCP Keepalive

补全 `src/gateway/client.rs` 中 Linux 的实现：

```rust
#[cfg(target_os = "linux")]
{
    use std::os::unix::io::{AsRawFd, FromRawFd};
    let raw_fd = socket.as_raw_fd();
    let socket2_socket = unsafe { socket2::Socket::from_raw_fd(raw_fd) };
    let keepalive = socket2::TcpKeepalive::new()
        .with_time(Duration::from_secs(30))
        .with_interval(Duration::from_secs(10));
    socket2_socket.set_tcp_keepalive(&keepalive)?;
    std::mem::forget(socket2_socket);
}
```

### 8. 启动流程适配

`src/main.rs` 的 `run_gui_mode()` 逻辑：

```
Linux 有桌面 (DISPLAY/WAYLAND_DISPLAY 存在):
  1. 单实例检测 (abstract socket)
  2. 启动 server
  3. 启动托盘 (ksni)
  4. 如果不是 --tray 模式，2秒后打开浏览器

Linux 无桌面:
  1. 单实例检测 (abstract socket)
  2. 启动 server
  3. 跳过托盘
  4. 输出终端 ASCII 二维码 + 配对提示
  5. 等待配对，配对成功后提示管理页地址
```

### 9. Web 管理页适配

**后端改动**:

`/api/bridge/status` 响应增加字段：
```json
{
    "autostart_mode": "systemd"
}
```
值：`"windows"` | `"systemd"` | `"unavailable"`

**前端改动** (`src/ui/index.html`):

设置页"自动启动"区域根据 `autostart_mode` 动态显示：

| autostart_mode | 显示 |
|----------------|------|
| `"windows"` | "开机自动启动" (不变) |
| `"systemd"` | "系统启动时自动启动 (systemd)" |
| `"unavailable"` | 隐藏该开关 |

## 修改文件清单

| 文件 | 改动 |
|------|------|
| `Cargo.toml` | 增加 `[target.'cfg(target_os = "linux")'.dependencies] ksni = "0.3"` |
| `src/api/autostart.rs` → `src/api/autostart/mod.rs` | 重构为 trait + 平台实现 |
| `src/api/autostart/windows.rs` | 迁移现有 Windows 代码 |
| `src/api/autostart/linux.rs` | 新建 Linux 实现 (systemd system service) |
| `src/api/mod.rs` | 更新 mod 声明和路由注册 |
| `src/tray.rs` | 增加 `#[cfg(target_os = "linux")]` ksni 实现 |
| `src/main.rs` | 更新 `is_already_running()`、托盘初始化、无桌面终端二维码输出 |
| `src/auth/middleware.rs` | `/ui/` 和管理 API 允许已配对设备 token 访问 |
| `src/gateway/client.rs` | 补全 Linux TCP keepalive |
| `src/bridge/router.rs` | status 响应增加 `autostart_mode` |
| `src/ui/index.html` | 设置页根据 autostart_mode 动态显示 |
| `src/lib.rs` | 更新 tray 模块的条件编译 |
| Android `QrScanViewModel.kt` | 增加自定义协议 `openmate:` 解析 |

## 不在范围内

- Linux 安装包格式 (deb/rpm/AppImage) — 后续单独处理
- 图标安装到系统路径 — 后续随安装包一起处理
- SELinux/AppArmor 策略 — 后续处理
- Wayland 特有的权限问题 — 依赖 D-Bus，暂不特殊处理
