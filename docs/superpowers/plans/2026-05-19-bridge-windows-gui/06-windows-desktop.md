# 步骤 06: Windows Desktop — 子系统切换 + 托盘 + 集成

> 依赖: 步骤 01-05 全部完成
> 产出: 新建 `src/tray.rs`，修改 `main.rs`、`Cargo.toml`

## 背景

将 Bridge 从 Console 应用变为 Windows 桌面应用：无控制台窗口、系统托盘驻留、单实例运行。

## 实现步骤

### Step 1: 更新 `Cargo.toml` — 新增依赖

```toml
[dependencies]
# 已有依赖保留...
open = "5"          # 步骤 04 已添加
qrcode = "0.14"     # 步骤 04 已添加

[target.'cfg(windows)'.dependencies]
windows-service = "0.8"   # 已有
windows = { version = "0.58", features = [
    "Win32_Foundation",
    "Win32_UI_Shell",
    "Win32_UI_WindowsAndMessaging",
    "Win32_System_Threading",
    "Win32_Security",
] }
```

### Step 2: 创建 `src/tray.rs` — 托盘图标

```rust
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::Arc;
use tokio::sync::Notify;

pub enum TrayEvent {
    OpenUi,
    ToggleAutostart,
    Quit,
}

pub struct TrayIcon {
    event_tx: Sender<TrayEvent>,
    event_rx: Receiver<TrayEvent>,
}

impl TrayIcon {
    pub fn new() -> Result<Self, String> {
        let (event_tx, event_rx) = mpsc::channel();
        Ok(Self { event_tx, event_rx })
    }

    pub fn event_receiver(&self) -> &Receiver<TrayEvent> {
        &self.event_rx
    }

    #[cfg(target_os = "windows")]
    pub fn run(win_rx: Receiver<TrayEvent>, shutdown_notify: Arc<Notify>, autostart_enabled: bool) {
        use windows::Win32::UI::Shell::*;
        use windows::Win32::UI::WindowsAndMessaging::*;
        use windows::Win32::Foundation::*;

        // 创建隐藏窗口用于接收托盘消息
        let hwnd = create_message_window();
        let mut nid = NOTIFYICONDATAW::default();
        nid.hWnd = hwnd;
        nid.uID = 1;
        nid.uCallbackMessage = WM_USER + 1;
        nid.uFlags = NIF_ICON | NIF_TIP | NIF_MESSAGE;
        // 设置图标和提示文字
        let tip: Vec<u16> = "OpenMate Bridge\0".encode_utf16().collect();
        tip.iter().enumerate().for_each(|(i, &c)| {
            if i < nid.szTip.len() { nid.szTip[i] = c; }
        });
        // 使用默认应用图标
        nid.hIcon = unsafe { LoadIconW(None, IDI_APPLICATION).unwrap_or_default() };

        unsafe {
            Shell_NotifyIconW(NIM_ADD, &mut nid);
        }

        // 消息循环
        let mut msg = MSG::default();
        loop {
            // 处理 Win32 消息
            while unsafe { PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE) }.as_bool() {
                if msg.message == WM_USER + 1 {
                    match msg.lParam.0 as u32 {
                        WM_LBUTTONDBLCLK => {
                            let _ = open::that("http://localhost:4097/ui/");
                        }
                        WM_RBUTTONUP => {
                            show_context_menu(hwnd, &win_rx, autostart_enabled);
                        }
                        _ => {}
                    }
                }
                if msg.message == WM_DESTROY {
                    let mut nid_del = NOTIFYICONDATAW::default();
                    nid_del.hWnd = hwnd;
                    nid_del.uID = 1;
                    unsafe { Shell_NotifyIconW(NIM_DELETE, &mut nid_del); }
                    return;
                }
                unsafe { TranslateMessage(&msg); }
                unsafe { DispatchMessageW(&msg); }
            }

            // 检查退出信号（非阻塞）
            if let Ok(TrayEvent::Quit) = win_rx.try_recv() {
                let mut nid_del = NOTIFYICONDATAW::default();
                nid_del.hWnd = hwnd;
                nid_del.uID = 1;
                unsafe { Shell_NotifyIconW(NIM_DELETE, &mut nid_del); }
                return;
            }

            std::thread::sleep(std::time::Duration::from_millis(50));
        }
    }
}

#[cfg(target_os = "windows")]
fn create_message_window() -> windows::Win32::Foundation::HWND {
    use windows::Win32::UI::WindowsAndMessaging::*;
    use windows::Win32::Foundation::*;

    unsafe {
        let class_name: Vec<u16> = "OpenMateTray\0".encode_utf16().collect();
        let wc = WNDCLASSW {
            lpfnWndProc: Some(def_window_proc),
            lpszClassName: PCWSTR(class_name.as_ptr()),
            ..Default::default()
        };
        RegisterClassW(&wc);
        CreateWindowExW(
            WINDOW_EX_STYLE::default(),
            PCWSTR(class_name.as_ptr()),
            PCWSTR(class_name.as_ptr()),
            WINDOW_STYLE::default(),
            0, 0, 0, 0,
            None, None, None, None,
        ).unwrap_or_default()
    }
}

#[cfg(target_os = "windows")]
fn show_context_menu(
    hwnd: windows::Win32::Foundation::HWND,
    rx: &Receiver<TrayEvent>,
    autostart_enabled: bool,
) {
    use windows::Win32::UI::WindowsAndMessaging::*;
    use windows::Win32::Foundation::*;

    unsafe {
        let hmenu = CreatePopupMenu().unwrap();
        AppendMenuW(hmenu, MF_STRING, 1001, PCWSTR(to_wide("打开管理页面").as_ptr()));
        AppendMenuW(hmenu, MF_SEPARATOR, 0, PCWSTR(std::ptr::null()));
        let check_flag = if autostart_enabled { MF_CHECKED } else { MF_UNCHECKED };
        AppendMenuW(hmenu, MF_STRING | check_flag, 1002, PCWSTR(to_wide("开机自动启动").as_ptr()));
        AppendMenuW(hmenu, MF_SEPARATOR, 0, PCWSTR(std::ptr::null()));
        AppendMenuW(hmenu, MF_STRING, 1003, PCWSTR(to_wide("退出").as_ptr()));

        let mut pt = POINT::default();
        GetCursorPos(&mut pt);
        SetForegroundWindow(hwnd);
        let cmd = TrackPopupMenu(
            hmenu,
            TPM_RIGHTBUTTON | TPM_NONOTIFY,
            pt.x, pt.y, 0,
            hwnd, None,
        );
        match cmd.0 {
            1001 => { let _ = open::that("http://localhost:4097/ui/"); }
            1002 => { /* toggle autostart - 通过 channel 通知 */ }
            1003 => { /* quit - 通过 channel 通知 */ }
            _ => {}
        }
        DestroyMenu(hmenu);
    }
}

fn to_wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}
```

