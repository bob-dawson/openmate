package com.openmate.core.data.sse

import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.ToolRef
import com.openmate.core.network.SseData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class PermissionEventHandler @Inject constructor() {
    var activeDirectory: String = ""

    private val _permissions = MutableSharedFlow<PermissionRequest>(extraBufferCapacity = 16)
    val permissions: SharedFlow<PermissionRequest> = _permissions

    open suspend fun handle(type: String, event: SseData) {
        if (type != "permission.v2.asked" && type != "permission.asked") return
        val props = event.properties
        val id = props["id"]?.jsonPrimitive?.contentOrNull ?: return
        val sessionID = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: ""
        val action = (props["action"]?.jsonPrimitive?.contentOrNull
            ?: props["permission"]?.jsonPrimitive?.contentOrNull) ?: ""
        val resources = (props["resources"]?.jsonArray ?: props["patterns"]?.jsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
        } ?: emptyList()
        val save = (props["save"]?.jsonArray ?: props["always"]?.jsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
        } ?: emptyList()
        val metadata = props["metadata"]?.jsonObject ?: buildJsonObject { }
        val sourceObj = props["source"]?.jsonObject ?: props["tool"]?.jsonObject
        val tool = sourceObj?.let {
            ToolRef(
                messageID = it["messageID"]?.jsonPrimitive?.contentOrNull ?: "",
                callID = it["callID"]?.jsonPrimitive?.contentOrNull
                    ?: it["id"]?.jsonPrimitive?.contentOrNull
                    ?: "",
            )
        }
        _permissions.emit(
            PermissionRequest(
                id = id,
                sessionID = sessionID,
                permission = action,
                patterns = resources,
                metadata = metadata,
                always = save,
                tool = tool,
            )
        )
    }
}
