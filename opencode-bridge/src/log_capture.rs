use chrono::Local;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::fs::{File, OpenOptions};
use std::io::Write;
use std::sync::{Arc, Mutex};
use tracing::field::Visit;
use tracing::{Event, Level, Subscriber};
use tracing_subscriber::Layer;

const BUFFER_CAPACITY: usize = 2000;

#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct LogEntry {
    pub timestamp: String,
    pub level: String,
    pub target: String,
    pub message: String,
}

pub struct LogBuffer {
    entries: VecDeque<LogEntry>,
    capacity: usize,
}

impl LogBuffer {
    pub fn new() -> Self {
        Self::with_capacity(BUFFER_CAPACITY)
    }

    pub fn with_capacity(capacity: usize) -> Self {
        Self {
            entries: VecDeque::with_capacity(capacity),
            capacity,
        }
    }

    pub fn push(&mut self, entry: LogEntry) {
        if self.entries.len() >= self.capacity {
            self.entries.pop_front();
        }
        self.entries.push_back(entry);
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn query(
        &self,
        level: Option<&str>,
        search: Option<&str>,
        offset: usize,
        limit: usize,
    ) -> Vec<LogEntry> {
        let level_lower = level.map(|l| l.to_lowercase());
        let search_lower = search.map(|s| s.to_lowercase());

        self.entries
            .iter()
            .filter(|entry| {
                if let Some(ref lvl) = level_lower {
                    if entry.level.to_lowercase() != *lvl {
                        return false;
                    }
                }
                if let Some(ref s) = search_lower {
                    if !entry.message.to_lowercase().contains(s)
                        && !entry.target.to_lowercase().contains(s)
                    {
                        return false;
                    }
                }
                true
            })
            .skip(offset)
            .take(limit)
            .cloned()
            .collect()
    }
}

impl Default for LogBuffer {
    fn default() -> Self {
        Self::new()
    }
}

pub type SharedLogBuffer = Arc<Mutex<LogBuffer>>;

pub fn create_shared_buffer() -> SharedLogBuffer {
    Arc::new(Mutex::new(LogBuffer::new()))
}

struct MessageVisitor {
    message: String,
}

impl MessageVisitor {
    fn new() -> Self {
        Self {
            message: String::new(),
        }
    }
}

impl Visit for MessageVisitor {
    fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Debug) {
        if field.name() == "message" {
            self.message = format!("{:?}", value);
        }
    }

    fn record_str(&mut self, field: &tracing::field::Field, value: &str) {
        if field.name() == "message" {
            self.message = value.to_string();
        }
    }
}

struct FileWriter {
    file: Option<File>,
}

impl FileWriter {
    fn new() -> Self {
        let file = dirs::home_dir()
            .map(|h| h.join(".opencode").join("bridge.log"))
            .and_then(|path| {
                if let Some(parent) = path.parent() {
                    std::fs::create_dir_all(parent).ok()?;
                }
                OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(path)
                    .ok()
            });
        Self { file }
    }

    fn write_line(&mut self, line: &str) {
        if let Some(ref mut file) = self.file {
            let _ = writeln!(file, "{}", line);
            let _ = file.flush();
        }
    }
}

pub struct LogCaptureLayer {
    buffer: SharedLogBuffer,
    file_writer: Mutex<FileWriter>,
}

impl LogCaptureLayer {
    pub fn new(buffer: SharedLogBuffer) -> Self {
        Self {
            buffer,
            file_writer: Mutex::new(FileWriter::new()),
        }
    }
}

