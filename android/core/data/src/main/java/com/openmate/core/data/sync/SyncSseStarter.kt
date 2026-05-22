package com.openmate.core.data.sync

interface SyncSseStarter {
    fun start()
    fun setActiveSession(sessionId: String?)
}

interface SyncRecoveryTrigger {
    fun requestCatchUpSync(sessionId: String? = null)
}
