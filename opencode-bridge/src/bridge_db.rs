use rusqlite::params;
use r2d2::Pool;
use r2d2_sqlite::SqliteConnectionManager;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::Arc;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedDevice {
    pub device_id: String,
    pub client_device_id: String,
    pub ip: String,
    pub name: String,
    pub user_agent: String,
    pub paired_at: i64,
    pub last_seen: i64,
}

#[derive(Clone)]
pub struct BridgeDb {
    pool: Arc<Pool<SqliteConnectionManager>>,
}

impl BridgeDb {
    pub fn open() -> Result<Self, String> {
        let db_dir = dirs::home_dir()
            .ok_or_else(|| "Cannot determine home directory".to_string())?
            .join(".openmate");
        std::fs::create_dir_all(&db_dir)
            .map_err(|e| format!("Failed to create DB directory: {}", e))?;
        let db_path = db_dir.join("bridge.db");
        Self::open_at(&db_path)
    }

    pub fn open_at(db_path: &PathBuf) -> Result<Self, String> {
        let manager = SqliteConnectionManager::file(db_path);
        let pool = Pool::builder()
            .max_size(4)
            .min_idle(Some(1))
            .connection_timeout(std::time::Duration::from_secs(5))
            .build(manager)
            .map_err(|e| format!("Failed to create connection pool: {}", e))?;

        let db = Self {
            pool: Arc::new(pool),
        };
        db.migrate()?;
        Ok(db)
    }

    fn conn(&self) -> Result<r2d2::PooledConnection<SqliteConnectionManager>, String> {
        self.pool.get().map_err(|e| format!("DB pool error: {}", e))
    }

    fn migrate(&self) -> Result<(), String> {
        let conn = self.conn()?;
        conn.execute_batch(
            "PRAGMA journal_mode=WAL;
             PRAGMA busy_timeout=5000;",
        )
            .map_err(|e| format!("Failed to set pragmas: {}", e))?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS paired_devices (
                device_id TEXT PRIMARY KEY,
                client_device_id TEXT NOT NULL DEFAULT '',
                ip TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                user_agent TEXT NOT NULL DEFAULT '',
                paired_at INTEGER NOT NULL,
                last_seen INTEGER NOT NULL
            );
            CREATE UNIQUE INDEX IF NOT EXISTS idx_paired_devices_client_device_id
                ON paired_devices(client_device_id) WHERE client_device_id != '';
            CREATE TABLE IF NOT EXISTS bridge_config (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS config (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            );"
        ).map_err(|e| format!("Migration failed: {}", e))?;
        Ok(())
    }

