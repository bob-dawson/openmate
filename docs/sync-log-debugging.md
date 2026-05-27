# 同步日志调试法 — 在 Android 设备上调试 app 功能

## 为什么不用 logcat

Android logcat 在真机调试中存在几个严重问题：

1. **不稳定**：USB 连接断开、adb server 重启都会丢失输出；后台切换时 logcat 经常不输出
2. **不方便**：需要 USB 连接或 adb over TCP；每次都要 `adb logcat | grep` 筛选，混合了系统和所有 app 的日志
3. **不可复制**：用户无法自行复制日志贴给开发者；只能开发者自己操作 adb
4. **不可持久**：logcat buffer 有限，老日志很快被冲掉；设备重启后全部丢失

## 同步日志调试法的核心思路

在 app 内部维护一个 **内存环形日志缓冲区**（`SyncLogStore`），通过 **app 内置 UI** 查看、搜索、筛选、复制日志。开发者添加临时调试日志 → 用户在设备上操作 → 复制日志文本贴给开发者 → 开发者定位问题 → 移除临时日志后提交。

## 架构

### 核心组件

| 组件 | 文件 | 说明 |
|------|------|------|
| `SyncLogStore` | `core:data/.../SyncLogStore.kt` | `@Singleton` 环形缓冲区，保留最近 500 条，提供 `log()` 方法 |
| `SyncLogEntry` | `core:data/.../SyncLogEntry.kt` | 日志条目数据类，含 `level`/`category`/`sessionId`/`message`/`renderedText` |
| `SyncLogLevel` | 同上 | `Info` / `Warn` / `Error` |
| `SyncLogCategory` | 同上 | `Sse` / `Sync` / `Manual` / `Poll` / `Gateway` / `Connection` |
| `SyncLogScreen` | `feature:session/.../SyncLogScreen.kt` | UI：按 category 筛选、正则搜索、复制（全部/前30/后30）、清空 |

### 入口

会话详情页右上角菜单 → "Sync Logs"（调试分区）

### 日志格式

```
HH:mm:ss.SSS LEVEL [CATEGORY] (sessionId) message
```

示例：
```
19:17:35.544 INFO [Poll] DiffViewer loadDiff dir=D:\openmate targetFilePath=...
19:17:39.044 ERROR [Poll] DiffViewer openFileView failed: NetworkOnMainThreadException message=null
```

## 使用步骤

### 第 1 步：添加临时调试日志

在需要调试的 ViewModel / Repository / 其他 Hilt 注入类中：

```kotlin
@HiltViewModel
class SomeViewModel @Inject constructor(
    private val someRepository: SomeRepository,
    private val logStore: SyncLogStore,  // ← 注入 SyncLogStore
) : ViewModel() {

    fun doSomething(input: String) {
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Poll, "SomeViewModel doSomething input=$input")
        viewModelScope.launch {
            try {
                val result = someRepository.fetch(input)
                logStore.log(SyncLogLevel.Info, SyncLogCategory.Poll, "SomeViewModel doSomething success len=${result.size}")
            } catch (e: Exception) {
                // 记录异常类名 + message，避免 e.message 为 null 时丢失关键信息
                logStore.log(SyncLogLevel.Error, SyncLogCategory.Poll, "SomeViewModel doSomething failed: ${e.javaClass?.simpleName} message=${e.message}")
            }
        }
    }
}
```

**关键要点：**

- **Category 选择**：通用调试用 `Poll`；SSE/同步相关用 `Sse`/`Sync`；连接相关用 `Connection`
- **异常日志**：必须记录 `e.javaClass?.simpleName`，因为很多 Android 异常的 `message` 为 null（如 `NetworkOnMainThreadException`）
- **日志内容**：打印关键参数值（路径、ID、size），便于定位数据流问题
- **Dispatchers**：网络调用必须用 `Dispatchers.IO`，否则 `NetworkOnMainThreadException` 的 `message` 为 null，容易误判

### 第 2 步：构建安装，让用户操作

```powershell
Invoke-RestMethod -Uri "http://localhost:5099/api/gradle/run" -Method Post -ContentType "application/json" -Body '{"args":[":app:assembleDebug"],"cwd":"D:\\openmate"}'
```

安装 APK 后让用户执行触发 bug 的操作。

### 第 3 步：用户复制日志

