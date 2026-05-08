# Incremental Sync 当前问题列表

## 已修复

1. **增量同步不工作** — EventReplayer timestamp 解析、Bridge serde 参数名 camelCase 不匹配导致全量拉取
2. **loadSession() 每次都 initSync** — 已改为 getLastSeq>0 时走 incrementalSync
3. **Bridge maxSeq 返回 null** — initSync 改为先获取 cursor seq 再拉数据
4. **coalesce 合并丢失消息** — Insert 被 Update 覆盖后 DB 找不到 existing，已合并为带最终 data 的 Insert
5. **UI 还原** — MarkdownText、工具渲染、Reasoning 折叠、metadata 隐藏
6. **思考过程设置无效** — showReasoning 未传入 SessionMessageRenderer
7. **refreshSessionStatusesFromMessages()** — 已实现，查询 session_message 表判断 BUSY/IDLE
8. **loadSession 非阻塞** — observeMessages 立即执行，同步在后台

9. **json_extract 不兼容** — Android SQLite 不支持 json_extract，改用 getLatestAssistant + Kotlin 过滤
10. **SSE 防抖** — SyncSseHandler 重写为 SharedFlow + debounce(500ms) + ConcurrentHashMap
11. **TUI 操作不触发 SSE** — TUI 直接写 DB 不经过 serve API，导致 SSE 不发 sync 事件，已加 15s 定时轮询兜底
12. **completedAt 独立列** — SessionMessageEntity 新增 completedAt: Long? 列，DAO/EventReplayer/Repository/ViewModel 全链路改用 completedAt IS NULL 判断
13. **QUEUED 状态实现** — queuedMessageId 基于"末条消息是 user 类型"判断，QUEUED 徽章附时间显示
14. **搜索面板全屏 Dialog** — SessionMessageSearchPanel 改为全屏 Dialog(usePlatformDefaultWidth=false)
15. **搜索定位滚动修复** — 导航时设 userNavigating=true 防止 SmartAutoScroll 覆盖滚动位置
16. **消息元信息** — User/Assistant 消息下显示时间 + 耗时 + 模型名称
17. **会话标题断网修复** — loadSession 先通过 observeSession Flow 读取本地 DB 标题，API 调用异步更新

## 待修复

### P1 - UI 功能缺失

1. **Question/Permission 交互卡片** — 旧版 MessageItem 支持 question/permission 交互
2. **子会话跳转** — TaskToolLine 可点击跳转
3. **文件附件渲染** — FileItem（mime 标签 + 文件名）
