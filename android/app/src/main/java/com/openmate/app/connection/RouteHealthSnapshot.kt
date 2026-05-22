package com.openmate.app.connection

data class RouteHealth(
    val isUsable: Boolean,
    val lastEvidenceAt: Long?,
    val source: String?,
)

data class RouteHealthSnapshot(
    val revision: Long,
    val direct: RouteHealth,
    val gateway: RouteHealth,
)
