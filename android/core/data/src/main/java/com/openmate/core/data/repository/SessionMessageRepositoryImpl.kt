package com.openmate.core.data.repository

import android.util.Log
import com.openmate.core.data.sync.MobileTruncator
import com.openmate.core.data.sync.SessionMessageMapper
import com.openmate.core.data.sync.SyncLogCategory
import com.openmate.core.data.sync.SyncLogLevel
import com.openmate.core.data.sync.SyncLogStore
import com.openmate.core.data.sync.extractDiffFiles
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.SessionMessageEntity
import com.openmate.core.database.entity.SessionMessageFullContentEntity
import com.openmate.core.database.entity.SyncStateEntity
import com.openmate.core.domain.model.DiffBuilder
import com.openmate.core.domain.model.DiffFile
import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.SessionMessageSyncEvent
import com.openmate.core.domain.model.SessionMessageSyncChange
import com.openmate.core.domain.model.SessionMessageSyncResult
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.network.SyncApiClient
import com.openmate.core.network.dto.MessagesResponseDto
import com.openmate.core.network.dto.SyncMessageDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.room.withTransaction
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class SessionMessageRepositoryImpl @Inject constructor(
    private val syncApiClient: SyncApiClient,
    private val dbProvider: ActiveDatabaseProvider,
    private val logStore: SyncLogStore,
) : SessionMessageRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val syncEvents = MutableSharedFlow<SessionMessageSyncEvent>(extraBufferCapacity = 64)

    private val syncingSessions = ConcurrentHashMap<String, Unit>()

    override fun observeMessages(sessionId: String): Flow<List<SessionMessage>> {
        return dbProvider.getActive().sessionMessageDao().observeBySession(sessionId)
            .map { entities ->
                val latest = entities.lastOrNull()
                logStore.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Sync,
                    message = "观察消息表 observe messages count=${entities.size} last=${latest?.id ?: "none"}/${latest?.type ?: "none"}",
                    sessionId = sessionId,
                )
                entities.map { it.toDomain() }
            }
    }

    override fun observeSyncEvents(): Flow<SessionMessageSyncEvent> = syncEvents

    override suspend fun getRecentWindow(sessionId: String, limit: Int): List<SessionMessage> {
        val startedAt = System.currentTimeMillis()
        val entities = dbProvider.getActive().sessionMessageDao().getRecentWindow(sessionId, limit)
        val costMs = System.currentTimeMillis() - startedAt
        val latest = entities.lastOrNull()
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sync,
            message = "读取消息表 load recent window count=${entities.size} limit=$limit cost=${costMs}ms last=${latest?.id ?: "none"}/${latest?.type ?: "none"}",
            sessionId = sessionId,
        )
        return entities.map { it.toDomain() }
    }

    override suspend fun getOlderPage(
        sessionId: String,
        beforeTimeCreated: Long,
        beforeId: String,
        limit: Int,
    ): List<SessionMessage> {
        val startedAt = System.currentTimeMillis()
        val entities = dbProvider.getActive().sessionMessageDao()
            .getOlderPage(sessionId, beforeTimeCreated, beforeId, limit)
        val costMs = System.currentTimeMillis() - startedAt
        val first = entities.firstOrNull()
        val last = entities.lastOrNull()
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sync,
            message = "读取旧消息页 load older page count=${entities.size} before=$beforeId/$beforeTimeCreated limit=$limit cost=${costMs}ms first=${first?.id ?: "none"} last=${last?.id ?: "none"}",
            sessionId = sessionId,
        )
        return entities.map { it.toDomain() }
    }

    override suspend fun getOlderPageByUserTurns(
        sessionId: String,
        beforeTimeCreated: Long,
        beforeId: String,
        userTurns: Int,
    ): List<SessionMessage> {
        val startedAt = System.currentTimeMillis()
        val entities = dbProvider.getActive().sessionMessageDao()
            .getOlderPageByUserTurns(sessionId, beforeTimeCreated, beforeId, userTurns)
        val costMs = System.currentTimeMillis() - startedAt
        val first = entities.firstOrNull()
        val last = entities.lastOrNull()
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sync,
            message = "按用户轮次加载旧消息 userTurns=$userTurns count=${entities.size} cost=${costMs}ms first=${first?.id ?: "none"} last=${last?.id ?: "none"}",
            sessionId = sessionId,
        )
        return entities.map { it.toDomain() }
    }

    override suspend fun findBusyStartTime(sessionId: String): Long? {
        return dbProvider.getActive().sessionMessageDao().findBusyStartTime(sessionId)
    }

    override suspend fun initSync(sessionId: String, limit: Int): SessionMessageSyncResult {
        val db = dbProvider.getActive()
        Log.d("SyncRepo", "initSync start: sessionId=$sessionId")

        val seqResponse = syncApiClient.events(sessionId, Long.MAX_VALUE)
        val currentSeq = seqResponse.maxSeq ?: 0L

        val response = syncApiClient.init(sessionId, limit)
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "initSync got ${response.messages.size} messages ids=${response.messages.take(3).map { it.id }.joinToString(",")}")
        val entities = response.messages.map { dto ->
            val truncatedData = MobileTruncator.truncate(dto.type, dto.data)
            dto.copy(data = truncatedData).let { SessionMessageMapper.dtoToEntity(it) }
        }
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "initSync entities ids=${entities.take(3).map { it.id }.joinToString(",")}")
        db.sessionMessageDao().replaceAllForSession(sessionId, entities)

        val maxTimeUpdated = entities.maxOfOrNull { it.timeUpdated } ?: 0L
        db.syncStateDao().upsert(SyncStateEntity(sessionId, currentSeq, maxTimeUpdated, 0L))
        Log.d("SyncRepo", "initSync saved cursor seq=$currentSeq lastTimeUpdated=$maxTimeUpdated")

        return SessionMessageSyncResult(
            lastSeq = currentSeq,
            changes = entities.map { entity ->
                SessionMessageSyncChange.Insert(entity.toDomain())
            },
        )
    }

    override suspend fun incrementalSync(sessionId: String) {
        if (syncingSessions.putIfAbsent(sessionId, Unit) != null) {
            Log.d("SyncRepo", "incrementalSync skip: already syncing $sessionId")
            return
        }
        try {
            doIncrementalSync(sessionId)
        } finally {
            syncingSessions.remove(sessionId)
        }
    }

    private suspend fun doIncrementalSync(sessionId: String) {
        val db = dbProvider.getActive()
        db.sessionMessageDao().fixRunningAssistantRoundMark()
        val syncState = db.syncStateDao().get(sessionId) ?: run {
            Log.w("SyncRepo", "incrementalSync skip: no sync state for $sessionId")
            return
        }
        var hasTodoEvent = false
        val t0 = System.currentTimeMillis()
        val traceId = "inc-${System.nanoTime()}"
        Log.d(
            "SyncRepo",
            ">> incrementalSync START: sessionId=$sessionId since=${syncState.lastTimeUpdated}"
        )
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sync,
            message = "增量同步开始 incremental sync begin since=${syncState.lastTimeUpdated} seq=${syncState.lastSeq} trace=$traceId",
            sessionId = sessionId,
        )

        try {
            var since = syncState.lastTimeUpdated
            var currentSeq = syncState.lastSeq
            val revertSince = 0L
            var totalMessages = 0
            var totalBytes = 0L
            var batchIndex = 0

            // Pre-sync local range for the deletion gate. New messages always have ids > lastId,
            // so a count over [firstId, lastId] can only shrink (deletions), never grow.
            val gateFirstId = db.sessionMessageDao().getFirstId(sessionId)
            val gateLastId = db.sessionMessageDao().getLastId(sessionId)
            val gateCount = if (gateFirstId != null && gateLastId != null) {
                db.sessionMessageDao().countBySession(sessionId).toLong()
            } else {
                0L
            }
            var serverCount: Long? = null

            while (true) {
                batchIndex++
                val payload = syncApiClient.messagesPayload(
                    sessionId = sessionId,
                    since = since,
                    firstId = if (batchIndex == 1) gateFirstId else null,
                    lastId = if (batchIndex == 1) gateLastId else null,
                    count = if (batchIndex == 1) gateCount else null,
                )
                val response = payload.response
                if (batchIndex == 1) serverCount = response.serverCount
                val packageBytes = payload.rawBody.toByteArray(Charsets.UTF_8).size
                totalBytes += packageBytes
                logStore.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Sync,
                    message = "增量包返回 session=$sessionId trace=$traceId bytes=$packageBytes",
                    sessionId = sessionId,
                )

                if (response.messages.isEmpty()) {
                    val maxSeq = response.maxSeq ?: currentSeq
                    if (maxSeq != currentSeq || since != syncState.lastTimeUpdated) {
                        db.syncStateDao().upsert(SyncStateEntity(sessionId, maxSeq, since, revertSince))
                    }
                    Log.d("SyncRepo", "<< messages sync done (batch=$batchIndex totalMsgs=$totalMessages)")
                    break
                }

                val msgCount = response.messages.size
                totalMessages += msgCount
                Log.d("SyncRepo", "  batch $batchIndex: $msgCount messages, maxSeq=${response.maxSeq}")

                db.withTransaction {
                    val batchChanges = mutableListOf<SessionMessageSyncChange>()

                    for (dto in response.messages) {
                        val truncatedData = MobileTruncator.truncate(dto.type, dto.data)
                        val entity = SessionMessageMapper.dtoToEntity(dto.copy(data = truncatedData))
                        val existing = db.sessionMessageDao().getById(entity.id)

                        if (!hasTodoEvent && entity.data.contains("todowrite")) {
                            hasTodoEvent = true
                        }

                        db.sessionMessageDao().upsert(entity)
                        if (existing == null) {
                            batchChanges += SessionMessageSyncChange.Insert(entity.toDomain())
                        } else {
                            batchChanges += SessionMessageSyncChange.Update(entity.toDomain())
                        }
                    }

                    val batchMaxTimeUpdated = response.messages.maxOfOrNull { it.timeUpdated }
                    val newTimeUpdated = batchMaxTimeUpdated?.let { maxOf(since, it) } ?: since
                    val maxSeq = response.maxSeq ?: currentSeq
                    val newSeq = maxOf(currentSeq, maxSeq)
                    db.syncStateDao().upsert(SyncStateEntity(sessionId, newSeq, newTimeUpdated, revertSince))
                    since = newTimeUpdated
                    currentSeq = newSeq

                    if (batchChanges.isNotEmpty()) {
                        syncEvents.tryEmit(
                            SessionMessageSyncEvent(
                                sessionId = sessionId,
                                result = SessionMessageSyncResult(
                                    lastSeq = newSeq,
                                    changes = batchChanges,
                                    hasTodoEvent = hasTodoEvent,
                                ),
                            )
                        )
                    }
                }

                if (!response.hasMore) break
            }

            // Deletion gate: serverCount < gateCount means the server deleted messages in range.
            if (gateFirstId != null && gateLastId != null && gateCount > 0 &&
                serverCount != null && serverCount < gateCount
            ) {
                val deletedIds = reconcileDeletions(sessionId, gateFirstId, gateLastId, gateCount, serverCount)
                if (deletedIds.isNotEmpty()) {
                    db.withTransaction {
                        for (id in deletedIds) db.sessionMessageDao().delete(id)
                    }
                    syncEvents.tryEmit(
                        SessionMessageSyncEvent(
                            sessionId = sessionId,
                            result = SessionMessageSyncResult(
                                lastSeq = currentSeq,
                                changes = deletedIds.map { SessionMessageSyncChange.Remove(it) },
                                hasTodoEvent = hasTodoEvent,
                            ),
                        )
                    )
                    logStore.log(
                        SyncLogLevel.Info,
                        SyncLogCategory.Sync,
                        "删除同步 detected=${deletedIds.size} gateCount=$gateCount serverCount=$serverCount trace=$traceId",
                        sessionId = sessionId,
                    )
                }
            }

            Log.d("SyncRepo", "<< incrementalSync END: totalMsgs=$totalMessages totalBytes=$totalBytes ${System.currentTimeMillis() - t0}ms")
            logStore.log(
                level = SyncLogLevel.Info,
                category = SyncLogCategory.Sync,
                message = "增量同步结束 incremental sync completed totalMessages=$totalMessages cost=${System.currentTimeMillis() - t0}ms totalBytes=$totalBytes seq=$currentSeq revertTs=$revertSince trace=$traceId",
                sessionId = sessionId,
            )
        } catch (e: Exception) {
            val totalMs = System.currentTimeMillis() - t0
            logStore.log(
                level = SyncLogLevel.Error,
                category = SyncLogCategory.Sync,
                message = "增量同步失败 incremental sync failed since=${syncState.lastTimeUpdated} error=${e.javaClass.simpleName}: ${e.message} cost=${totalMs}ms trace=$traceId",
                sessionId = sessionId,
            )
            throw e
        }
    }


    private suspend fun reconcileDeletions(
        sessionId: String,
        firstId: String,
        lastId: String,
        localCount: Long,
        serverCount: Long,
    ): List<String> {
        val dao = dbProvider.getActive().sessionMessageDao()
        val local = dao.getInRange(sessionId, firstId, lastId)
        if (local.isEmpty()) return emptyList()

        if (localCount <= REPAIR_FULL_LIMIT) {
            val serverIds = syncApiClient.ids(sessionId, firstId, lastId).ids.toHashSet()
            return local.filter { it.id !in serverIds }.map { it.id }
        }

        val ids = local.map { it.id }
        val n = ids.size
        val k = ceilSqrt(n)
        val positions = (0 until n step k).toMutableList().also {
            if (it.last() != n - 1) it.add(n - 1)
        }
        val counts = syncApiClient.probe(sessionId, ids[0], positions.map { ids[it] }).counts
        val d = localCount - serverCount

        var firstMismatch = -1
        for (i in positions.indices) {
            val c = counts.getOrNull(i) ?: break
            if (c != (positions[i] + 1).toLong()) {
                firstMismatch = i
                break
            }
        }
        if (firstMismatch < 0) {
            val serverIds = syncApiClient.ids(sessionId, firstId, lastId).ids.toHashSet()
            return local.filter { it.id !in serverIds }.map { it.id }
        }

        val blockStart = if (firstMismatch == 0) 0 else positions[firstMismatch - 1]
        var blockEnd = n - 1
        for (i in firstMismatch until positions.size) {
            val c = counts.getOrNull(i) ?: continue
            if (c == (positions[i] + 1 - d)) {
                blockEnd = positions[i]
                break
            }
        }
        val fromId = ids[blockStart]
        val toId = ids[blockEnd]
        val blockServerIds = syncApiClient.ids(sessionId, fromId, toId).ids.toHashSet()
        return local
            .filter { it.id >= fromId && it.id <= toId && it.id !in blockServerIds }
            .map { it.id }
    }

    override suspend fun incrementalSyncAndNotify(sessionId: String) {
        incrementalSync(sessionId)
    }

    override suspend fun insertOptimisticUserMessage(sessionId: String, messageId: String, text: String, created: Long) {
        try {
            val db = dbProvider.getActive()
            if (db.sessionMessageDao().getById(messageId) != null) return
            val data = buildJsonObject {
                put("text", text)
                put("time", buildJsonObject { put("created", created) })
            }
            val dto = SyncMessageDto(
                id = messageId,
                sessionId = sessionId,
                type = "user",
                timeCreated = created,
                timeUpdated = created,
                data = data,
            )
            val entity = SessionMessageMapper.dtoToEntity(dto)
            db.sessionMessageDao().upsert(entity)
            syncEvents.tryEmit(
                SessionMessageSyncEvent(
                    sessionId = sessionId,
                    result = SessionMessageSyncResult(
                        lastSeq = db.syncStateDao().get(sessionId)?.lastSeq ?: 0L,
                        changes = listOf(SessionMessageSyncChange.Insert(entity.toDomain())),
                    ),
                )
            )
            logStore.log(
                level = SyncLogLevel.Info,
                category = SyncLogCategory.Manual,
                message = "本地回显用户消息 optimistic user message id=$messageId",
                sessionId = sessionId,
            )
        } catch (e: Exception) {
            Log.w("SyncRepo", "insertOptimisticUserMessage failed: ${e.message}", e)
        }
    }

    override suspend fun fetchFullMessage(sessionId: String, messageId: String) {
        val db = dbProvider.getActive()
        val response = syncApiClient.full(sessionId, messageId)
        db.sessionMessageFullContentDao().upsert(
            SessionMessageFullContentEntity(
                messageId = response.id,
                content = response.data.toString(),
                fetchedAt = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun fetchDiffFiles(sessionId: String, messageId: String, toolName: String, targetFilePath: String?): List<DiffFile> {
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "fetchDiffFiles IN sessionId=$sessionId messageId=$messageId toolName=$toolName targetFilePath=$targetFilePath")
        val response = syncApiClient.full(sessionId, messageId)
        val data = response.data
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "fetchDiffFiles RESP id=${response.id} type=${response.type} dataKeys=${data.keys.joinToString(",")}")
        val files = extractDiffFiles(data, toolName, targetFilePath)
        if (files.isEmpty()) {
            logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, "fetchDiffFiles: no diff found (tool=$toolName)")
        }
        return files
    }

    override suspend fun getLastSeq(sessionId: String): Long? {
        return dbProvider.getActive().syncStateDao().get(sessionId)?.lastSeq
    }

    override suspend fun rollbackSeq(sessionId: String, count: Long) {
        val db = dbProvider.getActive()
        val current = db.syncStateDao().get(sessionId)?.lastSeq ?: return
        val newSeq = maxOf(0L, current - count)
        db.sessionMessageDao().deleteBySession(sessionId)
        db.sessionDao().updateRevertFields(sessionId, null, null, null, null)
        db.syncStateDao().upsert(SyncStateEntity(sessionId, newSeq, 0L, 0L))
    }

    override suspend fun deleteMessage(sessionId: String, messageId: String) {
        dbProvider.getActive().sessionMessageDao().delete(messageId)
    }
}

private const val REPAIR_FULL_LIMIT = 100L

private fun ceilSqrt(n: Int): Int {
    if (n <= 1) return 1
    var r = 1
    while (r * r < n) r++
    return r
}

private fun SessionMessageEntity.toDomain() = SessionMessage(
    id = id,
    sessionId = sessionId,
    type = type,
    data = data,
    timeCreated = timeCreated,
    timeUpdated = timeUpdated,
    completedAt = completedAt,
    roundMark = roundMark,
)
