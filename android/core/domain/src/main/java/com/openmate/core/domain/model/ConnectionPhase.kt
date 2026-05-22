package com.openmate.core.domain.model

enum class ConnectionPhase {
    DISCONNECTED,
    EVALUATING,
    CONNECTING,
    CONNECTED,
    RECOVERING,
    NEEDS_REPAIR,
    FAILED,
}
