# OpenMate Android UI 元素参考

从安卓源码提取的 UI 控件信息，供 uiautomator2 自动化测试脚本使用。

## 重要发现

代码库 **未使用** `Modifier.testTag()` 和 `Modifier.semantics {}`，因此 uiautomator2 无法通过 `resource-id` 定位 Compose 组件。定位策略：

1. **contentDescription**（最可靠，用于 IconButton/FAB）
2. **按钮文本**（可靠，用于 TextButton/Button）
3. **className**（EditText 定位文本输入框，需用 instance index 区分多个字段）

## 导航路由

| 路由 | 页面 | 参数 |
|------|------|------|
| `instance_list` | InstanceListScreen | 起始页 |
| `add_instance` | AddInstanceScreen | — |
| `edit_instance/{profileId}` | AddInstanceScreen（编辑模式） | profileId |
| `qr_scan` | QrScanScreen | — |
| `crash_log` | CrashLogScreen | — |
| `workspace_list` | WorkspaceListScreen | — |
| `session_list/{directory}` | SessionListScreen | directory（URL 编码） |
| `session_detail/{sessionID}` | SessionDetailScreen | sessionID |
| `subtask_detail/{subtaskSessionID}` | SessionDetailScreen（子任务） | subtaskSessionID |
| `workspace_browser/{directory}` | WorkspaceBrowserScreen | directory（URL 编码） |
| `local_file_manager` | LocalFileManagerScreen | — |
| `git_changes/{directory}` | GitChangesScreen | directory（URL 编码） |

---

## 各页面 UI 元素

### 1. InstanceListScreen

**TopBar**: 标题 "Instances"

| 元素 | 类型 | text / contentDescription | u2 选择器 |
|------|------|--------------------------|-----------|
| 更多菜单 | IconButton | content-desc: "More" | `d(description="More")` |
| Crash Log | DropdownMenuItem | text: "Crash Log" | `d(text="Crash Log")` |
| 扫码 FAB | FloatingActionButton | content-desc: "Scan QR Code" | `d(description="Scan QR Code")` |
| 添加实例 FAB | FloatingActionButton | content-desc: "Add Instance" | `d(description="Add Instance")` |
| 实例卡片 | Clickable Column | text: profile.name | `d(text="<name>")` |
| 卡片更多菜单 | IconButton | content-desc: "More" | `d(description="More")` |
| 编辑 | DropdownMenuItem | text: "Edit" | `d(text="Edit")` |
| 删除 | DropdownMenuItem | text: "Delete" | `d(text="Delete")` |

**状态标签**: "Online", "Online via Gateway", "Connecting...", "Connection failed", "Not Bridge", "Disconnected"

---

### 2. AddInstanceScreen（添加/编辑实例）

**TopBar**: 标题 "Add Instance" 或 "Edit Instance"

| 元素 | 类型 | label / text | u2 选择器 |
|------|------|-------------|-----------|
| 名称字段 | OutlinedTextField | label: "Name" | EditText[0] |
| 地址字段 | OutlinedTextField | label: "Address" | EditText[1] |
| 端口字段 | OutlinedTextField | label: "Port" | EditText[2] |
| 测试连接 | OutlinedButton | text: "Test Connection" | `d(text="Test Connection")` |
| 保存 | Button | text: "Save" | `d(text="Save")` |

**配对 AlertDialog**:

| 元素 | 类型 | text | u2 选择器 |
|------|------|------|-----------|
| 标题 | Text | "Pairing Required" | `d(text="Pairing Required")` |
| 确认 | TextButton | text: "Confirm" | `d(text="Confirm")` |
| 取消 | TextButton | text: "Cancel" | `d(text="Cancel")` |

---

### 3. WorkspaceListScreen（实例详情页）

**TopBar**: 标题 = 实例名 + 连接状态

**底部导航栏**（3 个 tab）:

