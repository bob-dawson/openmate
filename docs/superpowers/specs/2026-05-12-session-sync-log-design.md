## 会话同步日志设计

### 目标

在会话详情页内增加一套同步调试工具，让用户可以直接在手机上查看移动端同步行为。

该工具从会话详情菜单中的 `同步日志` 进入，提供以下能力：

- 实时的内存同步日志
- 自动跟随到最新日志
- 文本搜索过滤
- 对可见日志进行文本选择与复制
- 清除日志
- 复制过滤后的日志
- 重连全局同步 SSE
- 对当前会话手动触发一次增量同步

这是一个调试功能，不是业务功能。信息密度应优先服务排查效率，而不是普通用户友好性。

### 已确认的产品决策

- 只采用一种方案：全局内存日志服务 + 独立全屏日志页。
- `重连SSE` 表示重连当前实例的全局同步 SSE 连接。
- 日志范围是当前实例下的全局同步日志，但会对当前会话相关日志做高亮。
- 日志仅保存在内存中。
- 最多保留 200 条日志。
- `复制日志` 复制当前过滤结果中可见的日志。
- 过滤发生在最终渲染文本层，而不是结构化字段层。

### 用户体验

#### 入口

在会话详情右上角溢出菜单中新增 `同步日志`。

#### 页面布局

同步日志页采用独立全屏页面，包含：

- 带返回按钮的顶部标题栏，标题为 `同步日志`
- 当前会话摘要行
- 当前 SSE 状态摘要行
- 搜索过滤输入框
- 操作按钮区
- 可滚动日志列表
- 底部自动跟随状态 / 操作控件

建议草图：

```md
[< 返回] 同步日志
当前会话：同步探讨
状态：SSE已连接 | 自动滚动开启 | 共 87 条

[搜索过滤框                        ]
提示：输入后仅显示匹配正则的日志

[复制日志] [清除日志] [重连SSE] [增量同步]

--------------------------------------------------
15:42:18.892 INFO [Sync] 增量包返回 session=ses_xxx trace=inc-1042 events=47 maxSeq=133563 bytes=18234

15:42:18.894 INFO [Sync] 增量消息处理 session=ses_xxx trace=inc-1042 seq=133517 type=message.updated.1 bytes=412

15:42:18.917 INFO [Sync] 增量同步结束 session=ses_xxx trace=inc-1042 applied=31 lastSeq=133563 cost=167ms totalBytes=18234

15:48:02.018 WARN [Sse] SSE断开 trace=sse-303 reason=stream_closed reconnectIn=3000ms
--------------------------------------------------

[自动滚动到最新: 开]
```

#### 日志列表行为

- 首次打开页面时，滚动到最新日志。
- 当用户手动向上滚动后，暂停自动跟随。
- 自动跟随开启时，新日志进入后保持列表贴底。
- 自动跟随关闭时，新日志进入不打断当前阅读。
- 页面提供一个控件，允许一键跳回最新并重新开启自动跟随。

#### 搜索过滤行为

- 输入框为空时，显示全部保留日志。
- 输入框非空时，将输入内容视为正则表达式，只显示最终渲染文本能匹配该正则的日志。
- 正则默认大小写不敏感。
- 过滤仍然只作用在最终渲染文本层，不作用在结构化字段层。
- 如果正则非法，则保持当前可见日志不变，并在输入框附近显示“正则无效”的错误提示。
- 过滤只影响可见行与 `复制日志` 的输出内容。
- 过滤不会修改原始保留日志集合。

#### 文本选择与复制

- 可见日志文本必须支持选择和系统复制，体验类似会话里的可选中文本。
- 每条日志项直接展示最终文本内容，而不是字段标签块。
- `复制日志` 会把当前过滤结果中所有可见日志按时间顺序拼接为纯文本块并复制。

#### 操作按钮

##### 复制日志

- 复制当前过滤结果中可见的日志。
- 输出格式是纯文本，每条可见日志一行。

##### 清除日志

- 清空当前保留的全部内存日志。
- 应增加确认步骤，避免调试过程中误删。
- 清除完成后，应立即重新记录一条“日志已清除”相关日志，便于用户确认动作已发生。

##### 重连SSE

- 显式断开并重新建立当前实例的全局同步 SSE 连接。
- 该操作应追加一组 `Manual` 与 `Sse` 生命周期日志。

##### 增量同步

- 仅对当前会话手动触发一次增量同步。
- 该操作应追加一组带统一 trace 的 `Manual` 与 `Sync` 日志。

