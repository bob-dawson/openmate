package com.openmate.core.data.sse

import com.openmate.core.network.SseData
import javax.inject.Inject

open class QuestionEventHandler @Inject constructor() {
    open fun handle(type: String, event: SseData) {}
}
