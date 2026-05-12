# Model Variant Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让模型列表按实例唯一 ID 缓存、支持手动刷新，并在重进会话后恢复 variant 显示状态（包括“默认”）。

**Architecture:** 在 `SessionDetailViewModel` 中引入“按 `ServerProfile.id` 隔离的 provider 缓存 + 按实例隔离的 model variant 偏好”两层本地状态。UI 仍沿用 `SessionDetailScreen + ModelPickerSheet + VariantPickerSheet`，但打开模型对话框时优先读缓存，只有无缓存或用户手动刷新时才请求服务器。

**Tech Stack:** Kotlin, Jetpack Compose, StateFlow, SharedPreferences, kotlinx.serialization, existing `OpencodeApiClient`

---

### Task 1: Add failing ViewModel tests for cached providers and variant restoration

**Files:**
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`

- [ ] **Step 1: Write the failing test for cached providers being used before network**

```kotlin
@Test
fun loadProviders_usesCachedProvidersBeforeNetwork() = runTest(dispatcher) {
    appContext().getSharedPreferences("openmate_settings", Context.MODE_PRIVATE)
        .edit()
        .putString(
            "provider_cache_/workspace",
            Json.encodeToString(
                ProviderListDto(
                    all = listOf(
                        ProviderInfoDto(
                            id = "cached",
                            name = "Cached Provider",
                            models = mapOf(
                                "cached-model" to ModelInfoDto(
                                    id = "cached-model",
                                    providerID = "cached",
                                    name = "Cached Model",
                                    variants = mapOf("high" to buildJsonObject { }),
                                ),
                            ),
                        ),
                    ),
                    connected = listOf("cached"),
                    default = mapOf("cached" to "cached-model"),
                ),
            ),
        )
        .apply()

    val apiClient = FakeOpencodeApiClient()
    val viewModel = createViewModel(
        sessionMessageRepository = FakeSessionMessageRepository(
            recentWindow = listOf(message("m1", timeCreated = 1)),
            lastSeq = null,
        ),
        apiClient = apiClient.client,
    )
    viewModel.loadSession(SESSION_ID)
    waitUntil { viewModel.selectedModel.value != null || viewModel.sessionTitle.value.isNotBlank() }

    viewModel.loadProviders()

    waitUntil { viewModel.providers.value != null }

    assertThat(viewModel.providers.value!!.all.single().id).isEqualTo("cached")
    assertThat(apiClient.getProvidersCalls).isEqualTo(0)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests com.openmate.feature.session.SessionDetailViewModelTest.loadProviders_usesCachedProvidersBeforeNetwork --no-daemon`

Expected: FAIL because `loadProviders()` currently always requests remote data.

- [ ] **Step 3: Write the failing test for restoring explicit default variant state**

```kotlin
@Test
fun loadSession_restoresExplicitDefaultVariantSelection() = runTest(dispatcher) {
    appContext().getSharedPreferences("openmate_settings", Context.MODE_PRIVATE)
        .edit()
        .putString(
            "provider_cache_/workspace",
            Json.encodeToString(
                ProviderListDto(
                    all = listOf(
                        ProviderInfoDto(
                            id = "openai",
                            name = "OpenAI",
                            models = mapOf(
                                "gpt-5" to ModelInfoDto(
                                    id = "gpt-5",
                                    providerID = "openai",
                                    name = "gpt-5",
                                    variants = mapOf("high" to buildJsonObject { }),
                                ),
                            ),
                        ),
                    ),
                    connected = listOf("openai"),
                    default = mapOf("openai" to "gpt-5"),
                ),
            ),
        )
        .putString("variant_pref_/workspace/openai/gpt-5", "default")
        .apply()

    val sessionRepository = FakeSessionRepository(
        session = Session(
            id = SESSION_ID,
            title = "Session",
            directory = "/workspace",
            projectID = "project",
            createdAt = 1,
            updatedAt = 1,
            status = SessionStatus.IDLE,
            modelProviderID = "openai",
            modelID = "gpt-5",
            modelName = "gpt-5",
        ),
    )
    val viewModel = createViewModel(
        sessionMessageRepository = FakeSessionMessageRepository(
            recentWindow = listOf(message("m1", timeCreated = 1)),
            lastSeq = null,
        ),
        sessionRepository = sessionRepository,
    )

    viewModel.loadSession(SESSION_ID)
    waitUntil { viewModel.selectedModel.value?.modelID == "gpt-5" }

    assertThat(viewModel.availableVariants.value).containsExactly("high")
    assertThat(viewModel.selectedVariant.value).isNull()
    assertThat(viewModel.hasExplicitDefaultVariant.value).isTrue()
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests com.openmate.feature.session.SessionDetailViewModelTest.loadSession_restoresExplicitDefaultVariantSelection --no-daemon`

Expected: FAIL because explicit default variant state is not currently persisted/restored.

- [ ] **Step 5: Commit**

Do not commit yet unless the user explicitly asks.

### Task 2: Add instance-scoped provider cache and explicit default variant state

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`

- [ ] **Step 1: Add new persisted keys and state**

```kotlin
private val _hasExplicitDefaultVariant = MutableStateFlow(false)
val hasExplicitDefaultVariant: StateFlow<Boolean> = _hasExplicitDefaultVariant.asStateFlow()

private fun providerCacheKey(directory: String): String = "provider_cache_${directory}"
private fun variantPrefKey(directory: String, providerID: String, modelID: String): String =
    "variant_pref_${directory}/${providerID}/${modelID}"
```

- [ ] **Step 2: Add provider cache serialization helpers**

```kotlin
private fun loadCachedProviders(directory: String): ProviderListDto? {
    val raw = prefs.getString(providerCacheKey(directory), null) ?: return null
    return runCatching { Json.decodeFromString<ProviderListDto>(raw) }.getOrNull()
}

private fun saveCachedProviders(directory: String, providers: ProviderListDto) {
    prefs.edit().putString(providerCacheKey(directory), Json.encodeToString(providers)).apply()
}
```

- [ ] **Step 3: Add variant preference persistence helpers**

```kotlin
private fun saveVariantPreference(directory: String, providerID: String, modelID: String, variant: String?) {
    val stored = variant ?: "default"
    prefs.edit().putString(variantPrefKey(directory, providerID, modelID), stored).apply()
}

private fun restoreVariantPreference(directory: String, providerID: String, modelID: String) {
    when (val stored = prefs.getString(variantPrefKey(directory, providerID, modelID), null)) {
        null -> {
            _selectedVariant.value = null
            _hasExplicitDefaultVariant.value = false
        }
        "default" -> {
            _selectedVariant.value = null
            _hasExplicitDefaultVariant.value = true
        }
        else -> {
            _selectedVariant.value = stored
            _hasExplicitDefaultVariant.value = false
        }
    }
}
```

- [ ] **Step 4: Update `loadProviders(forceRefresh: Boolean = false)` to prefer cache**

```kotlin
fun loadProviders(forceRefresh: Boolean = false) {
    val directory = currentDirectory.ifBlank { return }
    if (!forceRefresh) {
        val cached = loadCachedProviders(directory)
        if (cached != null) {
            _providers.value = cached
            updateAvailableVariants()
            return
        }
    }
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val result = apiClient.getProviders()
            _providers.value = result
            saveCachedProviders(directory, result)
            updateAvailableVariants()
        } catch (e: Exception) {
            Log.e(TAG, "loadProviders FAILED", e)
        }
    }
}
```

- [ ] **Step 5: Update model/variant selection and restoration flow**

```kotlin
fun selectModel(providerID: String, modelID: String, modelName: String) {
    val ref = ModelRef(providerID, modelID, modelName)
    _selectedModel.value = ref
    updateAvailableVariants()
    restoreVariantPreference(currentDirectory, providerID, modelID)
    // keep recent model logic unchanged
}

fun selectVariant(variant: String?) {
    _selectedVariant.value = variant
    _hasExplicitDefaultVariant.value = variant == null
    _selectedModel.value?.let { model ->
        saveVariantPreference(currentDirectory, model.providerID, model.modelID, variant)
    }
}
```

- [ ] **Step 6: Update session load to restore cached providers and variant state early**

```kotlin
currentDirectory = session?.directory ?: ""
loadCachedProviders(currentDirectory)?.let {
    _providers.value = it
}
if (sPID != null && sMID != null && _selectedModel.value == null) {
    _selectedModel.value = ModelRef(sPID, sMID, session.modelName ?: sMID)
    updateAvailableVariants()
    restoreVariantPreference(currentDirectory, sPID, sMID)
}
```

- [ ] **Step 7: Run targeted tests**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests com.openmate.feature.session.SessionDetailViewModelTest --no-daemon`

Expected: PASS for the new caching/restoration tests.

- [ ] **Step 8: Commit**

Do not commit yet unless the user explicitly asks.

### Task 3: Add refresh button and cached-provider UX to model picker

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/component/ModelPickerSheet.kt`
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt`
- Modify: `android/feature/session/src/main/res/values/strings.xml`
- Modify: `android/feature/session/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: Extend `ModelPickerSheet` API to support refresh**

```kotlin
fun ModelPickerSheet(
    providers: ProviderListDto?,
    currentModel: SelectedModel?,
    recentModels: List<SelectedModel>,
    onSelect: (SelectedModel) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
)
```

- [ ] **Step 2: Add refresh button to the header row**

```kotlin
Row(
    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(
        text = stringResource(R.string.select_model),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    IconButton(onClick = onRefresh) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = stringResource(R.string.refresh),
        )
    }
}
```

- [ ] **Step 3: Update `SessionDetailScreen` to use cache-first loading and refresh callback**

```kotlin
IconButton(onClick = {
    viewModel.loadProviders()
    showModelPicker = true
}) { ... }

ModelPickerSheet(
    providers = providers,
    currentModel = ...,
    recentModels = ...,
    onSelect = { model -> ... },
    onRefresh = { viewModel.loadProviders(forceRefresh = true) },
    onDismiss = { showModelPicker = false },
)
```

- [ ] **Step 4: Make variant chip stay visible for explicit default after restore**

```kotlin
if (availableVariants.isNotEmpty()) {
    Text(
        text = when {
            selectedVariant != null -> selectedVariant!!
            hasExplicitDefaultVariant -> stringResource(R.string.variant_default)
            else -> stringResource(R.string.variant_default)
        },
        ...
    )
}
```

- [ ] **Step 5: Run compile verification**

Run: `./gradlew.bat :feature:session:compileDebugKotlin --no-daemon`

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

Do not commit yet unless the user explicitly asks.

### Task 4: End-to-end verification

**Files:**
- Modify: `待跟踪问题.md`

- [ ] **Step 1: Run ViewModel tests**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests com.openmate.feature.session.SessionDetailViewModelTest --no-daemon`

Expected: PASS

- [ ] **Step 2: Run network tests**

Run: `./gradlew.bat :core:network:testDebugUnitTest --tests com.openmate.core.network.OpencodeApiClientSendPromptTest --no-daemon`

Expected: PASS

- [ ] **Step 3: Build debug APK**

Run: `./gradlew.bat :app:assembleDebug --no-daemon`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Update tracking doc**

```markdown
- 已补实例级 provider 缓存，模型选择框优先使用缓存
- 已新增模型选择框刷新按钮，支持强制刷新并覆盖缓存
- 已补 variant 偏好持久化，默认 variant 重进会话后仍显示“默认”
```

- [ ] **Step 5: Commit**

Do not commit yet unless the user explicitly asks.

## Self-Review

- Spec coverage: 已覆盖实例级 provider 缓存、刷新按钮、默认 variant 恢复、缓存优先加载、强制刷新覆盖缓存。
- Placeholder scan: 无 TBD/TODO/“后续补充”类执行占位符。
- Type consistency: 计划统一使用 `ProviderListDto`、`ModelRef`、`selectedVariant`、`hasExplicitDefaultVariant`、`loadProviders(forceRefresh)` 这一组命名。
