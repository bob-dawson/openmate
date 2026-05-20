# 步骤 02: LogCapture — 日志捕获系统

> 依赖: 无（可与步骤 01 并行）
> 产出: `src/log_capture.rs`（新建）

## 背景

Windows 子系统无控制台窗口，日志需要通过 Web UI 查看。需要一个自定义 tracing Layer 将日志事件捕获到内存环形缓冲区中。

## 实现步骤

### Step 1: 创建 `src/log_capture.rs`

```rust
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::SystemTime;
use serde::{Deserialize, Serialize};
use tracing::Subscriber;
use tracing_subscriber::Layer;

const BUFFER_CAPACITY: usize = 2000;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogEntry {
    pub timestamp: String,
    pub level: String,
    pub target: String,
    pub message: String,
}

pub struct LogBuffer {
    entries: VecDeque<LogEntry>,
}

impl LogBuffer {
    pub fn new() -> Self {
        Self {
            entries: VecDeque::with_capacity(BUFFER_CAPACITY),
        }
    }

    pub fn push(&mut self, entry: LogEntry) {
        if self.entries.len() >= BUFFER_CAPACITY {
            self.entries.pop_front();
        }
        self.entries.push_back(entry);
    }

    pub fn entries(&self) -> &VecDeque<LogEntry> {
        &self.entries
    }

    pub fn query(&self, level: Option<&str>, search: Option<&str>, offset: usize, limit: usize) -> Vec<LogEntry> {
        self.entries
            .iter()
            .filter(|e| {
                if let Some(lvl) = level {
                    if e.level != lvl { return false; }
                }
                if let Some(s) = search {
                    if !e.message.contains(s) && !e.target.contains(s) { return false; }
                }
                true
            })
            .skip(offset)
            .take(limit)
            .cloned()
            .collect()
    }
}

pub type SharedLogBuffer = Arc<Mutex<LogBuffer>>;

pub fn create_shared_buffer() -> SharedLogBuffer {
    Arc::new(Mutex::new(LogBuffer::new()))
}

pub struct LogCaptureLayer {
    buffer: SharedLogBuffer,
}

impl LogCaptureLayer {
    pub fn new(buffer: SharedLogBuffer) -> Self {
        Self { buffer }
    }
}

impl<S: Subscriber> Layer<S> for LogCaptureLayer {
    fn on_event(
        &self,
        event: &tracing::Event<'_>,
        _ctx: tracing_subscriber::layer::Context<'_, S>,
    ) {
        let metadata = event.metadata();
        let level = metadata.level().to_string();
        let target = metadata.target().to_string();

        let mut visitor = MessageVisitor::default();
        event.record(&mut visitor);

        let timestamp = format_timestamp();

        let entry = LogEntry {
            timestamp,
            level,
            target,
            message: visitor.message,
        };

        if let Ok(mut buf) = self.buffer.lock() {
            buf.push(entry);
        }
    }
}

#[derive(Default)]
struct MessageVisitor {
    message: String,
}

impl tracing::field::Visit for MessageVisitor {
    fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Debug) {
        if field.name() == "message" {
            self.message = format!("{:?}", value);
        } else {
            if !self.message.is_empty() {
                self.message.push(' ');
            }
            self.message.push_str(&format!("{}={:?}", field.name(), value));
        }
    }

    fn record_str(&mut self, field: &tracing::field::Field, value: &str) {
        if field.name() == "message" {
            self.message = value.to_string();
        }
    }
}

fn format_timestamp() -> String {
    let duration = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap_or_default();
    let secs = duration.as_secs();
    let hours = (secs / 3600) % 24 + 8; // UTC+8
    let minutes = (secs / 60) % 60;
    let seconds = secs % 60;
    // 简化: 只显示时分秒，不需要完整日期
    let days = secs / 86400;
    // 用 chrono 更好，但先用简化版
    format!("{:02}:{:02}:{:02}", hours, minutes, seconds)
}
```

### Step 2: 修改 `src/lib.rs`

```rust
pub mod log_capture;  // 新增
```

### Step 3: 修改 `src/state.rs`，在 AppState 中加入 log buffer

```rust
use crate::log_capture::SharedLogBuffer;

pub struct AppStateInner {
    pub config: Config,
    pub opencode_status: RwLock<OpencodeStatus>,
    pub opencode_manager: OpencodeManager,
    pub secret_key: auth::key::SecretKey,
    pub pending_pairs: RwLock<auth::pair::PairState>,
    pub sync_db: SyncDb,
    pub log_buffer: SharedLogBuffer,  // 新增
    pub bridge_db: crate::bridge_db::BridgeDb,  // 新增（步骤 01）
}
```

> 注：bridge_db 字段来自步骤 01。如果步骤 01 尚未完成，先只加 log_buffer。

### Step 4: 在 `src/server.rs` 或 `src/main.rs` 注册 LogCaptureLayer

在 tracing_subscriber 初始化时追加 layer：

```rust
let log_buffer = log_capture::create_shared_buffer();
let capture_layer = log_capture::LogCaptureLayer::new(log_buffer.clone());

tracing_subscriber::fmt()
    .with_env_filter(...)
    .finish()
    .with(capture_layer)
    .init();
```

> 同时保留文件日志输出到 `~/.opencode/bridge.log`，可通过 tracing-appender 或简单的 File::create 实现。

### Step 5: 单元测试

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_buffer_push_and_query() {
        let mut buf = LogBuffer::new();
        buf.push(LogEntry {
            timestamp: "14:00:01".into(),
            level: "INFO".into(),
            target: "test".into(),
            message: "hello world".into(),
        });
        buf.push(LogEntry {
            timestamp: "14:00:02".into(),
            level: "WARN".into(),
            target: "test".into(),
            message: "warning msg".into(),
        });

        assert_eq!(buf.entries().len(), 2);
        let info_only = buf.query(Some("INFO"), None, 0, 10);
        assert_eq!(info_only.len(), 1);
        assert_eq!(info_only[0].level, "INFO");

        let search = buf.query(None, Some("warning"), 0, 10);
        assert_eq!(search.len(), 1);
    }

    #[test]
    fn test_buffer_ring_overflow() {
        let mut buf = LogBuffer::new();
        for i in 0..2500 {
            buf.push(LogEntry {
                timestamp: format!("14:00:{:02}", i % 60),
                level: "INFO".into(),
                target: "test".into(),
                message: format!("msg {}", i),
            });
        }
        assert_eq!(buf.entries().len(), 2000);
    }

    #[test]
    fn test_shared_buffer_threaded() {
        let buffer = create_shared_buffer();
        let buf_clone = buffer.clone();
        std::thread::spawn(move || {
            let mut buf = buf_clone.lock().unwrap();
            buf.push(LogEntry {
                timestamp: "14:00:01".into(),
                level: "INFO".into(),
                target: "test".into(),
                message: "from thread".into(),
            });
        }).join().unwrap();

        let buf = buffer.lock().unwrap();
        assert_eq!(buf.entries().len(), 1);
    }
}
```

### Step 6: 验证

```powershell
cargo test log_capture
```

### Step 7: 提交

```
feat(bridge): add log capture system with ring buffer
```
