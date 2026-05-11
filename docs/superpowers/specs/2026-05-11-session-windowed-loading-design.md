# 会话消息窗口增量更新与按需加载设计

## 背景

当前会话详情页虽然冷启动只做 `initSync(sessionId, 30)`，但 UI 最终仍然观察本地数据库中的整会话消息列表：

- `SessionMessageDao.observeBySession(sessionId)` 返回整会话消息
- 每次发送消息、轮询、手动刷新、SSE 触发同步后，都会写入 Room
- Room 失效后重新返回整会话列表
- `SessionDetailViewModel.observeMessages()` 再对整列表做多轮扫描

因此，大会话下会出现明显退化：

1. 即使本次只有 1 条消息变更，UI 仍然可能重读整会话
2. 已丢失“进入只看最近少量消息，滚到顶再按需加载历史”的能力
3. 热路径没有利用同步阶段已经掌握的“更新范围”信息

用户确认的目标是：消息窗口是主要工作区域，应追求理论最佳性能，而不仅仅是“比全量稍好”。

## 目标

1. 进入会话详情时只显示最近 30 条消息
2. 用户滚动到顶部时，再按页加载更早 30 条消息
3. 热路径不再依赖 Room 全量/窗口观察来刷新消息列表
4. 增量同步后，只按本次变化范围更新当前消息窗口
5. Room 继续作为持久化真相源，但不再承担热路径 UI 刷新职责
6. 为冷启动和历史分页查询补齐本地组合索引

## 非目标

1. 不重写 Bridge 同步协议
2. 不取消本地 DB 持久化
3. 不要求所有 UI 状态都直接从 DB Flow 推导
4. 不支持搜索结果跨当前窗口自动补载历史消息

## 已知约束

1. app 当前同一时刻只允许打开一个会话详情页，不存在多窗口并发查看同一会话的问题
2. 搜索/定位只针对当前窗口已加载范围内的消息，不会为了定位旧消息自动扩页
3. 消息定位直接复用当前已加载消息列表；若用户需要查找更老内容，后续再提供“按时间范围加载”能力

## 现状结论

### 1. Bridge 索引不是当前主因

Bridge 已确保 opencode.db 上存在：

- `event(aggregate_id, seq)`
- `session_message(session_id, time_created DESC)`

Android 发消息后的同步也不是走全量消息 API，而是走 Bridge 增量接口 `/api/bridge/sync/session/:id/events?afterSeq=...`。因此“Bridge 没建索引”不是主问题。

### 2. Android 热路径没有利用更新范围

当前每次 `incrementalSync()`：

1. 拉回少量事件
2. `EventReplayer` 生成 `ReplayChange`
3. 写入 Room
4. UI 再从 Room 读整会话列表

但其实第 2 步已经掌握了本次影响的消息范围和变更类型。这个信息没有被用于直接更新当前消息窗口。

## 方案对比

### 方案 A：DB 全量缓存，UI 窗口分页

- DB 保存全量
- UI 只观察最近窗口
- 顶部继续加载时扩大窗口

优点：

- 保留 Room 单一数据源
- 改动相对保守

缺点：

- 每次同步后仍要重查当前窗口
- 仍然会对窗口列表做重复扫描
- 热路径没有做到理论最佳性能

### 方案 B：全量观察后 UI 截取窗口

- Room 继续返回整会话
- UI 或 ViewModel 截取最近窗口

优点：

- 改动最小

缺点：

- 根本问题完全不解决

### 方案 C：热路径内存窗口增量更新，冷路径 DB 分页加载（选定）

- DB 继续持久化完整消息历史
- 当前消息窗口由 ViewModel 内存维护
- `incrementalSync()` 返回本次变更范围并直接更新内存窗口
- 顶部加载更早消息时，再按页从 DB 读取

优点：

- 热路径只处理本次变化的少量消息
- 能最大化利用同步阶段已经掌握的变更范围
- 支持按需加载历史消息