| Tab | Label | contentDescription | u2 选择器 |
|-----|-------|-------------------|-----------|
| Workspaces | "Workspaces" | "Workspaces" | `d(text="Workspaces")` |
| Sessions | "Sessions" | "Sessions" | `d(text="Sessions")` |
| Settings | "Settings" | "Settings" | `d(text="Settings")` |

| 元素 | 类型 | text / contentDescription | u2 选择器 |
|------|------|--------------------------|-----------|
| 新会话 FAB | FloatingActionButton | content-desc: "New Session" | `d(description="New Session")` |
| 搜索会话 | OutlinedTextField | placeholder: "Search sessions" | EditText |
| 工作区卡片 | Clickable Column | text: dirName | `d(text="<dirName>")` |
| 会话卡片 | Clickable Column | text: session.title 或 "Untitled" | `d(text="<title>")` |

**新会话 AlertDialog**:

| 元素 | 类型 | label / text | u2 选择器 |
|------|------|-------------|-----------|
| 标题 | Text | "New Session" | `d(text="New Session")` |
| 标题字段 | OutlinedTextField | label: "Title (optional)" | EditText |
| 选择目录 | Clickable Row | text: "Select directory" 或路径 | `d(text="Select directory")` |
| 创建 | TextButton | text: "Create" | `d(text="Create")` |
| 取消 | TextButton | text: "Cancel" | `d(text="Cancel")` |

**Settings Tab**:

| 元素 | text | u2 选择器 |
|------|------|-----------|
| 断开连接 | "Disconnect" | `d(text="Disconnect")` |
| 网关中继 | "Gateway relay" | `d(text="Gateway relay")` |
| 缓存删除 | "Delete" | `d(text="Delete")` |
| 管理 | "Manage" | `d(text="Manage")` |
| 刷新统计 | content-desc: "Refresh stats" | `d(description="Refresh stats")` |
| 显示推理 | "Show reasoning" | `d(text="Show reasoning")` |
| 紧凑模式 | "Compact mode" | `d(text="Compact mode")` |
| 检查更新 | "Check for updates" | `d(text="Check for updates")` |
| 升级 | "Upgrade" | `d(text="Upgrade")` |
| 重启 | "Restart" | `d(text="Restart")` |

---

### 4. SessionDetailScreen（聊天页）

**TopBar**: 标题 = session.title 或 "Chat"

| 元素 | contentDescription | u2 选择器 |
|------|-------------------|-----------|
| 搜索 | "Search messages" | `d(description="Search messages")` |
| 更多菜单 | "More" | `d(description="More")` |

**更多菜单项**: "Rename", "Delete", "Skill", "MCP", "Abort", "Initialize Session", "Compact", "Resync", "Sync Logs", "Upload Database"

**底部操作栏**:

| 元素 | contentDescription | u2 选择器 |
|------|-------------------|-----------|
| 上一条用户消息 | "Scroll to previous user message" | `d(description="Scroll to previous user message")` |
| 下一条用户消息 | "Scroll to next user message" | `d(description="Scroll to next user message")` |
| 滚动到底部 | "Scroll to bottom" | `d(description="Scroll to bottom")` |
| 回滚 | "Revert" | `d(description="Revert")` |
| 浏览文件 | "Browse Files" | `d(description="Browse Files")` |
| Git 变更 | "Git Changes" | `d(description="Git Changes")` |

**聊天输入栏**:

| 元素 | 类型 | text / contentDescription | u2 选择器 |
|------|------|--------------------------|-----------|
| 消息输入 | OutlinedTextField | placeholder: "Type a message..." | EditText |
| 发送 | IconButton | content-desc: "Send" | `d(description="Send")` |
| 中止 | IconButton | content-desc: "Abort" | `d(description="Abort")` |

**Agent/Model/Variant Chips**（输入框上方）:

| 元素 | text | u2 选择器 |
|------|------|-----------|
| Agent chip | agent name（大写） | `d(text="<AGENT>")` |
| Model chip | model name | `d(text="<model>")` |
| Variant chip | variant 或 "Default" | `d(text="Default")` |

