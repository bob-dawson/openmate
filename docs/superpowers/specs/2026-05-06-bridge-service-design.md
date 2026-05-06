# OpenMate Bridge Service Mode

## Overview

将 Bridge 可执行程序从 `opencode-bridge` 重命名为 `openmate`，并添加 Windows/Linux 系统服务支持。用户可通过 `openmate install` 一键安装为系统服务。

## 重命名

- `Cargo.toml` name: `opencode-bridge` → `openmate`
- CLI `#[command(name)]`: `opencode-bridge` → `openmate`
- package description 更新
- 所有用户可见日志/错误信息中的 `opencode-bridge` → `OpenMate`
- 发布二进制文件名: `openmate.exe` (Windows) / `openmate` (Linux)

## CLI 命令结构

```
openmate                        # 前台运行（现有行为）
openmate approve <pin>          # 批准配对 PIN
openmate reset-token            # 重置密钥

openmate install                # 安装为系统服务
openmate uninstall              # 卸载系统服务
openmate service                # 服务模式运行（由系统调用，用户不直接用）
```

## Windows 服务

使用 `windows-service` crate（纯 Rust 实现 Win32 Service API）。

### install
1. 获取当前 exe 绝对路径
2. 调用 Win32 Service API 创建服务：
   - 服务名: `OpenMate`
   - 显示名: `OpenMate Bridge`
   - 二进制路径: `"<exe_path>" service`
   - 启动类型: 自动
   - 工作目录: exe 所在目录
3. 启动服务

### uninstall
1. 如果服务正在运行，发送停止请求
2. 删除服务注册

### service 子命令
1. 调用 `windows_service::service_dispatcher::start("OpenMate", ffi_service_main)`
2. 在 `ffi_service_main` 中处理 SCM 事件（Start/Stop/Shutdown）
3. Start 事件 → 启动 axum server（复用 `run_server`）
4. Stop/Shutdown 事件 → graceful shutdown（通过 `tokio::sync::Notify` 或 `CancellationToken`）

## Linux 服务

使用 systemd unit 文件。

### install
1. 获取当前 exe 绝对路径
2. 生成 `/etc/systemd/system/openmate.service`：
   ```ini
   [Unit]
   Description=OpenMate Bridge
   After=network.target

   [Service]
   Type=simple
   ExecStart=<exe_path> service
   WorkingDirectory=<exe_dir>
   Restart=on-failure
   RestartSec=5

   [Install]
   WantedBy=multi-user.target
   ```
3. 执行 `systemctl daemon-reload && systemctl enable openmate && systemctl start openmate`

### uninstall
1. 执行 `systemctl stop openmate && systemctl disable openmate`
2. 删除 `/etc/systemd/system/openmate.service`
3. 执行 `systemctl daemon-reload`

### service 子命令
Linux 上 `service` 子命令等同于前台模式，直接调用 `run_server()`。systemd 负责进程守护和自动重启。

## 条件编译

```
Cargo.toml:
[target.'cfg(windows)'.dependencies]
windows-service = "0.8"

src/main.rs:
#[cfg(target_os = "windows")]
mod service_windows;

#[cfg(target_os = "linux")]
mod service_linux;
```

Windows 模块实现 `install`/`uninstall`/`run_service`。
Linux 模块实现 `install`/`uninstall`（生成 unit 文件 + 调用 systemctl）。

`service` 子命令的入口在 `main.rs`：
- Windows → `service_windows::run_service()`
- Linux → `run_server()`（同前台模式）

## 代码结构变更

```
src/
├── main.rs              # CLI 入口 + Commands 枚举新增 Install/Uninstall/Service
├── service_windows.rs   # Windows 服务实现（条件编译）
├── service_linux.rs     # Linux 服务实现（条件编译）
├── server.rs            # 新文件：从 main.rs 提取 axum server 启动逻辑
└── ...                  # 其余不变
```

将 `main.rs` 中的 server 启动逻辑提取到 `server.rs` 的 `pub async fn run_server(config: Config) -> anyhow::Result<()>`，供前台模式和服务模式共用。

## 错误处理

- `install` 时如果没有管理员/root 权限，打印明确错误提示
- `install` 时如果服务已存在，打印提示而非报错
- `uninstall` 时如果服务不存在，打印提示
- `service` 子命令在 Windows 上被用户直接调用时，提示用户应通过 SCM 管理

## 不做的事

- 不支持 macOS launchd（当前不需要）
- 不支持 openrc/sysvinit（仅 systemd）
- install 时不自动创建 bridge.toml（配置文件跟 exe 同目录，用户自行管理）
- 不添加日志文件配置（服务模式下 stdout/stderr 由系统捕获：Windows → Event Log / Linux → journald）
