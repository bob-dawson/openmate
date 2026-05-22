package com.openmate.app.connection

sealed interface ConnectionAction {
    data object ReevaluateRoutes : ConnectionAction
    data object StartBackoff : ConnectionAction
    data object StopActiveTransport : ConnectionAction
    data object RefreshSessionStatuses : ConnectionAction
}