缺点：

- ViewModel 维护窗口状态的复杂度更高
- 需要明确窗口内/外消息更新策略

## 选定方案

选择方案 C：热路径内存窗口增量更新，冷路径 DB 分页加载。

## 核心原则

1. **DB 是持久化真相源**
2. **内存窗口是当前会话详情页的工作集**
3. **同步结果优先直接更新工作集**
4. **历史消息只在用户需要时按页读取**

## 详细设计

### 1. 本地索引

`SessionMessageEntity` 增加组合索引：

- `(sessionId, timeCreated)`

作用：

- 冷启动读取最近 30 条
- 顶部按页加载更早 30 条
- 需要补窗口缺口时的 DB 查询

需要：

- 提升 Room version
- 添加 migration

### 2. 冷启动：从 DB 初始化窗口

进入会话详情时：

1. 先读取本地 DB 最近 30 条消息，作为窗口初始值
2. 再根据 `lastSeq` 决定走 `initSync(30)` 或 `incrementalSync()`
3. 同步完成后，不通过 Room 重新整窗口观察，而是把同步变化直接应用到当前窗口

这意味着：

- 冷启动首屏仍然可以很快显示
- 后续同步不再强依赖重新 query 当前窗口

### 3. 热路径：同步返回变更范围

调整 `SessionMessageRepository.incrementalSync(sessionId)` 的职责：

- 仍然写 DB
- 同时返回本次同步结果摘要，例如：
  - `changes: List<ReplayChange>`
  - `lastSeq`
  - 可能的辅助元信息

本次同步的 `ReplayChange` 已能覆盖：

- Insert
- Update
- Remove（若补齐）

因此，消息窗口更新不再依赖 Room invalidation，而是依赖同步返回结果。

### 4. ViewModel 内存窗口模型

`SessionDetailViewModel` 维护：

- `messageWindow: List<SessionMessage>` 当前展示窗口
- `loadedCount: Int` 当前已加载的历史条数，初始 30
- `hasOlderMessages: Boolean`
- `windowStartTime` 或等价边界信息
- `messageIndexById` 或等价结构，用于快速命中窗口内消息

窗口定义：

- 默认只包含最近 30 条
- 顶部加载后扩展为最近 60/90/120...
- 只要用户没有继续上翻，就不主动扩大窗口历史范围

### 5. 变更应用规则

#### 5.1 Insert

若新增消息时间落在当前窗口尾部：

- 直接 append 到窗口

若窗口已满且需要维持固定条数：

- 对“首次加载后的最近窗口模式”可裁掉最旧一条
- 对“用户已经向上扩页”的模式，不主动裁剪，保持当前已展开范围

#### 5.2 Update

若 message id 命中窗口内：

- 原位更新

若不在窗口内：

- 只写 DB，不更新窗口

#### 5.3 Remove

若命中窗口内：

- 从窗口中删除
- 必要时从 DB 再补 1 条更早消息，维持窗口连续性

若不在窗口内：

- 只写 DB

### 6. 顶部按需加载

用户滚动到顶部时：

1. 触发 `loadOlderMessages()`
2. 从 DB 读取当前窗口最早消息之前的更早 30 条
3. prepend 到窗口头部
4. 更新 `loadedCount` 与 `hasOlderMessages`
5. 调整滚动位置，避免 prepend 后跳动

推荐查询语义：

- 以当前窗口最早一条消息的 `(timeCreated, id)` 作为边界
- 查更早 30 条

### 7. 为什么热路径不再依赖 DB 观察

因为同步入口已经是唯一消息变更入口：

- SSE 只是通知需要同步
- 实际消息变化都在 `incrementalSync()` 中落库
- 每次同步最多只影响很少的消息

既然变化范围在同步阶段已知，再通过 Room 重新查询窗口属于重复劳动。

因此：

- 消息窗口不再由 `observeBySession()` 驱动
- Room 主要用于：持久化、冷启动、历史分页、窗口补洞