**回滚 Banner**:

| 元素 | text | u2 选择器 |
|------|------|-----------|
| 已回滚提示 | "已回滚消息" | `d(text="已回滚消息")` |
| 恢复按钮 | "恢复" | `d(text="恢复")` |

**对话框**:

| 对话框 | 标题 text | 确认按钮 | 取消按钮 |
|--------|----------|---------|---------|
| 重命名 | "Rename" | "Confirm" | "Cancel" |
| 删除会话 | "Delete Session" | "Delete" | "Cancel" |
| 重同步 | "Resync" | "Resync" | "Cancel" |
| 回滚 | "回滚" | "回滚" | "Cancel" |

---

### 5. DirectoryPickerSheet（目录选择器）

| 元素 | 类型 | label / text | u2 选择器 |
|------|------|-------------|-----------|
| 标题 | Text | "Select Directory" | `d(text="Select Directory")` |
| 路径输入 | OutlinedTextField | label: "Path (prefix search)" | EditText |
| 上级目录 | TextButton | text: ".." | `d(text="..")` |
| 确认 | TextButton | text: "Confirm" | `d(text="Confirm")` |
| 当前目录 | Clickable Row | text: ". (current)" | `d(text=". (current)")` |

---

### 6. ModelPickerSheet

| 元素 | 类型 | label / text | u2 选择器 |
|------|------|-------------|-----------|
| 标题 | Text | "Select Model" | `d(text="Select Model")` |
| 刷新 | IconButton | content-desc: "Refresh" | `d(description="Refresh")` |
| 搜索 | OutlinedTextField | label: "Search models or providers" | EditText |
| 模型行 | Clickable Row | text: modelName | `d(text="<modelName>")` |

---

### 7. AgentPickerSheet

| 元素 | 类型 | text | u2 选择器 |
|------|------|------|-----------|
| 标题 | Text | "Select Agent" | `d(text="Select Agent")` |
| 分区标题 | Text | "Primary", "Subagent" | `d(text="Primary")` |
| Agent 行 | Clickable Row | text: agent.name | `d(text="<agentName>")` |

---

### 8. PermissionDialog / PermissionCard

| 元素 | text | u2 选择器 |
|------|------|-----------|
| 标题 | "Permission Request" | `d(text="Permission Request")` |
| 允许 | "Allow" | `d(text="Allow")` |
| 拒绝 | "Deny" | `d(text="Deny")` |

---

### 9. QuestionDialog / QuestionCard

| 元素 | label / text | u2 选择器 |
|------|-------------|-----------|
| 标题 | "Question" | `d(text="Question")` |
| 选项按钮 | option.label | `d(text="<option>")` |
| 自定义回答 | OutlinedTextField, label: "Your answer" | EditText |
| 拒绝 | "Reject" | `d(text="Reject")` |
| 提交 | "Submit" | `d(text="Submit")` |

---

### 10. SyncLogScreen

| 元素 | text / contentDescription | u2 选择器 |
|------|--------------------------|-----------|
| 标题 | "Sync Logs" | `d(text="Sync Logs")` |
| 返回 | content-desc: "Back" | `d(description="Back")` |
| 重连 | "Reconnect" | `d(text="Reconnect")` |
| 同步 | "Sync" | `d(text="Sync")` |
| 过滤 chips | "ALL" 等 | `d(text="ALL")` |
| 搜索 | placeholder: "Filter logs with regex" | EditText |
| 复制 | "Copy" | `d(text="Copy")` |
| 删除 | "Delete" | `d(text="Delete")` |

---

### 11. GitChangesScreen

| 元素 | text / contentDescription | u2 选择器 |
|------|--------------------------|-----------|
| 标题 | "Git Changes" | `d(text="Git Changes")` |
| 返回 | content-desc: "Back" | `d(description="Back")` |
| 文件条目 | content-desc: "modified" 等 | `d(description="modified")` |
| 非 Git 仓库 | "此目录不在 Git 仓库中" | `d(text="此目录不在 Git 仓库中")` |
| 无变更 | "没有未提交的变更" | `d(text="没有未提交的变更")` |