> 注：以上是框架代码。实际实现中需要将托盘菜单事件通过 channel 发送到 tokio 世界处理（autostart toggle、quit 等）。context menu 的实现中，由于 `TrackPopupMenu` 是阻塞调用，需要特殊处理事件传递。

### Step 3: 修改 `src/main.rs` — 子系统切换 + GUI 入口

```rust
// 在文件最顶部添加（条件编译）
#![cfg_attr(all(windows, not(test)), windows_subsystem = "windows")]

use clap::{Parser, Subcommand};
use openmate::config::Config;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::Notify;

#[derive(Parser, Debug)]
#[command(name = "openmate", about = "OpenMate Bridge")]
struct Args {
    #[arg(short, long)]
    config: Option<PathBuf>,

    #[arg(long)]
    tray: bool,  // 新增: 静默启动，不打开浏览器

    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Subcommand, Debug)]
enum Commands {
    Approve { pin: String },
    ResetToken,
    Install,
    Uninstall,
    Service,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // 初始化日志（带 LogCapture layer）
    let log_buffer = openmate::log_capture::create_shared_buffer();
    let capture_layer = openmate::log_capture::LogCaptureLayer::new(log_buffer.clone());

    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .finish()
        .with(capture_layer)
        .init();

    let args = Args::parse();

    match args.command {
        Some(Commands::Approve { pin }) => return run_approve(&pin).await,
        Some(Commands::ResetToken) => return run_reset_token().await,
        Some(Commands::Install) => return run_install(),
        Some(Commands::Uninstall) => return run_uninstall(),
        Some(Commands::Service) => return run_service_mode(),
        None => {}
    }

    // GUI 模式
    let config = Config::find_and_load(args.config)?;

    // 单实例检测
    if is_already_running(&config) {
        tracing::info!("Another instance detected, sending open-ui signal");
        notify_existing_instance(&config).await;
        return Ok(());
    }

    let shutdown_notify = Arc::new(Notify::new());

    // 启动 axum 服务器（在 tokio task 中）
    let server_notify = shutdown_notify.clone();
    let server_handle = tokio::spawn(async move {
        openmate::server::run_server(config.clone(), Some(server_notify)).await
    });

    // 首次/手动启动时打开浏览器
    if !args.tray {
        tokio::spawn(async {
            tokio::time::sleep(std::time::Duration::from_secs(2)).await;
            let _ = open::that("http://localhost:4097/ui/");
        });
    }

    // Windows: 启动托盘（阻塞主线程）
    #[cfg(target_os = "windows")]
    {
        let (tx, rx) = std::sync::mpsc::channel();
        let notify = shutdown_notify.clone();
        std::thread::spawn(move || {
            openmate::tray::TrayIcon::run(rx, notify, false);
        });
        // 等待退出信号
        shutdown_notify.notified().await;
        let _ = tx.send(openmate::tray::TrayEvent::Quit);
    }

    #[cfg(not(target_os = "windows"))]
    {
        shutdown_notify.notified().await;
    }

    let _ = server_handle.await;
    Ok(())
}

fn is_already_running(config: &Config) -> bool {
    #[cfg(windows)]
    {
        use windows::Win32::System::Threading::*;
        use windows::core::PCWSTR;
        let name: Vec<u16> = "Global\\OpenMateBridge\0".encode_utf16().collect();
        unsafe {
            CreateMutexW(None, FALSE, PCWSTR(name.as_ptr())).is_ok()
                && GetLastError() == ERROR_ALREADY_EXISTS
        }
    }
    #[cfg(not(windows))]
    { false }
}

async fn notify_existing_instance(config: &Config) {
    let url = format!("http://127.0.0.1:{}/api/bridge/open-ui", config.bridge.port);
    let _ = reqwest::Client::new().post(&url).send().await;
}

// ... 现有 CLI 函数保持不变 ...
```

