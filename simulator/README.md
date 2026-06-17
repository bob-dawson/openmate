# Android 模拟器操作指南

本机测试用的 Android 模拟器环境，配合 `docker/` 中的 Bridge 容器进行 OpenMate 端到端测试。

## 环境

| 项 | 值 |
|----|----|
| SDK 路径 | `$env:LOCALAPPDATA\Android\Sdk` |
| emulator | `$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe` |
| avdmanager | `$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat` |
| adb | `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`（通常已在 PATH） |
| AVD 名称 | `openmate-test` |
| 系统镜像 | `system-images;android-36.1;google_apis_playstore;x86_64` |
| 设备配置 | `pixel_7`（LCD 已改为 720x1280 @ 320dpi） |
| 宿主机屏幕 | 1463x914 |

为方便调用，PowerShell 会话中可先解析路径：

```powershell
$sdk   = "$env:LOCALAPPDATA\Android\Sdk"
$emu   = "$sdk\emulator\emulator.exe"
$avdm  = "$sdk\cmdline-tools\latest\bin\avdmanager.bat"
```

## 1. 创建 AVD（已完成，仅参考）

```powershell
$sdk  = "$env:LOCALAPPDATA\Android\Sdk"
$avdm = "$sdk\cmdline-tools\latest\bin\avdmanager.bat"

# 列出已安装系统镜像
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --list_installed | Select-String "system-images"

# 列出可用 target
& $avdm list target

# 创建 AVD（--force 覆盖同名）
& $avdm create avd -n "openmate-test" `
    -k "system-images;android-36.1;google_apis_playstore;x86_64" `
    -d "pixel_7" --force

# 列出已有 AVD
& $avdm list avd
```

## 2. 调整分辨率（已完成，仅参考）

默认 `pixel_7` 是 1080x2400 @ 420dpi，窗口在 1463x914 屏幕上显示不全。
已通过修改 AVD 配置缩小：

```powershell
$cfg = "$env:USERPROFILE\.android\avd\openmate-test.avd\config.ini"
(Get-Content $cfg) `
    -replace '^hw\.lcd\.width=1080$',  'hw.lcd.width=720'  `
    -replace '^hw\.lcd\.height=2400$', 'hw.lcd.height=1280' `
    -replace '^hw\.lcd\.density=420$', 'hw.lcd.density=320' |
    Set-Content $cfg

# 验证
Get-Content $cfg | Select-String "hw.lcd"
```

> 注意：`emulator -scale` / `-multidisplay` 在本环境中实测无效或会导致启动失败，
> 直接改 `config.ini` 的 `hw.lcd.*` 最可靠。

## 3. 启动模拟器

### 3.1 前台可见启动（默认）

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
& "$sdk\emulator\emulator.exe" -avd openmate-test `
    -no-snapshot-load -noaudio -gpu swiftshader_indirect
```

### 3.2 后台启动（隐藏控制台窗口，GUI 仍可见）

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
Start-Process -FilePath "$sdk\emulator\emulator.exe" `
    -ArgumentList "-avd","openmate-test","-no-snapshot-load","-noaudio","-gpu","swiftshader_indirect" `
    -WindowStyle Hidden
```

> `-WindowStyle Hidden` 只隐藏 PowerShell 拉起子进程时的控制台窗口，
> 模拟器的 Qt GUI 窗口仍然显示。若 GUI 也未出现，检查窗口是否落在屏幕外（见 §6）。

### 3.3 启动参数说明

| 参数 | 作用 |
|------|------|
| `-avd <name>` | 指定 AVD |
| `-no-snapshot-load` | 冷启动（每次全新状态，便于测试） |
| `-no-snapshot-save` | 退出时不保存快照 |
| `-noaudio` | 禁用音频（减少资源占用） |
| `-gpu swiftshader_indirect` | 软件渲染（无需宿主 GPU 驱动） |
| `-no-window` | 完全无 GUI（headless，配合 scrcpy/adb 使用） |
| `-read-only` | 只读模式，允许并发启动多个实例 |