---

### 12. WorkspaceBrowserScreen

| 元素 | contentDescription | u2 选择器 |
|------|-------------------|-----------|
| 返回 | "Back" | `d(description="Back")` |
| 上传 | "Upload" | `d(description="Upload")` |
| 创建目录 | "Create directory" | `d(description="Create directory")` |
| 关闭 | "Close" | `d(description="Close")` |
| 搜索 | "Search files" | `d(description="Search files")` |

---

## contentDescription 完整索引

| contentDescription | 页面/组件 | 图标 |
|-------------------|----------|------|
| "More" | InstanceListScreen, SessionDetailScreen, DiffViewerScreen | MoreVert |
| "Add Instance" | InstanceListScreen FAB | Add (+) |
| "Scan QR Code" | InstanceListScreen FAB | QrCodeScanner |
| "Back" | 多个页面 | ArrowBack |
| "New Session" | WorkspaceListScreen, SessionListScreen FAB | Add (+) |
| "Search messages" | SessionDetailScreen | Search |
| "Scroll to previous user message" | SessionDetailScreen | KeyboardArrowLeft |
| "Scroll to next user message" | SessionDetailScreen | KeyboardArrowRight |
| "Scroll to bottom" | SessionDetailScreen | SkipNext |
| "Revert" | SessionDetailScreen | Undo |
| "Browse Files" | SessionDetailScreen | FolderOpen |
| "Git Changes" | SessionDetailScreen | Compare |
| "Abort" | ChatInputBar（停止按钮） | Stop |
| "Send" | ChatInputBar（发送按钮） | Send |
| "Refresh" | ModelPickerSheet | Refresh |
| "Refresh stats" | WorkspaceListScreen settings | Refresh |
| "Upload" | WorkspaceBrowserScreen | UploadFile |
| "Close" | WorkspaceBrowserScreen | Close |
| "Search files" | WorkspaceBrowserScreen, FileViewerScreen | Search |
| "Create directory" | WorkspaceBrowserScreen, LocalFileManagerScreen | CreateNewFolder |
| "Navigate up" | LocalFileManagerScreen | ArrowBack |
| "Clear All" | CrashLogScreen FAB | Delete |
| "Copy" | CrashLogScreen detail | ContentCopy |
| "Share" | CrashLogScreen detail | Share |

## 按钮文本完整索引

| 按钮文本 | 上下文 |
|---------|--------|
| "Save" | AddInstanceScreen |
| "Test Connection" | AddInstanceScreen |
| "Confirm" | 配对对话框、重命名对话框 |
| "Cancel" | 几乎所有对话框 |
| "Create" | 新会话对话框、创建目录对话框 |
| "Delete" | 删除会话、删除文件、清除缓存 |
| "Allow" | 权限对话框 |
| "Deny" | 权限对话框 |
| "Submit" | 问题对话框 |
| "Reject" | 问题对话框 |
| "Retry" | 扫码错误页 |
| "Clear All" | CrashLog 清除对话框 |
| "Clear" | SyncLog 清除对话框 |
| "Reconnect" | SyncLogScreen |
| "Sync" | SyncLogScreen |
| "Resync" | 重同步对话框 |
| "Disconnect" | SettingsContent |
| "Upgrade" | 升级对话框 |
| "Restart" | 重启对话框 |
| "回滚" | 回滚对话框 |
| "恢复" | 回滚 Banner |

## OutlinedTextField 标签完整索引

