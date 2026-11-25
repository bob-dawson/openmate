use rusqlite::{OpenFlags, params};
use r2d2::Pool;
use r2d2_sqlite::SqliteConnectionManager;
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use serde_json::{Map, Value, json};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use crate::config::Config;

fn table_exists(pool: &Pool<SqliteConnectionManager>, name: &str) -> bool {
    pool.get()
        .ok()
        .and_then(|conn| {
            conn.query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?1",
                params![name],
                |row| row.get::<_, i64>(0),
            )
            .ok()
        })
        .map(|found| found > 0)
        .unwrap_or(false)
}


fn session_part_table_exists(conn: &rusqlite::Connection) -> bool {
    conn.query_row(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'session_part'",
        [],
        |row| row.get::<_, i64>(0),
    )
    .map(|found| found > 0)
    .unwrap_or(false)
}

/// opencode 2.x stores message parts in a separate `session_part` table instead of
/// `session_message.data.content`. Merge them back so clients keep receiving the
/// same shape, and surface part updates through `timeUpdated`.
fn attach_session_parts(
    conn: &rusqlite::Connection,
    messages: &mut [Value],
) -> Result<(), String> {
    if messages.is_empty() || !session_part_table_exists(conn) {
        return Ok(());
    }

    let ids: Vec<String> = messages
        .iter()
        .filter_map(|m| m.get("id").and_then(|v| v.as_str()).map(|s| s.to_string()))
        .collect();
    if ids.is_empty() {
        return Ok(());
    }

    let placeholders = vec!["?"; ids.len()].join(",");
    let sql = format!(
        "SELECT message_id, data, time_updated FROM session_part \
         WHERE message_id IN ({}) ORDER BY ordinal ASC",
        placeholders
    );
    let mut stmt = conn
        .prepare(&sql)
        .map_err(|e| format!("Prepare session_part failed: {}", e))?;
    let params: Vec<&dyn rusqlite::ToSql> = ids.iter().map(|s| s as &dyn rusqlite::ToSql).collect();
    let rows = stmt
        .query_map(params.as_slice(), |row| {
            let mid: String = row.get(0)?;
            let data_str: String = row.get(1)?;
            let tu: i64 = row.get(2)?;
            Ok((mid, data_str, tu))
        })
        .map_err(|e| format!("Query session_part failed: {}", e))?;

    let mut parts_by_msg: HashMap<String, Vec<Value>> = HashMap::new();
    let mut updated_by_msg: HashMap<String, i64> = HashMap::new();
    for (mid, data_str, tu) in rows.flatten() {
        if let Ok(part) = serde_json::from_str::<Value>(&data_str) {
            parts_by_msg.entry(mid.clone()).or_default().push(part);
            updated_by_msg
                .entry(mid)
                .and_modify(|v| *v = (*v).max(tu))
                .or_insert(tu);
        }
    }

    for message in messages.iter_mut() {
        let id = match message.get("id").and_then(|v| v.as_str()) {
            Some(id) => id.to_string(),
            None => continue,
        };
        let parts = match parts_by_msg.get(&id) {
            Some(parts) => parts.clone(),
            None => continue,
        };
        let msg_type = message
            .get("type")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        if let Some(data_obj) = message.get_mut("data").and_then(|d| d.as_object_mut()) {
            if msg_type == "assistant" {
                // opencode's withParts merges the split parts before any inline content the
                // projector moved back, so downstream clients see both in event order.
                let inline = data_obj
                    .get("content")
                    .and_then(|c| c.as_array())
                    .cloned()
                    .unwrap_or_default();
                let mut merged = parts.clone();
                merged.extend(inline);
                data_obj.insert("content".to_string(), Value::Array(merged));
            } else if msg_type == "user" {
                let text_empty = data_obj
                    .get("text")
                    .and_then(|t| t.as_str())
                    .map(|t| t.trim().is_empty())
                    .unwrap_or(true);
                if text_empty {
                    let joined = parts
                        .iter()
                        .filter(|p| p.get("type").and_then(|t| t.as_str()) == Some("text"))
                        .filter_map(|p| p.get("text").and_then(|t| t.as_str()))
                        .collect::<Vec<_>>()
                        .join("\n");
                    if !joined.is_empty() {
                        data_obj.insert("text".to_string(), Value::String(joined));
                    }
                }
            }
        }

        if let Some(max_updated) = updated_by_msg.get(&id) {
            let current = message
                .get("timeUpdated")
                .and_then(|v| v.as_i64())
                .unwrap_or(0);
            if *max_updated > current {
                message["timeUpdated"] = json!(max_updated);
            }
        }
    }

    Ok(())
}

