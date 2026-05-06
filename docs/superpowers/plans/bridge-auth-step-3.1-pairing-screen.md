# Android 3.1: PairingScreen 配对界面

## 目标

在 `AddInstanceScreen` 的 "Test Connection" 流程中，当 Bridge 返回需要配对时（检测到 Bridge 启用了认证但无 token），显示配对 PIN 输入界面，完成配对后获取 token。

## 设计决策：内嵌式 vs 独立页面

**选择内嵌式**：在现有 `AddInstanceScreen` 中添加配对状态区域，而非新路由。原因：
- 配对是测试连接的延续，不是独立页面
- 避免 navigation 参数传递（profile 未保存前没有 ID）
- 用户体验更流畅

## 文件变更

| 操作 | 路径 |
|------|------|
| 修改 | `feature/instance/src/main/java/com/openmate/feature/instance/AddInstanceScreen.kt` — 添加配对 UI 区域 |
| 修改 | `feature/instance/src/main/res/values/strings.xml` — 添加配对相关字符串 |

## UI 流程

```
[Name] [Address] [Port]
[Password 字段 → 改为可选标签 "Password (legacy, leave empty for new instances)"]

[Test Connection] 按钮

测试结果区域：
  - Testing → "Testing…"
  - Success → Bridge 连接成功信息
  - Error → 错误信息
  - PairingRequired → 新增：显示配对流程
    ├── "This Bridge requires pairing. A PIN has been sent to the Bridge terminal."
    ├── [6位 PIN 输入框]
    └── [Confirm Pairing] 按钮
  - PairingSuccess → 新增：显示配对成功 + token 已保存
    └── "Paired successfully! Token saved."

[Save] 按钮
```

## `TestResult` 扩展

在 `AddInstanceViewModel.kt` 中的 `TestResult` sealed interface 添加：

```kotlin
sealed interface TestResult {
    data object Testing : TestResult
    data class Success(val status: BridgeStatusResponse) : TestResult
    data class Error(val message: String) : TestResult
    data class PairingRequired(val status: BridgeStatusResponse) : TestResult
    data object PairingSuccess : TestResult
}
```

### PairingRequired vs 需要配对的检测

Bridge 的 `/status` 端点是**公开的**（不需要认证），所以测试连接时：
1. 调用 `bridgeStatus()` 成功 → 知道 Bridge 存在
2. 调用 `pairRequest()` → 触发 PIN 生成
3. 用户输入 PIN → 调用 `pairConfirm(pin)` → 获取 token

但问题：如何知道 Bridge **需要** 配对？

**方案**：当 Bridge 启用了认证时，非配对端点的请求会返回 401。但测试连接只调用 `/status`（公开端点），不会触发 401。

**改进方案**：Bridge 在 `/status` 响应中添加 `auth_enabled: bool` 字段：

```json
{
  "bridge": { "version": "1.0.0", "auth_enabled": true },
  "opencode": { "status": "running", "directory": "/home/user/project" }
}
```

在 `BridgeStatusResponse` DTO 中添加：

```kotlin
@Serializable
data class BridgeInfo(
    val version: String = "",
    val authEnabled: Boolean = false,  // 新增
)
```

这样 Android 端可以：
- `authEnabled == false` → 直接保存 profile，无需配对
- `authEnabled == true` → 触发配对流程

## `AddInstanceScreen.kt` 变更

在测试结果 `when` 分支中添加 `PairingRequired` 和 `PairingSuccess`：

```kotlin
when (testResult) {
    is TestResult.Testing -> Text(stringResource(R.string.testing), ...)
    is TestResult.Success -> { /* 现有代码 */ }
    is TestResult.Error -> Text((testResult as TestResult.Error).message, ...)
    is TestResult.PairingRequired -> {
        Column {
            Text(
                stringResource(R.string.pairing_required),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { viewModel.pin.value = it },
                label = { Text(stringResource(R.string.pairing_pin)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.confirmPairing() },
                modifier = Modifier.fillMaxWidth(),
                enabled = pin.value.length == 6,
            ) {
                Text(stringResource(R.string.confirm_pairing))
            }
        }
    }
    is TestResult.PairingSuccess -> {
        Text(
            stringResource(R.string.pairing_success),
            color = MaterialTheme.colorScheme.primary,
        )
    }
    null -> {}
}
```

需要在 Screen 顶层添加 `pin` 状态收集：

```kotlin
val pin by viewModel.pin.collectAsState()
```

## `strings.xml` 新增

```xml
<string name="pairing_required">This Bridge requires pairing. Enter the PIN shown on the Bridge terminal.</string>
<string name="pairing_pin">6-digit PIN</string>
<string name="confirm_pairing">Confirm Pairing</string>
<string name="pairing_success">Paired successfully!</string>
<string name="pairing_failed">Pairing failed: %1$s</string>
<string name="instance_password_legacy">Password (legacy, leave empty)</string>
```

将 `instance_password` 替换为 `instance_password_legacy`，表示密码字段是旧版功能。

## Save 按钮逻辑变更

当 `authEnabled` 时，Save 按钮应该在配对成功后才可用：

```kotlin
Button(
    onClick = { viewModel.save(onBack) },
    modifier = Modifier.fillMaxWidth(),
    enabled = name.isNotBlank() && address.isNotBlank() &&
        (testResult is TestResult.Success || testResult is TestResult.PairingSuccess),
) {
    Text(stringResource(R.string.save))
}
```