### 3.4 等待就绪

```powershell
for ($i = 0; $i -lt 40; $i++) {
    $line = (adb devices 2>&1 | Select-String "emulator-5554")
    if ($line -and $line.ToString() -match "device\s*$") {
        Write-Host "Emulator ready"; break
    }
    Start-Sleep -Seconds 3
}
```

## 4. 状态检查

```powershell
# 设备列表（device=可用，offline=启动中/unauthorized）
adb devices

# 详细信息
adb -s emulator-5554 shell getprop ro.build.version.release   # Android 版本
adb -s emulator-5554 shell wm size                             # 分辨率
adb -s emulator-5554 shell wm density                          # DPI

# 列出已安装 AVD
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
```

## 5. 停止模拟器

```powershell
# 优雅停止（推荐）
adb -s emulator-5554 emu kill

# 验证已停止
Start-Sleep -Seconds 3; adb devices

# 兜底：强制结束进程（仅在 emu kill 失败时使用）
Get-Process | Where-Object { $_.ProcessName -like "*qemu*" } | Stop-Process -Force
```

## 6. 窗口位置调整

启动后窗口可能落在屏幕外，用 Win32 API 移动：

```powershell
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WinPos {
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
    [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr hWnd, int X, int Y, int nWidth, int nHeight, bool bRepaint);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    public struct RECT { public int Left, Top, Right, Bottom; }
}
"@
$p = Get-Process | Where-Object { $_.MainWindowTitle -like "*openmate*" } | Select-Object -First 1
$r = New-Object WinPos+RECT
[WinPos]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
$w = $r.Right - $r.Left; $h = $r.Bottom - $r.Top
[WinPos]::MoveWindow($p.MainWindowHandle, 20, 20, $w, $h, $true) | Out-Null
[WinPos]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
```

## 7. 安装 / 启动 OpenMate APK

```powershell
# 下载 APK（从 GitHub Release）
gh release download v0.1.25 --repo bob-dawson/openmate `
    --pattern "OpenMate-*.apk" --dir D:\openmate\temp_db_dir --clobber

# 安装（-r 覆盖已安装版本）
adb -s emulator-5554 install -r "D:\openmate\temp_db_dir\OpenMate-0.1.25.apk"

# 查询主启动 Activity
adb -s emulator-5554 shell cmd package resolve-activity --brief com.openmate

# 启动 app
adb -s emulator-5554 shell am start -n com.openmate/.app.MainActivity

# 确认已安装
adb -s emulator-5554 shell pm list packages | findstr openmate
```

## 8. 网络：模拟器 ↔ 宿主机

模拟器内部 `localhost` 指向模拟器自身，访问宿主机需用特殊 IP：

| 模拟器内访问目标 | 地址 |
|------------------|------|
| 宿主机 | `10.0.2.2` |
| 模拟器自身 | `127.0.0.1` / `localhost` |
| 其他模拟器（同一宿主） | `10.0.2.x`（x=2+序号） |

因此 OpenMate app 连接本机 Docker 中的 Bridge 时，应填：
`http://10.0.2.2:4097`

测试连通性：

```powershell
# 模拟器 ping 宿主机
adb -s emulator-5554 shell ping -c 2 10.0.2.2

# 模拟器访问 Bridge status（需 busybox 或 toybox curl）
adb -s emulator-5554 shell toybox wget -O - http://10.0.2.2:4097/api/bridge/status
```

## 9. 端到端测试拓扑

```
┌──────────────────────────────────────────────────────────┐
│  Windows 宿主机                                          │
│                                                          │
│  ┌────────────────────┐         ┌─────────────────────┐  │
│  │ Android Emulator   │  10.0.2.2  │ Docker Container │  │
│  │  - OpenMate APK    │◄──────────►│  - Bridge :4097   │  │
│  │  - pixel_7 /API36  │            │  - opencode :4096 │  │
│  │  - 720x1280        │            └─────────────────────┘  │
│  └────────────────────┘                  ↑                  │
│                                          │ localhost:4097   │
│                                  测试脚本 / curl            │
└──────────────────────────────────────────────────────────┘
```