    pub fn insert_device(&self, device: &PairedDevice) -> Result<(), String> {
        let conn = self.conn()?;
        conn.execute(
            "INSERT INTO paired_devices (device_id, client_device_id, ip, name, user_agent, paired_at, last_seen)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                device.device_id,
                device.client_device_id,
                device.ip,
                device.name,
                device.user_agent,
                device.paired_at,
                device.last_seen,
            ],
        ).map_err(|e| format!("Insert failed: {}", e))?;
        Ok(())
    }

    pub fn list_devices(&self) -> Result<Vec<PairedDevice>, String> {
        let conn = self.conn()?;
        let mut stmt = conn
            .prepare(
                "SELECT device_id, client_device_id, ip, name, user_agent, paired_at, last_seen
                 FROM paired_devices ORDER BY paired_at DESC",
            )
            .map_err(|e| format!("Prepare failed: {}", e))?;
        let devices = stmt
            .query_map([], |row| {
                Ok(PairedDevice {
                    device_id: row.get(0)?,
                    client_device_id: row.get(1)?,
                    ip: row.get(2)?,
                    name: row.get(3)?,
                    user_agent: row.get(4)?,
                    paired_at: row.get(5)?,
                    last_seen: row.get(6)?,
                })
            })
            .map_err(|e| format!("Query failed: {}", e))?
            .filter_map(|r| r.ok())
            .collect();
        Ok(devices)
    }

    pub fn device_exists(&self, device_id: &str) -> Result<bool, String> {
        let conn = self.conn()?;
        let result = conn.query_row(
            "SELECT COUNT(*) FROM paired_devices WHERE device_id = ?1",
            params![device_id],
            |row| row.get::<_, i64>(0),
        );
        match result {
            Ok(count) => Ok(count > 0),
            Err(e) => Err(format!("Query failed: {}", e)),
        }
    }

    pub fn update_last_seen(&self, device_id: &str, last_seen: i64) -> Result<(), String> {
        let conn = self.conn()?;
        let rows = conn
            .execute(
                "UPDATE paired_devices SET last_seen = ?1 WHERE device_id = ?2",
                params![last_seen, device_id],
            )
            .map_err(|e| format!("Update failed: {}", e))?;
        if rows == 0 {
            return Err("Device not found".to_string());
        }
        Ok(())
    }

    pub fn find_by_client_device_id(&self, client_device_id: &str) -> Result<Option<PairedDevice>, String> {
        let conn = self.conn()?;
        let result = conn.query_row(
            "SELECT device_id, client_device_id, ip, name, user_agent, paired_at, last_seen
             FROM paired_devices WHERE client_device_id = ?1",
            params![client_device_id],
            |row| {
                Ok(PairedDevice {
                    device_id: row.get(0)?,
                    client_device_id: row.get(1)?,
                    ip: row.get(2)?,
                    name: row.get(3)?,
                    user_agent: row.get(4)?,
                    paired_at: row.get(5)?,
                    last_seen: row.get(6)?,
                })
            },
        );
        match result {
            Ok(dev) => Ok(Some(dev)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(format!("Query failed: {}", e)),
        }
    }

    pub fn update_device(&self, device: &PairedDevice) -> Result<(), String> {
        let conn = self.conn()?;
        conn.execute(
            "UPDATE paired_devices SET ip = ?1, name = ?2, last_seen = ?3 WHERE device_id = ?4",
            params![device.ip, device.name, device.last_seen, device.device_id],
        ).map_err(|e| format!("Update failed: {}", e))?;
        Ok(())
    }

    pub fn rename_device(&self, device_id: &str, new_name: &str) -> Result<(), String> {
        let conn = self.conn()?;
        let rows = conn
            .execute(
                "UPDATE paired_devices SET name = ?1 WHERE device_id = ?2",
                params![new_name, device_id],
            )
            .map_err(|e| format!("Rename failed: {}", e))?;
        if rows == 0 {
            return Err(format!("Device not found: {}", device_id));
        }
        Ok(())
    }

    pub fn get_bridge_id(&self) -> Result<Option<String>, String> {
        let conn = self.conn()?;
        let result = conn.query_row(
            "SELECT value FROM bridge_config WHERE key = 'bridge_id'",
            [],
            |row| row.get::<_, String>(0),
        );
        match result {
            Ok(id) => Ok(Some(id)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(format!("Query failed: {}", e)),
        }
    }

    pub fn set_bridge_id(&self, id: &str) -> Result<(), String> {
        let conn = self.conn()?;
        conn.execute(
            "INSERT OR REPLACE INTO bridge_config (key, value) VALUES ('bridge_id', ?1)",
            params![id],
        ).map_err(|e| format!("Insert failed: {}", e))?;
        Ok(())
    }

    pub fn delete_device(&self, device_id: &str) -> Result<(), String> {
        let conn = self.conn()?;
        let rows = conn
            .execute(
                "DELETE FROM paired_devices WHERE device_id = ?1",
                params![device_id],
            )
            .map_err(|e| format!("Delete failed: {}", e))?;
        if rows == 0 {
            return Err(format!("Device not found: {}", device_id));
        }
        Ok(())
    }

    pub fn get_config(&self, key: &str) -> Result<Option<String>, String> {
        let conn = self.conn()?;
        let result = conn.query_row(
            "SELECT value FROM config WHERE key = ?1",
            params![key],
            |row| row.get::<_, String>(0),
        );
        match result {
            Ok(val) => Ok(Some(val)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(format!("Query failed: {}", e)),
        }
    }

    pub fn set_config(&self, key: &str, value: &str) -> Result<(), String> {
        let conn = self.conn()?;
        let now = chrono::Utc::now().timestamp();
        conn.execute(
            "INSERT OR REPLACE INTO config (key, value, updated_at) VALUES (?1, ?2, ?3)",
            params![key, value, now],
        ).map_err(|e| format!("Insert failed: {}", e))?;
        Ok(())
    }

    pub fn get_all_configs(&self) -> Result<Vec<(String, String)>, String> {
        let conn = self.conn()?;
        let mut stmt = conn
            .prepare("SELECT key, value FROM config ORDER BY key")
            .map_err(|e| format!("Prepare failed: {}", e))?;
        let rows = stmt
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })
            .map_err(|e| format!("Query failed: {}", e))?
            .filter_map(|r| r.ok())
            .collect();
        Ok(rows)
    }

    pub fn init_default_configs(&self) -> Result<(), String> {
        let configs = self.get_all_configs()?;
        if !configs.is_empty() {
            return Ok(());
        }
        let defaults = Self::default_config_values();
        for (key, value) in &defaults {
            self.set_config(key, value)?;
        }
        Ok(())
    }

    fn default_config_values() -> Vec<(&'static str, String)> {
        let db_path = crate::config::default_db_path();
        let secret_key = crate::auth::key::hex_encode(&crate::auth::key::generate_random_bytes(32));
        let instance_id = crate::auth::key::hex_encode(&crate::auth::key::generate_random_bytes(16));
        vec![
            ("bridge.port", "4097".to_string()),
            ("bridge.hostname", "0.0.0.0".to_string()),
            ("opencode.binary", "opencode".to_string()),
            ("opencode.hostname", "127.0.0.1".to_string()),
            ("opencode.port", "4096".to_string()),
            ("opencode.directory", String::new()),
            ("opencode.auto_start", "true".to_string()),
            ("opencode.auto_restart", "true".to_string()),
            ("opencode.db_path", db_path),
            ("fs.allowed_paths", String::new()),
            ("gateway.url", "https://gateway.clawmate.net".to_string()),
            ("gateway.auto_connect", "true".to_string()),
            ("auth.secret_key", secret_key),
            ("auth.instance_id", instance_id),
        ]
    }

    pub fn set_configs_batch(&self, entries: &[(String, String)]) -> Result<(), String> {
        let conn = self.conn()?;
        let now = chrono::Utc::now().timestamp();
        for (key, value) in entries {
            conn.execute(
                "INSERT OR REPLACE INTO config (key, value, updated_at) VALUES (?1, ?2, ?3)",
                params![key, value, now],
            ).map_err(|e| format!("Insert failed for key '{}': {}", key, e))?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_db() -> (BridgeDb, tempfile::TempDir) {
        let dir = tempfile::tempdir().expect("Failed to create temp dir");
        let db_path = dir.path().join("test_bridge.db");
        let db = BridgeDb::open_at(&db_path).expect("Failed to open test DB");
        (db, dir)
    }

    fn sample_device(id: &str) -> PairedDevice {
        PairedDevice {
            device_id: id.to_string(),
            client_device_id: String::new(),
            ip: "192.168.1.100".to_string(),
            name: "Test Device".to_string(),
            user_agent: "OpenMate/1.0".to_string(),
            paired_at: 1000,
            last_seen: 2000,
        }
    }

    #[test]
    fn test_open_creates_db() {
        let dir = tempfile::tempdir().expect("Failed to create temp dir");
        let db_path = dir.path().join("new_bridge.db");
        assert!(!db_path.exists());
        let _db = BridgeDb::open_at(&db_path).expect("Failed to open DB");
        assert!(db_path.exists());
    }

    #[test]
    fn test_insert_and_list_devices() {
        let (db, _dir) = temp_db();
        let d1 = sample_device("dev-1");
        let d2 = PairedDevice {
            device_id: "dev-2".to_string(),
            client_device_id: String::new(),
            ip: "10.0.0.1".to_string(),
            name: "Phone".to_string(),
            user_agent: "OpenMate/2.0".to_string(),
            paired_at: 3000,
            last_seen: 4000,
        };
        db.insert_device(&d1).unwrap();
        db.insert_device(&d2).unwrap();
        let devices = db.list_devices().unwrap();
        assert_eq!(devices.len(), 2);
        assert_eq!(devices[0].device_id, "dev-2");
        assert_eq!(devices[1].device_id, "dev-1");
    }

    #[test]
    fn test_device_exists() {
        let (db, _dir) = temp_db();
        assert!(!db.device_exists("dev-1").unwrap());
        db.insert_device(&sample_device("dev-1")).unwrap();
        assert!(db.device_exists("dev-1").unwrap());
    }

    #[test]
    fn test_update_last_seen() {
        let (db, _dir) = temp_db();
        db.insert_device(&sample_device("dev-1")).unwrap();
        db.update_last_seen("dev-1", 9999).unwrap();
        let devices = db.list_devices().unwrap();
        assert_eq!(devices[0].last_seen, 9999);
    }

    #[test]
    fn test_update_last_seen_nonexistent_fails() {
        let (db, _dir) = temp_db();
        let result = db.update_last_seen("no-such-device", 9999);
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Device not found"));
    }

    #[test]
    fn test_rename_device() {
        let (db, _dir) = temp_db();
        db.insert_device(&sample_device("dev-1")).unwrap();
        db.rename_device("dev-1", "Renamed").unwrap();
        let devices = db.list_devices().unwrap();
        assert_eq!(devices[0].name, "Renamed");
    }

    #[test]
    fn test_rename_nonexistent_fails() {
        let (db, _dir) = temp_db();
        let result = db.rename_device("no-such-device", "Name");
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Device not found"));
    }

    #[test]
    fn test_delete_device() {
        let (db, _dir) = temp_db();
        db.insert_device(&sample_device("dev-1")).unwrap();
        assert!(db.device_exists("dev-1").unwrap());
        db.delete_device("dev-1").unwrap();
        assert!(!db.device_exists("dev-1").unwrap());
    }

    #[test]
    fn test_delete_nonexistent_fails() {
        let (db, _dir) = temp_db();
        let result = db.delete_device("no-such-device");
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("Device not found"));
    }

    #[test]
    fn test_list_empty() {
        let (db, _dir) = temp_db();
        let devices = db.list_devices().unwrap();
        assert!(devices.is_empty());
    }

    #[test]
    fn test_migrate_idempotent() {
        let (db, _dir) = temp_db();
        db.migrate().unwrap();
        db.migrate().unwrap();
        db.insert_device(&sample_device("dev-1")).unwrap();
        assert_eq!(db.list_devices().unwrap().len(), 1);
    }

    #[test]
    fn test_insert_duplicate_fails() {
        let (db, _dir) = temp_db();
        db.insert_device(&sample_device("dev-1")).unwrap();
        let result = db.insert_device(&sample_device("dev-1"));
        assert!(result.is_err());
    }

    #[test]
    fn test_db_is_cloneable() {
        let (db, _dir) = temp_db();
        let db2 = db.clone();
        db.insert_device(&sample_device("dev-1")).unwrap();
        assert!(db2.device_exists("dev-1").unwrap());
    }

    #[test]
    fn test_get_config_missing_key() {
        let (db, _dir) = temp_db();
        assert!(db.get_config("nonexistent").unwrap().is_none());
    }

    #[test]
    fn test_set_and_get_config() {
        let (db, _dir) = temp_db();
        db.set_config("test.key", "test_value").unwrap();
        assert_eq!(db.get_config("test.key").unwrap(), Some("test_value".to_string()));
    }

    #[test]
    fn test_set_config_overwrite() {
        let (db, _dir) = temp_db();
        db.set_config("test.key", "v1").unwrap();
        db.set_config("test.key", "v2").unwrap();
        assert_eq!(db.get_config("test.key").unwrap(), Some("v2".to_string()));
    }

    #[test]
    fn test_get_all_configs_empty() {
        let (db, _dir) = temp_db();
        let configs = db.get_all_configs().unwrap();
        assert!(configs.is_empty());
    }

    #[test]
    fn test_get_all_configs_multiple() {
        let (db, _dir) = temp_db();
        db.set_config("b.key", "2").unwrap();
        db.set_config("a.key", "1").unwrap();
        let configs = db.get_all_configs().unwrap();
        assert_eq!(configs.len(), 2);
        assert_eq!(configs[0], ("a.key".to_string(), "1".to_string()));
        assert_eq!(configs[1], ("b.key".to_string(), "2".to_string()));
    }

    #[test]
    fn test_init_default_configs_populates() {
        let (db, _dir) = temp_db();
        db.init_default_configs().unwrap();
        let configs = db.get_all_configs().unwrap();
        assert!(!configs.is_empty());
        assert!(db.get_config("bridge.port").unwrap().is_some());
        assert!(db.get_config("auth.secret_key").unwrap().is_some());
    }

    #[test]
    fn test_init_default_configs_idempotent() {
        let (db, _dir) = temp_db();
        db.init_default_configs().unwrap();
        db.set_config("bridge.port", "9999").unwrap();
        db.init_default_configs().unwrap();
        assert_eq!(db.get_config("bridge.port").unwrap(), Some("9999".to_string()));
    }

    #[test]
    fn test_set_configs_batch() {
        let (db, _dir) = temp_db();
        let entries = vec![
            ("k1".to_string(), "v1".to_string()),
            ("k2".to_string(), "v2".to_string()),
        ];
        db.set_configs_batch(&entries).unwrap();
        assert_eq!(db.get_config("k1").unwrap(), Some("v1".to_string()));
        assert_eq!(db.get_config("k2").unwrap(), Some("v2".to_string()));
    }
}