fn blob_table_exists(conn: &rusqlite::Connection) -> bool {
    conn.query_row(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'blob'",
        [],
        |row| row.get::<_, i64>(0),
    )
    .map(|found| found > 0)
    .unwrap_or(false)
}

/// A `media:` ref is `media:` plus the lowercase hex SHA-256 of the blob's bytes.
fn media_ref_hash(uri: &str) -> Option<&str> {
    let hash = uri.strip_prefix("media:")?;
    if hash.len() == 64 && hash.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {
        Some(hash)
    } else {
        None
    }
}

fn load_blob_data(
    conn: &rusqlite::Connection,
    hashes: &HashSet<String>,
) -> Result<HashMap<String, String>, String> {
    let mut blobs = HashMap::new();
    let hashes: Vec<&String> = hashes.iter().collect();
    for chunk in hashes.chunks(400) {
        let placeholders = vec!["?"; chunk.len()].join(",");
        let sql = format!("SELECT hash, data FROM blob WHERE hash IN ({})", placeholders);
        let mut stmt = conn
            .prepare(&sql)
            .map_err(|e| format!("Prepare blob failed: {}", e))?;
        let params: Vec<&dyn rusqlite::ToSql> = chunk.iter().map(|h| *h as &dyn rusqlite::ToSql).collect();
        let rows = stmt
            .query_map(params.as_slice(), |row| {
                let hash: String = row.get(0)?;
                let data: Vec<u8> = row.get(1)?;
                Ok((hash, data))
            })
            .map_err(|e| format!("Query blob failed: {}", e))?;
        for row in rows.flatten() {
            blobs.insert(row.0, BASE64.encode(row.1));
        }
    }
    Ok(blobs)
}

/// Reads as this text when a blob is missing, mirroring opencode's SessionBlob hydration.
fn unavailable_file(file: &Map<String, Value>) -> Value {
    let mime = file
        .get("mime")
        .and_then(|v| v.as_str())
        .unwrap_or("application/octet-stream");
    let text = match file.get("name").and_then(|v| v.as_str()) {
        Some(name) => format!("[media unavailable: {} ({})]", name, mime),
        None => format!("[media unavailable: {}]", mime),
    };
    let mut out = file.clone();
    out.remove("uri");
    out.insert("mime".to_string(), Value::String("text/plain".to_string()));
    out.insert("data".to_string(), Value::String(BASE64.encode(text.as_bytes())));
    Value::Object(out)
}

fn inline_file(file: &Map<String, Value>, blobs: &HashMap<String, String>) -> Value {
    let Some(hash) = file.get("uri").and_then(|v| v.as_str()).and_then(media_ref_hash) else {
        return Value::Object(file.clone());
    };
    match blobs.get(hash) {
        Some(data) => {
            let mut out = file.clone();
            out.remove("uri");
            out.insert("data".to_string(), Value::String(data.clone()));
            Value::Object(out)
        }
        None => unavailable_file(file),
    }
}