## 10. 完整启动流程（一键脚本参考）

```powershell
# 0. 启动 Bridge 容器
docker compose -f D:\openmate\docker\docker-compose.yml up -d

# 1. 启动模拟器（后台）
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
Start-Process "$sdk\emulator\emulator.exe" `
    -ArgumentList "-avd","openmate-test","-no-snapshot-load","-noaudio","-gpu","swiftshader_indirect" `
    -WindowStyle Hidden

# 2. 等待就绪
for ($i = 0; $i -lt 40; $i++) {
    if ((adb devices 2>&1 | Select-String "emulator-5554") -match "device\s*$") { break }
    Start-Sleep -Seconds 3
}

# 3. 启动 OpenMate
adb -s emulator-5554 shell am start -n com.openmate/.app.MainActivity

# 4. 整理窗口位置（见 §6）
```

## 11. 完整关闭流程

```powershell
# 关闭模拟器
adb -s emulator-5554 emu kill

# 关闭 Bridge 容器
docker compose -f D:\openmate\docker\docker-compose.yml down
```

## 12. 自动配对脚本 `pair.py`

基于 uiautomator2 自动完成 OpenMate app 与 Bridge 的配对流程。

### 前置条件

1. Docker Bridge 容器运行中：`docker compose -f D:\openmate\docker\docker-compose.yml up -d`
2. Android 模拟器运行中且 OpenMate app 已安装
3. Python 依赖已安装：`pip install uiautomator2`

### 用法

```powershell
# 使用默认参数配对（名称 Test-Bridge，地址 10.0.2.2:4097）
python D:\openmate\simulator\pair.py

# 自定义参数
python D:\openmate\simulator\pair.py --name MyBridge --host 10.0.2.2 --port 4097

# 指定 Docker 容器名（默认 openmate-bridge）
python D:\openmate\simulator\pair.py --container my-bridge

# 指定模拟器序列号（默认 emulator-5554）
python D:\openmate\simulator\pair.py --serial emulator-5556
```

### 配对流程

脚本自动执行以下步骤：

1. **导航** — 打开 Add Instance 表单
2. **填表** — 设置 Name / Address / Port（使用 `set_text`，兼容 API 36）
3. **测试连接** — 点击 Test Connection，验证 Bridge 可达
4. **保存** — 点击 Save，触发配对请求，获取 PIN
5. **Approve** — 通过 `docker exec` 在容器内执行 `openmate approve <PIN>`
6. **Confirm** — 点击 Confirm 完成配对

### 注意事项

- **输入法兼容性**：API 36 上 uiautomator2 的 `send_keys()` / `clear_text()` 会因 `InputManager.getInstance()` 签名变更而崩溃，脚本改用 `set_text()`（Accessibility ACTION_SET_TEXT）规避
- **关闭键盘**：填完表单后通过点击输入法的 "Done" 按钮关闭键盘，不能用 BACK 键（会退出表单页）
- **截图**：每一步截图保存在 `simulator/screens/` 目录（已加入 .gitignore）

## 已知限制 / 注意事项

- **分辨率调整**：`emulator -scale` / `-multidisplay` 在本机无效或致启动失败，只能改 `config.ini`
- **Play Store 镜像**：无 root，无法直接 `adb root`；调试需用 `adb shell` 普通权限
- **冷启动**：`-no-snapshot-load` 保证每次全新状态，适合测试但启动较慢（~30s）
- **GPU**：`swiftshader_indirect` 软件渲染，性能足够测试，不依赖宿主 GPU 驱动
- **并发**：同一 AVD 默认不能多开，需要多实例时复制 AVD 或加 `-read-only`
- **uiautomator2 + API 36**：`send_keys()`/`clear_text()` 不兼容，必须用 `set_text()`
