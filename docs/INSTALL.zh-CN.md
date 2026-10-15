# OpenMate 安装指南

## 快速安装

### Bridge —— 把下面这段指令粘贴到 opencode

OpenMate 是 opencode 的配套产品，所以最省事的安装方式就是让 opencode 自己装。复制下面这段发给你正在用的 opencode 即可：

```text
请在这台电脑上安装并启动 OpenMate Bridge —— 它是 OpenMate 手机客户端的配套服务。

1. 先判断当前操作系统与 CPU 架构（Windows / Linux / macOS；x86_64 / arm64）。
2. 获取最新版本号：GET https://gateway.clawmate.net/version.json（失败则用
   https://raw.githubusercontent.com/bob-dawson/openmate/main/version.json），读取 bridge.tag。
3. 选择下载源：中国大陆优先 https://atomgit.com/article88/openmate/releases/download，
   其他地区优先 https://github.com/bob-dawson/openmate/releases/download；
   若某个源下载失败或超时，换另一个重试。
4. 从 {源}/{tag}/{文件名} 下载对应产物：
   - Windows x64 → openmate.exe
   - Linux x86_64 → openmate-linux-x86_64
   - Linux arm64 → openmate-linux-arm64
   - macOS Apple Silicon → openmate-darwin-arm64
5. 安装到合适位置并赋可执行权限：Windows 保存到 %LOCALAPPDATA%\OpenMate\openmate.exe；
   macOS/Linux 装到 ~/.local/bin/openmate 并 chmod +x（若该目录不在 PATH，请告诉我）。
6. 在后台启动它（macOS/Linux 用 nohup，日志写到 ~/.openmate/bridge.log）。
7. 用 GET http://127.0.0.1:4097/api/bridge/status 确认已运行，然后告诉我管理页面地址与配对方式。
8. 只有我明确要求开机自启时才配置：Linux 执行 sudo <路径>/openmate install；Windows 用管理员权限
   运行 openmate.exe install；macOS 写一个 launchd 代理。

不要修改我现有的全局 npm 配置。如果这台机器没装 opencode v2，请先告诉我。
```

就这样——opencode 会自己识别系统与架构、选择你所在区域更快的镜像并把 Bridge 跑起来。想自己动手见下面的[手动安装](#手动安装)。

### Android 客户端

手机扫码即可，页面会自动选择更快的镜像（GitHub / AtomGit）：

<p align="center">
  <img src="assets/qr-app.png" width="200" alt="下载 OpenMate 客户端">
</p>

也可以直接打开 <https://gateway.clawmate.net/app>，或从
[Releases](https://github.com/bob-dawson/openmate/releases/latest) /
[AtomGit 镜像](https://atomgit.com/article88/openmate/releases) 下载 `OpenMate-*.apk`。

## 手动安装

### 1. 运行 Bridge

Bridge 是连接 Android 客户端与 opencode 的代理服务。

#### Windows

1. 从 [Releases](https://github.com/bob-dawson/openmate/releases/latest) 下载 `openmate.exe` 双击运行
2. Bridge 启动后在终端或管理页面显示 **QR 码**

#### Linux / macOS

```bash
# Linux 用 openmate-linux-x86_64 或 openmate-linux-arm64
# macOS 用 openmate-darwin-arm64
chmod +x openmate
./openmate
```

Bridge 启动后在终端显示 **QR 码**。

#### 可选：仓库里的安装脚本

`scripts/install.sh`（Linux/macOS）与 `scripts/install.ps1`（Windows）会把上述步骤一次性做完，适合无人值守场景。同样会读 `version.json`、按区域选镜像并在失败时顺延：

```bash
sh scripts/install.sh --service        # 也支持 --version v0.3.9 --dir ~/bin --region cn
```

#### 安装为系统服务（可选）

```bash
./openmate install     # Windows 服务 / Linux systemd
./openmate uninstall   # 卸载
```

Windows 下一般不推荐作为服务运行，因为需要用户单独设置服务的账号密码。普通应用运行即可，
开机自启可在管理页面设置。macOS 没有 `install` 子命令，用 launchd 代理（`scripts/install.sh --service` 会生成）。

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
