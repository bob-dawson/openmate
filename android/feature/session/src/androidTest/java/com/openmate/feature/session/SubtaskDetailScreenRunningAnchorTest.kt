package com.openmate.feature.session

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.QuestionRequest
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.SessionMessageSyncEvent
import com.openmate.core.domain.model.SessionMessageSyncResult
import com.openmate.core.domain.model.SessionRetryStatus
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.domain.model.TodoInfo
import com.openmate.core.domain.model.Workspace
import com.openmate.core.domain.repository.PermissionRepository
import com.openmate.core.domain.repository.QuestionRepository
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.domain.repository.SessionRepository
import com.openmate.core.domain.repository.SseEventRepository
import com.openmate.core.domain.repository.TodoRepository
import com.openmate.core.network.OpencodeApiClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test

class SubtaskDetailScreenRunningAnchorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun subtaskScreen_displaysRestoredRunningDuration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("openmate_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("recent_models", "provider::model::Model")
            .apply()

        val startedAt = System.currentTimeMillis() - 125_000L
        val sessionId = "session-subtask"
        val messageRepository = FakeSessionMessageRepository(
            recentWindow = listOf(
                SessionMessage(
                    id = "m-running",
                    sessionId = sessionId,
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
                    timeCreated = startedAt,
                    timeUpdated = startedAt,
                    completedAt = null,
                ),
            ),
        )
        val viewModel = SessionDetailViewModel(
            appContext = context,
            sessionRepository = FakeSessionRepository(sessionId),
            sessionMessageRepository = messageRepository,
            todoRepository = FakeTodoRepository(),
            questionRepository = FakeQuestionRepository(),
            permissionRepository = FakePermissionRepository(),
            sseEventRepository = FakeSseEventRepository(),
            apiClient = OpencodeApiClient(
                client = OkHttpClient.Builder()
                    .callTimeout(10, TimeUnit.MILLISECONDS)
                    .build(),
            ),
        )

        composeRule.setContent {
            MaterialTheme {
                SubtaskDetailScreen(
                    subtaskSessionID = sessionId,
                    title = "Subtask",
                    onBack = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("2分", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class FakeSessionMessageRepository(
        private val recentWindow: List<SessionMessage>,
    ) : SessionMessageRepository {
        override fun observeMessages(sessionId: String): Flow<List<SessionMessage>> = flowOf(emptyList())

        override fun observeSyncEvents(): Flow<SessionMessageSyncEvent> = emptyFlow()

        override suspend fun getRecentWindow(sessionId: String, limit: Int): List<SessionMessage> = recentWindow

        override suspend fun getOlderPage(
            sessionId: String,
            beforeTimeCreated: Long,
            beforeId: String,
            limit: Int,
        ): List<SessionMessage> = emptyList()

        override suspend fun initSync(sessionId: String, limit: Int): SessionMessageSyncResult = SessionMessageSyncResult(0, emptyList())

        override suspend fun incrementalSync(sessionId: String): SessionMessageSyncResult = SessionMessageSyncResult(0, emptyList())

        override suspend fun incrementalSyncAndNotify(sessionId: String): SessionMessageSyncResult = SessionMessageSyncResult(0, emptyList())

        override suspend fun fetchFullMessage(sessionId: String, messageId: String) = Unit

        override suspend fun getLastSeq(sessionId: String): Long? = null
    }

    private class FakeSessionRepository(
        sessionId: String,
    ) : SessionRepository {
        private val retryStatusFlow = MutableStateFlow<SessionRetryStatus?>(null)
        private val session = Session(
            id = sessionId,
            title = "Subtask",
            directory = "/workspace",
            projectID = "project",
            createdAt = 1,
            updatedAt = 1,
            status = SessionStatus.BUSY,
        )

        override suspend fun getSessions(directory: String?, limit: Int?, start: Long?): List<Session> = listOf(session)
        override suspend fun getSession(id: String): Session? = session
        override suspend fun createSession(title: String?, directory: String?): Session = session
        override suspend fun deleteSession(id: String) = Unit
        override suspend fun updateSession(id: String, title: String?) = Unit
        override suspend fun abortSession(id: String, directory: String?) = Unit
        override suspend fun refreshSessionStatuses() = Unit
        override suspend fun refreshSessionStatusesFromMessages() = Unit
        override suspend fun syncSessionStatusFromRemote(sessionID: String) = Unit
        override fun observeSessions(directory: String?): Flow<List<Session>> = flowOf(listOf(session))
        override fun observeSession(id: String): Flow<Session?> = flowOf(session)
        override fun observeSessionRetryStatus(id: String): Flow<SessionRetryStatus?> = retryStatusFlow
        override fun observeWorkspaces(): Flow<List<Workspace>> = flowOf(emptyList())
        override suspend fun addSessionDuration(id: String, increment: Long) = Unit
        override suspend fun updateSessionModel(id: String, providerID: String?, modelID: String?, modelName: String?) = Unit
        override suspend fun updateSessionStatus(id: String, status: String) = Unit
        override suspend fun getSessionRetryStatus(id: String): SessionRetryStatus? = retryStatusFlow.value
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

    private class FakeSseEventRepository : SseEventRepository {
        override fun connect(address: String, port: Int, password: String?) = emptyFlow<com.openmate.core.domain.model.SseEvent>()
        override fun disconnect() = Unit
        override fun observeConnectionStatus() = flowOf<com.openmate.core.domain.model.ConnectionStatus>()
        override fun isConnectedTo(address: String, port: Int): Boolean = false
        override fun setActiveSessionScope(directory: String?, enabled: Boolean) = Unit
    }
}
