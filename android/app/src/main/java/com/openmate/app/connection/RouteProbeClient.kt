package com.openmate.app.connection

import com.openmate.core.domain.model.ConnectionRoute

interface RouteProbeClient {
    suspend fun probe(route: ConnectionRoute): RouteEvidence
}
