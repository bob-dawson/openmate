# 崩溃日志本地采集与展示

## 目标

在 Android 端实现零依赖的崩溃日志采集，捕获未处理异常并存储到本地文件，下次启动后通过实例列表页入口查看。

## 方案

自定义 `Thread.UncaughtExceptionHandler`，零第三方依赖。

## 新增文件

```
core/common/src/main/java/com/openmate/core/common/crash/
  CrashReport.kt        — 数据类
  CrashHandler.kt       — UncaughtExceptionHandler 实现
  CrashLogManager.kt    — 文件管理（读取/删除/列表）
```

```
feature/instance/src/main/java/com/openmate/feature/instance/
  CrashLogScreen.kt     — 崩溃日志列表 + 详情页面
  CrashLogViewModel.kt  — 崩溃日志 ViewModel
```

## 修改文件

| 文件 | 变更 |
|------|------|
| `OpenMateApp.kt` | `onCreate()` 注册 CrashHandler |
| `InstanceNavigation.kt` | 新增 crash_log 路由 |
| `InstanceListScreen.kt` | TopBar overflow menu 增加"崩溃日志"入口 + 未读角标 |

## 数据模型

```kotlin
data class CrashReport(
    val timestamp: Long,
    val threadName: String,
    val exceptionClass: String,
    val message: String?,
    val stackTrace: String,
    val deviceInfo: String,
)
```

## 崩溃报告内容

- 时间戳（epoch ms）
- 线程名
- 异常类名 + 消息
- 完整堆栈（含 cause chain）
- 设备信息（Build.MANUFACTURER + Build.MODEL, Android 版本, App 版本名）

## 存储路径

`context.filesDir/crashes/crash_{timestamp}.log`

文件名即时间戳，方便排序和清理。

## 文件管理

- 每次写入前检查数量，超过 10 条删除最旧的
- 每个文件为纯文本，格式如下：

```
Time: 2026-05-20 14:30:00
Thread: main
Device: Google Pixel 7, Android 15, App 1.0.0

java.lang.NullPointerException: ...
    at com.openmate...
Caused by: ...
    at ...
```

## 展示逻辑

### 入口

实例列表页 TopBar overflow menu 新增"崩溃日志"菜单项。CrashLogManager 提供 `hasUnreadCrashes()` 方法（基于 SharedPreferences 标记已读时间戳），有未读时在菜单文字旁显示红色数字角标。

### 崩溃日志页面（CrashLogScreen）

- 列表视图：每条显示时间、异常类名、消息摘要
- 点击进入详情：完整堆栈 + 设备信息
- 详情页支持：复制文本、分享（Intent ACTION_SEND）、删除
- 列表页支持：清空全部

### 导航路由

`InstanceRoutes.CRASH_LOG = "crash_log"`

## 注册时机

`OpenMateApp.onCreate()` 中调用 `CrashHandler.install(this)`，确保最早注册。

## 不做的事

- 不做崩溃上报到服务器
- 不做 ANR 检测（仅处理 UncaughtException）
- 不引入 ACRA 等第三方库
- 不在启动时弹对话框强制用户查看
