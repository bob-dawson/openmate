# 步骤 01: BridgeDb — Bridge 专用数据库

> 依赖: 无
> 产出: `src/bridge_db.rs`（新建），`src/lib.rs`（加 mod 声明）

## 背景

现有 SyncDb 只读 opencode 的 DB（`~/.local/share/opencode/opencode.db`）。Bridge 需要自己的可写 SQLite 数据库来存储 paired_devices 等管理数据。

DB 文件路径: `~/.opencode/bridge.db`（与 `bridge_secret_key` 同目录）

## 实现步骤

### Step 1: 创建 `src/bridge_db.rs` 基础结构

```rust
use rusqlite::{params, Connection, OpenFlags};
use r2d2::Pool;
use r2d2_sqlite::SqliteConnectionManager;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedDevice {
    pub device_id: String,
    pub ip: String,
    pub name: Option<String>,
    pub user_agent: Option<String>,
    pub paired_at: String,
    pub last_seen: Option<String>,
}

pub struct BridgeDb {
    pool: Pool<SqliteConnectionManager>,
}

impl BridgeDb {
    pub fn open() -> Result<Self, String> {
        let db_path = Self::db_path()?;
        Self::ensure_dir(&db_path)?;

        let manager = SqliteConnectionManager::file(&db_path)
            .with_flags(
                OpenFlags::SQLITE_OPEN_READ_WRITE
                    | OpenFlags::SQLITE_OPEN_CREATE
                    | OpenFlags::SQLITE_OPEN_NO_MUTEX,
            );
        let pool = Pool::builder()
            .max_size(2)
            .connection_timeout(std::time::Duration::from_secs(5))
            .build(manager)
            .map_err(|e| format!("BridgeDb pool error: {}", e))?;

        let db = Self { pool };
        db.migrate()?;
        Ok(db)
    }

    fn db_path() -> Result<PathBuf, String> {
        let base = dirs::home_dir().ok_or("Cannot determine home directory")?;
        Ok(base.join(".opencode").join("bridge.db"))
    }

    fn ensure_dir(path: &PathBuf) -> Result<(), String> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("Failed to create BridgeDb dir: {}", e))?;
        }
        Ok(())
    }

    fn conn(&self) -> Result<r2d2::PooledConnection<SqliteConnectionManager>, String> {
        self.pool.get().map_err(|e| format!("BridgeDb conn error: {}", e))
    }

    fn migrate(&self) -> Result<(), String> {
        let conn = self.conn()?;
        conn.execute_batch(
            "PRAGMA journal_mode=WAL;
             PRAGMA busy_timeout=5000;
             CREATE TABLE IF NOT EXISTS paired_devices (
                 device_id   TEXT PRIMARY KEY,
                 ip          TEXT NOT NULL,
                 name        TEXT,
                 user_agent  TEXT,
                 paired_at   TEXT NOT NULL,
                 last_seen   TEXT
             );"
        ).map_err(|e| format!("BridgeDb migration failed: {}", e))?;
        Ok(())
    }
}
```

### Step 2: 实现 paired_devices CRUD

在 `impl BridgeDb` 中添加：

