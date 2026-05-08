package com.openmate.core.data.sse

import com.openmate.core.network.SseData
import javax.inject.Inject

open class TodoEventHandler @Inject constructor() {
    suspend fun handle(type: String, event: SseData) {}
}
