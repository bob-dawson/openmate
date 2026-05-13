package com.openmate.syncdebugger.db

import java.sql.Connection
import java.sql.DriverManager

class JdbcDb(private val dbPath: String) : AutoCloseable {
    val connection: Connection

    init {
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        connection.autoCommit = false
        createTables()
    }

    private fun createTables() {
        connection.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS session_message (
                    id TEXT PRIMARY KEY,
                    sessionId TEXT NOT NULL,
                    type TEXT NOT NULL,
                    data TEXT NOT NULL,
                    timeCreated INTEGER NOT NULL,
                    timeUpdated INTEGER NOT NULL,
                    completedAt INTEGER,
                    roundMark INTEGER NOT NULL DEFAULT 1
                )
            """)
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sm_sessionId ON session_message(sessionId)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sm_sessionId_type ON session_message(sessionId, type)")
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_sm_timeCreated ON session_message(timeCreated)")
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sync_state (
                    sessionId TEXT PRIMARY KEY,
                    lastSeq INTEGER NOT NULL
                )
            """)
        }
        connection.commit()
    }

    fun transaction(block: () -> Unit) {
        try {
            block()
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        }
    }

    override fun close() {
        connection.close()
    }
}
