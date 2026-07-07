use rusqlite::{OpenFlags, params};
use r2d2::Pool;
use r2d2_sqlite::SqliteConnectionManager;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use crate::config::Config;

pub struct SyncDb {
    pool: Pool<SqliteConnectionManager>,
    db_path: PathBuf,
    indexes_ready: AtomicBool,
}

impl SyncDb {
    pub fn new(config: &Config) -> Self {
        let db_path = config.db_path();

        if !db_path.exists() {
            tracing::info!("opencode.db not found, creating empty database at {}", db_path.display());
            if let Some(parent) = db_path.parent() {
                std::fs::create_dir_all(parent).ok();
            }
            let create_manager = SqliteConnectionManager::file(&db_path)
                .with_flags(OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE | OpenFlags::SQLITE_OPEN_NO_MUTEX);
            let create_pool = Pool::builder()
                .max_size(1)
                .build(create_manager)
                .expect("Failed to create SQLite connection pool for DB initialization");
            let conn = create_pool.get().expect("Failed to get DB connection for initialization");
            conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;")
                .ok();
        }

        let manager = SqliteConnectionManager::file(&db_path)
            .with_flags(OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX);
        let pool = Pool::builder()
            .max_size(4)
            .min_idle(Some(1))
            .connection_timeout(std::time::Duration::from_secs(5))
            .build(manager)
            .expect("Failed to create SQLite connection pool");

        {
            let conn = pool.get().expect("Failed to get initial DB connection");
            conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;")
                .ok();
        }

        Self { pool, db_path: db_path.clone(), indexes_ready: AtomicBool::new(false) }
    }

    pub fn db_path(&self) -> &PathBuf {
        &self.db_path
    }

    fn conn(&self) -> Result<r2d2::PooledConnection<SqliteConnectionManager>, String> {
        self.pool.get().map_err(|e| format!("DB pool error: {}", e))
    }

    pub fn ensure_indexes(&self) -> Result<(), String> {
        let conn = rusqlite::Connection::open_with_flags(
            &self.db_path,
            OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        ).map_err(|e| format!("Failed to open DB for index creation: {}", e))?;
        conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;")
            .ok();
        conn.execute_batch(
            "CREATE INDEX IF NOT EXISTS idx_session_message_session_time_updated ON session_message(session_id, time_updated);
             CREATE INDEX IF NOT EXISTS idx_message_session_time_updated ON message(session_id, time_updated);
             CREATE INDEX IF NOT EXISTS idx_part_session_time_updated ON part(session_id, time_updated);"
        ).map_err(|e| format!("Failed to create index: {}", e))?;
        tracing::info!("Sync indexes ensured on opencode.db (session_message, message, part)");
        self.indexes_ready.store(true, Ordering::Relaxed);
        Ok(())
    }

    pub fn indexes_ready(&self) -> bool {
        self.indexes_ready.load(Ordering::Relaxed)
    }

    pub fn get_init_snapshot(&self, session_id: &str, limit: i64) -> Result<(Vec<Value>, Option<i64>), String> {
        let messages = self.get_legacy_message_snapshot(session_id, limit)?;
        let seq = self.query_max_seq(session_id);
        Ok((messages, seq))
    }

    fn query_max_seq(&self, aggregate_id: &str) -> Option<i64> {
        let conn = self.conn().ok()?;
        conn.query_row(
            "SELECT MAX(seq) FROM event WHERE aggregate_id = ?",
            params![aggregate_id],
            |row| row.get(0),
        ).ok().flatten()
    }

