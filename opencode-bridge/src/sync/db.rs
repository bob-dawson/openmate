use rusqlite::{Connection, OpenFlags, params};
use serde_json::{Value, json};
use std::path::PathBuf;
use crate::config::Config;

pub struct SyncDb {
    db_path: PathBuf,
}

impl SyncDb {
    pub fn new(config: &Config) -> Self {
        Self {
            db_path: config.db_path(),
        }
    }

    fn connect(&self) -> Result<Connection, String> {
        Connection::open_with_flags(
            &self.db_path,
            OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        ).map_err(|e| format!("Failed to open opencode.db: {}", e))
    }

    pub fn ensure_indexes(&self) -> Result<(), String> {
        let conn = self.connect()?;
        conn.execute_batch(
            "CREATE INDEX IF NOT EXISTS idx_event_aggregate_seq ON event(aggregate_id, seq);
             CREATE INDEX IF NOT EXISTS idx_session_message_session_time ON session_message(session_id, time_created DESC);"
        ).map_err(|e| format!("Failed to create indexes: {}", e))
    }

    pub fn get_init_snapshot(&self, session_id: &str, limit: i64) -> Result<(Vec<Value>, Option<i64>), String> {
        let conn = self.connect()?;
        let mut stmt = conn.prepare(
            "SELECT id, session_id, type, time_created, time_updated, data
             FROM session_message
             WHERE session_id = ?
             ORDER BY time_created DESC
             LIMIT ?"
        ).map_err(|e| format!("Prepare failed: {}", e))?;

        let messages: Vec<Value> = stmt.query_map(params![session_id, limit], |row| {
            let id: String = row.get(0)?;
            let sid: String = row.get(1)?;
            let msg_type: String = row.get(2)?;
            let time_created: i64 = row.get(3)?;
            let time_updated: i64 = row.get(4)?;
            let data: String = row.get(5)?;
            Ok(json!({
                "id": id,
                "sessionId": sid,
                "type": msg_type,
                "timeCreated": time_created,
                "timeUpdated": time_updated,
                "data": data,
            }))
        }).map_err(|e| format!("Query failed: {}", e))?
          .filter_map(|r| r.ok())
          .collect();

        let max_seq: Option<i64> = conn.query_row(
            "SELECT seq FROM event_sequence WHERE aggregate_id = ?",
            params![session_id],
            |row| row.get(0),
        ).ok();

        Ok((messages, max_seq))
    }

    pub fn get_events(&self, session_id: &str, after_seq: i64) -> Result<(Vec<Value>, Option<i64>), String> {
        let conn = self.connect()?;
        let mut stmt = conn.prepare(
            "SELECT id, aggregate_id, seq, type, data
             FROM event
             WHERE aggregate_id = ? AND seq > ?
             ORDER BY seq"
        ).map_err(|e| format!("Prepare failed: {}", e))?;

        let events: Vec<Value> = stmt.query_map(params![session_id, after_seq], |row| {
            let id: String = row.get(0)?;
            let aggregate_id: String = row.get(1)?;
            let seq: i64 = row.get(2)?;
            let event_type: String = row.get(3)?;
            let data: String = row.get(4)?;
            Ok(json!({
                "id": id,
                "aggregateId": aggregate_id,
                "seq": seq,
                "type": event_type,
                "data": data,
            }))
        }).map_err(|e| format!("Query failed: {}", e))?
          .filter_map(|r| r.ok())
          .collect();

        let actual_max: Option<i64> = conn.query_row(
            "SELECT seq FROM event_sequence WHERE aggregate_id = ?",
            params![session_id],
            |row| row.get(0),
        ).ok();

        Ok((events, actual_max))
    }

    pub fn get_full_message(&self, message_id: &str) -> Result<Option<Value>, String> {
        let conn = self.connect()?;
        let result = conn.query_row(
            "SELECT id, type, data FROM session_message WHERE id = ?",
            params![message_id],
            |row| {
                let id: String = row.get(0)?;
                let msg_type: String = row.get(1)?;
                let data: String = row.get(2)?;
                Ok(json!({
                    "id": id,
                    "type": msg_type,
                    "data": data,
                }))
            },
        );
        match result {
            Ok(val) => Ok(Some(val)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(format!("Query failed: {}", e)),
        }
    }

    pub fn get_sessions(&self) -> Result<Vec<Value>, String> {
        let conn = self.connect()?;
        let mut stmt = conn.prepare(
            "SELECT s.id, s.title, s.agent, s.model, s.time_created, s.time_updated,
                    CASE WHEN es.seq IS NOT NULL THEN 1 ELSE 0 END as hasEvents,
                    es.seq as maxSeq
             FROM session s
             LEFT JOIN event_sequence es ON es.aggregate_id = s.id
             ORDER BY s.time_updated DESC"
        ).map_err(|e| format!("Prepare failed: {}", e))?;

        let sessions: Vec<Value> = stmt.query_map([], |row| {
            let id: String = row.get(0)?;
            let title: String = row.get(1)?;
            let agent: Option<String> = row.get(2)?;
            let model_json: Option<String> = row.get(3)?;
            let time_created: i64 = row.get(4)?;
            let time_updated: i64 = row.get(5)?;
            let has_events: bool = row.get(6)?;
            let max_seq: Option<i64> = row.get(7)?;
            let model: Option<Value> = model_json
                .and_then(|s| serde_json::from_str(&s).ok());
            Ok(json!({
                "id": id,
                "title": title,
                "agent": agent,
                "model": model,
                "timeCreated": time_created,
                "timeUpdated": time_updated,
                "hasEvents": has_events,
                "maxSeq": max_seq,
            }))
        }).map_err(|e| format!("Query failed: {}", e))?
          .filter_map(|r| r.ok())
          .collect();

        Ok(sessions)
    }
}
