use rusqlite::params;
use r2d2::Pool;
use r2d2_sqlite::SqliteConnectionManager;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::Arc;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedDevice {
    pub device_id: String,
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
            .join(".opencode");
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
                ip TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                user_agent TEXT NOT NULL DEFAULT '',
                paired_at INTEGER NOT NULL,
                last_seen INTEGER NOT NULL
            );"
        ).map_err(|e| format!("Migration failed: {}", e))?;
        Ok(())
    }

    pub fn insert_device(&self, device: &PairedDevice) -> Result<(), String> {
        let conn = self.conn()?;
        conn.execute(
            "INSERT INTO paired_devices (device_id, ip, name, user_agent, paired_at, last_seen)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                device.device_id,
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
                "SELECT device_id, ip, name, user_agent, paired_at, last_seen
                 FROM paired_devices ORDER BY paired_at DESC",
            )
            .map_err(|e| format!("Prepare failed: {}", e))?;
        let devices = stmt
            .query_map([], |row| {
                Ok(PairedDevice {
                    device_id: row.get(0)?,
                    ip: row.get(1)?,
                    name: row.get(2)?,
                    user_agent: row.get(3)?,
                    paired_at: row.get(4)?,
                    last_seen: row.get(5)?,
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
}