    #[allow(dead_code)]
    fn get_session_message_snapshot(&self, session_id: &str, limit: i64) -> Result<(Vec<Value>, Option<i64>), String> {
        let conn = self.conn()?;
        let mut stmt = conn.prepare(
            "SELECT id, session_id, type, time_created, time_updated, data
             FROM session_message
             WHERE session_id = ?
             ORDER BY time_created DESC
             LIMIT ?"
        ).map_err(|e| format!("Prepare failed: {}", e))?;

        let mut messages: Vec<Value> = stmt.query_map(params![session_id, limit], |row| {
            let id: String = row.get(0)?;
            let sid: String = row.get(1)?;
            let msg_type: String = row.get(2)?;
            let time_created: i64 = row.get(3)?;
            let time_updated: i64 = row.get(4)?;
            let data_str: String = row.get(5)?;
            let data_val: Value = serde_json::from_str(&data_str).unwrap_or(Value::String(data_str.clone()));
            Ok(json!({
                "id": id,
                "sessionId": sid,
                "type": msg_type,
                "timeCreated": time_created,
                "timeUpdated": time_updated,
                "data": data_val,
            }))
        }).map_err(|e| format!("Query failed: {}", e))?
          .filter_map(|r| r.ok())
          .collect();

        messages.reverse();

        let max_seq: Option<i64> = conn.query_row(
            "SELECT seq FROM event_sequence WHERE aggregate_id = ?",
            params![session_id],
            |row| row.get(0),
        ).ok();

        Ok((messages, max_seq))
    }