```rust
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
        ).map_err(|e| format!("Insert device failed: {}", e))?;
        Ok(())
    }

    pub fn list_devices(&self) -> Result<Vec<PairedDevice>, String> {
        let conn = self.conn()?;
        let mut stmt = conn.prepare(
            "SELECT device_id, ip, name, user_agent, paired_at, last_seen
             FROM paired_devices ORDER BY last_seen DESC"
        ).map_err(|e| format!("List devices prepare failed: {}", e))?;

        let devices = stmt.query_map([], |row| {
            Ok(PairedDevice {
                device_id: row.get(0)?,
                ip: row.get(1)?,
                name: row.get(2)?,
                user_agent: row.get(3)?,
                paired_at: row.get(4)?,
                last_seen: row.get(5)?,
            })
        }).map_err(|e| format!("List devices query failed: {}", e))?
          .filter_map(|r| r.ok())
          .collect();

        Ok(devices)
    }

    pub fn device_exists(&self, device_id: &str) -> Result<bool, String> {
        let conn = self.conn()?;
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM paired_devices WHERE device_id = ?1",
            params![device_id],
            |row| row.get(0),
        ).map_err(|e| format!("Device exists check failed: {}", e))?;
        Ok(count > 0)
    }

    pub fn update_last_seen(&self, device_id: &str, last_seen: &str) -> Result<(), String> {
        let conn = self.conn()?;
        conn.execute(
            "UPDATE paired_devices SET last_seen = ?1 WHERE device_id = ?2",
            params![last_seen, device_id],
        ).map_err(|e| format!("Update last_seen failed: {}", e))?;
        Ok(())
    }

    pub fn rename_device(&self, device_id: &str, name: &str) -> Result<(), String> {
        let conn = self.conn()?;
        conn.execute(
            "UPDATE paired_devices SET name = ?1 WHERE device_id = ?2",
            params![name, device_id],
        ).map_err(|e| format!("Rename device failed: {}", e))?;
        Ok(())
    }

    pub fn delete_device(&self, device_id: &str) -> Result<(), String> {
        let conn = self.conn()?;
        conn.execute(
            "DELETE FROM paired_devices WHERE device_id = ?1",
            params![device_id],
        ).map_err(|e| format!("Delete device failed: {}", e))?;
        Ok(())
    }
```

### Step 3: 修改 `src/lib.rs` 加 mod 声明

```rust
pub mod bridge_db;  // 新增
```

### Step 4: 单元测试

在 `src/bridge_db.rs` 底部添加：

```rust
#[cfg(test)]
mod tests {
    use super::*;

    fn test_db() -> BridgeDb {
        let tmp = std::env::temp_dir().join("bridge_test_db");
        let _ = std::fs::remove_file(&tmp);
        let manager = SqliteConnectionManager::file(&tmp)
            .with_flags(
                OpenFlags::SQLITE_OPEN_READ_WRITE
                    | OpenFlags::SQLITE_OPEN_CREATE
                    | OpenFlags::SQLITE_OPEN_NO_MUTEX,
            );
        let pool = Pool::builder()
            .max_size(1)
            .build(manager)
            .unwrap();
        let db = BridgeDb { pool };
        db.migrate().unwrap();
        db
    }

    fn sample_device(id: &str) -> PairedDevice {
        PairedDevice {
            device_id: id.to_string(),
            ip: "192.168.1.55".to_string(),
            name: Some("Test Device".to_string()),
            user_agent: None,
            paired_at: "2026-05-19T14:00:00Z".to_string(),
            last_seen: None,
        }
    }

    #[test]
    fn test_insert_and_list() {
        let db = test_db();
        db.insert_device(&sample_device("dev-001")).unwrap();
        db.insert_device(&sample_device("dev-002")).unwrap();
        let devices = db.list_devices().unwrap();
        assert_eq!(devices.len(), 2);
    }

    #[test]
    fn test_device_exists() {
        let db = test_db();
        db.insert_device(&sample_device("dev-001")).unwrap();
        assert!(db.device_exists("dev-001").unwrap());
        assert!(!db.device_exists("dev-999").unwrap());
    }

    #[test]
    fn test_update_last_seen() {
        let db = test_db();
        db.insert_device(&sample_device("dev-001")).unwrap();
        db.update_last_seen("dev-001", "2026-05-19T15:00:00Z").unwrap();
        let devices = db.list_devices().unwrap();
        assert_eq!(devices[0].last_seen.as_deref(), Some("2026-05-19T15:00:00Z"));
    }

    #[test]
    fn test_rename_device() {
        let db = test_db();
        db.insert_device(&sample_device("dev-001")).unwrap();
        db.rename_device("dev-001", "New Name").unwrap();
        let devices = db.list_devices().unwrap();
        assert_eq!(devices[0].name.as_deref(), Some("New Name"));
    }

    #[test]
    fn test_delete_device() {
        let db = test_db();
        db.insert_device(&sample_device("dev-001")).unwrap();
        db.delete_device("dev-001").unwrap();
        assert!(!db.device_exists("dev-001").unwrap());
    }
}
```

### Step 5: 验证

```powershell
cargo test bridge_db
```

### Step 6: 提交

```
feat(bridge): add BridgeDb with paired_devices table and CRUD
```
