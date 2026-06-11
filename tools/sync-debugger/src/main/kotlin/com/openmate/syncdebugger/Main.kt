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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.sql.DriverManager

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
    private val eventLimit by option("--limit", help = "Events per batch").int().default(100)
    private val offlineDb by option("--offline", help = "Read events directly from opencode DB (no Bridge)")
    private val verifyDb by option("--verify", help = "Verify against opencode DB session_message table")

    override fun run() = runBlocking {
        val db = JdbcDb(dbPath)
        val dao = JdbcDao(db)

        try {
            if (offlineDb != null) {
                runOffline(dao, db)
                return@runBlocking
            }

            if (verifyDb != null) {
                runVerify(dao)
                return@runBlocking
            }

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
                var currentAfterSeq = effectiveAfterSeq
                println("Fetching events after seq=$currentAfterSeq (paginated)...")

                val loader = createLoader(dao)
                val replayer = EventReplayer()
                val allChanges = mutableListOf<ReplayChange>()
                val steps = mutableListOf<StepResult>()
                val skipReasons = mutableMapOf<String, Int>()
                var totalEvents = 0
                var batchIndex = 0
                var revertFromId: String? = null
                var revertToId: String? = null

                var totalFetchMs = 0L
                var totalReplayMs = 0L
                var totalDbWriteMs = 0L
                val loaderCallCounts = mutableMapOf<String, Int>()
                val batchTimings = mutableListOf<BatchTiming>()
                val wallStart = System.currentTimeMillis()

                val countingLoader = EventReplayer.DbLoader { action ->
                    val key = action::class.simpleName ?: action.toString()
                    loaderCallCounts[key] = (loaderCallCounts[key] ?: 0) + 1
                    loader(action)
                }

                while (true) {
                    batchIndex++
                    val fetchStart = System.currentTimeMillis()
                    val eventsResponse = client.getEvents(sessionId, currentAfterSeq, eventLimit)
                    val events = eventsResponse.events
                    val fetchMs = System.currentTimeMillis() - fetchStart
                    totalFetchMs += fetchMs
                    println("Batch $batchIndex: got ${events.size} events, maxSeq=${eventsResponse.maxSeq} fetch=${fetchMs}ms")

                    if (events.isEmpty()) {
                        val lastSeq = eventsResponse.maxSeq ?: currentAfterSeq
                        if (lastSeq != currentAfterSeq) {
                            dao.upsertSyncState(SyncStateEntity(sessionId, lastSeq))
                        }
                        println("No more events (batches=$batchIndex totalEvents=$totalEvents)")
                        break
                    }

                    totalEvents += events.size

                    val batchChanges = mutableListOf<ReplayChange>()
                    val replayStart = System.currentTimeMillis()

                    for (event in events) {
                        val strippedType = event.type.replace(Regex("\\.\\d+$"), "")
                        val replayEvent = ReplayEvent(event.id, strippedType, event.data)
                        val changes = replayer.processEvent(replayEvent, sessionId, countingLoader)
                        for (change in changes) {
                            batchChanges += change
                            allChanges += change
                        }

                        val (cacheId, cacheType, toolCount) = replayer.getCacheState()
                        val cacheState = CacheSnapshot(id = cacheId, type = cacheType, toolCount = toolCount)
                        val primaryChange = changes.firstOrNull()
                        val skipReason = if (changes.isEmpty()) "noChange" else null

                        if (skipReason != null) {
                            skipReasons[skipReason] = (skipReasons[skipReason] ?: 0) + 1
                        }

                        val step = StepResult(
                            seq = event.seq,
                            eventType = event.type,
                            eventId = event.id,
                            cacheState = cacheState,
                            changeType = when (primaryChange) {
                                is ReplayChange.Insert -> "INSERT ${primaryChange.entity.type} ${primaryChange.entity.id}"
                                is ReplayChange.Update -> "UPDATE ${primaryChange.type} ${primaryChange.id}"
                                is ReplayChange.Delete -> "DELETE ${primaryChange.id}"
                                null -> null
                            },
                            skipReason = skipReason,
                            batch = batchIndex,
                        )
                        steps += step

                        if (!jsonOnly) {
                            println(OutputFormatter().formatStepConsole(step))
                        }
                    }

                    val replayMs = System.currentTimeMillis() - replayStart
                    totalReplayMs += replayMs

                    val dbWriteStart = System.currentTimeMillis()
                    db.transaction {
                        applyChanges(dao, batchChanges)
                        val batchMaxSeq = events.maxOfOrNull { it.seq }
                        val newSeq = batchMaxSeq?.let { maxOf(currentAfterSeq, it) } ?: currentAfterSeq
                        dao.upsertSyncState(SyncStateEntity(sessionId, newSeq))
                        currentAfterSeq = newSeq
                    }

                    val dbWriteMs = System.currentTimeMillis() - dbWriteStart
                    totalDbWriteMs += dbWriteMs
                    batchTimings += BatchTiming(batchIndex, fetchMs, replayMs, dbWriteMs, events.size)
                    println("  batch $batchIndex timing: fetch=${fetchMs}ms replay=${replayMs}ms dbWrite=${dbWriteMs}ms")
                }

                printSummaryAndOutput(steps, allChanges, skipReasons, totalEvents, batchIndex,
                    effectiveAfterSeq, session!!, loaderCallCounts, batchTimings, wallStart, totalFetchMs, totalReplayMs, totalDbWriteMs)

            } catch (e: Exception) {
                val errorLog = "ERROR: ${e::class.simpleName}: ${e.message}\n${e.stackTraceToString()}"
                println(errorLog)
                File("sync-debugger-error.log").writeText(errorLog)
            } finally {
                client.close()
            }
        } finally {
            db.close()
        }
    }

    private suspend fun runOffline(dao: JdbcDao, db: JdbcDb) {
        val opencodeDbPath = offlineDb!!
        val sessionId = session ?: run {
            println("Error: --session is required with --offline")
            return
        }

        val opencodeConn = DriverManager.getConnection("jdbc:sqlite:$opencodeDbPath")
        opencodeConn.autoCommit = true

        val allEvents = opencodeConn.prepareStatement(
            "SELECT id, seq, type, data FROM event WHERE aggregate_id = ? ORDER BY seq"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                val events = mutableListOf<SyncEventDto>()
                while (rs.next()) {
                    val dataStr = rs.getString("data")
                    val dataJson = runCatching { Json.parseToJsonElement(dataStr) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
                    events.add(SyncEventDto(
                        id = rs.getString("id"),
                        aggregateId = sessionId,
                        seq = rs.getLong("seq"),
                        type = rs.getString("type"),
                        data = dataJson,
                    ))
                }
                events
            }
        }

        val groundTruth = opencodeConn.prepareStatement(
            "SELECT id, type, seq, time_created, data FROM session_message WHERE session_id = ? ORDER BY seq"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                val messages = mutableMapOf<String, Pair<String, String>>()
                while (rs.next()) {
                    messages[rs.getString("id")] = Pair(rs.getString("type"), rs.getString("data"))
                }
                messages
            }
        }

        val maxSeq = allEvents.maxOfOrNull { it.seq } ?: 0L
        println("Loaded ${allEvents.size} events from $opencodeDbPath (session=$sessionId maxSeq=$maxSeq)")
        println("Ground truth: ${groundTruth.size} session_message rows")

        opencodeConn.close()

        val loader = createLoader(dao)
        val replayer = EventReplayer()
        val allChanges = mutableListOf<ReplayChange>()
        val steps = mutableListOf<StepResult>()
        val skipReasons = mutableMapOf<String, Int>()
        var totalEvents = 0
        val loaderCallCounts = mutableMapOf<String, Int>()
        val wallStart = System.currentTimeMillis()

        val countingLoader = EventReplayer.DbLoader { action ->
            val key = action::class.simpleName ?: action.toString()
            loaderCallCounts[key] = (loaderCallCounts[key] ?: 0) + 1
            loader(action)
        }

        for (event in allEvents) {
            totalEvents++
            val strippedType = event.type.replace(Regex("\\.\\d+$"), "")
            val replayEvent = ReplayEvent(event.id, strippedType, event.data)
            val changes = replayer.processEvent(replayEvent, sessionId, countingLoader)
            for (change in changes) {
                allChanges += change
            }

            if (changes.isNotEmpty()) {
                db.transaction {
                    applyChanges(dao, changes)
                }
            }

            val (cacheId, cacheType, toolCount) = replayer.getCacheState()
            val cacheState = CacheSnapshot(id = cacheId, type = cacheType, toolCount = toolCount)
            val primaryChange = changes.firstOrNull()
            val skipReason = if (changes.isEmpty()) "noChange" else null

            if (skipReason != null) {
                skipReasons[skipReason] = (skipReasons[skipReason] ?: 0) + 1
            }

            val step = StepResult(
                seq = event.seq,
                eventType = event.type,
                eventId = event.id,
                cacheState = cacheState,
                changeType = when (primaryChange) {
                    is ReplayChange.Insert -> "INSERT ${primaryChange.entity.type} ${primaryChange.entity.id}"
                    is ReplayChange.Update -> "UPDATE ${primaryChange.type} ${primaryChange.id}"
                    is ReplayChange.Delete -> "DELETE ${primaryChange.id}"
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
            dao.upsertSyncState(SyncStateEntity(sessionId, maxSeq))
        }

        val replayMs = System.currentTimeMillis() - wallStart
        println()
        println("=== Replay Complete ===")
        println("Events: $totalEvents, Changes: ${allChanges.size}, Skipped: ${steps.count { it.skipReason != null }}")
        println("Replay time: ${replayMs}ms, Loader calls: $loaderCallCounts")

        val replayedMessages = dao.getAllForSession(sessionId)
        println("Replayed messages in DB: ${replayedMessages.size}")
        println("Ground truth messages: ${groundTruth.size}")

        var matchCount = 0
        var mismatchCount = 0
        var missingInReplay = 0
        var extraInReplay = 0
        val mismatches = mutableListOf<String>()

        for ((id, gtEntry) in groundTruth) {
            val replayed = replayedMessages.find { it.id == id }
            if (replayed == null) {
                missingInReplay++
                mismatches.add("MISSING in replay: id=$id type=${gtEntry.first}")
                continue
            }
            if (replayed.type != gtEntry.first) {
                mismatchCount++
                mismatches.add("TYPE MISMATCH: id=$id replayed=${replayed.type} groundTruth=${gtEntry.first}")
                continue
            }
            val replayedNorm = normalizeJson(replayed.data)
            val gtNorm = normalizeJson(gtEntry.second)
            if (jsonSemanticEquals(
                    jsonParser.parseToJsonElement(replayedNorm),
                    jsonParser.parseToJsonElement(gtNorm)
                )
            ) {
                matchCount++
            } else {
                mismatchCount++
                mismatches.add("DATA MISMATCH: id=$id type=${replayed.type}")
                mismatches.add("  replayed keys: ${extractKeys(replayedNorm)}")
                mismatches.add("  groundTruth keys: ${extractKeys(gtNorm)}")
                mismatches.add("  diff: ${findDiff(replayedNorm, gtNorm)}")
            }
        }

        for (replayed in replayedMessages) {
            if (!groundTruth.containsKey(replayed.id)) {
                extraInReplay++
                mismatches.add("EXTRA in replay: id=${replayed.id} type=${replayed.type}")
            }
        }

        println()
        println("=== Verification ===")
        println("Match: $matchCount / ${groundTruth.size}")
        println("Mismatch: $mismatchCount")
        println("Missing in replay: $missingInReplay")
        println("Extra in replay: $extraInReplay")
        if (mismatches.isNotEmpty()) {
            println()
            println("=== Mismatches ===")
            for (line in mismatches.take(100)) {
                println(line)
            }
            if (mismatches.size > 100) {
                println("... and ${mismatches.size - 100} more")
            }
        }

        val json = Json { prettyPrint = true; encodeDefaults = true }
        val result = SyncResult(
            sessionId = sessionId,
            afterSeq = 0L,
            totalEvents = totalEvents,
            steps = steps,
            summary = Summary(
                totalChanges = allChanges.size,
                skippedEvents = steps.count { it.skipReason != null },
                skipReasons = skipReasons,
                perf = PerfSummary(
                    totalWallMs = replayMs,
                    replayMs = replayMs,
                    loaderCalls = loaderCallCounts,
                ),
            ),
        )
        File(jsonOutput).writeText(json.encodeToString<SyncResult>(result))
        println("JSON output: $jsonOutput")
    }

    private suspend fun runVerify(dao: JdbcDao) {
        val opencodeDbPath = verifyDb!!
        val sessionId = session ?: run {
            println("Error: --session is required with --verify")
            return
        }

        val opencodeConn = DriverManager.getConnection("jdbc:sqlite:$opencodeDbPath")
        opencodeConn.autoCommit = true

        val groundTruth = opencodeConn.prepareStatement(
            "SELECT id, type, seq, time_created, data FROM session_message WHERE session_id = ? ORDER BY seq"
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                val messages = mutableMapOf<String, Pair<String, String>>()
                while (rs.next()) {
                    messages[rs.getString("id")] = Pair(rs.getString("type"), rs.getString("data"))
                }
                messages
            }
        }

        opencodeConn.close()

        val replayedMessages = dao.getAllForSession(sessionId)
        println("Ground truth: ${groundTruth.size} messages")
        println("Replayed: ${replayedMessages.size} messages")

        var matchCount = 0
        var mismatchCount = 0
        var missingInReplay = 0
        var extraInReplay = 0
        val mismatches = mutableListOf<String>()

        for ((id, gtEntry) in groundTruth) {
            val replayed = replayedMessages.find { it.id == id }
            if (replayed == null) {
                missingInReplay++
                mismatches.add("MISSING in replay: id=$id type=${gtEntry.first}")
                continue
            }
            if (replayed.type != gtEntry.first) {
                mismatchCount++
                mismatches.add("TYPE MISMATCH: id=$id replayed=${replayed.type} groundTruth=${gtEntry.first}")
                continue
            }
            val replayedData = normalizeJson(replayed.data)
            val gtData = normalizeJson(gtEntry.second)
            if (replayedData != gtData) {
                mismatchCount++
                mismatches.add("DATA MISMATCH: id=$id type=${replayed.type}")
                mismatches.add("  diff: ${findDiff(replayedData, gtData)}")
            } else {
                matchCount++
            }
        }

        for (replayed in replayedMessages) {
            if (!groundTruth.containsKey(replayed.id)) {
                extraInReplay++
                mismatches.add("EXTRA in replay: id=${replayed.id} type=${replayed.type}")
            }
        }

        println()
        println("Match: $matchCount / ${groundTruth.size}")
        println("Mismatch: $mismatchCount")
        println("Missing: $missingInReplay")
        println("Extra: $extraInReplay")
        for (line in mismatches.take(100)) {
            println(line)
        }
    }

    private fun createLoader(dao: JdbcDao) = EventReplayer.DbLoader { action ->
        when (action) {
            is EventReplayer.DbLoader.Action.LoadById -> dao.getById(action.id)
            is EventReplayer.DbLoader.Action.LoadLatestIncompleteAssistant -> dao.getLatestIncompleteAssistant(action.sessionId)
            is EventReplayer.DbLoader.Action.LoadLatestAssistant -> dao.getLatestAssistant(action.sessionId)
            is EventReplayer.DbLoader.Action.LoadLatestIncompleteCompaction -> dao.getLatestIncompleteCompaction(action.sessionId)
            is EventReplayer.DbLoader.Action.LoadAssistantByToolCallId -> dao.getAssistantByToolCallId(action.sessionId, action.callID)
            is EventReplayer.DbLoader.Action.HasNewerUserMessageAfter -> dao.findUserMessageAfter(action.sessionId, action.afterTimeCreated)
            is EventReplayer.DbLoader.Action.FindLatestUserMessage -> dao.findLatestUserMessage(action.sessionId)
        }
    }

    private fun applyChanges(dao: JdbcDao, batchChanges: List<ReplayChange>) {
        val coalesced = mutableMapOf<String, ReplayChange>()
        for (change in batchChanges) {
            val key = when (change) {
                is ReplayChange.Insert -> change.entity.id
                is ReplayChange.Update -> change.id
                is ReplayChange.Delete -> change.id
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
            } else if (change is ReplayChange.Delete) {
                if (prev is ReplayChange.Insert) {
                    coalesced.remove(key)
                } else {
                    coalesced[key] = change
                }
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
                is ReplayChange.Delete -> dao.delete(change.id)
            }
        }
    }

    private fun printSummaryAndOutput(
        steps: List<StepResult>,
        allChanges: List<ReplayChange>,
        skipReasons: Map<String, Int>,
        totalEvents: Int,
        batches: Int,
        afterSeq: Long,
        sessionId: String,
        loaderCallCounts: Map<String, Int>,
        batchTimings: List<BatchTiming>,
        wallStart: Long,
        totalFetchMs: Long,
        totalReplayMs: Long,
        totalDbWriteMs: Long,
    ) {
        val result = SyncResult(
            sessionId = sessionId,
            afterSeq = afterSeq,
            totalEvents = totalEvents,
            batches = batches,
            steps = steps,
            summary = Summary(
                totalChanges = allChanges.size,
                skippedEvents = steps.count { it.skipReason != null },
                skipReasons = skipReasons,
                perf = PerfSummary(
                    totalWallMs = System.currentTimeMillis() - wallStart,
                    fetchMs = totalFetchMs,
                    replayMs = totalReplayMs,
                    dbWriteMs = totalDbWriteMs,
                    loaderCalls = loaderCallCounts,
                    batchTimings = batchTimings,
                ),
            ),
        )

        val formatter = OutputFormatter()
        println(formatter.formatSummaryConsole(result))

        val json = Json { prettyPrint = true; encodeDefaults = true }
        File(jsonOutput).writeText(json.encodeToString<SyncResult>(result))
        println("JSON output: $jsonOutput")
    }

    companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }

        private fun normalizeJson(dataStr: String): String {
            return runCatching {
                val element = jsonParser.parseToJsonElement(dataStr)
                val cleaned = removeEmptyOutputPaths(element)
                jsonParser.encodeToString(JsonElement.serializer(), cleaned)
            }.getOrDefault(dataStr)
        }

        private fun removeEmptyOutputPaths(element: JsonElement): JsonElement {
            return when (element) {
                is JsonObject -> {
                    val mutable = element.toMutableMap()
                    val iter = mutable.entries.iterator()
                    while (iter.hasNext()) {
                        val entry = iter.next()
                        if (entry.key == "outputPaths" && entry.value is JsonArray && (entry.value as JsonArray).isEmpty()) {
                            iter.remove()
                        } else {
                            entry.setValue(removeEmptyOutputPaths(entry.value))
                        }
                    }
                    JsonObject(mutable)
                }
                is JsonArray -> JsonArray(element.map { removeEmptyOutputPaths(it) })
                else -> element
            }
        }

        private fun jsonSemanticEquals(a: JsonElement, b: JsonElement): Boolean {
            return when {
                a is JsonObject && b is JsonObject -> {
                    if (a.keys != b.keys) return false
                    a.keys.all { k -> jsonSemanticEquals(a[k]!!, b[k]!!) }
                }
                a is JsonArray && b is JsonArray -> {
                    if (a.size != b.size) return false
                    a.indices.all { i -> jsonSemanticEquals(a[i], b[i]) }
                }
                a is kotlinx.serialization.json.JsonPrimitive && b is kotlinx.serialization.json.JsonPrimitive -> {
                    if (a.isString != b.isString) return false
                    a.content == b.content
                }
                else -> a == b
            }
        }

        private fun extractKeys(dataStr: String): String {
            return runCatching {
                val obj = jsonParser.parseToJsonElement(dataStr).jsonObject
                obj.keys.sorted().joinToString(",")
            }.getOrDefault("?")
        }

        private fun findDiff(a: String, b: String): String {
            return runCatching {
                val objA = jsonParser.parseToJsonElement(a).jsonObject
                val objB = jsonParser.parseToJsonElement(b).jsonObject
                val diffs = mutableListOf<String>()
                for (key in (objA.keys + objB.keys).distinct().sorted()) {
                    val va = objA[key]
                    val vb = objB[key]
                    if (va != vb) {
                        if (va == null) diffs.add("+$key")
                        else if (vb == null) diffs.add("-$key")
                        else diffs.add("~$key")
                    }
                }
                diffs.joinToString(", ")
            }.getOrDefault("parse error")
        }
    }
}

fun main(args: Array<String>) = SyncDebuggerCli().main(args)
