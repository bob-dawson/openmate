# Diff Viewer 设计文档

## 目标

展开 edit/apply_patch 工具卡片后，列出变更文件列表，点击文件名进入全屏横屏 diff 查看界面，显示完整的文件编辑差异。

## 背景

Bridge 同步数据截取了 edit 的 oldString/newString 和 apply_patch 的 patchText/patch 字段，导致 Android 端无法展示有效差异。Bridge 提供 full content API 返回未截取的原始数据。

## 数据流

```
用户点击文件名
  → fetchFullMessage(sessionId, messageId)  // 按需加载，不存 DB
  → 解析 full content 中该文件的 diff 数据
  → UnifiedDiffBuilder 转为 unified diff 格式
  → DiffViewerActivity 全屏横屏渲染
```

## 模块设计

### 1. UnifiedDiffBuilder

位置：`core/domain/src/main/java/com/openmate/core/domain/model/UnifiedDiff.kt`

将两种来源统一转为 unified diff 行列表：

- **edit**：从 oldString/newString/filePath 生成 unified diff（Myers diff 算法或简单行级对比）
- **apply_patch**：从 patchText 解析 apply_patch 格式，提取每个文件的 hunks，转为 unified diff

输出数据模型：

```kotlin
data class DiffFile(
    val filePath: String,
    val hunks: List<DiffHunk>,
)

data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
)

data class DiffLine(
    val type: DiffLineType,  // CONTEXT, ADD, REMOVE
    val content: String,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
)
```

### 2. DiffViewerActivity

位置：`app/src/main/java/com/openmate/app/diff/DiffViewerActivity.kt`

- 全屏横屏 Compose Activity
- 接收参数：sessionId, messageId, filePath（要查看的文件路径，apply_patch 多文件时定位到指定文件）
- 启动时按需调 full API 加载完整消息
- 加载中显示 loading，失败显示错误信息

### 3. DiffViewerScreen

位置：`feature/session/src/main/java/com/openmate/feature/session/diff/DiffViewerScreen.kt`

Compose 渲染——左右分屏对比（side-by-side）：

- 顶部：文件路径 + 关闭按钮
- 左栏：old 内容
  - 红色高亮删除行
  - 默认背景上下文行
  - 左侧行号（old）
- 右栏：new 内容
  - 绿色高亮新增行
  - 默认背景上下文行
  - 左侧行号（new）
- 双栏滚动同步
- 横屏全屏

### 4. BlockToolLine 文件列表交互修改

位置：`feature/session/src/main/java/com/openmate/feature/session/component/SessionMessagePartRenderer.kt`

当前文件列表点击 `onViewFile(filePath)` 只能查看文件内容。修改为：

- edit：文件路径点击 → 打开 DiffViewerActivity
- apply_patch：文件列表点击 → 打开 DiffViewerActivity（传入对应 filePath）

需要传递额外上下文（sessionId, messageId, toolName）到文件点击回调。

## Full Content API

已有端点：`GET /api/bridge/sync/session/{sessionID}/message/{messageID}/full`

已有 Android 端方法：`SessionMessageRepositoryImpl.fetchFullMessage()`

修改：fetchFullMessage 改为返回解析后的数据而非存 DB（或新增一个不存 DB 的方法）。

## Diff 算法

edit 的 oldString/newString 对比使用 **Myers diff 算法**，生成最小编辑脚本，正确处理行删除、新增和上下文。自实现，不引入外部库。

apply_patch 的 patchText 已有 hunks 信息，直接解析即可，不需要 diff 算法。

## Diff 渲染模式

**左右分屏对比**（side-by-side）：

- 左栏：old 内容（红色高亮删除行）
- 右栏：new 内容（绿色高亮新增行）
- 上下文行两侧同步显示
- 双栏滚动同步（LazyColumn 共享 scroll state）
- 行号：左栏显示 old 行号，右栏显示 new 行号
- 横屏全屏

## 不做的事情

- 不存 DB：full content 只在内存中用于渲染，不持久化
- 不做行内 diff（word-level）：后续优化
- 不做编辑功能：只读查看
