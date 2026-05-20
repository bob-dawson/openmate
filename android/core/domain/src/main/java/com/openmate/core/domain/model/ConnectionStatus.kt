package com.openmate.core.domain.model

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    GATEWAY_CONNECTED,
    ERROR,
    NOT_BRIDGE,
    PAIRING,
}
