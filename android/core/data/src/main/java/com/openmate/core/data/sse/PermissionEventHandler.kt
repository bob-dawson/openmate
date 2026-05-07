package com.openmate.core.data.sse

import com.openmate.core.network.SseData
import javax.inject.Inject

open class PermissionEventHandler @Inject constructor() {
    var activeDirectory: String = ""

    open suspend fun handle(type: String, event: SseData) {}
}