1. 打开会话详情 → 右上角菜单 → "Sync Logs"
2. 用 category 筛选（如选 `Poll`）或搜索关键词
3. 点击 "Copy" → 选择复制范围（All / First 30 / Last 30）
4. 把复制的内容贴给开发者

### 第 4 步：开发者分析日志，定位问题

日志会显示完整的数据流：输入参数 → 解析后的值 → 成功/失败的异常类型和消息。

**典型场景：**

| 问题 | 日志证据 | 修复方向 |
|------|----------|----------|
| 文件路径错误 | `resolved=D:/openmate/...` 但 API 返回 404 | 检查路径拼接逻辑 |
| 网络在主线程 | `failed: NetworkOnMainThreadException message=null` | 改用 `Dispatchers.IO` |
| baseUrl 未设置 | `failed: IllegalStateException message=null` | 检查 ConnectionManager |
| 数据为空 | `success len=0` | 检查 API 响应解析 |

### 第 5 步：移除临时日志，提交

**必须移除所有 `logStore.log()` 调试日志后再提交。** 删除 `logStore` 注入和相关 import。

## 与 logcat 的对比

| 维度 | logcat | 同步日志 |
|------|--------|----------|
| 连接要求 | USB / adb TCP | 无需连接 |
| 用户可操作 | 否 | 是（UI 内查看/复制） |
| 稳定性 | 经常断连/丢失 | 内存环形缓冲，app 内可靠 |
| 筛选 | `grep` + tag | UI 内 category + 正则 |
| 异常信息 | 完整 stacktrace | 类名 + message（够用） |
| 持久性 | buffer 环形，重启丢失 | app 内环形，重启丢失（但可随时复制） |
| 性能影响 | 低 | 低（内存操作，不写磁盘） |
| 提交风险 | 无（不进代码） | 需确保移除临时日志 |

## 最佳实践

1. **临时日志只在调试阶段存在**：不提交带 `logStore.log()` 的代码，除非是长期需要的运维日志
2. **打印异常类名**：`e.javaClass?.simpleName` 比 `e.message` 更重要，很多 Android 异常 message 为 null
3. **打印关键参数**：路径、ID、size 等可以快速定位数据在哪个环节出错
4. **选合适的 category**：方便筛选，不用在几百条日志里找
5. **分步打印**：进入方法时打印输入，成功时打印结果，失败时打印异常——三步覆盖完整数据流
6. **网络调用用 IO 线程**：`viewModelScope.launch(Dispatchers.IO)` 防止 `NetworkOnMainThreadException`

## 实际案例

### 案例 1：DiffViewer "查看文件" 功能失败

**现象**：点击 Diff 查看器的 "查看文件" 菜单项，报 "Failed to load file"

**调试过程**：

1. 添加日志到 `DiffViewerViewModel.openFileView()`：
   ```kotlin
   logStore.log(SyncLogLevel.Info, SyncLogCategory.Poll, "DiffViewer openFileView filePath=$filePath resolved=$resolved currentDirectory=$currentDirectory")
   // catch 中：
   logStore.log(SyncLogLevel.Error, SyncLogCategory.Poll, "DiffViewer openFileView failed: ${e.javaClass?.simpleName} message=${e.message}")
   ```

2. 用户操作后复制日志：
   ```
   19:17:39.031 INFO [Poll] DiffViewer openFileView filePath=D:\openmate\... resolved=D:/openmate/... currentDirectory=D:\openmate
   19:17:39.044 ERROR [Poll] DiffViewer openFileView failed: NetworkOnMainThreadException message=null
   ```

3. **定位根因**：`NetworkOnMainThreadException` — OkHttp 同步调用在主线程执行

4. **修复**：`viewModelScope.launch(Dispatchers.IO)` 替换 `viewModelScope.launch`

5. 移除日志，提交

### 案例 2：apply_patch 路径匹配失败

**现象**：apply_patch 工具点击文件名查看 diff，显示空数据

**调试过程**：

1. 在 `fetchDiffFiles` 中添加路径匹配日志
2. 发现 `structured.files[].filePath` 是绝对路径 `D:\openmate\...`，但 `targetFilePath` 是相对路径 `opencode-bridge/src/main.rs`
3. 修复为后缀匹配 + 分隔符规范化

详见 `1d2a268` 提交。