### 8. 状态计算策略

当前 `observeMessages()` 中有多次整列表扫描。改造后改成两类：

#### 8.1 可随窗口增量维护的状态

- queued message ids
- isStreaming
- 当前 busy start
- last finalized assistant

优先通过：

- 同步变化直接修正
- 只看窗口尾部少量消息重新计算

#### 8.2 需要历史补充的信息

如果某些状态确实依赖窗口外消息：

- 用小查询补充
- 不能回退成整会话全量扫描

### 9. 一致性与恢复

为防止内存窗口状态漂移：

1. 进入会话时始终用 DB 最近 30 条重建窗口
2. 手动 refresh 仍走同步，但完成后按变更集修正窗口
3. 若检测到窗口修正失败或边界不一致，可退回“按当前 loadedCount 从 DB 重建窗口”作为保底恢复路径

这是一种受控的 fallback，只在异常场景使用，不作为常规热路径。

## 模块改动

### `core/database`

- `SessionMessageEntity`：增加组合索引
- `AppDatabase`：升级 version
- migration：增加索引 migration
- `SessionMessageDao`：新增最近 N 条、按边界取更早一页、补单条消息等查询

### `core/data`

- `SessionMessageRepository`：`incrementalSync()` 返回同步变更结果，而不只是 `Unit`
- `SessionMessageRepositoryImpl`：写库后返回 `ReplayChange` 摘要
- 补充冷启动/分页所需的 DB 读取接口

### `feature/session`

- `SessionDetailViewModel`：从“DB Flow 驱动整列表”改成“内存窗口驱动 UI”
- `SessionDetailScreen`：顶部按需加载更早 30 条
- 状态计算改为窗口增量维护 + 小范围重算

## 风险

### 1. 窗口边界维护复杂

需要明确定义：

- 新消息进来时窗口是否裁剪
- 用户已扩页后是否保持展开范围
- 删除后如何补洞

### 2. 变更集与窗口应用不一致

若 `ReplayChange` 到 `SessionMessage` 的映射不完整，窗口可能与 DB 不一致。

缓解：

- 保留“按 loadedCount 从 DB 重建窗口”的恢复路径

### 3. 搜索/定位消息与窗口分页交互

搜索与定位只作用于当前窗口已加载范围。

本阶段要求：

- 明确限定搜索结果范围为当前窗口
- 不实现窗口外消息的自动补载或跳转
- 搜索面板与消息定位复用当前窗口消息列表，不额外引入独立历史查询路径

后续扩展方向：

- 如果用户需要查找更老消息，再新增“按时间范围加载消息”功能，而不是在当前版本为搜索自动扩页

## 验证

### 功能验证

1. 进入会话时只显示最近 30 条
2. 滚到顶部时继续加载更早 30 条
3. 新消息同步回来后，窗口直接更新，无需整表重读
4. assistant streaming / finish / error 正常更新
5. 删除、更新、重试等消息变更不丢失
6. 搜索结果仅来自当前窗口，窗口外旧消息不会被错误宣称为可定位

### 性能验证

1. 大会话发送消息后，消息返回 UI 的延迟明显下降
2. 热路径不再随会话历史总量线性退化
3. 顶部扩页耗时主要取决于 30 条分页查询，而非整会话大小

### 一致性验证

1. 退出再进入会话后，窗口能从 DB 正确重建
2. 手动 refresh 后窗口与 DB 一致
3. 异常 fallback 重建窗口后，显示顺序和状态正确

## 实施顺序

1. 增加 Android 本地组合索引与 migration
2. 为 DAO 增加最近 30 条与更早 30 条分页查询
3. 调整 `SessionMessageRepository.incrementalSync()` 返回变更摘要
4. ViewModel 改为维护内存消息窗口
5. 顶部按需加载更早 30 条
6. 将 busy/queued/streaming 等状态迁移到窗口增量维护
7. 做大会话性能与一致性回归验证