### 数据模型

内部采集保持结构化，但 UI 展示与过滤只使用最终文本。

```kotlin
data class SyncLogEntry(
    val id: Long,
    val timestamp: Long,
    val level: SyncLogLevel,
    val category: SyncLogCategory,
    val sessionId: String?,
    val title: String,
    val message: String,
    val bytes: Int?,
    val relatedSeq: Long?,
    val traceId: String?,
)
```

Supporting enums:

- `SyncLogLevel`: `Info`, `Warn`, `Error`
- `SyncLogCategory`: `Sse`, `Sync`, `Manual`, `Poll`

### 渲染模型

每条结构化日志在进入 UI 前统一格式化成一条最终文本，再基于该文本做展示与过滤。

格式示例：

```text
15:42:18.892 INFO [Sync] 增量包返回 session=ses_203a2838affeX2O5izmHusImSN trace=inc-1042 events=47 maxSeq=133563 bytes=18234
```

错误示例：

```text
15:48:12.312 ERROR [Sync] 增量同步失败 session=ses_203a2838affeX2O5izmHusImSN trace=inc-1049 afterSeq=133563 error=SocketTimeoutException cost=208ms
```

最终可见文本是以下能力的唯一数据源：

- 页面展示文本
- 局部选择与复制
- 搜索过滤
- 一键复制输出

当前会话高亮仍然基于结构化字段里的 `sessionId` 判断，不通过反向解析文本实现。

### 保留策略

- 使用单例内存环形缓冲区保存日志。
- 最大条数：200。
- 超出容量后丢弃最旧日志。
- 不写入数据库。
- 不写入文件。
- 清除日志会直接清空环形缓冲区。

### 事件覆盖范围

#### SSE 生命周期

必须记录：

- 用户请求重连
- 显式断开请求
- 发起连接
- 连接成功
- 连接失败
- 非预期断开 / 流结束
- 已安排重连 / 重试延时
- 连接取消

#### SSE 通知

必须记录：

- 收到通知
- 通知进入 debounce 队列
- debounce 到期，准备触发同步
- 因已有活动同步而跳过

#### 增量同步生命周期

必须记录：

- 用户手动触发同步
- 轮询补偿触发同步
- 同步开始时的 `afterSeq`
- 响应返回后的事件数、最大 seq、包大小
- replay 汇总结果
- 同步完成汇总
- 同步失败汇总

#### 增量事件明细

每条增量事件必须记录：

- `seq`
- 事件类型
- aggregate / session id
- 事件字节数

这是流量排查和事件级排查所必需的。

#### 流量统计口径

需要统一记录：

- `增量包大小`：一次增量 events 请求完整 HTTP 响应体的 UTF-8 字节数
- `每条增量消息大小`：单条 event JSON 负载的 UTF-8 字节数

所有字节统计必须统一使用 UTF-8 口径。

### 耗时规则

不要在每条日志上强制加通用 duration 字段。

很多日志只是一个瞬时点事件，并没有天然耗时。只有具备明确开始/结束边界的流程，才在“结束日志”里写汇总耗时。

例如：

- SSE 发起连接 -> SSE 连接成功 / 失败
- 手动增量同步开始 -> 完成 / 失败
- 增量同步开始 -> 完成 / 失败

这些耗时统一写在结束日志文本中，例如：

- `cost=183ms`
- `cost=167ms`

跨多条日志的链路通过 `traceId` 关联。

### Trace ID 规则

每条多步骤流程都应带一个 trace id。

例如：

- `sse-302`
- `notify-8812`
- `inc-1042`

`traceId` 需要出现在最终文本里，便于用户直接搜索过滤。

### 当前会话高亮规则

- 若 `entry.sessionId == currentSessionId`，则对该行做视觉高亮。
- 若 `sessionId` 为空，则视为普通全局日志。
- 其他会话日志保持可见，但不高亮。
- `Warn` 与 `Error` 日志还应叠加更醒目的视觉样式。

### 日志样例

#### 正常流程样例