| label / placeholder | 页面 | 用途 |
|---------------------|------|------|
| "Name" | AddInstanceScreen | 实例名称 |
| "Address" | AddInstanceScreen | 实例地址 |
| "Port" | AddInstanceScreen | 实例端口 |
| "Title (optional)" | 新会话对话框 | 会话标题 |
| "Search sessions" | SessionListScreen | 搜索会话 |
| "Type a message..." | ChatInputBar | 消息输入 |
| "Path (prefix search)" | DirectoryPickerSheet | 目录路径 |
| "Search models or providers" | ModelPickerSheet | 搜索模型 |
| "Search skills" | SkillPickerSheet | 搜索技能 |
| "Your answer" | QuestionDialog | 自定义回答 |
| "Filter logs with regex" | SyncLogScreen | 日志过滤 |
| "自定义" | 重同步对话框 | 自定义数量 |
| "Search files" | WorkspaceBrowserScreen | 搜索文件 |
| "Enter search query" | SessionMessageSearchPanel | 搜索消息 |

## 硬编码中文字符串

所有 UI 层硬编码中文字符串已在 2026-06-18 的国际化修复中替换为 `stringResource(R.string.xxx)`。
剩余未国际化的仅为**调试日志消息**（`logStore.log()` 调用），属于开发者调试内容，优先级低。

### 已修复的字符串（现使用 stringResource）

| 旧硬编码中文 | 新 R.string 名称 | 英文值 |
|-------------|-----------------|--------|
| "回滚" | `revert_dialog_title` / `revert_confirm` | "Revert" |
| "确定回滚到上一条消息？..." | `revert_dialog_message` | "Revert to the previous user message?..." |
| "已回滚消息" | `reverted_message` | "Message reverted" |
| "恢复" | `unrevert` | "Unrevert" |
| "自定义" | `custom` | "Custom" |
| "调试" | `debug` | "Debug" |
| "同步日志" | `sync_logs`（已有资源，之前未使用） | "Sync Logs" |
| "自动重试中" | `auto_retrying` | "Auto-retrying" |
| " · 第 N 次" | `retry_attempt` | " · Attempt %1$d" |
| " · N 秒后重试" | `retry_in_seconds` | " · Retry in %1$ds" |
| " · N 工具" | `tool_count` | " · %1$d tools" |
| " · 继续执行" | `step_continuing` | " · Continuing" |
| " · 出错" | `step_error` | " · Error" |
| "回滚至此" | `revert_to_here` | "Revert to here" |
| "此目录不在 Git 仓库中" | `not_git_repo` | "This directory is not in a Git repository" |
| "没有未提交的变更" | `no_uncommitted_changes` | "No uncommitted changes" |
| "文件已被删除" | `file_deleted`（已有资源，复用） | "File deleted" |
| "正则无效" | `invalid_regex` | "Invalid regex" |
| "Nh Nm Ns" | `duration_hms` / `duration_ms` / `duration_s` | "%1$dh %2$dm %3$ds" 等 |
| "此 Bridge 尚未配对..." | `bridge_not_paired` | "This Bridge has not been paired..." |
| "未找到设备凭证..." | `device_credentials_not_found` | "Device credentials not found..." |
| "Bridge 地址未知且不在线" | `bridge_address_unknown_offline` | "Bridge address unknown and not online" |

## 建议添加 testTag

当前代码库无 `Modifier.testTag()`，建议为关键交互元素添加，以提升自动化测试可靠性：

- 聊天输入框: `testTag = "chat_input"`
- 发送按钮: `testTag = "send_button"`
- 新会话 FAB: `testTag = "new_session_fab"`
- Model picker 触发器: `testTag = "model_picker_chip"`
- Agent picker 触发器: `testTag = "agent_picker_chip"`

## 技术实现备注

- **时长格式化**：`formatDurationMillis()` 在 `core/common` 模块中，通过 lambda 参数接收格式化字符串，调用方在 Composable 上下文中用 `stringResource()` 获取模板后传入
- **非 Composable 函数中的字符串**：`filterRenderedLogs()` 通过 `regexErrorMessage` 参数传入国际化字符串
- **ViewModel 中的字符串**：`QrScanViewModel` 通过 `@ApplicationContext` 注入 Context，使用 `context.getString()` 获取国际化字符串
