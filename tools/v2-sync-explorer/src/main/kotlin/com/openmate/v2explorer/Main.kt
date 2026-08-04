package com.openmate.v2explorer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

class Explorer : CliktCommand() {
    private val url by option("--url", help = "V2 server URL").default("http://127.0.0.1:17099")
    private val password by option("--password", help = "V2 server password").prompt("Server password")

    override fun run() {
        val client = V2Client(url, password)
        val sse = V2SseSubscriber(url, password)

        runBlocking {
            println("=== V2 Sync Explorer ===")
            println("Server: $url\n")

            println("--- Health Check ---")
            val health = client.health()
            println(health)
            println()

            println("--- Session List ---")
            val sessions = client.listSessions()
            println(sessions.toString())
            println()

            val sessionData = sessions["data"]?.jsonArray
            if (sessionData.isNullOrEmpty()) {
                println("No sessions found. Creating one...")
                val newSess = client.createSession()
                val sid = newSess["data"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                println("Created session: $sid")
                if (sid != null) exploreSession(client, sse, sid)
                return@runBlocking
            }

            println("Sessions:")
            sessionData.forEachIndexed { i, s ->
                val id = s.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "?"
                val title = s.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: "(no title)"
                println("  [$i] $id — $title")
            }
            print("\nSelect session index (or 'new' to create): ")
            val input = readlnOrNull()?.trim()
            val sid = when {
                input == "new" -> {
                    val newSess = client.createSession()
                    newSess["data"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                }
                input?.toIntOrNull() != null -> {
                    sessionData[input.toInt()].jsonObject["id"]?.jsonPrimitive?.contentOrNull
                }
                else -> input
            }

            if (sid == null) {
                println("Invalid selection")
                return@runBlocking
            }

            exploreSession(client, sse, sid)
        }
    }

    private suspend fun exploreSession(client: V2Client, sse: V2SseSubscriber, sessionId: String) {
        println("\n=== Exploring Session: $sessionId ===\n")

        println("--- Phase 1: Initial Sync (message snapshot) ---")
        val snapshot = mutableListOf<JsonElement>()
        var cursor: String? = null
        var page = 0
        while (true) {
            page++
            val resp = client.getMessages(sessionId, cursor)
            val data = resp["data"]?.jsonArray ?: break
            val nextCursor = resp["cursor"]?.jsonObject?.get("next")?.jsonPrimitive?.contentOrNull
            snapshot.addAll(data)
            println("  Page $page: ${data.size} messages (total=${snapshot.size}, nextCursor=${nextCursor?.take(20)}...)")
            if (nextCursor == null || data.isEmpty()) break
            cursor = nextCursor
        }
        println("Initial sync complete: ${snapshot.size} messages")
        snapshot.forEach { msg ->
            val type = msg.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "?"
            val id = msg.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "?"
            val textPreview = extractTextPreview(msg)
            println("  [$type] $id: $textPreview")
        }
        println()

        println("--- Phase 2: Event History ---")
        val events = client.getEvents(sessionId, after = 0)
        val eventData = events["data"]?.jsonArray
        if (eventData != null) {
            println("  ${eventData.size} events found")
            eventData.forEach { evt ->
                val seq = evt.jsonObject["seq"]?.jsonPrimitive?.contentOrNull ?: "?"
                val type = evt.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "?"
                println("  seq=$seq type=$type")
            }
        } else {
            println("  history endpoint: ${events["error"]?.jsonPrimitive?.contentOrNull}")
        }
        println()

        println("--- Phase 3: SSE Subscription ---")
        println("Listening to /api/event. Commands: send <text> | revert <msgID> | sync | events | quit")
        println()

        val eventLog = ConcurrentHashMap.newKeySet<EventLogEntry>()

        sse.start { type, data, raw ->
            val dataObj = try { Json.parseToJsonElement(data).jsonObject } catch(e: Exception) { JsonObject(emptyMap()) }
            val sid = dataObj["sessionID"]?.jsonPrimitive?.contentOrNull

            val shouldSync = sid == sessionId && (
                type.startsWith("session.text.") ||
                type.startsWith("session.reasoning.") ||
                type.startsWith("session.tool.") ||
                type.startsWith("session.step.") ||
                type.startsWith("session.execution.") ||
                type.startsWith("session.revert.") ||
                type.startsWith("session.input.")
            )

            eventLog.add(EventLogEntry(type, sid, data))
            val tag = if (shouldSync) " *** SYNC ***" else ""
            println("  [SSE] $type (session=$sid)$tag")
        }

        var waitCount = 0
        while (!sse.connected && waitCount < 20) {
            delay(200)
            waitCount++
        }
        println("SSE connected=${sse.connected}")

        while (true) {
            print("> ")
            val input = readlnOrNull()?.trim() ?: break
            val parts = input.split(" ", limit = 2)
            when (parts[0]) {
                "quit", "exit" -> break
                "send" -> {
                    val text = parts.getOrNull(1) ?: "Hello"
                    println("Sending prompt: $text")
                    val result = client.sendPrompt(sessionId, text)
                    println("Prompt admitted: ${result["data"]}")
                }
                "revert" -> {
                    val msgID = parts.getOrNull(1)
                    if (msgID != null) {
                        println("Staging revert to $msgID...")
                        println(client.revertStage(sessionId, msgID))
                        println("Committing revert...")
                        val status = client.revertCommit(sessionId)
                        println("Revert committed: $status")
                    } else {
                        println("Usage: revert <msgID>")
                    }
                }
                "sync" -> {
                    println("\n--- Manual Sync ---")
                    val resp = client.getMessages(sessionId)
                    val msgs = resp["data"]?.jsonArray ?: JsonArray(emptyList())
                    println("Current messages: ${msgs.size}")
                    msgs.forEach { msg ->
                        val type = msg.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "?"
                        val id = msg.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "?"
                        println("  [$type] $id")
                    }
                    println()
                }
                "events" -> {
                    println("\n--- Event Log (${eventLog.size} events) ---")
                    eventLog.toList().sortedBy { it.timestamp }.forEach { entry ->
                        val preview = entry.data.take(80)
                        println("  ${entry.type} session=${entry.sessionId ?: "global"} data=$preview...")
                    }
                    println()
                }
                "test" -> {
                    println("\n=== AUTOMATED TEST ===\n")

                    println("--- Step 1: Record current state ---")
                    val beforeResp = client.getMessages(sessionId)
                    val beforeMsgs = beforeResp["data"]?.jsonArray ?: JsonArray(emptyList())
                    println("Messages before: ${beforeMsgs.size}")
                    beforeMsgs.forEach { m ->
                        val t = m.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                        val id = m.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                        println("  [$t] $id")
                    }

                    println("\n--- Step 2: Send prompt ---")
                    val promptText = parts.getOrNull(1) ?: "What is 1+1?"
                    val promptResult = client.sendPrompt(sessionId, promptText)
                    val newMsgId = promptResult["id"]?.jsonPrimitive?.contentOrNull
                    println("Prompt admitted: id=$newMsgId")

                    println("\n--- Step 3: Wait for SSE events (30s timeout) ---")
                    val sseStart = System.currentTimeMillis()
                    var sseCount = 0
                    while (System.currentTimeMillis() - sseStart < 30000) {
                        delay(1000)
                        val elapsed = (System.currentTimeMillis() - sseStart) / 1000
                        val count = eventLog.size
                        if (count > sseCount) {
                            sseCount = count
                            println("  [${elapsed}s] $sseCount events received")
                        }
                        val lastEvents = eventLog.toList().sortedBy { it.timestamp }.takeLast(3)
                        val hasCompleted = lastEvents.any { it.type.contains("execution.succeeded") || it.type.contains("step.ended") }
                        if (hasCompleted && elapsed > 3) {
                            println("  Execution completed at ${elapsed}s")
                            break
                        }
                    }

                    println("\n--- Step 4: SSE Event Analysis ---")
                    val allEvents = eventLog.toList().sortedBy { it.timestamp }
                    println("Total SSE events: ${allEvents.size}")
                    val byType = allEvents.groupBy { it.type }
                    byType.forEach { (type, events) ->
                        println("  $type: ${events.size}x")
                    }
                    println("\nEvents for this session:")
                    allEvents.filter { it.sessionId == sessionId }.forEach { e ->
                        val dataPreview = e.data.take(120)
                        println("  ${e.type}: $dataPreview")
                    }

                    println("\n--- Step 5: Incremental sync (messages) ---")
                    val afterResp = client.getMessages(sessionId)
                    val afterMsgs = afterResp["data"]?.jsonArray ?: JsonArray(emptyList())
                    println("Messages after: ${afterMsgs.size}")
                    afterMsgs.forEach { m ->
                        val t = m.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                        val id = m.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                        val text = extractTextPreview(m)
                        val isNew = beforeMsgs.none { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == id }
                        val marker = if (isNew) " *** NEW ***" else ""
                        println("  [$t] $id: $text$marker")
                    }

                    println("\n--- Step 6: Test revert ---")
                    val lastAssistant = afterMsgs.lastOrNull {
                        it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "assistant"
                    }
                    if (lastAssistant != null) {
                        val revertTarget = lastAssistant.jsonObject["id"]?.jsonPrimitive?.contentOrNull!!
                        println("Reverting message: $revertTarget")
                        client.revertStage(sessionId, revertTarget)
                        delay(1000)
                        val revertStatus = client.revertCommit(sessionId)
                        println("Revert committed: $revertStatus")
                        delay(1000)

                        println("\n--- Step 7: Verify deletion ---")
                        val afterRevertResp = client.getMessages(sessionId)
                        val afterRevertMsgs = afterRevertResp["data"]?.jsonArray ?: JsonArray(emptyList())
                        println("Messages after revert: ${afterRevertMsgs.size}")
                        afterRevertMsgs.forEach { m ->
                            val t = m.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                            val id = m.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                            val stillExists = id?.let { id_ -> afterMsgs.any { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == id_ } } ?: false
                            val marker = if (!stillExists) "" else ""
                            println("  [$t] $id$marker")
                        }

                        val deletedMsgs = afterMsgs.filter { m ->
                            val id = m.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                            id != null && afterRevertMsgs.none { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == id }
                        }
                        println("\nDeleted messages: ${deletedMsgs.size}")
                        deletedMsgs.forEach { m ->
                            val id = m.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                            val t = m.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                            println("  [$t] $id")
                        }

                        println("\n--- Step 8: Check revert events ---")
                        val revertEvents = eventLog.toList().filter { it.type.contains("revert") }
                        println("Revert events: ${revertEvents.size}")
                        revertEvents.forEach { e ->
                            println("  ${e.type}: ${e.data}")
                        }
                    } else {
                        println("No assistant message to revert")
                    }

                    println("\n=== TEST COMPLETE ===\n")
                }
                "help" -> {
                    println("Commands:")
                    println("  send <text>     — Send a prompt")
                    println("  revert <msgID>  — Revert to a message")
                    println("  sync            — Manual sync check")
                    println("  events          — Show event log")
                    println("  test [text]     — Run automated test sequence")
                    println("  quit            — Exit")
                }
                else -> {
                    if (input.isNotEmpty()) println("Unknown command: $input (type 'help')")
                }
            }
        }

        sse.stop()
        client.httpClient.close()
        println("Explorer stopped.")
    }

    private fun extractTextPreview(msg: JsonElement): String {
        val obj = msg.jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        return when (type) {
            "user" -> obj["text"]?.jsonPrimitive?.contentOrNull?.take(60) ?: ""
            "assistant" -> {
                val content = obj["content"]?.jsonArray
                content?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.take(60) ?: ""
            }
            else -> ""
        }
    }
}

data class EventLogEntry(
    val type: String,
    val sessionId: String?,
    val data: String,
    val timestamp: Long = System.currentTimeMillis(),
)

fun main(args: Array<String>) = Explorer().main(args)