```text
15:42:11.084 INFO [Manual] 用户请求重连SSE trace=sse-manual-301 message=reconnect sync sse requested from sync log screen
15:42:11.086 INFO [Sse] 主动断开SSE trace=sse-manual-301 message=disconnect requested currentBaseUrl=http://192.168.1.8:4097
15:42:11.101 INFO [Sse] 发起SSE连接 trace=sse-302 message=connecting to /api/bridge/sync/events token=true
15:42:11.284 INFO [Sse] SSE连接成功 trace=sse-302 message=connected to sync event stream cost=183ms
15:42:18.245 INFO [Sse] 收到同步通知 session=ses_203a2838affeX2O5izmHusImSN trace=notify-8812 seq=134040 message=session sync notification received
15:42:18.246 INFO [Sse] 同步通知入队 session=ses_203a2838affeX2O5izmHusImSN trace=notify-8812 seq=134040 message=queued for debounce window=500ms
15:42:18.749 INFO [Sync] 准备发起增量同步 session=ses_203a2838affeX2O5izmHusImSN trace=inc-1042 message=debounce elapsed starting incremental sync afterSeq=133516 trigger=sse
15:42:18.750 INFO [Sync] 增量同步开始 session=ses_203a2838affeX2O5izmHusImSN trace=inc-1042 message=incremental sync begin afterSeq=133516
15:42:18.892 INFO [Sync] 增量包返回 session=ses_203a2838affeX2O5izmHusImSN trace=inc-1042 bytes=18234 message=events response received eventCount=47 maxSeq=133563
15:42:18.894 INFO [Sync] 增量消息处理 session=ses_203a2838affeX2O5izmHusImSN trace=inc-1042 seq=133517 bytes=168 message=event type=session.next.step.ended.1 aggregateId=ses_203a2838affeX2O5izmHusImSN
15:42:18.895 INFO [Sync] 增量消息处理 session=ses_203a2838affeX2O5izmHusImSN trace=inc-1042 seq=133518 bytes=412 message=event type=message.part.updated.1 aggregateId=ses_203a2838affeX2O5izmHusImSN
15:42:18.895 INFO [Sync] 增量消息处理 session=ses_203a2838affeX2O5izmHusImSN trace=inc-1042 seq=133519 bytes=287 message=event type=message.updated.1 aggregateId=ses_203a2838affeX2O5izmHusImSN
15:42:18.903 INFO [Sync] Replay结果 session=ses_203a2838affeX2O5izmHusImSN trace=inc-1042 message=replay finished eventCount=47 changeCount=47 coalescedWrites=31
15:42:18.917 INFO [Sync] 增量同步结束 session=ses_203a2838affeX2O5izmHusImSN trace=inc-1042 seq=133563 bytes=18234 message=incremental sync completed applied=31 cost=167ms totalBytes=18234
15:42:20.104 INFO [Sse] 收到同步通知 session=ses_other_77 trace=notify-8813 seq=9021 message=session sync notification received
15:42:20.611 INFO [Sync] 准备发起增量同步 session=ses_other_77 trace=inc-1043 message=debounce elapsed starting incremental sync afterSeq=9008 trigger=sse
15:42:20.742 INFO [Sync] 增量同步结束 session=ses_other_77 trace=inc-1043 seq=9021 bytes=2140 message=incremental sync completed applied=4 cost=131ms totalBytes=2140
```

#### 异常流程样例

```text
15:48:02.018 WARN [Sse] SSE断开 trace=sse-303 message=stream closed unexpectedly reconnectIn=3000ms
15:48:05.031 INFO [Sse] 发起SSE连接 trace=sse-304 message=connecting to /api/bridge/sync/events token=true
15:48:05.447 ERROR [Sse] SSE连接失败 trace=sse-304 message=http 401 unauthorized cost=416ms
15:48:12.104 INFO [Manual] 用户发起增量同步 session=ses_203a2838affeX2O5izmHusImSN trace=inc-1049 message=manual incremental sync requested
15:48:12.312 ERROR [Sync] 增量同步失败 session=ses_203a2838affeX2O5izmHusImSN trace=inc-1049 message=incremental sync failed afterSeq=133563 error=SocketTimeoutException cost=208ms
```

### 实现注意事项

- 应先建立统一日志采集面，再让各同步链路复用它。
- 最终文本格式化必须集中在一处，保证搜索、展示、复制三者口径一致。
- UI 不应使用另一套规则重新拼装文本，否则会导致“展示文本”和“复制/过滤文本”不一致。
- 该功能本身不应改变同步机制，除非用户明确点击了 `重连SSE`、`增量同步`、`清除日志`。

### 不在本次范围内

- 持久化日志存储
- 导出到文件
- 更高级的过滤语法
- 按会话分隔的独立日志池
- 远程分析 / 遥测上传
