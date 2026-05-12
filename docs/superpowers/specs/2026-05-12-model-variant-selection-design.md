## 背景

问题16要求 Android 会话页完整支持模型 variant 选择，并同时满足以下 UI 要求：

- 底部模型显示行右侧提供 variant 选择入口（如果当前模型支持 variants）
- 模型显示区域本身可点击，打开模型选择
- 模型显示只保留模型名称，不显示 provider，减少占用空间

当前代码已经具备以下基础：

- `GET /provider` 已能返回 `models[].variants`
- `ModelPickerSheet` 已能选择模型
- `OpencodeApiClient.sendPrompt()` 已支持传 model/provider
- `SessionDetailScreen` 底部已有模型状态展示区

缺失的是 variant 的状态管理、选择 UI、发送时透传，以及模型行的交互调整。

## 目标

本次只实现问题16，不顺带修复其他已知回归。

完成后应满足：

1. 底部模型标签只显示 `modelName`
2. 点击模型标签可重新打开 `ModelPickerSheet`
3. 若当前模型存在 variants，模型标签右侧显示当前 variant 标签
4. 点击 variant 标签可打开 variant 选择面板
5. 选择模型后，若该模型存在 variants，自动继续弹出 variant 选择面板
6. 发送消息时，请求体带上 `variant`
7. 模型列表缓存按实例隔离，不同实例之间不共用模型列表缓存
8. 模型选择面板提供手动刷新入口，可重新拉取并覆盖缓存

## 方案

采用独立的两段式选择流程：

1. 先保留现有 `ModelPickerSheet` 负责选模型
2. 再新增轻量 `VariantPickerSheet` 负责选 variant

这样可以避免把 variant 选择塞进模型列表里，保持现有模型选择 UI 的复杂度不变。

## 组件与状态改动

### SessionDetailViewModel

新增状态：

- `selectedVariant: StateFlow<String?>`
- `availableVariants: StateFlow<List<String>>`
- `providers: StateFlow<ProviderListDto?>` 优先从本地实例缓存恢复

新增行为：

- `selectVariant(variant: String?)`
- `updateAvailableVariants()`：根据当前 `selectedModel` 和 `providers` 计算可选 variants
- `loadProviders(forceRefresh: Boolean = false)`：默认优先读实例缓存；强制刷新时走网络并覆盖缓存

规则：

- 切换模型时重置 `selectedVariant = null`
- 清空模型时同步清空 `selectedVariant` 和 `availableVariants`
- providers 加载成功后重新计算 `availableVariants`
- providers 缓存必须按实例隔离，缓存 key 需包含当前实例稳定唯一标识，避免不同实例共享错误模型列表
- `selectedVariant` 的持久化也需按 `instance + providerID/modelID` 维度隔离

### 模型列表缓存

采用本地 `SharedPreferences` 缓存 `ProviderListDto` 的 JSON 串。

缓存策略：

- 每个实例单独一份缓存，不同实例不共享
- 打开模型选择器时：
  - 若当前实例已有缓存，直接显示缓存内容
  - 若当前实例无缓存，维持当前加载逻辑，从服务端请求
- 点击刷新按钮时：
  - 强制请求服务端
  - 成功后覆盖当前实例缓存
  - 同步刷新当前内存中的 `providers`

实例标识：

- 使用当前 active `ServerProfile.id` 作为实例唯一标识
- 不使用实例名称；名称可编辑，仅用于展示
- 不使用 `directory` 作为实例缓存 key；目录只用于 API scope，不代表实例身份
- 缓存 key 必须稳定且与当前实例一一对应，不能是全局单例 key

### SessionDetailScreen

底部模型状态行改为：

- 模型标签仅显示 `selectedModel.modelName`
- 模型标签加 `clickable`，点击后打开 `showModelPicker`
- 如果 `selectedVariant != null`，在模型标签右侧显示一个 variant 标签
- variant 标签加 `clickable`，点击后打开 `showVariantPicker`
- `ModelPickerSheet` 头部提供刷新按钮；普通打开优先显示缓存，点击刷新后强制重新拉取 providers

补充约束：

- 如果当前模型支持 variants，即使当前选择的是默认值，也要显示 variant 标签，文案为“默认”
- 重新进入会话时，如果已恢复到支持 variants 的当前模型，也要恢复并显示该标签状态

新增本地 UI 状态：

- `showVariantPicker`

交互流程：

- 用户从 `ModelPickerSheet` 选择模型
- Screen 调 `viewModel.selectModel(...)`
- 关闭 `ModelPickerSheet`
- 如果 `availableVariants` 非空，则立即打开 `VariantPickerSheet`

### VariantPickerSheet

新增一个轻量 bottom sheet：

- 标题：`Select Variant`
- 第一项始终是 `Default`
- 其后是 `availableVariants`
- 单选行为，选择后回调 `onSelect(String?)`

`Default` 对应 `null`，表示不显式传 variant。

但在本地持久化层面，需要把“默认”也视为一个显式选择状态保存下来，否则退出会话再进入后会丢失该状态的展示。

建议持久化规则：

- UI 选择 `Default` 时，内存态仍可用 `null` 表示不透传给接口
- 持久化时保存为字符串哨兵值，例如 `default`
- 恢复时如果读到 `default`，则：
  - `selectedVariant` 仍恢复为 `null`
  - 但需要额外有能力判断“这是显式默认，不是从未选择过”，以保证标签显示“默认”
- 当前实现使用额外状态 `hasExplicitDefaultVariant` 表示该差异，并驱动默认项选中态

如果不想引入额外状态，也可以把 `selectedVariant` 直接改成三态模型：

- 未初始化/未选择
- 默认
- 具体 variant

本次优先选实现成本更低、对现有代码侵入更小的方案。

## 网络请求改动

### OpencodeApiClient.sendPrompt

给 `sendPrompt()` 增加参数：

- `variant: String? = null`

当 `variant != null` 时，在 `prompt_async` body 中附加：

```json
{
  "variant": "high"
}
```

### SessionDetailViewModel.sendMessage

发送时读取：

- `_selectedModel`
- `_selectedVariant`

并把 `variant` 透传到 `apiClient.sendPrompt(...)`。

如果当前是“默认”状态，则不传 `variant` 字段，仍使用服务端默认行为。

## 边界与限制

- 本次需要处理本地恢复时的 variant 显示状态恢复，但不依赖历史消息回放
- 本次不要求把 recent models 细化到 recent model + variant 组合
- 本次不处理问题6/9/10/12 的回归，它们后续单独修

## 验证

至少验证：

1. 选择普通模型时，不出现 variant 标签
2. 选择支持 variants 的模型时，自动弹出 variant 选择
3. 选择 `Default` 后，发送请求不带 `variant`
4. 选择具体 variant 后，发送请求带对应 `variant`
5. 底部模型标签只显示模型名，不显示 provider
6. 点击模型标签能重新打开模型选择
7. 点击 variant 标签能重新打开 variant 选择
8. `assembleDebug --no-daemon` 通过
9. 不同实例间打开模型选择器时，读取各自独立缓存，互不污染
10. 有缓存时打开模型选择器不再依赖实时网络请求
11. 点击刷新按钮后能重新拉取并更新缓存