/// Restores image bytes stored in `blob` (opencode's `experimental.media_blob`) for user messages.
/// Non-image attachments keep their short `media:` ref: clients only show their file name.
fn hydrate_user_image_files(
    conn: &rusqlite::Connection,
    messages: &mut [Value],
) -> Result<(), String> {
    if messages.is_empty() || !blob_table_exists(conn) {
        return Ok(());
    }

    let mut hashes: HashSet<String> = HashSet::new();
    for message in messages.iter() {
        let Some(files) = message
            .get("data")
            .and_then(|d| d.get("files"))
            .and_then(|f| f.as_array())
        else {
            continue;
        };
        for file in files {
            let Some(obj) = file.as_object() else { continue };
            if !obj.get("mime").and_then(|v| v.as_str()).unwrap_or("").starts_with("image/") {
                continue;
            }
            if let Some(hash) = obj.get("uri").and_then(|v| v.as_str()).and_then(media_ref_hash) {
                hashes.insert(hash.to_string());
            }
        }
    }
    if hashes.is_empty() {
        return Ok(());
    }

    let blobs = load_blob_data(conn, &hashes)?;
    for message in messages.iter_mut() {
        let Some(files) = message
            .get_mut("data")
            .and_then(|d| d.get_mut("files"))
            .and_then(|f| f.as_array_mut())
        else {
            continue;
        };
        for file in files.iter_mut() {
            let Some(obj) = file.as_object() else { continue };
            if !obj.get("mime").and_then(|v| v.as_str()).unwrap_or("").starts_with("image/") {
                continue;
            }
            *file = inline_file(obj, &blobs);
        }
    }
    Ok(())
}

/// Tool results may carry inline `data:` files. The App only renders their type/name, so drop the
/// base64 before it crosses the wire and ends up in the phone's database.
fn strip_tool_inline_media(messages: &mut [Value]) {
    for message in messages.iter_mut() {
        let Some(content) = message
            .get_mut("data")
            .and_then(|d| d.get_mut("content"))
            .and_then(|c| c.as_array_mut())
        else {
            continue;
        };
        for part in content.iter_mut() {
            if part.get("type").and_then(|t| t.as_str()) != Some("tool") {
                continue;
            }
            let Some(items) = part
                .get_mut("state")
                .and_then(|s| s.get_mut("content"))
                .and_then(|c| c.as_array_mut())
            else {
                continue;
            };
            for item in items.iter_mut() {
                if item.get("type").and_then(|t| t.as_str()) != Some("file") {
                    continue;
                }
                let inline = item
                    .get("uri")
                    .and_then(|u| u.as_str())
                    .map(|u| u.starts_with("data:"))
                    .unwrap_or(false);
                if inline {
                    if let Some(obj) = item.as_object_mut() {
                        obj.remove("uri");
                    }
                }
            }
        }
    }
}

/// Brings a row to the shape clients expect: split parts merged back, image bytes restored,
/// and bulky inline tool media removed.
fn prepare_messages(
    conn: &rusqlite::Connection,
    messages: &mut [Value],
) -> Result<(), String> {
    attach_session_parts(conn, messages)?;
    hydrate_user_image_files(conn, messages)?;
    strip_tool_inline_media(messages);
    Ok(())
}

pub struct SyncDb {
    pool: Pool<SqliteConnectionManager>,
    db_path: PathBuf,
    sessions_table: &'static str,
}

impl SyncDb {
    pub fn new(config: &Config) -> Self {
        Self::from_path(config.db_path())
    }

