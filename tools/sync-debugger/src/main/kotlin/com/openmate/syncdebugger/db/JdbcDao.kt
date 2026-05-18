package com.openmate.syncdebugger.db

import com.openmate.syncdebugger.model.SessionMessageEntity
import com.openmate.syncdebugger.model.SyncStateEntity

class JdbcDao(private val db: JdbcDb) {

    fun getById(id: String): SessionMessageEntity? {
        val sql = "SELECT * FROM session_message WHERE id = ?"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return readEntity(rs)
            }
        }
    }

    fun getLatestIncompleteAssistant(sessionId: String): SessionMessageEntity? {
        val sql = "SELECT * FROM session_message WHERE sessionId = ? AND type = 'assistant' AND roundMark = 0 AND completedAt IS NULL ORDER BY timeCreated DESC LIMIT 1"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return readEntity(rs)
            }
        }
    }

    fun getLatestIncompleteCompaction(sessionId: String): SessionMessageEntity? {
        val sql = "SELECT * FROM session_message WHERE sessionId = ? AND type = 'compaction' AND completedAt IS NULL ORDER BY timeCreated DESC LIMIT 1"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return readEntity(rs)
            }
        }
    }

    fun findUserMessageAfter(sessionId: String, afterTimeCreated: Long): SessionMessageEntity? {
        val sql = "SELECT * FROM session_message WHERE sessionId = ? AND type = 'user' AND timeCreated > ? ORDER BY timeCreated ASC LIMIT 1"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.setLong(2, afterTimeCreated)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return readEntity(rs)
            }
        }
    }

    fun getAssistantByToolCallId(sessionId: String, callID: String): SessionMessageEntity? {
        val sql = "SELECT * FROM session_message WHERE sessionId = ? AND type = 'assistant' AND data LIKE '%' || ? || '%' ORDER BY timeCreated DESC LIMIT 1"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, callID)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return readEntity(rs)
            }
        }
    }

    fun getFirstIdAfterTimeCreated(sessionId: String, timeCreated: Long): String? {
        val sql = "SELECT id FROM session_message WHERE sessionId = ? AND timeCreated >= ? ORDER BY timeCreated ASC, id ASC LIMIT 1"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.setLong(2, timeCreated)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return rs.getString("id")
            }
        }
    }

    fun getMaxIdGte(sessionId: String, fromId: String): String? {
        val sql = "SELECT id FROM session_message WHERE sessionId = ? AND id >= ? ORDER BY id DESC LIMIT 1"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, fromId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return rs.getString("id")
            }
        }
    }

    fun getAllForSession(sessionId: String): List<SessionMessageEntity> {
        val sql = "SELECT * FROM session_message WHERE sessionId = ? ORDER BY timeCreated"
        val result = mutableListOf<SessionMessageEntity>()
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(readEntity(rs))
                }
            }
        }
        return result
    }

    fun delete(id: String) {
        db.connection.prepareStatement("DELETE FROM session_message WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    fun getInRange(sessionId: String, fromId: String, toId: String): List<SessionMessageEntity> {
        val sql = "SELECT * FROM session_message WHERE sessionId = ? AND id >= ? AND id <= ? ORDER BY timeCreated"
        val result = mutableListOf<SessionMessageEntity>()
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, fromId)
            ps.setString(3, toId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(readEntity(rs))
                }
            }
        }
        return result
    }

    fun deleteRange(sessionId: String, fromId: String, toId: String) {
        val sql = "DELETE FROM session_message WHERE sessionId = ? AND id >= ? AND id <= ?"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, fromId)
            ps.setString(3, toId)
            ps.executeUpdate()
        }
    }

    fun upsert(entity: SessionMessageEntity) {
        val sql = "INSERT OR REPLACE INTO session_message (id, sessionId, type, data, timeCreated, timeUpdated, completedAt, roundMark) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, entity.id)
            ps.setString(2, entity.sessionId)
            ps.setString(3, entity.type)
            ps.setString(4, entity.data)
            ps.setLong(5, entity.timeCreated)
            ps.setLong(6, entity.timeUpdated)
            entity.completedAt?.let { ps.setLong(7, it) } ?: ps.setNull(7, java.sql.Types.INTEGER)
            ps.setInt(8, if (entity.roundMark) 1 else 0)
            ps.executeUpdate()
        }
    }

    fun deleteBySession(sessionId: String) {
        db.connection.prepareStatement("DELETE FROM session_message WHERE sessionId = ?").use { ps ->
            ps.setString(1, sessionId)
            ps.executeUpdate()
        }
    }

    fun replaceAllForSession(sessionId: String, messages: List<SessionMessageEntity>) {
        deleteBySession(sessionId)
        for (msg in messages) {
            upsert(msg)
        }
    }

    fun getSyncState(sessionId: String): SyncStateEntity? {
        val sql = "SELECT * FROM sync_state WHERE sessionId = ?"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, sessionId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return SyncStateEntity(rs.getString("sessionId"), rs.getLong("lastSeq"))
            }
        }
    }

    fun upsertSyncState(entity: SyncStateEntity) {
        val sql = "INSERT OR REPLACE INTO sync_state (sessionId, lastSeq) VALUES (?, ?)"
        db.connection.prepareStatement(sql).use { ps ->
            ps.setString(1, entity.sessionId)
            ps.setLong(2, entity.lastSeq)
            ps.executeUpdate()
        }
    }

    private fun readEntity(rs: java.sql.ResultSet): SessionMessageEntity {
        val completedAt = rs.getLong("completedAt")
        return SessionMessageEntity(
            id = rs.getString("id"),
            sessionId = rs.getString("sessionId"),
            type = rs.getString("type"),
            data = rs.getString("data"),
            timeCreated = rs.getLong("timeCreated"),
            timeUpdated = rs.getLong("timeUpdated"),
            completedAt = if (rs.wasNull()) null else completedAt,
            roundMark = rs.getInt("roundMark") != 0,
        )
    }
}
