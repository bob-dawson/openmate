# OpenMate 安装指南

## 1. 运行 Bridge

Bridge 是连接 Android 客户端与 opencode 的代理服务。

### Windows

1. 双击 `openmate.exe` 运行
2. Bridge 启动后在终端或管理页面显示 **QR 码**

### Linux

```bash
chmod +x openmate
./openmate
```

Bridge 启动后在终端显示 **QR 码**。

### 安装为系统服务（可选）

```bash
./openmate install     # Windows 服务 / Linux systemd
./openmate uninstall   # 卸载
```

Windows 下一般不推荐作为服务运行，因为需要用户单独设置服务的账号密码。普通应用运行即可，开机自启可在管理页面设置。

## 2. 安装 Android APK

将 `OpenMate-*.apk` 传输到手机，打开安装。

## 3. 扫码配对

首次打开 App 会进入扫码配对页面：

1. Bridge 运行后会显示 **QR 码**（终端显示或管理页面查看）
2. 在 App 中扫描 QR 码，自动完成配对并连接
3. 如果不在同一局域网，App 会自动通过网关中继连接

### 手动配对（备选）

如果无法扫码，可使用 PIN 码配对：

1. App 显示 **6 位 PIN 码**
2. 在 Bridge 运行的电脑上执行：
   ```
   openmate approve <PIN>
   ```
3. App 自动完成配对并连接

### 手动添加实例

在 App 中添加实例，地址填 Bridge 所在电脑的 IP，端口默认 `4097`。