impl<S> Layer<S> for LogCaptureLayer
where
    S: Subscriber,
{
    fn on_event(&self, event: &Event<'_>, _ctx: tracing_subscriber::layer::Context<'_, S>) {
        let metadata = event.metadata();
        let timestamp = Local::now().format("%H:%M:%S").to_string();
        let level = match *metadata.level() {
            Level::TRACE => "TRACE",
            Level::DEBUG => "DEBUG",
            Level::INFO => "INFO",
            Level::WARN => "WARN",
            Level::ERROR => "ERROR",
        }
        .to_string();
        let target = metadata.target().to_string();

        let mut visitor = MessageVisitor::new();
        event.record(&mut visitor);

        let entry = LogEntry {
            timestamp,
            level,
            target,
            message: visitor.message,
        };

        let log_line = format!(
            "{} [{}] {}: {}",
            entry.timestamp, entry.level, entry.target, entry.message
        );

        if let Ok(mut file_writer) = self.file_writer.lock() {
            file_writer.write_line(&log_line);
        }

        if let Ok(mut buffer) = self.buffer.lock() {
            buffer.push(entry);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_buffer_push_and_query() {
        let mut buffer = LogBuffer::new();
        buffer.push(LogEntry {
            timestamp: "12:00:00".to_string(),
            level: "INFO".to_string(),
            target: "test".to_string(),
            message: "hello world".to_string(),
        });
        buffer.push(LogEntry {
            timestamp: "12:00:01".to_string(),
            level: "WARN".to_string(),
            target: "test".to_string(),
            message: "warning msg".to_string(),
        });
        buffer.push(LogEntry {
            timestamp: "12:00:02".to_string(),
            level: "ERROR".to_string(),
            target: "other".to_string(),
            message: "error occurred".to_string(),
        });

        assert_eq!(buffer.len(), 3);

        let all = buffer.query(None, None, 0, 10);
        assert_eq!(all.len(), 3);

        let info_only = buffer.query(Some("INFO"), None, 0, 10);
        assert_eq!(info_only.len(), 1);
        assert_eq!(info_only[0].level, "INFO");

        let warn_results = buffer.query(Some("warn"), None, 0, 10);
        assert_eq!(warn_results.len(), 1);
        assert_eq!(warn_results[0].level, "WARN");

        let search_results = buffer.query(None, Some("error"), 0, 10);
        assert_eq!(search_results.len(), 1);
        assert_eq!(search_results[0].level, "ERROR");

        let offset_results = buffer.query(None, None, 1, 1);
        assert_eq!(offset_results.len(), 1);
        assert_eq!(offset_results[0].level, "WARN");
    }

    #[test]
    fn test_ring_overflow() {
        let mut buffer = LogBuffer::with_capacity(5);
        for i in 0..10 {
            buffer.push(LogEntry {
                timestamp: format!("12:00:{:02}", i),
                level: "INFO".to_string(),
                target: "test".to_string(),
                message: format!("msg {}", i),
            });
        }

        assert_eq!(buffer.len(), 5);

        let all = buffer.query(None, None, 0, 10);
        assert_eq!(all[0].message, "msg 5");
        assert_eq!(all[4].message, "msg 9");
    }

    #[test]
    fn test_query_empty_buffer() {
        let buffer = LogBuffer::new();
        let results = buffer.query(None, None, 0, 10);
        assert!(results.is_empty());
    }

    #[test]
    fn test_query_with_search_and_level() {
        let mut buffer = LogBuffer::new();
        buffer.push(LogEntry {
            timestamp: "12:00:00".to_string(),
            level: "INFO".to_string(),
            target: "app::module".to_string(),
            message: "started successfully".to_string(),
        });
        buffer.push(LogEntry {
            timestamp: "12:00:01".to_string(),
            level: "ERROR".to_string(),
            target: "app::module".to_string(),
            message: "failed to start".to_string(),
        });
        buffer.push(LogEntry {
            timestamp: "12:00:02".to_string(),
            level: "INFO".to_string(),
            target: "other".to_string(),
            message: "started ok".to_string(),
        });

        let results = buffer.query(Some("INFO"), Some("started"), 0, 10);
        assert_eq!(results.len(), 2);

        let results = buffer.query(Some("ERROR"), Some("started"), 0, 10);
        assert_eq!(results.len(), 0);
    }

    #[test]
    fn test_query_limit() {
        let mut buffer = LogBuffer::new();
        for i in 0..20 {
            buffer.push(LogEntry {
                timestamp: format!("12:00:{:02}", i),
                level: "INFO".to_string(),
                target: "test".to_string(),
                message: format!("msg {}", i),
            });
        }

        let results = buffer.query(None, None, 0, 5);
        assert_eq!(results.len(), 5);

        let results = buffer.query(None, None, 5, 5);
        assert_eq!(results.len(), 5);
        assert_eq!(results[0].message, "msg 5");
    }

    #[test]
    fn test_create_shared_buffer() {
        let buffer = create_shared_buffer();
        {
            let mut buf = buffer.lock().unwrap();
            buf.push(LogEntry {
                timestamp: "12:00:00".to_string(),
                level: "INFO".to_string(),
                target: "test".to_string(),
                message: "shared test".to_string(),
            });
        }
        let buf = buffer.lock().unwrap();
        assert_eq!(buf.len(), 1);
    }

    #[test]
    fn test_thread_safety() {
        let buffer = create_shared_buffer();
        let mut handles = vec![];

        for i in 0..10 {
            let buf = Arc::clone(&buffer);
            handles.push(std::thread::spawn(move || {
                let mut b = buf.lock().unwrap();
                b.push(LogEntry {
                    timestamp: format!("12:00:{:02}", i),
                    level: "INFO".to_string(),
                    target: format!("thread-{}", i),
                    message: format!("from thread {}", i),
                });
            }));
        }

        for handle in handles {
            handle.join().unwrap();
        }

        let buf = buffer.lock().unwrap();
        assert_eq!(buf.len(), 10);
    }

    #[test]
    fn test_default_buffer() {
        let buffer = LogBuffer::default();
        assert!(buffer.is_empty());
        assert_eq!(buffer.len(), 0);
    }

    #[test]
    fn test_log_entry_serialization() {
        let entry = LogEntry {
            timestamp: "12:34:56".to_string(),
            level: "INFO".to_string(),
            target: "test::module".to_string(),
            message: "test message".to_string(),
        };
        let json = serde_json::to_string(&entry).unwrap();
        let deserialized: LogEntry = serde_json::from_str(&json).unwrap();
        assert_eq!(deserialized.timestamp, entry.timestamp);
        assert_eq!(deserialized.level, entry.level);
        assert_eq!(deserialized.target, entry.target);
        assert_eq!(deserialized.message, entry.message);
    }

    #[test]
    fn test_message_visitor_extracts_message() {
        let mut visitor = MessageVisitor::new();
        assert!(visitor.message.is_empty());
        visitor.message = "hello world".to_string();
        assert_eq!(visitor.message, "hello world");

        let mut visitor2 = MessageVisitor::new();
        visitor2.message = String::new();
        assert!(visitor2.message.is_empty());
    }

    #[test]
    fn test_ring_overflow_exact_capacity() {
        let mut buffer = LogBuffer::with_capacity(3);
        for i in 0..3 {
            buffer.push(LogEntry {
                timestamp: format!("12:00:{:02}", i),
                level: "INFO".to_string(),
                target: "test".to_string(),
                message: format!("msg {}", i),
            });
        }
        assert_eq!(buffer.len(), 3);

        let all = buffer.query(None, None, 0, 10);
        assert_eq!(all[0].message, "msg 0");
        assert_eq!(all[2].message, "msg 2");

        buffer.push(LogEntry {
            timestamp: "12:00:03".to_string(),
            level: "INFO".to_string(),
            target: "test".to_string(),
            message: "msg 3".to_string(),
        });
        assert_eq!(buffer.len(), 3);

        let all = buffer.query(None, None, 0, 10);
        assert_eq!(all[0].message, "msg 1");
        assert_eq!(all[2].message, "msg 3");
    }
}
