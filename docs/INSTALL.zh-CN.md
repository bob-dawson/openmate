# OpenMate 安装指南

## 快速安装

### Bridge —— 命令行一条命令

**Linux / macOS**

```bash
curl -fsSL https://gateway.clawmate.net/install.sh | sh
```

**Windows（PowerShell）**

```powershell
irm https://gateway.clawmate.net/install.ps1 | iex
```

安装脚本会自动识别系统与 CPU 架构、从 `version.json` 读取当前版本、优先使用你所在区域对应的
下载镜像（GitHub / AtomGit），下载失败会自动顺延到另一个镜像。脚本源码就在本仓库，想先看再用：

```bash
curl -fsSL https://raw.githubusercontent.com/bob-dawson/openmate/main/scripts/install.sh | sh
```

常用参数：

```bash
# 开机自启（Linux 用 systemd，macOS 用 launchd）
curl -fsSL https://gateway.clawmate.net/install.sh | sh -s -- --service

# 指定版本、安装目录，或强制镜像顺序
curl -fsSL https://gateway.clawmate.net/install.sh | sh -s -- --version v0.3.9 --dir ~/bin --region cn
```

> `scripts/install.sh` 与 `scripts/install.ps1` 是源码；上面的 gateway 地址只是为了方便写成一条短命令。

### Android 客户端

手机扫码打开下载页，选最新 APK 安装：

| 国内（AtomGit） | 海外（GitHub） |
|:---:|:---:|
| <img src="assets/qr-app-atomgit.png" width="180" alt="AtomGit 下载页"> | <img src="assets/qr-app-github.png" width="180" alt="GitHub 下载页"> |

直接下载：[AtomGit](https://atomgit.com/article88/openmate/releases) ·
[GitHub](https://github.com/bob-dawson/openmate/releases/latest)

## 手动安装

### 1. 运行 Bridge

Bridge 是连接 Android 客户端与 opencode 的代理服务。

#### Windows

1. 双击 `openmate.exe` 运行
2. Bridge 启动后在终端或管理页面显示 **QR 码**

#### Linux / macOS

```bash
chmod +x openmate          # macOS 产物名为 openmate-darwin-arm64
./openmate
```

Bridge 启动后在终端显示 **QR 码**。

#### 安装为系统服务（可选）

```bash
./openmate install     # Windows 服务 / Linux systemd
./openmate uninstall   # 卸载
```

Windows 下一般不推荐作为服务运行，因为需要用户单独设置服务的账号密码。普通应用运行即可，
开机自启可在管理页面设置。macOS 没有 `install` 子命令，用安装脚本的 `--service` 生成 launchd 代理即可。

### 2. 安装 Android APK

从 [Releases](https://github.com/bob-dawson/openmate/releases/latest)（或
[AtomGit 镜像](https://atomgit.com/article88/openmate/releases)）下载 `OpenMate-*.apk` 传到手机安装，
也可以直接扫上面的二维码。

### 3. 扫码配对

首次打开 App 会进入扫码配对页面：

1. **获取二维码** — Bridge 在管理页面（`http://127.0.0.1:4097/ui/`，Windows/macOS 下自动打开）显示 QR 码。Linux 下终端也会直接输出 QR 码，方便 SSH 场景下不方便打开浏览器时使用
2. 在 App 中扫描 QR 码，自动完成配对并连接
3. 如果不在同一局域网，App 会自动通过网关中继连接

#### 手动配对（备选）

如果无法扫码，可使用 PIN 码配对：

1. App 显示 **6 位 PIN 码**
2. 在 Bridge 运行的电脑上执行：
   ```
   openmate approve <PIN>
   ```
3. App 自动完成配对并连接

#### 手动添加实例

在 App 中添加实例，地址填 Bridge 所在电脑的 IP，端口默认 `4097`。

## 常见问题

**Bridge 找不到 opencode** —— Bridge 会在 `PATH` 以及常见安装目录（`~/.opencode/bin`、
`/opt/homebrew/bin`、`/usr/local/bin`、`~/.local/bin`）中查找 `opencode`。以服务方式启动时
（`nohup`、launchd、systemd）不会读取 `~/.zshrc`，这一点尤其重要。若 opencode 装在别处，可在管理页面
把 `opencode.binary` 改成绝对路径。

**会话里看不到消息** —— Bridge 直接读取 opencode 的数据库，默认使用
`~/.local/share/opencode/opencode.db`；当配置的路径为空或不可用时会自动改用同目录下真正在用的库。
仍然异常时，检查管理页面的 `opencode.db_path` 与 `~/.openmate/bridge.log` 中的告警行。

**端口被占用** —— Bridge 会回退到随机端口并打印告警（`Port <配置值> is already in use`），
实际端口以管理页面显示为准。