    pub fn get_messages_since(&self, session_id: &str, since: i64, limit: i64) -> Result<(Vec<Value>, bool, Option<i64>), String> {
        let conn = self.conn()?;
        let mut msg_stmt = conn.prepare(
            "SELECT id, session_id, time_created, time_updated, data
             FROM message
             WHERE session_id = ? AND (
               time_updated > ?
               OR id IN (SELECT DISTINCT message_id FROM part WHERE session_id = ? AND time_updated > ?)
             )
             ORDER BY time_updated ASC
             LIMIT ?"
        ).map_err(|e| format!("Prepare messages_since failed: {}", e))?;

        #[derive(Clone)]
        struct MsgRow {
            id: String,
            session_id: String,
            time_created: i64,
            time_updated: i64,
            data: Value,
        }

        let msg_rows: Vec<MsgRow> = msg_stmt.query_map(params![session_id, since, session_id, since, limit + 1], |row| {
            let id: String = row.get(0)?;
            let sid: String = row.get(1)?;
            let tc: i64 = row.get(2)?;
            let tu: i64 = row.get(3)?;
            let data_str: String = row.get(4)?;
            Ok(MsgRow {
                id,
                session_id: sid,
                time_created: tc,
                time_updated: tu,
                data: serde_json::from_str(&data_str).unwrap_or(Value::Null),
            })
        }).map_err(|e| format!("Query messages_since failed: {}", e))?
          .filter_map(|r| r.ok())
          .collect();

        let has_more = msg_rows.len() > limit as usize;
        let result_rows: Vec<MsgRow> = if has_more {
            msg_rows[..limit as usize].to_vec()
        } else {
            msg_rows
        };

        if result_rows.is_empty() {
            return Ok((vec![], false, None));
        }

        let msg_ids: Vec<&str> = result_rows.iter().map(|m| m.id.as_str()).collect();
        let placeholders: Vec<String> = msg_ids.iter().enumerate().map(|(i, _)| format!("?{}", i + 1)).collect();
        let sql = format!(
            "SELECT message_id, data FROM part WHERE message_id IN ({}) ORDER BY time_created ASC",
            placeholders.join(",")
        );
        let mut part_stmt = conn.prepare(&sql).map_err(|e| format!("Part query prepare failed: {}", e))?;

        let mut parts_by_msg: HashMap<String, Vec<Value>> = HashMap::new();
        for msg_id in &msg_ids {
            parts_by_msg.insert(msg_id.to_string(), vec![]);
        }

        let params: Vec<&dyn rusqlite::ToSql> = msg_ids.iter().map(|s| s as &dyn rusqlite::ToSql).collect();
        let part_rows = part_stmt.query_map(params.as_slice(), |row| {
            let mid: String = row.get(0)?;
            let data_str: String = row.get(1)?;
            Ok((mid, data_str))
        }).map_err(|e| format!("Part query failed: {}", e))?;

        for pr in part_rows {
            if let Ok((mid, data_str)) = pr {
                if let Ok(pdata) = serde_json::from_str::<Value>(&data_str) {
                    if let Some(vec) = parts_by_msg.get_mut(&mid) {
                        vec.push(pdata);
                    }
                }
            }
        }

        let compaction_summary_by_parent: HashMap<String, (String, Option<i64>)> = {
            let mut map = HashMap::new();
            for msg in &result_rows {
                if msg.data.get("mode").and_then(|m| m.as_str()) == Some("compaction") {
                    if let Some(parent_id) = msg.data.get("parentID").and_then(|p| p.as_str()) {
                        let parts = parts_by_msg.get(&msg.id).cloned().unwrap_or_default();
                        let summary_text = parts.iter()
                            .filter(|p| p.get("type").and_then(|t| t.as_str()) == Some("text"))
                            .filter_map(|p| p.get("text").and_then(|t| t.as_str()))
                            .collect::<Vec<_>>()
                            .join("\n");
                        let completed_at = msg.data.get("time")
                            .and_then(|t| t.get("completed"))
                            .and_then(|c| c.as_i64());
                        map.insert(parent_id.to_string(), (summary_text, completed_at));
                    }
                }
            }
            map
        };

        let mut messages: Vec<Value> = Vec::new();
        let mut max_time_updated: Option<i64> = None;
        for msg in &result_rows {
            if msg.time_updated > since {
                max_time_updated = Some(max_time_updated.map_or(msg.time_updated, |m| m.max(msg.time_updated)));
            }

            if msg.data.get("mode").and_then(|m| m.as_str()) == Some("compaction") {
                continue;
            }

            let parts = parts_by_msg.get(&msg.id).cloned().unwrap_or_default();
            let has_compaction_part = parts.iter().any(|p| {
                p.get("type").and_then(|t| t.as_str()) == Some("compaction")
            });
            let msg_type = match msg.data.get("role").and_then(|r| r.as_str()) {
                Some("user") => {
                    if has_compaction_part { "compaction" } else { "user" }
                }
                Some("assistant") => "assistant",
                _ => continue,
            };

            let compaction_summary = if msg_type == "compaction" {
                compaction_summary_by_parent.get(&msg.id).cloned()
            } else {
                None
            };

            let session_msg_data = self.build_legacy_session_message_data(msg_type, &msg.data, &parts, compaction_summary.as_ref());

            let mut result_msg = json!({
                "id": msg.id,
                "sessionId": msg.session_id,
                "type": msg_type,
                "timeCreated": msg.time_created,
                "timeUpdated": msg.time_updated,
                "data": session_msg_data,
            });
            if let Some((_, Some(completed_at))) = compaction_summary {
                if let Some(obj) = result_msg.as_object_mut() {
                    obj.insert("completedAt".to_string(), json!(completed_at));
                }
            }

            messages.push(result_msg);
        }

        if !result_rows.is_empty() {
            let ids: Vec<&str> = result_rows.iter().map(|m| m.id.as_str()).collect();
            let ph: Vec<String> = ids.iter().enumerate().map(|(i, _)| format!("?{}", i + 1)).collect();
            let sql = format!("SELECT message_id, MAX(time_updated) FROM part WHERE message_id IN ({}) AND time_updated > ? GROUP BY message_id", ph.join(","));
            if let Ok(mut stmt) = conn.prepare(&sql) {
                let mut params: Vec<&dyn rusqlite::ToSql> = ids.iter().map(|s| s as &dyn rusqlite::ToSql).collect();
                params.push(&since);
                if let Ok(rows) = stmt.query_map(params.as_slice(), |row| {
                    let mid: String = row.get(0)?;
                    let max_ptu: i64 = row.get(1)?;
                    Ok((mid, max_ptu))
                }) {
                    for r in rows.flatten() {
                        let (_, ptu): (String, i64) = r;
                        max_time_updated = Some(max_time_updated.map_or(ptu, |m| m.max(ptu)));
                    }
                }
            }
        }

        Ok((messages, has_more, max_time_updated))
    }

