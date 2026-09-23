package com.openmate.v2explorer

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.Base64

class V2SseSubscriber(
    private val baseUrl: String,
    private val password: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val basicAuth = "Basic " + Base64.getEncoder().encodeToString("opencode:$password".toByteArray())
    private var listenJob: Job? = null
    @Volatile var connected = false
        private set

    fun start(onEvent: (type: String, data: String, raw: String) -> Unit) {
        listenJob = CoroutineScope(Dispatchers.IO).launch {
            val client = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = Long.MAX_VALUE
                    connectTimeoutMillis = 10000
                    socketTimeoutMillis = Long.MAX_VALUE
                }
            }
            println("[SSE] Starting subscriber to $baseUrl/api/event")
            while (isActive) {
                try {
                    val resp = client.get("$baseUrl/api/event") {
                        header(HttpHeaders.Authorization, basicAuth)
                        header(HttpHeaders.Accept, "text/event-stream")
                    }
                    println("[SSE] Response status: ${resp.status}")
                    if (!resp.status.isSuccess()) {
                        println("[SSE] Connect failed: HTTP ${resp.status.value}, retry in 3s")
                        connected = false
                        delay(3000)
                        continue
                    }
                    connected = true
                    println("[SSE] Connected, listening for events...")

                    val channel = resp.bodyAsChannel()
                    var dataLine: String? = null

                    while (!channel.isClosedForRead && isActive) {
                        val line = channel.readUTF8Line() ?: break
                        when {
                            line.startsWith("data:") -> {
                                dataLine = line.removePrefix("data:").trim()
                            }
                            line.startsWith(":") -> {
                            }
                            line.isEmpty() && dataLine != null -> {
                                val parsed = parseEvent(dataLine!!)
                                if (parsed != null) {
                                    onEvent(parsed.first, parsed.second, dataLine!!)
                                }
                                dataLine = null
                            }
                        }
                    }
                    println("[SSE] Stream ended, reconnecting in 3s...")
                    connected = false
                    delay(3000)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isActive) {
                        println("[SSE] Error: ${e.message}, retry in 5s...")
                        connected = false
                        delay(5000)
                    }
                }
            }
            client.close()
        }
    }

    private fun parseEvent(raw: String): Pair<String, String>? {
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: return null
            val data = obj["data"]?.toString() ?: "{}"
            Pair(type, data)
        } catch (e: Exception) {
            null
        }
    }

    fun stop() {
        listenJob?.cancel()
    }
}