    pub fn from_path(db_path: PathBuf) -> Self {
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

        let sessions_table = if table_exists(&pool, "session") {
            "session"
        } else if table_exists(&pool, "session_v2") {
            "session_v2"
        } else {
            tracing::warn!(
                "opencode database {} has neither `session` nor `session_v2` table; check opencode.db_path",
                db_path.display()
            );
            "session"
        };
        if !table_exists(&pool, "session_message") {
            tracing::warn!(
                "opencode database {} has no `session_message` table - sessions will show no messages. \
                 opencode.db_path should point at the database opencode actually uses (usually opencode.db)",
                db_path.display()
            );
        }

        Self { pool, db_path: db_path.clone(), sessions_table }
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

    pub fn get_messages_since(&self, session_id: &str, since: i64, limit: i64) -> Result<(Vec<Value>, bool, Option<i64>), String> {
        let conn = self.conn()?;
        let has_parts = session_part_table_exists(&conn);
        let sql = if has_parts {
            "SELECT id, session_id, type, time_created, time_updated, data
             FROM session_message m
             WHERE session_id = ? AND (
                 m.time_updated > ? OR EXISTS (
                     SELECT 1 FROM session_part p
                     WHERE p.message_id = m.id AND p.time_updated > ?
                 )
             )
             ORDER BY m.time_updated ASC
             LIMIT ?"
        } else {
            "SELECT id, session_id, type, time_created, time_updated, data
             FROM session_message
             WHERE session_id = ? AND time_updated > ?
             ORDER BY time_updated ASC
             LIMIT ?"
        };
        let mut stmt = conn.prepare(sql).map_err(|e| format!("Prepare failed: {}", e))?;

        let mut messages: Vec<Value> = stmt.query_map(params![session_id, since, since, limit + 1], |row| {
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

        let has_more = messages.len() > limit as usize;
        if has_more {
            messages.truncate(limit as usize);
        }

        prepare_messages(&conn, &mut messages)?;

        let max_seq: Option<i64> = conn.query_row(
            "SELECT seq FROM event_sequence WHERE aggregate_id = ?",
            params![session_id],
            |row| row.get(0),
        ).ok();

        Ok((messages, has_more, max_seq))
    }

    pub fn count_alive_in_range(&self, session_id: &str, from_id: &str, to_id: &str) -> Result<i64, String> {
        let conn = self.conn()?;
        conn.query_row(
            "SELECT COUNT(*) FROM session_message
             WHERE session_id = ?1 AND id >= ?2 AND id <= ?3",
            params![session_id, from_id, to_id],
            |row| row.get::<_, i64>(0),
        ).map_err(|e| format!("Query failed: {}", e))
    }

    pub fn alive_ids_in_range(&self, session_id: &str, from_id: &str, to_id: &str) -> Result<Vec<String>, String> {
        let conn = self.conn()?;
        let mut stmt = conn.prepare(
            "SELECT id FROM session_message
             WHERE session_id = ?1 AND id >= ?2 AND id <= ?3
             ORDER BY id ASC"
        ).map_err(|e| format!("Prepare failed: {}", e))?;

        let ids: Vec<String> = stmt
            .query_map(params![session_id, from_id, to_id], |row| row.get::<_, String>(0))
            .map_err(|e| format!("Query failed: {}", e))?
            .filter_map(|r| r.ok())
            .collect();

        Ok(ids)
    }

    pub fn count_alive_upto(&self, session_id: &str, base_id: &str, ids: &[String]) -> Result<Vec<i64>, String> {
        if ids.is_empty() {
            return Ok(vec![]);
        }
        let top = ids.iter().max().cloned().unwrap_or_default();
        let alive = self.alive_ids_in_range(session_id, base_id, &top)?;
        let counts = ids
            .iter()
            .map(|id| alive.partition_point(|a| a.as_str() <= id.as_str()) as i64)
            .collect();
        Ok(counts)
    }

    pub fn get_deleted_message_ids(&self, session_id: &str, after_time: i64, known_ids: &[String]) -> Result<Vec<String>, String> {
        if known_ids.is_empty() {
            return Ok(vec![]);
        }
        let conn = self.conn()?;
        let placeholders: Vec<String> = known_ids.iter().enumerate().map(|(i, _)| format!("?{}", i + 2)).collect();
        let sql = format!(
            "SELECT id FROM session_message WHERE session_id = ?1 AND time_created >= ?2 AND id IN ({})",
            placeholders.join(",")
        );
        let mut stmt = conn.prepare(&sql).map_err(|e| format!("Prepare failed: {}", e))?;
        let mut params_vec: Vec<&dyn rusqlite::ToSql> = vec![&session_id, &after_time];
        for id in known_ids {
            params_vec.push(id);
        }
        let existing_ids: std::collections::HashSet<String> = stmt
            .query_map(params_vec.as_slice(), |row| row.get::<_, String>(0))
            .map_err(|e| format!("Query failed: {}", e))?
            .filter_map(|r| r.ok())
            .collect();

        Ok(known_ids.iter().filter(|id| !existing_ids.contains(*id)).cloned().collect())
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

        prepare_messages(&conn, &mut messages)?;

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
            Ok(val) => {
                let mut messages = vec![val];
                prepare_messages(&conn, &mut messages)?;
                Ok(messages.pop())
            }
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
        let sql = format!(
            "SELECT s.id, s.title, s.agent, s.model, s.time_created, s.time_updated,
                    CASE WHEN es.seq IS NOT NULL THEN 1 ELSE 0 END as hasEvents,
                    es.seq as maxSeq
             FROM {} s
             LEFT JOIN event_sequence es ON es.aggregate_id = s.id
             ORDER BY s.time_updated DESC",
            self.sessions_table
        );
        let mut stmt = conn.prepare(&sql).map_err(|e| format!("Prepare failed: {}", e))?;

        let sessions: Vec<Value> = stmt.query_map([], |row| {
            let id: String = row.get(0)?;
            let title: Option<String> = row.get(1)?;
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
                "title": title.unwrap_or_default(),
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

    fn build_legacy_session_message_data(&self, msg_type: &str, msg_data: &Value, raw_parts: &[Value]) -> Value {
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

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;
    use tempfile::tempdir;

    fn setup(rows: &[(&str, &str, i64)]) -> (SyncDb, tempfile::TempDir) {
        let dir = tempdir().unwrap();
        let path = dir.path().join("opencode.db");
        {
            let conn = Connection::open(&path).unwrap();
            conn.execute_batch(
                "CREATE TABLE session_message (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    type TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    time_created INTEGER NOT NULL,
                    time_updated INTEGER NOT NULL,
                    data TEXT NOT NULL
                );",
            )
            .unwrap();
            for (id, session_id, time) in rows {
                conn.execute(
                    "INSERT INTO session_message (id, session_id, type, seq, time_created, time_updated, data)
                     VALUES (?1, ?2, 'assistant', 0, ?3, ?3, '{}')",
                    params![id, session_id, time],
                )
                .unwrap();
            }
        }
        (SyncDb::from_path(path), dir)
    }

    #[test]
    fn counts_alive_within_range_only() {
        let (db, _d) = setup(&[
            ("msg_a", "ses_1", 1),
            ("msg_b", "ses_1", 2),
            ("msg_d", "ses_1", 4),
            ("msg_x", "ses_2", 3),
        ]);
        assert_eq!(db.count_alive_in_range("ses_1", "msg_a", "msg_d").unwrap(), 3);
        assert_eq!(db.count_alive_in_range("ses_1", "msg_a", "msg_c").unwrap(), 2);
        assert_eq!(db.count_alive_in_range("ses_1", "msg_e", "msg_z").unwrap(), 0);
    }

    #[test]
    fn alive_ids_are_ordered_and_bounded() {
        let (db, _d) = setup(&[
            ("msg_a", "ses_1", 1),
            ("msg_b", "ses_1", 2),
            ("msg_d", "ses_1", 4),
        ]);
        assert_eq!(
            db.alive_ids_in_range("ses_1", "msg_a", "msg_z").unwrap(),
            vec!["msg_a", "msg_b", "msg_d"]
        );
        assert_eq!(
            db.alive_ids_in_range("ses_1", "msg_b", "msg_c").unwrap(),
            vec!["msg_b"]
        );
    }

    #[test]
    fn probe_returns_prefix_counts() {
        let (db, _d) = setup(&[
            ("msg_a", "ses_1", 1),
            ("msg_b", "ses_1", 2),
            ("msg_d", "ses_1", 4),
        ]);
        let probes: Vec<String> = ["msg_a", "msg_c", "msg_d", "msg_z"]
            .iter()
            .map(|s| s.to_string())
            .collect();
        assert_eq!(
            db.count_alive_upto("ses_1", "msg_a", &probes).unwrap(),
            vec![1, 2, 3, 3]
        );
    }

    #[test]
    fn probe_empty_is_empty() {
        let (db, _d) = setup(&[]);
        assert!(db.count_alive_upto("ses_1", "msg_a", &[]).unwrap().is_empty());
    }
}

#[cfg(test)]
mod session_part_tests {
    use super::attach_session_parts;
    use rusqlite::Connection;
    use serde_json::json;

    fn conn() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE TABLE session_message (
                 id TEXT PRIMARY KEY, session_id TEXT, type TEXT, seq INTEGER,
                 time_created INTEGER, time_updated INTEGER, data TEXT);
             CREATE TABLE session_part (
                 message_id TEXT, session_id TEXT, ordinal INTEGER, type TEXT,
                 call_id TEXT, time_created INTEGER, time_updated INTEGER, data TEXT);",
        )
        .unwrap();
        conn
    }

    fn message(id: &str, msg_type: &str, data: serde_json::Value, time_updated: i64) -> serde_json::Value {
        json!({
            "id": id,
            "sessionId": "ses_1",
            "type": msg_type,
            "timeCreated": 100,
            "timeUpdated": time_updated,
            "data": data,
        })
    }

    fn insert_part(conn: &Connection, mid: &str, ordinal: i64, data: serde_json::Value, time_updated: i64) {
        conn.execute(
            "INSERT INTO session_part (message_id, session_id, ordinal, type, call_id, time_created, time_updated, data)
             VALUES (?1, 'ses_1', ?2, 'x', NULL, 100, ?3, ?4)",
            rusqlite::params![mid, ordinal, time_updated, data.to_string()],
        )
        .unwrap();
    }

    #[test]
    fn merges_parts_in_ordinal_order_and_bumps_time_updated() {
        let conn = conn();
        insert_part(&conn, "m1", 1, json!({"type": "text", "text": "hello"}), 150);
        insert_part(&conn, "m1", 0, json!({"type": "reasoning", "text": "think"}), 120);
        insert_part(&conn, "m1", 2, json!({"type": "tool", "name": "shell"}), 200);

        let mut messages = vec![message("m1", "assistant", json!({"content": [], "finish": "stop"}), 110)];
        attach_session_parts(&conn, &mut messages).unwrap();

        let content = messages[0]["data"]["content"].as_array().unwrap();
        assert_eq!(content.len(), 3);
        assert_eq!(content[0]["type"], "reasoning");
        assert_eq!(content[1]["type"], "text");
        assert_eq!(content[2]["type"], "tool");
        assert_eq!(messages[0]["timeUpdated"], 200);
    }

    #[test]
    fn appends_parts_before_existing_inline_content() {
        let conn = conn();
        insert_part(&conn, "m1", 0, json!({"type": "text", "text": "from parts"}), 150);

        let mut messages = vec![message(
            "m1",
            "assistant",
            json!({"content": [{"type": "text", "text": "inline"}]}),
            110,
        )];
        attach_session_parts(&conn, &mut messages).unwrap();

        let content = messages[0]["data"]["content"].as_array().unwrap();
        assert_eq!(content.len(), 2);
        assert_eq!(content[0]["text"], "from parts");
        assert_eq!(content[1]["text"], "inline");
    }

    #[test]
    fn fills_user_text_from_parts() {
        let conn = conn();
        insert_part(&conn, "u1", 0, json!({"type": "text", "text": "hi there"}), 150);

        let mut messages = vec![message("u1", "user", json!({"text": ""}), 110)];
        attach_session_parts(&conn, &mut messages).unwrap();

        assert_eq!(messages[0]["data"]["text"], "hi there");
    }

    #[test]
    fn no_op_without_session_part_table() {
        let conn = Connection::open_in_memory().unwrap();
        let mut messages = vec![message("m1", "assistant", json!({"content": []}), 110)];
        attach_session_parts(&conn, &mut messages).unwrap();
        assert_eq!(messages[0]["data"]["content"].as_array().unwrap().len(), 0);
    }
}

#[cfg(test)]
mod blob_tests {
    use super::{hydrate_user_image_files, media_ref_hash, strip_tool_inline_media};
    use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
    use rusqlite::{params, Connection};
    use serde_json::json;

    const HASH: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    fn conn() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE TABLE blob (
                 hash TEXT PRIMARY KEY, mime TEXT NOT NULL, bytes INTEGER NOT NULL,
                 width INTEGER, height INTEGER, data BLOB NOT NULL, time_created INTEGER NOT NULL);",
        )
        .unwrap();
        conn
    }

    fn store_blob(conn: &Connection, hash: &str, mime: &str, data: &[u8]) {
        conn.execute(
            "INSERT INTO blob (hash, mime, bytes, data, time_created) VALUES (?1, ?2, ?3, ?4, 0)",
            params![hash, mime, data.len() as i64, data],
        )
        .unwrap();
    }

    #[test]
    fn media_ref_requires_lowercase_hex_sha256() {
        assert_eq!(media_ref_hash(&format!("media:{}", HASH)), Some(HASH));
        assert_eq!(media_ref_hash(&format!("media:{}", HASH.to_uppercase())), None);
        assert_eq!(media_ref_hash("media:abc"), None);
        assert_eq!(media_ref_hash("data:image/png;base64,AAA"), None);
    }

    #[test]
    fn hydrates_image_file_from_blob() {
        let conn = conn();
        store_blob(&conn, HASH, "image/png", b"PNGDATA");
        let mut messages = vec![json!({
            "data": {"files": [{"uri": format!("media:{}", HASH), "mime": "image/png", "name": "a.png"}]}
        })];

        hydrate_user_image_files(&conn, &mut messages).unwrap();

        let file = &messages[0]["data"]["files"][0];
        assert_eq!(file["data"], json!(BASE64.encode(b"PNGDATA")));
        assert!(file.get("uri").is_none());
        assert_eq!(file["mime"], json!("image/png"));
    }

    #[test]
    fn missing_blob_becomes_unavailable_text() {
        let conn = conn();
        let mut messages = vec![json!({
            "data": {"files": [{"uri": format!("media:{}", HASH), "mime": "image/png", "name": "a.png"}]}
        })];

        hydrate_user_image_files(&conn, &mut messages).unwrap();

        let file = &messages[0]["data"]["files"][0];
        assert_eq!(file["mime"], json!("text/plain"));
        let decoded = BASE64.decode(file["data"].as_str().unwrap()).unwrap();
        assert_eq!(String::from_utf8(decoded).unwrap(), "[media unavailable: a.png (image/png)]");
        assert!(file.get("uri").is_none());
    }

    #[test]
    fn leaves_non_image_refs_and_inline_files_alone() {
        let conn = conn();
        store_blob(&conn, HASH, "application/pdf", b"PDF");
        let mut messages = vec![json!({
            "data": {"files": [
                {"uri": format!("media:{}", HASH), "mime": "application/pdf", "name": "a.pdf"},
                {"uri": "data:image/png;base64,AAA", "mime": "image/png", "name": "b.png"}
            ]}
        })];

        hydrate_user_image_files(&conn, &mut messages).unwrap();

        assert_eq!(messages[0]["data"]["files"][0]["uri"], json!(format!("media:{}", HASH)));
        assert_eq!(messages[0]["data"]["files"][1]["uri"], json!("data:image/png;base64,AAA"));
    }

    #[test]
    fn strips_inline_tool_media_but_keeps_refs() {
        let mut messages = vec![json!({
            "data": {"content": [{
                "type": "tool",
                "state": {"status": "completed", "content": [
                    {"type": "file", "uri": "data:image/png;base64,AAA", "mime": "image/png", "name": "x.png"},
                    {"type": "file", "uri": format!("media:{}", HASH), "mime": "image/png", "name": "y.png"},
                    {"type": "text", "text": "done"}
                ]}
            }]}
        })];

        strip_tool_inline_media(&mut messages);

        let items = messages[0]["data"]["content"][0]["state"]["content"].as_array().unwrap();
        assert!(items[0].get("uri").is_none());
        assert_eq!(items[0]["mime"], json!("image/png"));
        assert_eq!(items[1]["uri"], json!(format!("media:{}", HASH)));
        assert_eq!(items[2]["text"], json!("done"));
    }
}