### Step 4: 修改 `src/lib.rs`

```rust
#[cfg(target_os = "windows")]
pub mod tray;  // 新增
```

### Step 5: 创建 `src/tray.rs` 非 Windows 桩实现

对于非 Windows 平台编译，提供空实现：

```rust
#[cfg(not(target_os = "windows"))]
impl TrayIcon {
    pub fn run(_rx: std::sync::mpsc::Receiver<TrayEvent>, _notify: Arc<Notify>, _autostart: bool) {
        // no-op on non-windows
    }
}
```

### Step 6: 端到端验证清单

手动验证（无法自动化测试 Win32 GUI）：

1. **构建**: `cargo build --release`
2. **无参数启动**: 双击 exe → 托盘图标出现 → 浏览器自动打开管理页
3. **--tray 启动**: `openmate.exe --tray` → 托盘图标出现 → 不打开浏览器
4. **单实例**: 再次双击 exe → 浏览器打开 → 无新进程
5. **托盘菜单**: 右键 → 打开管理页 / 开机自启切换 / 退出
6. **管理页面**: 概览数据正确、设备管理可用、QR 码生成、日志实时
7. **扫码落地页**: 手机扫码 → 显示地址和下载链接
8. **开机自启**: 设置页开启 → Startup 文件夹有快捷方式 → 重启验证
9. **CLI 子命令**: `openmate.exe approve 123456` → 终端输出正常
10. **Graceful shutdown**: 托盘退出 → opencode 停止 → 服务关闭

### Step 7: 提交

```
feat(bridge): Windows desktop mode with system tray, single instance, GUI entry point
```
