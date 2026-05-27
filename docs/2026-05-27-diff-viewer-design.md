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

Compose 渲染：

- 顶部：文件路径 + 关闭按钮
- 内联 diff 模式（单栏，红绿高亮，类似 git diff 输出）
  - 红色背景：删除行（-）
  - 绿色背景：新增行（+）
  - 默认背景：上下文行
  - 左侧行号（old/new 双列）
- 支持滚动
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

## Diff 算法选择

edit 的 oldString/newString 对比需要 diff 算法。两个选项：

- **简单行级对比**：直接按行分割，标记新增/删除/上下文。无法处理行内修改，但实现简单
- **Myers diff**：标准 diff 算法，生成最小编辑脚本。需要自己实现或引入库

推荐：先实现简单行级对比（old 中出现的行标记 REMOVE，new 中标记 ADD，共同行标记 CONTEXT）。后续可升级为 Myers diff。

apply_patch 的 patchText 已有 hunks 信息，直接解析即可，不需要 diff 算法。

## apply_patch 格式解析

```
*** Begin Patch
*** Update File: path/to/file
@@ -old_start[,old_count] +new_start[,new_count] @@
 context line
-removed line
+added line
*** End Patch
```

解析逻辑：
1. 按 `*** Update File:` / `*** Add File:` / `*** Delete File:` 分割文件块
2. 每个 hunk 以 `@@` 开始
3. 每行前缀：空格=context, -=remove, +=add

## 不做的事情

- 不存 DB：full content 只在内存中用于渲染，不持久化
- 不做左右分屏：内联 diff 模式足够，实现更简单
- 不做行内 diff（word-level）：后续优化
- 不做编辑功能：只读查看
