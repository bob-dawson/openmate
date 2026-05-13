package com.openmate.syncdebugger

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.openmate.syncdebugger.db.JdbcDao
import com.openmate.syncdebugger.db.JdbcDb
import com.openmate.syncdebugger.model.*
import com.openmate.syncdebugger.replayer.EventReplayer
import com.openmate.syncdebugger.replayer.SessionMessageMapper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

class SyncDebuggerCli : CliktCommand(name = "sync-debugger") {
    private val session by option("--session", help = "Session ID")
    private val afterSeq by option("--after-seq", help = "Start from this seq").long()
    private val bridge by option("--bridge", help = "Bridge URL").default("http://127.0.0.1:4097")
    private val dbPath by option("--db", help = "SQLite DB file path").default("sync-debug.db")
    private val initLimit by option("--init", help = "Init with snapshot (specify limit)").int()
    private val listSessions by option("--list", help = "List sessions").flag()
    private val token by option("--token", help = "Bridge auth token (or file path starting with @)")
    private val jsonOutput by option("--output", help = "JSON output file").default("sync-result.json")
    private val jsonOnly by option("--json-only", help = "Only output JSON").flag()

    override fun run() = runBlocking {
        val db = JdbcDb(dbPath)
        val dao = JdbcDao(db)
        val resolvedToken = token?.let { t ->
            if (t.startsWith("@")) File(t.substring(1)).readText().trim() else t
        }
        val client = BridgeClient(bridge, resolvedToken)

        try {
            if (listSessions) {
                val sessions = client.getSessions()
                for (s in sessions.sessions) {
                    println("${s.id}  ${s.title}  maxSeq=${s.maxSeq}")
                }
                return@runBlocking
            }

            if (session == null) {
                println("Error: --session is required (or use --list)")
                return@runBlocking
            }
            val sessionId = session!!

            if (initLimit != null) {
                println("Loading init snapshot (limit=$initLimit)...")
                val initResponse = client.getInit(sessionId, initLimit!!)
                val entities = initResponse.messages.map { SessionMessageMapper.dtoToEntity(it) }
                db.transaction {
                    dao.replaceAllForSession(sessionId, entities)
                    dao.upsertSyncState(SyncStateEntity(sessionId, initResponse.maxSeq ?: 0L))
                }
                println("Init done: ${entities.size} messages, maxSeq=${initResponse.maxSeq}")
            }

            val effectiveAfterSeq = afterSeq ?: dao.getSyncState(sessionId)?.lastSeq ?: 0L
            println("Fetching events after seq=$effectiveAfterSeq...")

            val eventsResponse = client.getEvents(sessionId, effectiveAfterSeq)
            val events = eventsResponse.events
            println("Got ${events.size} events, maxSeq=${eventsResponse.maxSeq}")

            if (events.isEmpty()) {
                println("No new events.")
                return@runBlocking
            }

            val loader = EventReplayer.DbLoader { action ->
                when (action) {
                    is EventReplayer.DbLoader.Action.LoadById ->
                        dao.getById(action.id)
                    is EventReplayer.DbLoader.Action.LoadLatestIncompleteAssistant ->
                        dao.getLatestIncompleteAssistant(action.sessionId)
                    is EventReplayer.DbLoader.Action.LoadLatestIncompleteCompaction ->
                        dao.getLatestIncompleteCompaction(action.sessionId)
                    is EventReplayer.DbLoader.Action.LoadAssistantByToolCallId ->
                        dao.getAssistantByToolCallId(action.sessionId, action.callID)
                }
            }

            val replayer = EventReplayer()
            val allChanges = mutableListOf<ReplayChange>()
            val steps = mutableListOf<StepResult>()
            val skipReasons = mutableMapOf<String, Int>()

            for (event in events) {
                val singleEvent = listOf(ReplayEvent(event.id, event.type, event.data))
                val changes = replayer.replay(singleEvent, sessionId, loader)
                allChanges.addAll(changes)

                val (cacheId, cacheType, toolCount) = replayer.getCacheState()
                val cacheState = CacheSnapshot(id = cacheId, type = cacheType, toolCount = toolCount)
                val change = changes.firstOrNull()
                val skipReason = if (changes.isEmpty()) "noChange" else null

                if (skipReason != null) {
                    skipReasons[skipReason] = (skipReasons[skipReason] ?: 0) + 1
                }

                val step = StepResult(
                    seq = event.seq,
                    eventType = event.type,
                    eventId = event.id,
                    cacheState = cacheState,
                    changeType = when (change) {
                        is ReplayChange.Insert -> "INSERT ${change.entity.type}"
                        is ReplayChange.Update -> "UPDATE ${change.type} ${change.id}"
                        null -> null
                    },
                    changeDetail = when (change) {
                        is ReplayChange.Insert -> change.entity.id
                        is ReplayChange.Update -> change.id
                        null -> null
                    },
                    skipReason = skipReason,
                )
                steps += step

                if (!jsonOnly) {
                    println(OutputFormatter().formatStepConsole(step))
                }
            }

            db.transaction {
                val coalesced = mutableMapOf<String, ReplayChange>()
                for (change in allChanges) {
                    val key = when (change) {
                        is ReplayChange.Insert -> change.entity.id
                        is ReplayChange.Update -> change.id
                    }
                    val prev = coalesced[key]
                    if (prev is ReplayChange.Insert && change is ReplayChange.Update) {
                        val dataCompletedAt = change.data["time"]?.jsonObject?.get("completed")?.jsonPrimitive?.longOrNull
                        coalesced[key] = ReplayChange.Insert(prev.entity.copy(
                            data = change.data.toString(),
                            timeUpdated = change.timeUpdated,
                            completedAt = dataCompletedAt ?: change.completedAt ?: prev.entity.completedAt,
                            roundMark = change.roundMark ?: prev.entity.roundMark,
                        ))
                    } else {
                        coalesced[key] = change
                    }
                }

                for (change in coalesced.values) {
                    when (change) {
                        is ReplayChange.Insert -> dao.upsert(change.entity)
                        is ReplayChange.Update -> {
                            val existing = dao.getById(change.id) ?: continue
                            val dataCompletedAt = change.data["time"]?.jsonObject?.get("completed")?.jsonPrimitive?.longOrNull
                            val updated = SessionMessageEntity(
                                id = change.id,
                                sessionId = existing.sessionId,
                                type = existing.type,
                                data = change.data.toString(),
                                timeCreated = existing.timeCreated,
                                timeUpdated = change.timeUpdated,
                                completedAt = dataCompletedAt ?: change.completedAt ?: existing.completedAt,
                                roundMark = change.roundMark ?: existing.roundMark,
                            )
                            dao.upsert(updated)
                        }
                    }
                }

                val maxSeq = eventsResponse.maxSeq ?: effectiveAfterSeq
                dao.upsertSyncState(SyncStateEntity(sessionId, maxSeq))
            }

            val result = SyncResult(
                sessionId = sessionId,
                afterSeq = effectiveAfterSeq,
                totalEvents = events.size,
                steps = steps,
                summary = Summary(
                    totalChanges = allChanges.size,
                    skippedEvents = steps.count { it.skipReason != null },
                    skipReasons = skipReasons,
                ),
            )

            val formatter = OutputFormatter()
            println(formatter.formatSummaryConsole(result))

            val json = Json { prettyPrint = true; encodeDefaults = true }
            File(jsonOutput).writeText(json.encodeToString<SyncResult>(result))
            println("JSON output: $jsonOutput")

        } catch (e: Exception) {
            val errorLog = "ERROR: ${e::class.simpleName}: ${e.message}\n${e.stackTraceToString()}"
            println(errorLog)
            File("sync-debugger-error.log").writeText(errorLog)
        } finally {
            client.close()
            db.close()
        }
    }
}

fun main(args: Array<String>) = SyncDebuggerCli().main(args)
