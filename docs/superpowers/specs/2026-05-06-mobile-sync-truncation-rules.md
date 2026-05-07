# 移动端同步字段截断规则

> 本文档定义移动端同步时各消息/工具类型的大字段截断策略。
> 详见主文档 `2026-05-06-mobile-incremental-sync-design.md`

## 截断执行位置

- **Bridge 侧**：初始化快照（`/init`）时，Bridge 读取 SessionMessage 后应用截断规则再返回
- **移动端侧**：增量 replay 时，EventReplayer 将 event 转换为 SessionMessage 写入本地 DB 前应用截断规则
- 两处使用**同一套规则**，确保一致性

## 通用规则

| 字段类型 | 策略 |
|---------|------|
| ID / 时间戳 / 枚举 | 全量保留 |
| `snapshot` | 跳过 |
| `metadata` | 跳过 |
| `diagnostics` | 跳过 |
| 附件（base64） | 跳过 |

## SessionMessage 类型级截断

| Message 类型 | 字段 | 截断策略 |
|-------------|------|---------|
| `assistant` | `content[].text` (type=text) | 不截断（LLM 回复正文，核心内容） |
| `assistant` | `content[]` (type=reasoning) | 保留前后各 100 字符，中间 `...[truncated]...` |
| `assistant` | `content[]` (type=tool) | 按工具类型分别处理（见下表） |
| `assistant` | `snapshot` | 跳过 |
| `assistant` | `tokens` / `cost` | 全量保留 |
| `assistant` | `finish` / `error` | 全量保留 |
| `user` | `text` | 不截断（用户输入本身有限） |
| `user` | `files[].name` / `uri` / `mime` | 保留 |
| `user` | `files[].source.text` / `description` | 跳过 |
| `shell` | `command` | 保留 |
| `shell` | `output` | 前 5 行 + 后 5 行，保留 `truncated`/`exit` |
| `compaction` | `text` (summary) | 前 10 行 + 后 10 行，中间 `...[truncated]...` |
| `compaction` | `include` | 跳过 |
| `agent-switched` / `model-switched` | 全部 | 全量保留（很小） |
| `synthetic` | `text` | 不截断 |

## 按工具类型的截断规则

### 核心思路

- **文件操作类**（read/write/edit/apply_patch）：只保留文件名，内容跳过。UI 可提供"打开文件"入口。
- **bash**：输入保留命令，输出取首尾行摘要，退出码必留。
- **搜索类**（glob/grep）：保留统计信息和前几条结果。
- **网络类**（webfetch/websearch）：保留 URL/query，跳过大段内容。
- **交互类**（question/todowrite/skill）：通常很小，全量保留。

### 详细规则

| 工具 ID | 说明 | input 保留字段 | input 跳过字段 | output 保留字段 | output 跳过字段 |
|---------|------|--------------|---------------|----------------|----------------|
| `bash` | Shell 命令 | `command` | `timeout`, `workdir`, `description` | `exit`, `truncated`, `output` 前5行+后5行 | `outputPath`, `output` 其余行 |
| `read` | 读取文件/目录 | `filePath` | `offset`, `limit` | `truncated` | `preview`, `loaded`, 附件 |
| `write` | 写入文件 | `filePath` | `content` | `filepath`, `exists` | `diagnostics` |
| `edit` | 编辑文件 | `filePath` | `oldString`, `newString`, `replaceAll` | 行数统计（`additions`/`deletions`） | `diff`, `filediff`, `diagnostics` |
| `apply_patch` | 应用补丁 | 无（`patchText` 跳过） | `patchText` | `files[]` 的 `{filePath, type, additions, deletions}` | `files[].patch`, `diff`, `diagnostics` |
| `glob` | 文件匹配 | `pattern`, `path` | — | `count`, `truncated` | 匹配列表 |
| `grep` | 内容搜索 | `pattern`, `path`, `include` | — | `matches`, `truncated` | 结果详情 |
| `task` | 子代理 | `description`, `subagent_type` | `prompt`, `command`, `task_id` | `sessionId`, `model` | 子代理输出文本 |
| `todowrite` | 待办列表 | `todos` 全量 | — | `todos` 全量 | — |
| `webfetch` | 网页抓取 | `url`, `format` | `timeout` | 无 | 网页内容, 附件 |
| `websearch` | 网页搜索 | `query` | `numResults`, `livecrawl`, `type`, `contextMaxCharacters` | 搜索结果摘要 | `contextMaxCharacters` |
| `skill` | 加载技能 | `name` | — | `name`, `dir` | 技能内容 |
| `question` | 提问用户 | `questions` 全量 | — | `answers` 全量 | — |
| `lsp` | LSP 操作 | `operation`, `filePath`, `line`, `character`, `query` | — | 无 | `result` |
| `plan_exit` | 退出计划模式 | 无 | — | 无 | — |
| `invalid` | 无效工具 | `tool`, `error` | — | 无 | — |

### bash output 截取算法

```
fun truncateBashOutput(output: String, maxHead: Int = 5, maxTail: Int = 5): String {
    val lines = output.lines()
    if (lines.size <= maxHead + maxTail) return output
    val head = lines.take(maxHead)
    val tail = lines.takeLast(maxTail)
    return head.joinToString("\n") + "\n... [${lines.size - maxHead - maxTail} lines truncated] ...\n" + tail.joinToString("\n")
}
```

### 未知工具处理

遇到未在上述列表中的工具（自定义插件等）：
- input：保留 `name`，跳过其余
- output：跳过 `content`，保留 `structured`（如 ≤ 500 字符）

## 补充说明

### tool.state.content 中的文件附件

`tool.success` / `tool.progress` 的 `content[]` 可能包含 `{type:"file", uri, mime, name}` 附件。
截断时保留文件元信息（uri/name/mime），但跳过内嵌 base64 数据（如有）。

### step.ended tokens.total 字段

`step.ended` 的 `tokens` 含 `total` 字段（实际 DB 验证存在），截断规则中 tokens 类字段全量保留，包括 total。

## 已确认的截断决策

- [x] compaction `text` / `include` 截断策略 → text 前10后10行，include 跳过
- [x] prompt.text 2000 字符上限是否够 → 不截断，用户输入有限
- [x] synthetic `text` 截断策略 → 不截断
- [x] glob/grep 前 10 条结果是否合适 → 只保留输入+匹配数量，不保留结果列表
- [x] edit output 是否需要保留 diff 片段 → 只保留行数统计（additions/deletions）
- [x] tool.state.content 文件附件 → 保留元信息，跳过内嵌数据
