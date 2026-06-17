use rusqlite::{OpenFlags, params};
use r2d2::Pool;
use r2d2_sqlite::SqliteConnectionManager;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::path::PathBuf;
use crate::config::Config;

pub struct SyncDb {
    pool: Pool<SqliteConnectionManager>,
    db_path: PathBuf,
}

impl SyncDb {
    pub fn new(config: &Config) -> Self {
        let db_path = config.db_path();
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

        Self { pool, db_path: db_path.clone() }
    }

    pub fn db_path(&self) -> &PathBuf {
        &self.db_path
    }

    fn conn(&self) -> Result<r2d2::PooledConnection<SqliteConnectionManager>, String> {
        self.pool.get().map_err(|e| format!("DB pool error: {}", e))
    }

    pub fn get_init_snapshot(&self, session_id: &str, limit: i64) -> Result<(Vec<Value>, Option<i64>), String> {
        let (messages, max_seq) = self.get_session_message_snapshot(session_id, limit)?;
        if !messages.is_empty() {
            let seq = max_seq.or_else(|| self.query_max_seq(session_id));
            return Ok((messages, seq));
        }
        let fallback = self.get_legacy_message_snapshot(session_id, limit)?;
        let seq = self.query_max_seq(session_id);
        Ok((fallback, seq))
    }

    fn query_max_seq(&self, aggregate_id: &str) -> Option<i64> {
        let conn = self.conn().ok()?;
        conn.query_row(
            "SELECT MAX(seq) FROM event WHERE aggregate_id = ?",
            params![aggregate_id],
            |row| row.get(0),
        ).ok().flatten()
    }

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

    pub fn get_full_message(&self, message_id: &str) -> Result<Option<Value>, String> {
        let conn = self.conn()?;
        let result = conn.query_row(
            "SELECT id, type, data FROM session_message WHERE id = ?",
            params![message_id],
            |row| {
                let id: String = row.get(0)?;
                let msg_type: String = row.get(1)?;
                let data_str: String = row.get(2)?;
                let data_val: Value = serde_json::from_str(&data_str).unwrap_or(Value::String(data_str.clone()));
                Ok(json!({
                    "id": id,
                    "type": msg_type,
                    "data": data_val,
                }))
            },
        );
        match result {
            Ok(val) => Ok(Some(val)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(format!("Query failed: {}", e)),
        }
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
        for msg in msg_rows {
            let msg_type = match msg.data.get("role").and_then(|r| r.as_str()) {
                Some("user") => "user".to_string(),
                Some("assistant") => "assistant".to_string(),
                _ => continue,
            };

            let parts = parts_by_msg.get(&msg.id).cloned().unwrap_or_default();
            let session_msg_data = self.build_legacy_session_message_data(&msg_type, &msg.data, &parts);

            results.push(json!({
                "id": msg.id,
                "sessionId": msg.session_id,
                "type": msg_type,
                "timeCreated": msg.time_created,
                "timeUpdated": msg.time_updated,
                "data": session_msg_data,
            }));
        }

        Ok(results)
    }

    fn build_legacy_session_message_data(&self, msg_type: &str, msg_data: &Value, parts: &[Value]) -> Value {
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
                            content.push(json!({"type": "compaction"}));
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
            _ => json!({}),
        }
    }
}