    pub fn get_events(&self, session_id: &str, after_seq: i64, limit: i64) -> Result<(Vec<Value>, Option<i64>), String> {
        let conn = self.conn()?;
        let mut stmt = conn.prepare(
            "SELECT id, aggregate_id, seq, type, data
             FROM event
             WHERE aggregate_id = ? AND seq > ?
             ORDER BY seq
             LIMIT ?"
        ).map_err(|e| format!("Prepare failed: {}", e))?;

        let events: Vec<Value> = stmt.query_map(params![session_id, after_seq, limit], |row| {
            let id: String = row.get(0)?;
            let aggregate_id: String = row.get(1)?;
            let seq: i64 = row.get(2)?;
            let event_type: String = row.get(3)?;
            let data_str: String = row.get(4)?;
            let data_val: Value = serde_json::from_str(&data_str).unwrap_or(Value::String(data_str.clone()));
            Ok(json!({
                "id": id,
                "aggregateId": aggregate_id,
                "seq": seq,
                "type": event_type,
                "data": data_val,
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

    pub fn get_events_filtered(&self, session_id: &str, after_seq: i64, limit: i64) -> Result<(Vec<Value>, Option<i64>), String> {
        let conn = self.conn()?;
        let mut stmt = conn.prepare(
            "SELECT id, aggregate_id, seq, type, data
             FROM event
             WHERE aggregate_id = ? AND seq > ?
               AND type IN ('message.removed.1', 'session.updated.1')
             ORDER BY seq
             LIMIT ?"
        ).map_err(|e| format!("Prepare events_filtered failed: {}", e))?;

        let events: Vec<Value> = stmt.query_map(params![session_id, after_seq, limit], |row| {
            let id: String = row.get(0)?;
            let aggregate_id: String = row.get(1)?;
            let seq: i64 = row.get(2)?;
            let event_type: String = row.get(3)?;
            let data_str: String = row.get(4)?;
            let data_val: Value = serde_json::from_str(&data_str).unwrap_or(Value::String(data_str.clone()));
            Ok(json!({
                "id": id,
                "aggregateId": aggregate_id,
                "seq": seq,
                "type": event_type,
                "data": data_val,
            }))
        }).map_err(|e| format!("Query events_filtered failed: {}", e))?
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
        let conn = self.conn()?;
        let result = conn.query_row(
            "SELECT id, session_id, time_created, time_updated, data FROM message WHERE id = ?",
            params![message_id],
            |row| {
                let id: String = row.get(0)?;
                let sid: String = row.get(1)?;
                let tc: i64 = row.get(2)?;
                let tu: i64 = row.get(3)?;
                let data_str: String = row.get(4)?;
                Ok((id, sid, tc, tu, data_str))
            },
        );
        let (id, session_id, time_created, time_updated, data_str) = match result {
            Ok(val) => val,
            Err(rusqlite::Error::QueryReturnedNoRows) => return Ok(None),
            Err(e) => return Err(format!("Query failed: {}", e)),
        };

        let msg_data: Value = serde_json::from_str(&data_str).unwrap_or(Value::Null);

        if msg_data.get("mode").and_then(|m| m.as_str()) == Some("compaction") {
            return Ok(None);
        }

        let mut part_stmt = conn.prepare(
            "SELECT data FROM part WHERE message_id = ? ORDER BY time_created ASC"
        ).map_err(|e| format!("Part prepare failed: {}", e))?;

        let parts: Vec<Value> = part_stmt.query_map(params![message_id], |row| {
            let data_str: String = row.get(0)?;
            Ok(data_str)
        }).map_err(|e| format!("Part query failed: {}", e))?
          .filter_map(|r| r.ok())
          .filter_map(|data_str| serde_json::from_str::<Value>(&data_str).ok())
          .collect();

        let has_compaction_part = parts.iter().any(|p| {
            p.get("type").and_then(|t| t.as_str()) == Some("compaction")
        });
        let msg_type = match msg_data.get("role").and_then(|r| r.as_str()) {
            Some("user") => {
                if has_compaction_part { "compaction" } else { "user" }
            }
            Some("assistant") => "assistant",
            other => other.unwrap_or("unknown"),
        };

        let compaction_summary: Option<(String, Option<i64>)> = if msg_type == "compaction" {
            let summary_msg = conn.query_row(
                "SELECT data FROM message WHERE json_extract(data, '$.parentID') = ? AND json_extract(data, '$.mode') = 'compaction'",
                params![message_id],
                |row| {
                    let data_str: String = row.get(0)?;
                    Ok(data_str)
                },
            );
            match summary_msg {
                Ok(summary_data_str) => {
                    let summary_msg_data: Value = serde_json::from_str(&summary_data_str).unwrap_or(Value::Null);
                    let mut summary_part_stmt = conn.prepare(
                        "SELECT data FROM part WHERE message_id = (SELECT id FROM message WHERE json_extract(data, '$.parentID') = ? AND json_extract(data, '$.mode') = 'compaction') ORDER BY time_created ASC"
                    ).ok();
                    let summary_text = if let Some(ref mut stmt) = summary_part_stmt {
                        stmt.query_map(params![message_id], |row| {
                            let d: String = row.get(0)?;
                            Ok(d)
                        }).ok()
                          .map(|rows| {
                              rows.filter_map(|r| r.ok())
                                  .filter_map(|d| serde_json::from_str::<Value>(&d).ok())
                                  .filter(|p| p.get("type").and_then(|t| t.as_str()) == Some("text"))
                                  .filter_map(|p| p.get("text").and_then(|t| t.as_str()).map(|s| s.to_string()))
                                  .collect::<Vec<_>>()
                                  .join("\n")
                          })
                          .unwrap_or_default()
                    } else {
                        String::new()
                    };
                    let completed_at = summary_msg_data.get("time")
                        .and_then(|t| t.get("completed"))
                        .and_then(|c| c.as_i64());
                    Some((summary_text, completed_at))
                }
                Err(_) => None,
            }
        } else {
            None
        };

        let session_msg_data = self.build_legacy_session_message_data(msg_type, &msg_data, &parts, compaction_summary.as_ref());

        let mut result_msg = json!({
            "id": id,
            "sessionId": session_id,
            "type": msg_type,
            "timeCreated": time_created,
            "timeUpdated": time_updated,
            "data": session_msg_data,
        });
        if let Some((_, Some(completed_at))) = compaction_summary {
            if let Some(obj) = result_msg.as_object_mut() {
                obj.insert("completedAt".to_string(), json!(completed_at));
            }
        }

        Ok(Some(result_msg))
    }

    pub fn resolve_message_id(&self, session_id: &str, time_created: i64) -> Result<Option<String>, String> {
        let conn = self.conn()?;
        let result = conn.query_row(
            "SELECT id FROM message WHERE session_id = ? AND time_created = ? AND json_extract(data, '$.role') = 'user'",
            params![session_id, time_created],
            |row| row.get(0),
        );
        match result {
            Ok(id) => Ok(Some(id)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(format!("Query failed: {}", e)),
        }
    }

    pub fn get_session_stats(&self, session_id: &str) -> Result<(i64, Option<i64>), String> {
        let conn = self.conn()?;
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM message WHERE session_id = ?",
            params![session_id],
            |row| row.get(0),
        ).map_err(|e| format!("Query count failed: {}", e))?;
        let min_time: Option<i64> = conn.query_row(
            "SELECT MIN(time_created) FROM message WHERE session_id = ?",
            params![session_id],
            |row| row.get(0),
        ).ok();
        Ok((count, min_time))
    }

    pub fn resolve_evt_id(&self, session_id: &str, message_id: &str) -> Result<Option<String>, String> {
        let conn = self.conn()?;
        let time_created: i64 = match conn.query_row(
            "SELECT time_created FROM message WHERE id = ? AND session_id = ?",
            params![message_id, session_id],
            |row| row.get(0),
        ) {
            Ok(t) => t,
            Err(rusqlite::Error::QueryReturnedNoRows) => return Ok(None),
            Err(e) => return Err(format!("Query failed: {}", e)),
        };
        let result = conn.query_row(
            "SELECT id FROM session_message WHERE session_id = ? AND time_created = ? AND type = 'user'",
            params![session_id, time_created],
            |row| row.get(0),
        );
        match result {
            Ok(id) => Ok(Some(id)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(format!("Query failed: {}", e)),
        }
    }

    pub fn get_sessions(&self) -> Result<Vec<Value>, String> {
        tracing::info!("get_sessions: using connection pool");
        let conn = self.conn()?;
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
          .filter_map(|r| r.map_err(|e| { tracing::error!("session row error: {}", e); e }).ok())
          .collect();

        Ok(sessions)
    }

    fn get_legacy_message_snapshot(&self, session_id: &str, limit: i64) -> Result<Vec<Value>, String> {
        let conn = self.conn()?;
        let mut msg_stmt = conn.prepare(
            "SELECT id, session_id, time_created, time_updated, data
             FROM (
                 SELECT id, session_id, time_created, time_updated, data
                 FROM message
                 WHERE session_id = ?
                 ORDER BY time_created DESC
                 LIMIT ?
             ) ORDER BY time_created ASC"
        ).map_err(|e| format!("Legacy prepare failed: {}", e))?;

        struct MsgRow {
            id: String,
            session_id: String,
            time_created: i64,
            time_updated: i64,
            data: Value,
        }

        let msg_rows: Vec<MsgRow> = msg_stmt.query_map(params![session_id, limit], |row| {
            let id: String = row.get(0)?;
            let sid: String = row.get(1)?;
            let tc: i64 = row.get(2)?;
            let tu: i64 = row.get(3)?;
            let data_str: String = row.get(4)?;
            Ok(MsgRow {
                id,
                session_id: sid,
                time_created: tc,
                time_updated: tu,
                data: serde_json::from_str(&data_str).unwrap_or(Value::Null),
            })
        }).map_err(|e| format!("Legacy query failed: {}", e))?
          .filter_map(|r| r.ok())
          .collect();

        if msg_rows.is_empty() {
            return Ok(vec![]);
        }

        let msg_ids: Vec<&str> = msg_rows.iter().map(|m| m.id.as_str()).collect();
        let placeholders: Vec<String> = msg_ids.iter().enumerate().map(|(i, _)| format!("?{}", i + 1)).collect();
        let sql = format!(
            "SELECT message_id, data FROM part WHERE message_id IN ({}) ORDER BY time_created ASC",
            placeholders.join(",")
        );
        let mut part_stmt = conn.prepare(&sql).map_err(|e| format!("Legacy part prepare failed: {}", e))?;

        let mut parts_by_msg: HashMap<String, Vec<Value>> = HashMap::new();
        for msg_id in &msg_ids {
            parts_by_msg.insert(msg_id.to_string(), vec![]);
        }

        let params: Vec<&dyn rusqlite::ToSql> = msg_ids.iter().map(|s| s as &dyn rusqlite::ToSql).collect();
        let part_rows = part_stmt.query_map(params.as_slice(), |row| {
            let mid: String = row.get(0)?;
            let data_str: String = row.get(1)?;
            Ok((mid, data_str))
        }).map_err(|e| format!("Legacy part query failed: {}", e))?;

        for pr in part_rows {
            if let Ok((mid, data_str)) = pr {
                if let Ok(pdata) = serde_json::from_str::<Value>(&data_str) {
                    if let Some(vec) = parts_by_msg.get_mut(&mid) {
                        vec.push(pdata);
                    }
                }
            }
        }

        let mut results: Vec<Value> = Vec::new();

        let compaction_summary_by_parent: HashMap<String, (String, Option<i64>)> = {
            let mut map = HashMap::new();
            for msg in &msg_rows {
                if msg.data.get("mode").and_then(|m| m.as_str()) == Some("compaction") {
                    if let Some(parent_id) = msg.data.get("parentID").and_then(|p| p.as_str()) {
                        let parts = parts_by_msg.get(&msg.id).cloned().unwrap_or_default();
                        let summary_text = parts.iter()
                            .filter(|p| p.get("type").and_then(|t| t.as_str()) == Some("text"))
                            .filter_map(|p| p.get("text").and_then(|t| t.as_str()))
                            .collect::<Vec<_>>()
                            .join("\n");
                        let completed_at = msg.data.get("time")
                            .and_then(|t| t.get("completed"))
                            .and_then(|c| c.as_i64());
                        map.insert(parent_id.to_string(), (summary_text, completed_at));
                    }
                }
            }
            map
        };

        for msg in msg_rows {
            if msg.data.get("mode").and_then(|m| m.as_str()) == Some("compaction") {
                continue;
            }

            let parts = parts_by_msg.get(&msg.id).cloned().unwrap_or_default();
            let has_compaction_part = parts.iter().any(|p| {
                p.get("type").and_then(|t| t.as_str()) == Some("compaction")
            });
            let msg_type = match msg.data.get("role").and_then(|r| r.as_str()) {
                Some("user") => {
                    if has_compaction_part { "compaction" } else { "user" }
                }
                Some("assistant") => "assistant",
                _ => continue,
            };

            let compaction_summary = if msg_type == "compaction" {
                compaction_summary_by_parent.get(&msg.id).cloned()
            } else {
                None
            };

            let session_msg_data = self.build_legacy_session_message_data(msg_type, &msg.data, &parts, compaction_summary.as_ref());

            let mut result_msg = json!({
                "id": msg.id,
                "sessionId": msg.session_id,
                "type": msg_type,
                "timeCreated": msg.time_created,
                "timeUpdated": msg.time_updated,
                "data": session_msg_data,
            });
            if let Some((_, Some(completed_at))) = compaction_summary {
                if let Some(obj) = result_msg.as_object_mut() {
                    obj.insert("completedAt".to_string(), json!(completed_at));
                }
            }

            results.push(result_msg);
        }

        Ok(results)
    }

    fn build_legacy_session_message_data(&self, msg_type: &str, msg_data: &Value, raw_parts: &[Value], compaction_summary: Option<&(String, Option<i64>)>) -> Value {
        let parts: Vec<&Value> = raw_parts.iter()
            .filter(|p| !p.get("synthetic").and_then(|v| v.as_bool()).unwrap_or(false))
            .collect();
        match msg_type {
            "user" => {
                let text = parts.iter()
                    .filter(|p| p.get("type").and_then(|t| t.as_str()) == Some("text"))
                    .filter_map(|p| p.get("text").and_then(|t| t.as_str()))
                    .collect::<Vec<_>>()
                    .join("\n");
                json!({
                    "text": text,
                    "time": msg_data.get("time").cloned().unwrap_or(json!({})),
                })
            }
            "assistant" => {
                let mut content: Vec<Value> = Vec::new();
                for part in parts {
                    let part_type = part.get("type").and_then(|t| t.as_str()).unwrap_or("");
                    match part_type {
                        "text" => {
                            let text = part.get("text").and_then(|t| t.as_str()).unwrap_or("");
                            if !text.is_empty() {
                                content.push(json!({
                                    "type": "text",
                                    "text": text,
                                }));
                            }
                        }
                        "tool" => {
                            let name = part.get("tool").and_then(|t| t.as_str()).unwrap_or("unknown");
                            let state = part.get("state").cloned().unwrap_or(json!({"status": "completed"}));
                            let call_id = part.get("callID").and_then(|t| t.as_str())
                                .or_else(|| part.get("id").and_then(|t| t.as_str()))
                                .unwrap_or("");
                            let mut tool_item = json!({
                                "type": "tool",
                                "name": name,
                                "state": state,
                            });
                            if let Some(obj) = tool_item.as_object_mut() {
                                if !call_id.is_empty() {
                                    obj.insert("callID".to_string(), json!(call_id));
                                }
                            }
                            content.push(tool_item);
                        }
                        "reasoning" => {
                            let text = part.get("text").and_then(|t| t.as_str()).unwrap_or("");
                            if !text.is_empty() {
                                content.push(json!({
                                    "type": "reasoning",
                                    "text": text,
                                }));
                            }
                        }
                        "step-start" => {
                            let mut item = json!({"type": "step-start"});
                            if let Some(obj) = item.as_object_mut() {
                                if let Some(snapshot) = part.get("snapshot") {
                                    obj.insert("snapshot".to_string(), snapshot.clone());
                                }
                                if let Some(time) = part.get("time") {
                                    obj.insert("time".to_string(), time.clone());
                                }
                            }
                            content.push(item);
                        }
                        "step-finish" => {
                            let mut item = json!({"type": "step-finish"});
                            if let Some(obj) = item.as_object_mut() {
                                if let Some(reason) = part.get("reason") {
                                    obj.insert("reason".to_string(), reason.clone());
                                }
                                if let Some(finish) = part.get("finish") {
                                    obj.insert("finish".to_string(), finish.clone());
                                }
                                if let Some(time) = part.get("time") {
                                    obj.insert("time".to_string(), time.clone());
                                }
                                if let Some(tokens) = part.get("tokens") {
                                    obj.insert("tokens".to_string(), tokens.clone());
                                }
                                if let Some(cost) = part.get("cost") {
                                    obj.insert("cost".to_string(), cost.clone());
                                }
                            }
                            content.push(item);
                        }
                        "agent" => {
                            let name = part.get("name").and_then(|t| t.as_str()).unwrap_or("");
                            content.push(json!({"type": "agent", "name": name}));
                        }
                        "subtask" => {
                            let description = part.get("description").and_then(|t| t.as_str()).unwrap_or("");
                            let prompt = part.get("prompt").and_then(|t| t.as_str()).unwrap_or("");
                            content.push(json!({"type": "subtask", "description": description, "prompt": prompt}));
                        }
                        "compaction" => {
                            let mut item = json!({"type": "compaction"});
                            if let Some(obj) = item.as_object_mut() {
                                if let Some(auto) = part.get("auto") {
                                    obj.insert("auto".to_string(), auto.clone());
                                }
                                if let Some(overflow) = part.get("overflow") {
                                    obj.insert("overflow".to_string(), overflow.clone());
                                }
                            }
                            content.push(item);
                        }
                        "retry" => {
                            let mut item = json!({"type": "retry"});
                            if let Some(obj) = item.as_object_mut() {
                                if let Some(attempt) = part.get("attempt") {
                                    obj.insert("attempt".to_string(), attempt.clone());
                                }
                                if let Some(error) = part.get("error") {
                                    obj.insert("error".to_string(), error.clone());
                                }
                            }
                            content.push(item);
                        }
                        _ => {}
                    }
                }

                let mut result = json!({
                    "content": content,
                });

                if let Some(obj) = result.as_object_mut() {
                    if let Some(time) = msg_data.get("time") {
                        obj.insert("time".to_string(), time.clone());
                    }
                    if let Some(agent) = msg_data.get("agent") {
                        obj.insert("agent".to_string(), agent.clone());
                    }
                    if let Some(model_id) = msg_data.get("modelID").and_then(|m| m.as_str()) {
                        let provider_id = msg_data.get("providerID").and_then(|p| p.as_str()).unwrap_or("");
                        obj.insert("model".to_string(), json!({
                            "id": model_id,
                            "providerID": provider_id,
                        }));
                    }
                    if let Some(tokens) = msg_data.get("tokens") {
                        obj.insert("tokens".to_string(), tokens.clone());
                    }
                    if let Some(cost) = msg_data.get("cost") {
                        obj.insert("cost".to_string(), cost.clone());
                    }
                    if let Some(finish) = msg_data.get("finish") {
                        obj.insert("finish".to_string(), finish.clone());
                    }
                }

                result
            }
            "compaction" => {
                let mut result = json!({});
                if let Some(obj) = result.as_object_mut() {
                    let auto = raw_parts.iter().any(|p| {
                        p.get("type").and_then(|t| t.as_str()) == Some("compaction")
                            && p.get("auto").and_then(|a| a.as_bool()).unwrap_or(false)
                    });
                    obj.insert("reason".to_string(), json!(if auto { "auto" } else { "manual" }));
                    if let Some((summary_text, _)) = compaction_summary {
                        if !summary_text.is_empty() {
                            obj.insert("summary".to_string(), json!(summary_text));
                        }
                    }
                    let mut time = msg_data.get("time").cloned().unwrap_or(json!({}));
                    if let Some((_, Some(completed_at))) = compaction_summary {
                        if let Some(time_obj) = time.as_object_mut() {
                            time_obj.insert("completed".to_string(), json!(completed_at));
                        }
                    }
                    obj.insert("time".to_string(), time);
                }
                result
            }
            _ => json!({}),
        }
    }
}