# Git Changes Feature Design

## 概述

在会话详情底部工具栏，文件浏览器按钮旁新增"Git 变更"按钮。点击后导航到新页面，显示当前工作目录下 git 未提交变更的文件列表。点击文件可复用现有 DiffViewer 查看变更详情。

## 设计决策：为什么不在 Android 端直接调用 opencode VCS API

opencode 已有 `/vcs/status`、`/vcs/diff`、`/vcs/diff/raw` 等 VCS API，但选择 Bridge 独立实现 git API，理由：

1. **移动端适配**：opencode 的 `/vcs/diff` 一次返回所有文件的 patch，体量大，不适合移动端网络环境。Bridge 独立 API 支持按单文件获取 diff，按需加载。
2. **Agent 解耦**：Bridge 作为通用代理层，不应依赖 opencode 的特定 API。将来对接其他 agent 工具时，git 功能仍然可用。
3. **接口可控**：独立实现可以精确控制返回格式和错误处理，不受 opencode 版本迭代影响。

参考 opencode 的 `src/project/vcs.ts` 和 `src/git/` 实现逻辑，但 API 设计独立。

## Relay Gateway

**不需要变更。** 网关对 `/api/bridge/git/*` 路径的请求走普通 HTTP 隧道转发（else 分支），无需特殊处理（不像 upload/download 需流式传输）。

## Bridge API

新增 `src/git/` 模块，两个端点：

### `GET /api/bridge/git/status?path=<dir>`

在指定目录下执行 git status，返回该目录范围内的变更文件列表。

**实现**：在 `<dir>` 下执行 `git status --porcelain=v2 --relative`，解析 v2 格式输出。

**返回**：
```json
[
  {"path": "src/main.rs", "status": "modified"},
  {"path": "src/new.rs", "status": "added"},
  {"path": "src/old.rs", "status": "deleted", "oldPath": "src/old.rs"},
  {"path": "README.md", "status": "untracked"}
]
```

**错误**：目录不在 git 仓库内时返回 `404 {error: "not a git repository"}`。

### `GET /api/bridge/git/diff?file=<filepath>`

返回指定文件的 unified diff 文本。

**实现**：
1. 从文件路径定位 git 仓库根：在文件所在目录向上执行 `git rev-parse --show-toplevel`
2. 在仓库根目录执行 `git diff HEAD -- <file>`（含 staged + unstaged）
3. 新增文件：`git diff /dev/null -- <file>`
4. 删除文件：`git diff HEAD -- <oldPath>`（仅显示删除内容）

**返回**：`text/plain; charset=utf-8`，内容为 unified diff 格式文本。

**错误**：文件不在 git 仓库内时返回 `404 {error: "not a git repository"}`。

## Android 端

### API 层

`OpencodeApiClient` 新增方法：
- `bridgeGitStatus(path: String): List<BridgeGitStatusEntry>`
- `bridgeGitDiff(file: String): String`

新增 DTO：
```kotlin
@Serializable
data class BridgeGitStatusEntry(
    val path: String,
    val status: String,       // modified / added / deleted / untracked
    val oldPath: String? = null,
)
```

### 导航

- `SessionRoutes` 新增 `GIT_CHANGES = "git_changes"`
- 路由：`git_changes/{directory}`（URL-encoded）
- `SessionNavigation.kt` 新增 composable 导航到 `GitChangesScreen`
- `SessionDetailScreen` 新增回调 `onNavigateToGitChanges: (directory: String) -> Unit`

### 底部工具栏

现有布局：
```
[← → ⏭] | [Undo] [📂 FolderOpen] ····spacer···· [token display]
```

新增后：
```
[← → ⏭] | [Undo] [📂 FolderOpen] [Compare GitChanges] ····spacer···· [token display]
```

- 图标：`Icons.Default.Compare`（语义贴近"查看变更比较"）
- 点击：`onNavigateToGitChanges(viewModel.getWorkingDirectory())`
- 大小与 FolderOpen 按钮一致（28dp icon button，24dp icon）

### GitChangesScreen

新路由页面，结构：
- **TopBar**：标题 "Git Changes"，返回按钮
- **内容区**：LazyColumn 显示变更文件列表
  - 每行：状态色标 + 文件路径
  - 颜色：modified → 橙色，added → 绿色，deleted → 红色，untracked → 蓝色
  - 点击文件 → 启动 DiffViewerActivity（`toolName="git"`, `filePath=文件全路径`, `directory=工作目录`）
- **空状态**：
  - 非 git 仓库："此目录不在 Git 仓库中"
  - 无变更文件："没有未提交的变更"
- **加载状态**：CircularProgressIndicator

### GitChangesViewModel

- `loadStatus(directory: String)` — 调用 `apiClient.bridgeGitStatus(directory)`
- 状态：`StateFlow<GitChangesState>`
  ```kotlin
  data class GitChangesState(
      val loading: Boolean = true,
      val files: List<BridgeGitStatusEntry>? = null,
      val error: String? = null,
      val isNotGitRepo: Boolean = false,
  )
  ```

### DiffViewer 复用

现有 `DiffViewerViewModel.loadDiff()` 通过 `sessionMessageRepository.fetchDiffFiles()` 获取 diff。需扩展支持 git diff 场景：

- 当 `toolName == "git"` 时，改用 `apiClient.bridgeGitDiff(filePath)` 获取 raw diff 文本
- 将 raw diff 文本解析为 `List<DiffFile>`（复用现有 unified diff 解析逻辑）
- 其余渲染流程不变

## 涉及文件

### Bridge（D:\openmate\opencode-bridge）
- 新建 `src/git/mod.rs`
- 新建 `src/git/router.rs` — API handlers
- 新建 `src/git/operations.rs` — git 命令执行与输出解析
- 修改 `src/server.rs` — 注册 git 路由
- 修改 `src/lib.rs` — 导出 git 模块

### Android（D:\openmate\android）
- 修改 `core/network/.../OpencodeApiClient.kt` — 新增 bridgeGitStatus / bridgeGitDiff
- 修改 `core/network/.../Dto.kt`（或同类文件）— 新增 BridgeGitStatusEntry DTO
- 新建 `feature/session/.../component/GitChangesScreen.kt` — 变更文件列表 UI
- 新建 `feature/session/.../GitChangesViewModel.kt` — 状态管理
- 修改 `feature/session/.../SessionDetailScreen.kt` — 新增按钮 + 回调
- 修改 `feature/session/.../SessionNavigation.kt` — 新增路由
- 修改 `app/.../diff/DiffViewerViewModel.kt` — 支持 git diff 场景
