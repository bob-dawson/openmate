# OpenMate 安装指南

## 1. 运行 Bridge

Bridge 是连接 Android 客户端与 opencode 的代理服务。

### Windows

1. 将 `openmate-windows-x86_64.exe` 和 `bridge.toml` 放到同一目录
2. 编辑 `bridge.toml`，修改 `binary` 为 opencode 的实际路径：
   ```toml
   [opencode]
   binary = 'opencode'          # 已在 PATH 中则直接用名称
   # binary = 'C:\path\to\opencode.cmd'  # 或完整路径
   ```
3. 双击 exe 或命令行运行：
   ```
   openmate-windows-x86_64.exe
   ```

### Linux

```bash
chmod +x openmate-linux-x86_64
./openmate-linux-x86_64
```

### 安装为系统服务（可选）

```bash
./openmate install     # Windows 服务 / Linux systemd
./openmate uninstall   # 卸载
```

## 2. 安装 Android APK

将 `OpenMate-*.apk` 传输到手机，打开安装。

## 3. 配对

首次打开 App 会要求配对：

1. App 显示 **6 位 PIN 码**
2. 在 Bridge 运行的电脑上执行：
   ```
   openmate approve <PIN>
   ```
3. App 自动完成配对并连接

之后在 App 中添加实例，地址填 Bridge 所在电脑的 IP，端口默认 `4097`。
