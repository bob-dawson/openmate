package com.openmate.feature.session

import android.content.Context
import android.os.SystemClock
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import com.openmate.core.common.AppDispatchers
import com.openmate.core.data.sync.SyncDebugController
import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncLogEntry
import com.openmate.core.data.sync.SyncLogLevel
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.data.sync.SyncSseStarter
import com.openmate.core.network.SyncSseConnection
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.domain.model.ConnectionSnapshot
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.SessionMessageSyncEvent
import com.openmate.core.domain.model.SessionMessageSyncChange
import com.openmate.core.domain.model.SessionMessageSyncResult
import com.openmate.core.domain.model.SessionRetryStatus
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.domain.model.TodoInfo
import com.openmate.core.domain.model.Workspace
import com.openmate.core.domain.model.ServerProfile
import com.openmate.core.domain.repository.ConnectionRepository
import com.openmate.core.domain.repository.PermissionRepository
import com.openmate.core.domain.repository.QuestionRepository
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.domain.repository.TodoRepository
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.DatabaseFactory
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.ModelInfoDto
import com.openmate.core.network.dto.ProviderInfoDto
import com.openmate.core.network.dto.ProviderListDto
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionDetailViewModelTest {

    @get:Rule
    val instantTaskExecutorRule: TestRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        appContext().getSharedPreferences("openmate_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        appContext().getSharedPreferences("openmate_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @Test
    fun loadSession_initializesMessagesFromRecentWindow() = runTest(dispatcher) {
        val repository = FakeSessionMessageRepository(
            recentWindow = listOf(
                message("m1", timeCreated = 1),
                message("m2", timeCreated = 2),
            ),
            lastSeq = null,
        )
        val viewModel = createViewModel(sessionMessageRepository = repository)

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.map { it.id } == listOf("m1", "m2") }

        assertThat(viewModel.messages.value.map { it.id }).containsExactly("m1", "m2").inOrder()
        assertThat(repository.observeMessagesCalls).isEqualTo(0)
        assertThat(repository.getRecentWindowCalls).containsExactly(SESSION_ID to 30)
    }

    @Test
    fun refresh_appliesSyncChangesWithoutReobservingDatabase() = runTest(dispatcher) {
        val repository = FakeSessionMessageRepository(
            recentWindow = listOf(
                message("m1", timeCreated = 1, data = "old"),
                message("m2", timeCreated = 2),
            ),
            lastSeq = null,
            incrementalSyncResults = ArrayDeque(
                listOf(
                    SessionMessageSyncResult(
                        lastSeq = 2,
                        changes = listOf(
                            SessionMessageSyncChange.Update(message("m1", timeCreated = 1, data = "new")),
                            SessionMessageSyncChange.Insert(message("m3", timeCreated = 3)),
                        ),
                    ),
                ),
            ),
        )
        val viewModel = createViewModel(sessionMessageRepository = repository)

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.map { it.id } == listOf("m1", "m2") }
        repository.getRecentWindowCalls.clear()

        viewModel.refresh()
        waitUntil { viewModel.messages.value.map { it.id } == listOf("m1", "m2", "m3") }

        assertThat(viewModel.messages.value.map { it.id }).containsExactly("m1", "m2", "m3").inOrder()
        assertThat(viewModel.messages.value.first { it.id == "m1" }.data).isEqualTo("new")
        assertThat(repository.observeMessagesCalls).isEqualTo(0)
        assertThat(repository.getRecentWindowCalls).isEmpty()
    }

    @Test
    fun loadOlderMessages_prependsOlderPage() = runTest(dispatcher) {
        val recentWindow = (3L..32L).map { timeCreated ->
            message("m$timeCreated", timeCreated = timeCreated)
        }
        val repository = FakeSessionMessageRepository(
            recentWindow = recentWindow,
            lastSeq = null,
            olderPages = ArrayDeque(
                listOf(
                    listOf(
                        message("m1", timeCreated = 1),
                        message("m2", timeCreated = 2),
                    ),
                ),
            ),
        )
        val viewModel = createViewModel(sessionMessageRepository = repository)

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.map { it.id } == recentWindow.map { it.id } }

        viewModel.loadOlderMessages()
        waitUntil { viewModel.messages.value.map { it.id } == listOf("m1", "m2") + recentWindow.map { it.id } }

        assertThat(viewModel.messages.value.map { it.id }).containsExactlyElementsIn(listOf("m1", "m2") + recentWindow.map { it.id }).inOrder()
        assertThat(repository.getOlderPageCalls).containsExactly(OlderPageCall(SESSION_ID, 3, "m3", 30))
    }

    @Test
    fun loadOlderMessages_stopsAfterShortOlderPage() = runTest(dispatcher) {
        val recentWindow = (3L..32L).map { timeCreated ->
            message("m$timeCreated", timeCreated = timeCreated)
        }
        val repository = FakeSessionMessageRepository(
            recentWindow = recentWindow,
            lastSeq = null,
            olderPages = ArrayDeque(
                listOf(
                    listOf(
                        message("m1", timeCreated = 1),
                        message("m2", timeCreated = 2),
                    ),
                ),
            ),
        )
        val viewModel = createViewModel(sessionMessageRepository = repository)

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.map { it.id } == recentWindow.map { it.id } }

        viewModel.loadOlderMessages()
        waitUntil { viewModel.messages.value.map { it.id } == listOf("m1", "m2") + recentWindow.map { it.id } }
        viewModel.loadOlderMessages()
        advanceUntilIdle()

        assertThat(repository.getOlderPageCalls).containsExactly(OlderPageCall(SESSION_ID, 3, "m3", 30))
    }

    @Test
    fun loadOlderMessages_ignoresConcurrentRequests() = runTest(dispatcher) {
        val recentWindow = (3L..32L).map { timeCreated ->
            message("m$timeCreated", timeCreated = timeCreated)
        }
        val repository = FakeSessionMessageRepository(
            recentWindow = recentWindow,
            lastSeq = null,
            olderPageDelayMillis = 1_000,
            olderPages = ArrayDeque(
                listOf(
                    listOf(
                        message("m1", timeCreated = 1),
                        message("m2", timeCreated = 2),
                    ),
                ),
            ),
        )
        val viewModel = createViewModel(sessionMessageRepository = repository)

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.map { it.id } == recentWindow.map { it.id } }

        viewModel.loadOlderMessages()
        viewModel.loadOlderMessages()
        waitUntil { repository.getOlderPageCalls.size == 1 }

        assertThat(repository.getOlderPageCalls).containsExactly(OlderPageCall(SESSION_ID, 3, "m3", 30))
    }

    @Test
    fun loadSession_restoresRunningAnchorFromIncompleteMessageAge() = runTest(dispatcher) {
        val messageStartedAt = System.currentTimeMillis() - 125_000L
        val repository = FakeSessionMessageRepository(
            recentWindow = listOf(
                SessionMessage(
                    id = "m-running",
                    sessionId = SESSION_ID,
                    type = "assistant",
                    data = """
                        {
                          "content": [
                            {
                              "type": "text",
                              "text": "Working"
                            }
                          ]
                        }
                    """.trimIndent(),
                    timeCreated = messageStartedAt,
                    timeUpdated = messageStartedAt,
                    completedAt = null,
                ),
            ),
            lastSeq = null,
        )
        val viewModel = createViewModel(sessionMessageRepository = repository)

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.runningAnchors.value.containsKey("m-running") }

        val anchor = viewModel.runningAnchors.value.getValue("m-running")
        val restoredElapsed = SystemClock.elapsedRealtime() - anchor

        assertThat(viewModel.messages.value.single().completedAt).isNull()
        assertThat(viewModel.runningAnchors.value).containsKey("m-running")
        assertThat(restoredElapsed).isAtLeast(120_000L)
    }

    @Test
    fun backgroundSyncEvent_updatesCurrentWindowMessages() = runTest(dispatcher) {
        val repository = FakeSessionMessageRepository(
            recentWindow = listOf(
                message("m1", timeCreated = 1),
                message("m2", timeCreated = 2),
            ),
            lastSeq = null,
        )
        val viewModel = createViewModel(sessionMessageRepository = repository)

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.map { it.id } == listOf("m1", "m2") }

        repository.emitSyncEvent(
            SessionMessageSyncEvent(
                sessionId = SESSION_ID,
                result = SessionMessageSyncResult(
                    lastSeq = 3,
                    changes = listOf(
                        SessionMessageSyncChange.Insert(message("m3", timeCreated = 3)),
                    ),
                ),
            ),
        )

        waitUntil { viewModel.messages.value.map { it.id } == listOf("m1", "m2", "m3") }
    }

    @Test
    fun loadSession_initSyncFullPage_keepsOlderPagingEnabled() = runTest(dispatcher) {
        val syncedWindow = (1L..30L).map { timeCreated ->
            message("m$timeCreated", timeCreated = timeCreated)
        }
        val repository = FakeSessionMessageRepository(
            recentWindow = emptyList(),
            lastSeq = null,
            initSyncResult = SessionMessageSyncResult(
                lastSeq = 30,
                changes = syncedWindow.map { SessionMessageSyncChange.Insert(it) },
            ),
            olderPages = ArrayDeque(
                listOf(
                    listOf(message("m-1", timeCreated = 0)),
                ),
            ),
        )
        val viewModel = createViewModel(sessionMessageRepository = repository)

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.map { it.id } == syncedWindow.map { it.id } }

        viewModel.loadOlderMessages()
        waitUntil { viewModel.messages.value.firstOrNull()?.id == "m-1" }

        assertThat(repository.getOlderPageCalls).containsExactly(OlderPageCall(SESSION_ID, 1, "m1", 30))
    }

    @Test
    fun loadSession_exposesRetryStatusFromSessionRepository() = runTest(dispatcher) {
        val repository = FakeSessionMessageRepository(
            recentWindow = listOf(message("m1", timeCreated = 1)),
            lastSeq = null,
        )
        val sessionRepository = FakeSessionRepository(
            retryStatus = SessionRetryStatus(
                sessionId = SESSION_ID,
                attempt = 2,
                message = "Provider overloaded",
                next = System.currentTimeMillis() + 30_000,
            ),
        )
        val viewModel = createViewModel(
            sessionMessageRepository = repository,
            sessionRepository = sessionRepository,
        )

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.sessionRetryStatus.value?.message == "Provider overloaded" }

        assertThat(viewModel.sessionRetryStatus.value?.attempt).isEqualTo(2)
    }

    @Test
    fun syncLogActions_exposeLogsAndInvokeController() = runTest(dispatcher) {
        val repository = FakeSessionMessageRepository(
            recentWindow = listOf(message("m1", timeCreated = 1)),
            lastSeq = null,
        )
        val logStore = SyncLogStore()
        val sessionMessageRepository = FakeSessionMessageRepository()
        val controller = SyncDebugController(
            syncSseConnection = FakeSyncSseConnection(),
            syncSseStarter = FakeSyncSseStarter(),
            apiClient = FakeOpencodeApiClient().client,
            logStore = logStore,
            sessionMessageRepository = sessionMessageRepository,
            appDispatchers = AppDispatchers(io = dispatcher, main = dispatcher, default = dispatcher),
        )
        val viewModel = createViewModel(
            sessionMessageRepository = repository,
            syncDebugController = controller,
        )

        logStore.append(fakeLog("12:00:00.000 INFO [Sse] SSE连接成功 trace=sse-1 message=connected", id = 1L))
        logStore.append(fakeLog("12:00:01.000 INFO [Sync] 增量同步结束 session=$SESSION_ID trace=inc-1 message=done", id = 2L))
        advanceUntilIdle()

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.syncLogLines.value.size == 2 }

        assertThat(viewModel.syncLogLines.value.size).isEqualTo(2)

        viewModel.reconnectSyncSse()
        viewModel.triggerManualIncrementalSync()
        advanceUntilIdle()
        viewModel.clearSyncLogs()
        advanceUntilIdle()

        assertThat(sessionMessageRepository.incrementalSyncCalls).containsExactly(SESSION_ID)
        assertThat(viewModel.syncLogLines.value.last()).contains("日志已清除")
    }

    @Test
    fun copyVisibleSyncLogs_joinsOnlyFilteredVisibleRows() = runTest(dispatcher) {
        val viewModel = createViewModel(
            sessionMessageRepository = FakeSessionMessageRepository(),
        )

        viewModel.copyVisibleSyncLogsToClipboard(
            listOf(
                "12:00:01.000 ERROR [Sync] 增量同步失败 trace=inc-1 message=SocketTimeoutException",
            )
        )

        val clipboard = appContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertThat(clipboard.primaryClip?.getItemAt(0)?.text?.toString()).isEqualTo(
            "12:00:01.000 ERROR [Sync] 增量同步失败 trace=inc-1 message=SocketTimeoutException"
        )
    }

    @Test
    fun loadSession_updatesRetryStatusImmediatelyFromObservedRetryFlow() = runTest(dispatcher) {
        val repository = FakeSessionMessageRepository(
            recentWindow = listOf(message("m1", timeCreated = 1)),
            lastSeq = null,
        )
        val sessionRepository = FakeSessionRepository()
        val viewModel = createViewModel(
            sessionMessageRepository = repository,
            sessionRepository = sessionRepository,
        )

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.isNotEmpty() }

        sessionRepository.emitRetryStatus(
            SessionRetryStatus(
                sessionId = SESSION_ID,
                attempt = 3,
                message = "Provider overloaded",
                next = System.currentTimeMillis() + 15_000,
            )
        )

        waitUntil { viewModel.sessionRetryStatus.value?.attempt == 3 }

        assertThat(viewModel.sessionRetryStatus.value?.message).isEqualTo("Provider overloaded")
    }

    @Test
    fun compact_startsSummarizeWithoutWaitingForCompletionSync() = runTest(dispatcher) {
        val apiClient = FakeOpencodeApiClient()
        val repository = FakeSessionMessageRepository(
            recentWindow = listOf(message("m1", timeCreated = 1)),
            lastSeq = null,
        )
        val viewModel = createViewModel(
            sessionMessageRepository = repository,
            apiClient = apiClient.client,
        )

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.isNotEmpty() }
        viewModel.selectModel("openai", "gpt-5", "gpt-5")

        viewModel.compact(SESSION_ID)
        waitUntil { apiClient.summarizeCalls.isNotEmpty() }

        assertThat(apiClient.summarizeCalls).hasSize(1)
        val summarizeCall = apiClient.summarizeCalls.single()
        assertThat(summarizeCall.sessionId).isEqualTo(SESSION_ID)
        assertThat(summarizeCall.providerId).isEqualTo("openai")
        assertThat(summarizeCall.modelId).isEqualTo("gpt-5")
        assertThat(repository.incrementalSyncCalls).isEmpty()
        assertThat(repository.incrementalSyncAndNotifyCalls).isEmpty()
    }

    @Test
    fun compact_surfacesStartupFailure() = runTest(dispatcher) {
        val apiClient = FakeOpencodeApiClient().apply {
            summarizeErrors += IllegalStateException("network down")
        }
        val repository = FakeSessionMessageRepository(
            recentWindow = listOf(message("m1", timeCreated = 1)),
            lastSeq = null,
        )
        val viewModel = createViewModel(
            sessionMessageRepository = repository,
            apiClient = apiClient.client,
        )

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.isNotEmpty() }
        viewModel.selectModel("openai", "gpt-5", "gpt-5")

        viewModel.compact(SESSION_ID)
        waitUntil { viewModel.errorMessage.value != null }

        assertThat(viewModel.errorMessage.value).contains("network down")
    }

    @Test
    fun selectModel_updatesAvailableVariants_and_selectVariant_changesSelection() = runTest(dispatcher) {
        val viewModel = createViewModel(
            sessionMessageRepository = FakeSessionMessageRepository(
                recentWindow = listOf(message("m1", timeCreated = 1)),
                lastSeq = null,
            ),
        )

        viewModel.loadProviders()
        waitUntil { viewModel.providers.value != null }

        viewModel.selectModel("openai", "gpt-5", "gpt-5")

        assertThat(viewModel.availableVariants.value).containsExactly("medium", "high")
        assertThat(viewModel.selectedVariant.value).isNull()

        viewModel.selectVariant("high")

        assertThat(viewModel.selectedVariant.value).isEqualTo("high")

        viewModel.selectModel("openai", "gpt-4o-mini", "gpt-4o-mini")

        assertThat(viewModel.availableVariants.value).isEmpty()
        assertThat(viewModel.selectedVariant.value).isNull()
    }

    @Test
    fun sendMessage_passesSelectedVariantToApiClient() = runTest(dispatcher) {
        val apiClient = FakeOpencodeApiClient()
        val repository = FakeSessionMessageRepository(
            recentWindow = listOf(message("m1", timeCreated = 1)),
            lastSeq = null,
            incrementalSyncResults = ArrayDeque(listOf(SessionMessageSyncResult(lastSeq = 1, changes = emptyList()))),
        )
        val viewModel = createViewModel(
            sessionMessageRepository = repository,
            apiClient = apiClient.client,
        )

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.isNotEmpty() }
        viewModel.loadProviders()
        waitUntil { viewModel.providers.value != null }
        viewModel.selectModel("openai", "gpt-5", "gpt-5")
        viewModel.selectVariant("high")
        viewModel.updateInput("hello")

        viewModel.sendMessage(SESSION_ID)
        waitUntil { apiClient.promptCalls.isNotEmpty() }

        assertThat(apiClient.promptCalls.single().variant).isEqualTo("high")
    }

    @Test
    fun loadProviders_andVariantPreference_areScopedByProfileId_notDirectoryOrName() = runTest(dispatcher) {
        val prefs = appContext().getSharedPreferences("openmate_settings", Context.MODE_PRIVATE)
        val profileOneProviders = ProviderListDto(
            all = listOf(
                ProviderInfoDto(
                    id = "openai",
                    name = "OpenAI",
                    models = mapOf(
                        "gpt-5" to ModelInfoDto(
                            id = "gpt-5",
                            providerID = "openai",
                            name = "gpt-5 profile 1",
                            variants = mapOf("medium" to buildJsonObject { }),
                        ),
                    ),
                ),
            ),
            connected = listOf("openai"),
            default = mapOf("openai" to "gpt-5"),
        )
        val profileTwoProviders = ProviderListDto(
            all = listOf(
                ProviderInfoDto(
                    id = "openai",
                    name = "OpenAI",
                    models = mapOf(
                        "gpt-5" to ModelInfoDto(
                            id = "gpt-5",
                            providerID = "openai",
                            name = "gpt-5 profile 2",
                            variants = mapOf("high" to buildJsonObject { }),
                        ),
                    ),
                ),
            ),
            connected = listOf("openai"),
            default = mapOf("openai" to "gpt-5"),
        )
        prefs.edit()
            .putString("provider_cache::profile-1", Json.encodeToString(profileOneProviders))
            .putString("provider_cache::profile-2", Json.encodeToString(profileTwoProviders))
            .putString("variant_pref::profile-1::openai::gpt-5", "medium")
            .putString("variant_pref::profile-2::openai::gpt-5", "default")
            .apply()

        val profileOneDbProvider = ActiveDatabaseProvider(DatabaseFactory(appContext())).apply {
            setActive("profile-1")
        }
        val profileOneViewModel = createViewModel(
            sessionMessageRepository = FakeSessionMessageRepository(
                recentWindow = listOf(message("m1", timeCreated = 1)),
                lastSeq = null,
            ),
            dbProvider = profileOneDbProvider,
        )

        profileOneViewModel.loadProviders()
        waitUntil { profileOneViewModel.providers.value != null }
        profileOneViewModel.selectModel("openai", "gpt-5", "gpt-5")

        assertThat(profileOneViewModel.providers.value?.all?.single()?.models?.get("gpt-5")?.name)
            .isEqualTo("gpt-5 profile 1")
        assertThat(profileOneViewModel.selectedVariant.value).isEqualTo("medium")

        val profileTwoDbProvider = ActiveDatabaseProvider(DatabaseFactory(appContext())).apply {
            setActive("profile-2")
        }
        val profileTwoViewModel = createViewModel(
            sessionMessageRepository = FakeSessionMessageRepository(
                recentWindow = listOf(message("m1", timeCreated = 1)),
                lastSeq = null,
            ),
            dbProvider = profileTwoDbProvider,
        )

        profileTwoViewModel.loadProviders()
        waitUntil { profileTwoViewModel.providers.value != null }
        profileTwoViewModel.selectModel("openai", "gpt-5", "gpt-5")

        assertThat(profileTwoViewModel.providers.value?.all?.single()?.models?.get("gpt-5")?.name)
            .isEqualTo("gpt-5 profile 2")
        assertThat(profileTwoViewModel.selectedVariant.value).isNull()
        assertThat(profileTwoViewModel.hasExplicitDefaultVariant.value).isTrue()

        profileOneDbProvider.clearActive()
        profileTwoDbProvider.clearActive()
    }

    @Test
    fun openFilePreview_resolvesRelativePathAgainstCurrentDirectory() = runTest(dispatcher) {
        val apiClient = FakeOpencodeApiClient()
        val viewModel = createViewModel(
            sessionMessageRepository = FakeSessionMessageRepository(
                recentWindow = listOf(message("m1", timeCreated = 1)),
                lastSeq = null,
            ),
            apiClient = apiClient.client,
        )

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.isNotEmpty() }

        viewModel.openFilePreview("src/Main.kt")
        waitUntil { apiClient.readFileCalls.isNotEmpty() }

        assertThat(apiClient.readFileCalls.single()).isEqualTo("/workspace/src/Main.kt")
    }

    @Test
    fun openFilePreview_keepsWindowsAbsolutePathWithoutPrefixingCurrentDirectory() = runTest(dispatcher) {
        val apiClient = FakeOpencodeApiClient()
        val viewModel = createViewModel(
            sessionMessageRepository = FakeSessionMessageRepository(
                recentWindow = listOf(message("m1", timeCreated = 1)),
                lastSeq = null,
            ),
            apiClient = apiClient.client,
        )

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.isNotEmpty() }

        viewModel.openFilePreview("D:/openmate/AGENTS.md")
        waitUntil { apiClient.readFileCalls.isNotEmpty() }

        assertThat(apiClient.readFileCalls.single()).isEqualTo("D:/openmate/AGENTS.md")
    }

    @Test
    fun openFilePreview_trimsWindowsAbsolutePathBeforeCheckingWorkspacePrefix() = runTest(dispatcher) {
        val apiClient = FakeOpencodeApiClient()
        val viewModel = createViewModel(
            sessionMessageRepository = FakeSessionMessageRepository(
                recentWindow = listOf(message("m1", timeCreated = 1)),
                lastSeq = null,
            ),
            apiClient = apiClient.client,
        )

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.isNotEmpty() }

        viewModel.openFilePreview("  D:\\openmate\\AGENTS.md  ")
        waitUntil { apiClient.readFileCalls.isNotEmpty() }

        assertThat(apiClient.readFileCalls.single()).isEqualTo("D:/openmate/AGENTS.md")
    }

    @Test
    fun connectionStatus_usesConnectionRepositoryStatus() = runTest(dispatcher) {
        val viewModel = createViewModel(
            sessionMessageRepository = FakeSessionMessageRepository(),
            connectionRepository = FakeConnectionRepository(ConnectionStatus.GATEWAY_CONNECTED),
            sseEventRepository = FakeSseEventRepository(status = ConnectionStatus.CONNECTED),
        )

        advanceUntilIdle()

        assertThat(viewModel.connectionStatus.value).isEqualTo(ConnectionStatus.GATEWAY_CONNECTED)
    }

    @Test
    fun abort_stopsBusyTimerImmediatelyBeforeSyncCatchesUp() = runTest(dispatcher) {
        val repository = FakeSessionMessageRepository(
            recentWindow = listOf(
                SessionMessage(
                    id = "u1",
                    sessionId = SESSION_ID,
                    type = "user",
                    data = "{}",
                    timeCreated = 500L,
                    timeUpdated = 500L,
                    roundMark = true,
                ),
                SessionMessage(
                    id = "m-running",
                    sessionId = SESSION_ID,
                    type = "assistant",
                    data = """
                        {
                          "content": [
                            {
                              "type": "text",
                              "text": "Working"
                            }
                          ]
                        }
                    """.trimIndent(),
                    timeCreated = 1_000L,
                    timeUpdated = 1_000L,
                    completedAt = null,
                    roundMark = false,
                ),
            ),
            lastSeq = null,
            incrementalSyncResults = ArrayDeque(
                listOf(
                    SessionMessageSyncResult(lastSeq = 1, changes = emptyList()),
                    SessionMessageSyncResult(lastSeq = 1, changes = emptyList()),
                ),
            ),
        )
        val sessionRepository = FakeSessionRepository()
        val viewModel = createViewModel(
            sessionMessageRepository = repository,
            sessionRepository = sessionRepository,
        )

        viewModel.loadSession(SESSION_ID)
        advanceUntilIdle()
        waitUntil { viewModel.currentBusyStart.value != null }

        viewModel.abort(SESSION_ID)
        waitUntil { sessionRepository.abortCalls == 1 }
        waitUntil { viewModel.currentBusyStart.value == null }

        assertThat(sessionRepository.abortCalls).isEqualTo(1)
        assertThat(viewModel.currentBusyStart.value).isNull()
        assertThat(viewModel.runningAnchors.value).isEmpty()
        assertThat(viewModel.sessionStatus.value).isEqualTo(SessionStatus.IDLE.name)
        assertThat(viewModel.messages.value.first { it.id == "m-running" }.completedAt).isNull()
    }

    @Test
    fun abort_stopsBusyTimerWhenNoAssistantMessageYet() = runTest(dispatcher) {
        val repository = FakeSessionMessageRepository(
            recentWindow = listOf(
                SessionMessage(
                    id = "u1",
                    sessionId = SESSION_ID,
                    type = "user",
                    data = "{}",
                    timeCreated = 500L,
                    timeUpdated = 500L,
                    roundMark = true,
                ),
            ),
            lastSeq = null,
            incrementalSyncResults = ArrayDeque(
                listOf(
                    SessionMessageSyncResult(lastSeq = 1, changes = emptyList()),
                    SessionMessageSyncResult(lastSeq = 1, changes = emptyList()),
                    SessionMessageSyncResult(lastSeq = 1, changes = emptyList()),
                ),
            ),
        )
        val sessionRepository = FakeSessionRepository()
        val viewModel = createViewModel(
            sessionMessageRepository = repository,
            sessionRepository = sessionRepository,
        )

        viewModel.loadSession(SESSION_ID)
        advanceUntilIdle()

        assertThat(viewModel.currentBusyStart.value).isEqualTo(500L)
        assertThat(viewModel.sessionStatus.value).isEqualTo(SessionStatus.BUSY.name)

        viewModel.abort(SESSION_ID)
        waitUntil { sessionRepository.abortCalls == 1 }
        waitUntil { viewModel.currentBusyStart.value == null }

        assertThat(viewModel.currentBusyStart.value).isNull()
        assertThat(viewModel.sessionStatus.value).isEqualTo(SessionStatus.IDLE.name)
    }

    @Test
    fun loadSession_restoresRunningAnchorFromIncompleteCompactionMessageAge() = runTest(dispatcher) {
        val messageStartedAt = System.currentTimeMillis() - 125_000L
        val repository = FakeSessionMessageRepository(
            recentWindow = listOf(
                SessionMessage(
                    id = "compaction-running",
                    sessionId = SESSION_ID,
                    type = "compaction",
                    data = """
                        {
                          "summary": "",
                          "time": {
                            "created": $messageStartedAt
                          }
                        }
                    """.trimIndent(),
                    timeCreated = messageStartedAt,
                    timeUpdated = messageStartedAt,
                    completedAt = null,
                    roundMark = true,
                ),
            ),
            lastSeq = null,
        )
        val viewModel = createViewModel(sessionMessageRepository = repository)

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.runningAnchors.value.containsKey("compaction-running") }

        val anchor = viewModel.runningAnchors.value.getValue("compaction-running")
        val restoredElapsed = SystemClock.elapsedRealtime() - anchor

        assertThat(viewModel.messages.value.single().completedAt).isNull()
        assertThat(viewModel.runningAnchors.value).containsKey("compaction-running")
        assertThat(restoredElapsed).isAtLeast(120_000L)
    }

    @Test
    fun concurrentSyncEventAndLoadOlder_keepsBothUpdates() = runTest(dispatcher) {
        val recentWindow = (3L..32L).map { timeCreated ->
            message("m$timeCreated", timeCreated = timeCreated)
        }
        val repository = FakeSessionMessageRepository(
            recentWindow = recentWindow,
            lastSeq = null,
            olderPageDelayMillis = 100,
            olderPages = ArrayDeque(
                listOf(
                    listOf(
                        message("m1", timeCreated = 1),
                        message("m2", timeCreated = 2),
                    ),
                ),
            ),
        )
        val viewModel = createViewModel(sessionMessageRepository = repository)

        viewModel.loadSession(SESSION_ID)
        waitUntil { viewModel.messages.value.map { it.id } == recentWindow.map { it.id } }

        viewModel.loadOlderMessages()
        repository.emitSyncEvent(
            SessionMessageSyncEvent(
                sessionId = SESSION_ID,
                result = SessionMessageSyncResult(
                    lastSeq = 33,
                    changes = listOf(SessionMessageSyncChange.Insert(message("m33", timeCreated = 33))),
                ),
            ),
        )

        waitUntil {
            val ids = viewModel.messages.value.map { it.id }
            ids.containsAll(listOf("m1", "m2", "m33"))
        }

        assertThat(viewModel.messages.value.map { it.id })
            .containsExactlyElementsIn(listOf("m1", "m2") + recentWindow.map { it.id } + listOf("m33"))
            .inOrder()
    }

    private fun createViewModel(
        sessionMessageRepository: FakeSessionMessageRepository,
        sessionRepository: SessionRepository = FakeSessionRepository(),
        todoRepository: TodoRepository = FakeTodoRepository(),
        questionRepository: QuestionRepository = FakeQuestionRepository(),
        permissionRepository: PermissionRepository = FakePermissionRepository(),
        sseEventRepository: SseEventRepository = FakeSseEventRepository(),
        connectionRepository: ConnectionRepository = FakeConnectionRepository(ConnectionStatus.DISCONNECTED),
        syncDebugController: SyncDebugController = SyncDebugController(
            syncSseConnection = FakeSyncSseConnection(),
            syncSseStarter = FakeSyncSseStarter(),
            apiClient = FakeOpencodeApiClient().client,
            logStore = SyncLogStore(),
            sessionMessageRepository = FakeSessionMessageRepository(),
            appDispatchers = AppDispatchers(io = dispatcher, main = dispatcher, default = dispatcher),
        ),
        apiClient: OpencodeApiClient = FakeOpencodeApiClient().client,
        dbProvider: ActiveDatabaseProvider = ActiveDatabaseProvider(DatabaseFactory(appContext())).apply {
            setActive("profile-default")
        },
    ): SessionDetailViewModel {
        appContext().getSharedPreferences("openmate_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("recent_models", "provider::model::Model")
            .apply()

        return SessionDetailViewModel(
            appContext = appContext(),
            sessionRepository = sessionRepository,
            sessionMessageRepository = sessionMessageRepository,
            todoRepository = todoRepository,
            questionRepository = questionRepository,
            permissionRepository = permissionRepository,
            sseEventRepository = sseEventRepository,
            connectionRepository = connectionRepository,
            syncDebugController = syncDebugController,
            dbProvider = dbProvider,
            syncSseStarter = FakeSyncSseStarter(),
            apiClient = apiClient,
            bridgeFileOpener = BridgeFileOpener(
                appContext = appContext(),
                apiClient = apiClient,
            ),
        )
    }

    private fun appContext() = RuntimeEnvironment.getApplication()

    private fun waitUntil(timeoutMillis: Long = 2.seconds.inWholeMilliseconds, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertThat(condition()).isTrue()
    }

    private data class OlderPageCall(
        val sessionId: String,
        val beforeTimeCreated: Long,
        val beforeId: String,
        val limit: Int,
    )

    private class FakeSessionMessageRepository(
        recentWindow: List<SessionMessage> = emptyList(),
        private val lastSeq: Long? = null,
        private val initSyncResult: SessionMessageSyncResult = SessionMessageSyncResult(0, emptyList()),
        private val incrementalSyncResults: ArrayDeque<SessionMessageSyncResult> = ArrayDeque(),
        private val olderPageDelayMillis: Long = 0,
        private val olderPages: ArrayDeque<List<SessionMessage>> = ArrayDeque(),
    ) : SessionMessageRepository {
        private val observedMessages = MutableStateFlow(emptyList<SessionMessage>())
        private val syncEvents = MutableSharedFlow<SessionMessageSyncEvent>(extraBufferCapacity = 16)
        private var recentWindowMessages = recentWindow
        private var syncedMessages = recentWindow

        var observeMessagesCalls = 0
        val getRecentWindowCalls = mutableListOf<Pair<String, Int>>()
        val getOlderPageCalls = mutableListOf<OlderPageCall>()
        val incrementalSyncCalls = mutableListOf<String>()
        val incrementalSyncAndNotifyCalls = mutableListOf<String>()

        override fun observeMessages(sessionId: String): Flow<List<SessionMessage>> {
            observeMessagesCalls += 1
            return observedMessages
        }

        override fun observeSyncEvents(): Flow<SessionMessageSyncEvent> = syncEvents

        override suspend fun getRecentWindow(sessionId: String, limit: Int): List<SessionMessage> {
            getRecentWindowCalls += sessionId to limit
            return recentWindowMessages
        }

        override suspend fun getOlderPage(
            sessionId: String,
            beforeTimeCreated: Long,
            beforeId: String,
            limit: Int,
        ): List<SessionMessage> {
            getOlderPageCalls += OlderPageCall(sessionId, beforeTimeCreated, beforeId, limit)
            if (olderPageDelayMillis > 0) {
                delay(olderPageDelayMillis)
            }
            return if (olderPages.isEmpty()) emptyList() else olderPages.removeFirst()
        }

        override suspend fun getOlderPageByUserTurns(
            sessionId: String,
            beforeTimeCreated: Long,
            beforeId: String,
            userTurns: Int,
        ): List<SessionMessage> = emptyList()

        override suspend fun findBusyStartTime(sessionId: String): Long? = null

        override suspend fun initSync(sessionId: String, limit: Int): SessionMessageSyncResult = initSyncResult

        override suspend fun incrementalSync(sessionId: String) {
            incrementalSyncCalls += sessionId
            val result = if (incrementalSyncResults.isEmpty()) {
                SessionMessageSyncResult(lastSeq = lastSeq ?: 0, changes = emptyList())
            } else {
                incrementalSyncResults.removeFirst()
            }
            applyChanges(result.changes)
            syncEvents.emit(SessionMessageSyncEvent(sessionId = sessionId, result = result))
        }

        override suspend fun incrementalSyncAndNotify(sessionId: String) {
            incrementalSyncAndNotifyCalls += sessionId
            incrementalSync(sessionId)
        }

        override suspend fun fetchFullMessage(sessionId: String, messageId: String) = Unit

        override suspend fun getLastSeq(sessionId: String): Long? = lastSeq

        override suspend fun rollbackSeq(sessionId: String, count: Long) = Unit

        override suspend fun deleteMessage(sessionId: String, messageId: String) = Unit

        fun emitSyncEvent(event: SessionMessageSyncEvent) {
            syncEvents.tryEmit(event)
        }

        private fun applyChanges(changes: List<SessionMessageSyncChange>) {
            var updated = syncedMessages
            for (change in changes) {
                updated = when (change) {
                    is SessionMessageSyncChange.Insert -> {
                        (updated + change.message).sortedBy(SessionMessage::timeCreated)
                    }
                    is SessionMessageSyncChange.Update -> {
                        updated.map { existing ->
                            if (existing.id == change.message.id) change.message else existing
                        }
                    }
                    is SessionMessageSyncChange.Remove -> {
                        updated.filterNot { existing -> existing.id == change.messageId }
                    }
                }
            }
            syncedMessages = updated
            observedMessages.value = updated
        }
    }

    private class FakeOpencodeApiClient {
        data class PromptCall(
            val sessionId: String,
            val variant: String?,
        )

        data class SummarizeCall(
            val sessionId: String,
            val providerId: String,
            val modelId: String,
            val directory: String?,
        )

        val summarizeErrors = ArrayDeque<Exception>()
        val summarizeCalls = mutableListOf<SummarizeCall>()
        val promptCalls = mutableListOf<PromptCall>()
        val readFileCalls = mutableListOf<String>()

        val client: OpencodeApiClient = OpencodeApiClient(
            client = OkHttpClient.Builder()
                .callTimeout(10, TimeUnit.MILLISECONDS)
                .addInterceptor { chain ->
                    val request = chain.request()
                    if (request.url.encodedPath == "/provider") {
                        val providersJson = Json.encodeToString(
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
                                                variants = mapOf(
                                                    "medium" to buildJsonObject { },
                                                    "high" to buildJsonObject { },
                                                ),
                                            ),
                                            "gpt-4o-mini" to ModelInfoDto(
                                                id = "gpt-4o-mini",
                                                providerID = "openai",
                                                name = "gpt-4o-mini",
                                                variants = null,
                                            ),
                                        ),
                                    ),
                                ),
                                connected = listOf("openai"),
                                default = mapOf("openai" to "gpt-5"),
                            ),
                        )
                        return@addInterceptor Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(providersJson.toResponseBody())
                            .build()
                    }
                    if (request.url.encodedPath.contains("/summarize")) {
                        if (summarizeErrors.isNotEmpty()) {
                            throw summarizeErrors.removeFirst()
                        }
                        val sessionId = request.url.pathSegments.getOrNull(1).orEmpty()
                        val bodyText = Buffer().also { buffer ->
                            request.body?.writeTo(buffer)
                        }.readUtf8()
                        val body = Json.parseToJsonElement(bodyText).jsonObject
                        summarizeCalls += SummarizeCall(
                            sessionId = sessionId,
                            providerId = body["providerID"]!!.jsonPrimitive.content,
                            modelId = body["modelID"]!!.jsonPrimitive.content,
                            directory = request.url.queryParameter("directory"),
                        )
                    } else if (request.url.encodedPath.contains("/prompt_async")) {
                        val sessionId = request.url.pathSegments.getOrNull(1).orEmpty()
                        val bodyText = Buffer().also { buffer ->
                            request.body?.writeTo(buffer)
                        }.readUtf8()
                        val body = Json.parseToJsonElement(bodyText).jsonObject
                        promptCalls += PromptCall(
                            sessionId = sessionId,
                            variant = body["variant"]?.jsonPrimitive?.contentOrNull,
                        )
                    } else if (request.url.encodedPath == "/api/bridge/fs/read") {
                        val path = request.url.queryParameter("path").orEmpty()
                        readFileCalls += path
                        return@addInterceptor Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .header("content-type", "text/plain")
                            .body("preview".toResponseBody())
                            .build()
                    }

                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(204)
                        .message("No Content")
                        .body("".toResponseBody())
                        .build()
                }
                .build(),
            baseUrl = "http://test",
        )
    }

    private class FakeSessionRepository(
        private val retryStatus: SessionRetryStatus? = null,
    ) : SessionRepository {
        private val retryStatusFlow = MutableStateFlow(retryStatus)
        var abortCalls = 0

        private val session = Session(
            id = SESSION_ID,
            title = "Session",
            directory = "/workspace",
            projectID = "project",
            createdAt = 1,
            updatedAt = 1,
            status = SessionStatus.IDLE,
        )

        override suspend fun getSessions(directory: String?, limit: Int?, start: Long?): List<Session> = listOf(session)

        override suspend fun getSession(id: String): Session? = session

        override suspend fun createSession(title: String?, directory: String?): Session = session

        override suspend fun deleteSession(id: String) = Unit

        override suspend fun updateSession(id: String, title: String?) = Unit

        override suspend fun abortSession(id: String, directory: String?) {
            abortCalls += 1
        }

        override suspend fun refreshSessionStatuses(directory: String?) = Unit

        override suspend fun syncSessionStatusFromRemote(sessionID: String) = Unit

        override fun observeSessions(directory: String?): Flow<List<Session>> = flowOf(listOf(session))

        override fun observeSession(id: String): Flow<Session?> = flowOf(session)

        override fun observeSessionRetryStatus(id: String): Flow<SessionRetryStatus?> = retryStatusFlow

        override fun observeWorkspaces(): Flow<List<Workspace>> = flowOf(emptyList())

        override suspend fun addSessionDuration(id: String, increment: Long) = Unit

        override suspend fun updateSessionModel(id: String, providerID: String?, modelID: String?, modelName: String?) = Unit

        override suspend fun updateSessionStatus(id: String, status: String) = Unit

        override suspend fun getSessionRetryStatus(id: String): SessionRetryStatus? = retryStatusFlow.value

        override suspend fun revertSession(sessionID: String, messageID: String, partID: String?, directory: String?) = Unit

        override suspend fun unrevertSession(sessionID: String, directory: String?) = Unit

        override suspend fun resolveMessageID(sessionID: String, timeCreated: Long): String? = null

        override suspend fun resolveEvtID(sessionID: String, messageID: String): String? = null

        fun emitRetryStatus(status: SessionRetryStatus?) {
            retryStatusFlow.value = status
        }
    }

    private class FakeTodoRepository : TodoRepository {
        override fun observeTodos(sessionID: String): Flow<List<TodoInfo>> = flowOf(emptyList())

        override suspend fun refreshTodos(sessionID: String) = Unit
    }

    private class FakeQuestionRepository : QuestionRepository {
        override suspend fun refresh(directory: String) = Unit

        override suspend fun reply(requestID: String, answers: List<List<String>>, directory: String?) = Unit

        override suspend fun reject(requestID: String, directory: String?) = Unit

        override fun observePending(): Flow<List<QuestionRequest>> = flowOf(emptyList())
    }

    private class FakePermissionRepository : PermissionRepository {
        override suspend fun refresh(directory: String) = Unit

        override suspend fun reply(requestID: String, reply: PermissionReply, message: String?, directory: String?) = Unit

        override fun observePending(): Flow<List<PermissionRequest>> = flowOf(emptyList())
    }

    private class FakeSseEventRepository(
        private val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    ) : SseEventRepository {
        override fun connect(address: String, port: Int, password: String?) = emptyFlow<com.openmate.core.domain.model.SseEvent>()
        override fun connectViaGateway(baseUrl: String) = emptyFlow<com.openmate.core.domain.model.SseEvent>()
        override fun disconnect() = Unit
        override fun observeConnectionStatus() = flowOf(status)
        override fun isConnectedTo(address: String, port: Int): Boolean = false
        override fun setActiveSessionScope(directory: String?, enabled: Boolean) = Unit
        override fun observeMessageSyncNeeded() = emptyFlow<String>()
        override fun observeSessionErrors() = emptyFlow<Pair<String, String>>()
    }

    private class FakeConnectionRepository(
        status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    ) : ConnectionRepository {
        override val connectionStatus = MutableStateFlow(status).asStateFlow()
        override val connectionSnapshot = MutableStateFlow<ConnectionSnapshot?>(null).asStateFlow()
        override val activeProfile = MutableStateFlow<ServerProfile?>(null).asStateFlow()
        override val isConnected = MutableStateFlow(false).asStateFlow()
        override val errorMessage = MutableStateFlow<String?>(null).asStateFlow()
        override val needsRepairing = MutableStateFlow<String?>(null).asStateFlow()

        override fun connect(profile: ServerProfile) = Unit
        override fun reconnect() = Unit
        override fun disconnect() = Unit
        override fun confirmRepairing(profileId: String, token: String) = Unit
        override fun clearNeedsRepairing() = Unit
        override fun clearError() = Unit
    }

    private fun message(id: String, timeCreated: Long, data: String = "{}") =
        SessionMessage(
            id = id,
            sessionId = SESSION_ID,
            type = "assistant",
            data = data,
            timeCreated = timeCreated,
            timeUpdated = timeCreated,
            completedAt = timeCreated,
        )

    private fun fakeLog(text: String, id: Long): SyncLogEntry =
        SyncLogEntry(
            id = id,
            timestamp = id,
            level = when {
                text.contains("ERROR") -> SyncLogLevel.Error
                text.contains("WARN") -> SyncLogLevel.Warn
                else -> SyncLogLevel.Info
            },
            category = when {
                text.contains("[Sse]") -> SyncLogCategory.Sse
                text.contains("[Manual]") -> SyncLogCategory.Manual
                text.contains("[Poll]") -> SyncLogCategory.Poll
                else -> SyncLogCategory.Sync
            },
            sessionId = text.substringAfter("session=", "").substringBefore(' ').ifBlank { null },
            message = text.substringAfter("] ", ""),
        )

    private class FakeSyncSseConnection : SyncSseConnection {
        override val currentBaseUrl: String? = "http://test"

        override suspend fun connect(baseUrl: String, forceRestart: Boolean) = Unit

        override fun disconnect(traceId: String?) = Unit
    }

    private class FakeSyncSseStarter : SyncSseStarter {
        override fun start() = Unit
        override fun setActiveSession(sessionId: String?) = Unit
    }

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
