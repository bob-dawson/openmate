use serde_json::{Map, Value, json};

pub fn truncate_message(msg_type: &str, data: &Value) -> Value {
    match msg_type {
        "user" => truncate_user(data),
        "assistant" => truncate_assistant(data),
        "shell" => truncate_shell(data),
        "compaction" => truncate_compaction(data),
        _ => data.clone(),
    }
}

fn base_event_type(event_type: &str) -> &str {
    if let Some(pos) = event_type.rfind('.') {
        let suffix = &event_type[pos + 1..];
        if suffix.chars().all(|c| c.is_ascii_digit()) {
            return &event_type[..pos];
        }
    }
    event_type
}

pub fn truncate_event(event_type: &str, data: &Value) -> Value {
    let base = base_event_type(event_type);
    match base {
        "session.next.step.started" | "session.next.step.ended" => truncate_event_step(data),
        "session.next.reasoning.ended" => truncate_event_reasoning_ended(data),
        "session.next.tool.called" => truncate_event_tool_called(data),
        "session.next.tool.success" | "session.next.tool.progress" => {
            truncate_event_tool_result(data)
        }
        "session.next.compaction.ended" => truncate_event_compaction_ended(data),
        "session.next.shell.ended" => truncate_event_shell_ended(data),
        "session.next.prompted" => truncate_event_prompted(data),
        "message.part.updated" => truncate_event_part_updated(data),
        "message.updated" => truncate_event_message_updated(data),
        _ => data.clone(),
    }
}

fn truncate_event_step(data: &Value) -> Value {
    let mut result = data.clone();
    if let Some(obj) = result.as_object_mut() {
        obj.remove("snapshot");
    }
    result
}

fn truncate_event_reasoning_ended(data: &Value) -> Value {
    let mut result = data.clone();
    if let Some(obj) = result.as_object_mut() {
        if let Some(text) = obj.get("text").and_then(|t| t.as_str()) {
            let truncated = truncate_ends_chars(text, TEXT_KEEP);
            obj.insert(String::from("text"), Value::String(truncated));
        }
    }
    result
}

fn truncate_tool_input(tool_name: &str, input: &Value) -> Value {
    match tool_name {
        "shell" | "bash" => keep_fields(input, &["command", "description", "background"]),
        "read" => keep_fields(input, &["path", "filePath"]),
        "write" => keep_fields(input, &["path", "filePath"]),
        "edit" => keep_fields(input, &["path", "filePath"]),
        "patch" | "apply_patch" => keep_fields(input, &[]),
        "glob" => keep_fields(input, &["pattern", "path"]),
        "grep" => keep_fields(input, &["pattern", "path", "include"]),
        "subagent" | "task" => keep_fields(input, &["description", "agent", "subagent_type"]),
        "webfetch" => keep_fields(input, &["url", "format"]),
        "websearch" => keep_fields(input, &["query"]),
        "skill" => keep_fields(input, &["id", "name"]),
        "execute" => keep_fields(input, &["code"]),
        "lsp" => keep_fields(input, &["operation", "path", "filePath", "line", "character", "query"]),
        "question" | "todowrite" => input.clone(),
        _ => input.clone(),
    }
}

fn truncate_event_tool_called(data: &Value) -> Value {
    let mut result = data.clone();
    if let Some(obj) = result.as_object_mut() {
        if let Some(input) = obj.get("input").cloned() {
            let tool_name = obj
                .get("tool")
                .and_then(|t| t.as_str())
                .unwrap_or("");
            obj.insert(String::from("input"), truncate_tool_input(tool_name, &input));
        }
    }
    result
}

fn truncate_event_tool_result(data: &Value) -> Value {
    let mut result = data.clone();
    let obj = match result.as_object_mut() {
        Some(o) => o,
        None => return result,
    };

    if let Some(structured) = obj.get("structured") {
        if structured.to_string().len() > 500 {
            obj.remove("structured");
        }
    }

    if let Some(content) = obj.get_mut("content").and_then(|c| c.as_array_mut()) {
        for item in content.iter_mut() {
            match item.get("type").and_then(|t| t.as_str()) {
                Some("text") => {
                    if let Some(text) = item.get("text").and_then(|t| t.as_str()) {
                        if text.len() > 500 {
                            let truncated = truncate_bash_output(text, 5, 5);
                            if let Some(io) = item.as_object_mut() {
                                io.insert(String::from("text"), Value::String(truncated));
                            }
                        }
                    }
                }
                Some("file") | Some("image") => {
                    *item = sanitize_file_item(item);
                }
                _ => {}
            }
        }
    }

    if let Some(attachments) = obj.get_mut("attachments").and_then(|a| a.as_array_mut()) {
        for att in attachments.iter_mut() {
            if let Some(ao) = att.as_object_mut() {
                ao.remove("url");
            }
        }
    }

    result
}

fn truncate_event_compaction_ended(data: &Value) -> Value {
    let mut result = data.clone();
    if let Some(obj) = result.as_object_mut() {
        obj.remove("include");
        if let Some(text) = obj.get("text").and_then(|t| t.as_str()) {
            let truncated = truncate_ends_lines(text, 10, 10);
            obj.insert(String::from("text"), Value::String(truncated));
        }
    }
    result
}

fn truncate_event_shell_ended(data: &Value) -> Value {
    let mut result = data.clone();
    if let Some(obj) = result.as_object_mut() {
        if let Some(output) = obj.get("output").and_then(|o| o.as_str()) {
            let truncated = truncate_bash_output(output, 5, 5);
            obj.insert(String::from("output"), Value::String(truncated));
        }
    }
    result
}

fn truncate_event_prompted(data: &Value) -> Value {
    let mut result = data.clone();
    if let Some(obj) = result.as_object_mut() {
        if let Some(prompt) = obj.get_mut("prompt").and_then(|p| p.as_object_mut()) {
            if let Some(files) = prompt.get_mut("files").and_then(|f| f.as_array_mut()) {
                for file in files.iter_mut() {
                    if let Some(fo) = file.as_object_mut() {
                        if let Some(source) = fo.get_mut("source").and_then(|s| s.as_object_mut()) {
                            source.remove("text");
                        }
                        fo.remove("description");
                    }
                }
            }
            if let Some(agents) = prompt.get_mut("agents").and_then(|a| a.as_array_mut()) {
                for agent in agents.iter_mut() {
                    if let Some(ao) = agent.as_object_mut() {
                        ao.remove("source");
                    }
                }
            }
        }
    }
    result
}

fn truncate_event_message_updated(data: &Value) -> Value {
    let mut result = data.clone();
    if let Some(obj) = result.as_object_mut() {
        if let Some(info) = obj.get_mut("info").and_then(|i| i.as_object_mut()) {
            if let Some(summary) = info.get_mut("summary").and_then(|s| s.as_object_mut()) {
                summary.remove("diffs");
            }
        }
    }
    result
}

fn truncate_event_part_updated(data: &Value) -> Value {
    let mut result = data.clone();
    if let Some(obj) = result.as_object_mut() {
        if let Some(part) = obj.get_mut("part").and_then(|p| p.as_object_mut()) {
            let part_type = part
                .get("type")
                .and_then(|t| t.as_str())
                .unwrap_or("")
                .to_string();

            match part_type.as_str() {
                "tool" => {
                    let tool_name = part
                        .get("name")
                        .and_then(|n| n.as_str())
                        .or_else(|| {
                            part.get("state")
                                .and_then(|s| s.get("name"))
                                .and_then(|n| n.as_str())
                        })
                        .unwrap_or("")
                        .to_string();
                    if let Some(state) = part.get("state").cloned() {
                        part.insert(String::from("state"), truncate_tool(&tool_name, &state));
                    }
                }
                "patch" => {
                    if let Some(files) =
                        part.get_mut("files").and_then(|f| f.as_array_mut())
                    {
                        for file in files.iter_mut() {
                            if let Some(fo) = file.as_object_mut() {
                                fo.remove("patch");
                            }
                        }
                    }
                }
                _ => {}
            }
        }
    }
    result
}

const TEXT_KEEP: usize = 1500;

fn truncate_user(data: &Value) -> Value {
    let mut result = data.clone();
    let obj = match result.as_object_mut() {
        Some(o) => o,
        None => return result,
    };

    if let Some(text) = obj.get("text").and_then(|t| t.as_str()) {
        let truncated = truncate_ends_chars(text, TEXT_KEEP);
        obj.insert(String::from("text"), Value::String(truncated));
    }

    if let Some(files) = obj.get_mut("files").and_then(|f| f.as_array_mut()) {
        for file in files.iter_mut() {
            *file = sanitize_file_item(file);
        }
    }

    if let Some(agents) = obj.get_mut("agents").and_then(|a| a.as_array_mut()) {
        for agent in agents.iter_mut() {
            if let Some(ao) = agent.as_object_mut() {
                ao.remove("source");
            }
        }
    }

    result
}

fn truncate_assistant(data: &Value) -> Value {
    let mut result = data.clone();
    let obj = match result.as_object_mut() {
        Some(o) => o,
        None => return result,
    };

    obj.remove("snapshot");
    obj.remove("metadata");
    obj.remove("diagnostics");

    if let Some(content) = obj.get_mut("content").and_then(|c| c.as_array_mut()) {
        for item in content.iter_mut() {
            let item_type = item.get("type").and_then(|t| t.as_str()).unwrap_or("");
            match item_type {
                "text" => {
                    if let Some(text) = item.get("text").and_then(|t| t.as_str()) {
                        let truncated = truncate_ends_chars(text, TEXT_KEEP);
                        if let Some(io) = item.as_object_mut() {
                            io.insert(String::from("text"), Value::String(truncated));
                        }
                    }
                }
                "reasoning" => {
                    if let Some(text) = item.get("text").and_then(|t| t.as_str()) {
                        let truncated = truncate_ends_chars(text, TEXT_KEEP);
                        if let Some(io) = item.as_object_mut() {
                            io.insert(String::from("text"), Value::String(truncated));
                        }
                    }
                }
                "tool" => {
                    let tool_name =
                        item.get("name").and_then(|n| n.as_str()).unwrap_or("").to_string();
                    if let Some(state) = item.get("state").cloned() {
                        let truncated_state = truncate_tool(&tool_name, &state);
                        if let Some(io) = item.as_object_mut() {
                            io.insert(String::from("state"), truncated_state);
                        }
                    }
                }
                "file" | "image" => {
                    *item = sanitize_file_item(item);
                }
                "patch" => {
                    *item = sanitize_patch_item(item);
                }
                _ => {}
            }
        }
    }

    result
}

fn truncate_shell(data: &Value) -> Value {
    let mut result = data.clone();
    let obj = match result.as_object_mut() {
        Some(o) => o,
        None => return result,
    };

    if let Some(output) = obj.get("output").and_then(|o| o.as_str()) {
        let truncated = truncate_bash_output(output, 5, 5);
        obj.insert(String::from("output"), Value::String(truncated));
    }

    result
}

fn truncate_compaction(data: &Value) -> Value {
    let mut result = data.clone();
    let obj = match result.as_object_mut() {
        Some(o) => o,
        None => return result,
    };

    obj.remove("include");

    if let Some(text) = obj.get("text").and_then(|t| t.as_str()) {
        let truncated = truncate_ends_lines(text, 10, 10);
        obj.insert(String::from("text"), Value::String(truncated));
    }

    result
}

fn truncate_ends_chars(text: &str, keep: usize) -> String {
    let chars: Vec<char> = text.chars().collect();
    if chars.len() <= keep * 2 {
        return text.to_string();
    }
    let head: String = chars[..keep].iter().collect();
    let tail: String = chars[chars.len() - keep..].iter().collect();
    format!("{}...[truncated]...{}", head, tail)
}

pub fn truncate_bash_output(output: &str, max_head: usize, max_tail: usize) -> String {
    let lines: Vec<&str> = output.lines().collect();
    if lines.is_empty() || lines.len() <= max_head + max_tail {
        return output.to_string();
    }
    let head: Vec<&str> = lines.iter().take(max_head).copied().collect();
    let tail: Vec<&str> = lines
        .iter()
        .rev()
        .take(max_tail)
        .copied()
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    let omitted = lines.len() - max_head - max_tail;
    format!(
        "{}\n... [{} lines truncated] ...\n{}",
        head.join("\n"),
        omitted,
        tail.join("\n")
    )
}

fn truncate_ends_lines(text: &str, head_keep: usize, tail_keep: usize) -> String {
    let lines: Vec<&str> = text.lines().collect();
    if lines.is_empty() || lines.len() <= head_keep + tail_keep {
        return text.to_string();
    }
    let head: Vec<&str> = lines.iter().take(head_keep).copied().collect();
    let tail: Vec<&str> = lines
        .iter()
        .rev()
        .take(tail_keep)
        .copied()
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    format!("{}\n...[truncated]...\n{}", head.join("\n"), tail.join("\n"))
}

fn keep_fields(obj: &Value, fields: &[&str]) -> Value {
    match obj.as_object() {
        Some(map) => {
            let mut result = Map::new();
            for &field in fields {
                if let Some(val) = map.get(field) {
                    result.insert(field.to_string(), val.clone());
                }
            }
            Value::Object(result)
        }
        None => obj.clone(),
    }
}

/// Maximum inline (data:) payload we are willing to mirror to the phone.
const MAX_INLINE_PAYLOAD: usize = 4096;
/// Maximum size for a single kept string value (e.g. execute code).
const MAX_KEPT_STRING: usize = 8000;

/// Keep only file metadata and drop inline binary payloads (data: URIs).
fn sanitize_file_item(item: &Value) -> Value {
    let mut result = Map::new();
    for key in ["type", "mime", "name", "filename"] {
        if let Some(value) = item.get(key) {
            result.insert(key.to_string(), value.clone());
        }
    }
    for key in ["uri", "url"] {
        if let Some(Value::String(value)) = item.get(key) {
            if !value.starts_with("data:") && value.len() <= MAX_INLINE_PAYLOAD {
                result.insert(key.to_string(), Value::String(value.clone()));
            }
        }
    }
    Value::Object(result)
}

/// Keep a patch content item: drop per-file patch text, keep paths and stats.
fn sanitize_patch_item(item: &Value) -> Value {
    let mut result = Map::new();
    for key in ["type", "hash"] {
        if let Some(value) = item.get(key) {
            result.insert(key.to_string(), value.clone());
        }
    }
    if let Some(files) = item.get("files").and_then(|f| f.as_array()) {
        let kept: Vec<Value> = files
            .iter()
            .map(|file| match file {
                Value::String(_) => file.clone(),
                _ => keep_fields(file, &["filePath", "file", "type", "additions", "deletions"]),
            })
            .collect();
        result.insert(String::from("files"), Value::Array(kept));
    }
    Value::Object(result)
}

/// Cap oversized string values (e.g. execute code) so they cannot slip through.
fn cap_value(value: &Value) -> Value {
    match value {
        Value::String(s) if s.len() > MAX_KEPT_STRING => {
            Value::String(truncate_bash_output(s, 40, 40))
        }
        Value::Array(items) => Value::Array(items.iter().map(cap_value).collect()),
        Value::Object(map) => {
            Value::Object(map.iter().map(|(k, v)| (k.clone(), cap_value(v))).collect())
        }
        other => other.clone(),
    }
}

fn truncate_tool(tool_name: &str, state: &Value) -> Value {
    match tool_name {
        "shell" | "bash" => build_state(
            state,
            &["command", "description", "background"],
            &["exit", "truncated"],
            ContentMode::Text,
        ),
        "read" => build_state(state, &["path", "filePath"], &["truncated"], ContentMode::Text),
        "write" => build_state(
            state,
            &["path", "filePath"],
            &["filepath", "exists", "truncated"],
            ContentMode::Text,
        ),
        "edit" => build_state(
            state,
            &["path", "filePath"],
            &["additions", "deletions"],
            ContentMode::Text,
        ),
        "patch" | "apply_patch" => truncate_tool_patch(state),
        "glob" => build_state(
            state,
            &["pattern", "path"],
            &["count", "truncated"],
            ContentMode::Text,
        ),
        "grep" => build_state(
            state,
            &["pattern", "path", "include"],
            &["matches", "truncated"],
            ContentMode::Text,
        ),
        "subagent" | "task" => build_state(
            state,
            &["description", "agent", "subagent_type"],
            &["sessionId", "sessionID", "model"],
            ContentMode::Text,
        ),
        "webfetch" => build_state(
            state,
            &["url", "format"],
            &["contentType", "truncated"],
            ContentMode::Text,
        ),
        "websearch" => build_state(
            state,
            &["query"],
            &["summary", "truncated"],
            ContentMode::Text,
        ),
        "skill" => build_state(
            state,
            &["id", "name"],
            &["name", "directory", "dir"],
            ContentMode::Skip,
        ),
        "execute" => build_state(
            state,
            &["code"],
            &["toolCalls", "truncated"],
            ContentMode::Skip,
        ),
        "question" => build_state(state, &["questions"], &["answers"], ContentMode::Text),
        "todowrite" => state.clone(),
        "lsp" => build_state(
            state,
            &["operation", "path", "filePath", "line", "character", "query"],
            &[],
            ContentMode::Text,
        ),
        "plan_exit" | "invalid" => build_state(state, &["tool", "error"], &[], ContentMode::Skip),
        _ => truncate_tool_unknown(state),
    }
}

fn build_state(
    state: &Value,
    input_keep: &[&str],
    metadata_keep: &[&str],
    content_mode: ContentMode,
) -> Value {
    let mut result = Map::new();

    if let Some(status) = state.get("status") {
        result.insert(String::from("status"), status.clone());
    }
    if let Some(error) = state.get("error") {
        result.insert(String::from("error"), cap_value(error));
    }

    if let Some(input) = state.get("input") {
        result.insert(
            String::from("input"),
            cap_value(&keep_fields(input, input_keep)),
        );
    }

    // V2 stores result metadata in `metadata`; V1 used `structured`.
    for key in ["metadata", "structured"] {
        if let Some(meta) = state.get(key) {
            let filtered = keep_fields(meta, metadata_keep);
            if !filtered.as_object().map_or(true, |m| m.is_empty()) {
                result.insert(key.to_string(), cap_value(&filtered));
            }
        }
    }

    if let Some(content) = state.get("content").and_then(|c| c.as_array()) {
        match content_mode {
            ContentMode::Skip => {}
            ContentMode::Text => {
                let items: Vec<Value> = content
                    .iter()
                    .map(|item| match item.get("type").and_then(|t| t.as_str()) {
                        Some("text") => {
                            let text =
                                item.get("text").and_then(|t| t.as_str()).unwrap_or("");
                            json!({"type": "text", "text": truncate_bash_output(text, 5, 5)})
                        }
                        _ => sanitize_file_item(item),
                    })
                    .collect();
                result.insert(String::from("content"), Value::Array(items));
            }
        }
    }

    if let Some(attachments) = state.get("attachments").and_then(|a| a.as_array()) {
        let filtered: Vec<Value> = attachments.iter().map(truncate_attachment).collect();
        result.insert(String::from("attachments"), Value::Array(filtered));
    }

    Value::Object(result)
}

#[derive(Clone, Copy)]
enum ContentMode {
    /// Drop tool result content entirely.
    Skip,
    /// Keep text (truncated) and strip binary payloads from other items.
    Text,
}

fn truncate_attachment(attachment: &Value) -> Value {
    keep_fields(attachment, &["type", "mime", "id", "sessionID", "messageID"])
}

fn truncate_tool_patch(state: &Value) -> Value {
    let mut result = Map::new();

    if let Some(status) = state.get("status") {
        result.insert(String::from("status"), status.clone());
    }
    if let Some(input) = state.get("input") {
        result.insert(
            String::from("input"),
            cap_value(&keep_fields(input, &[])),
        );
    }

    for key in ["metadata", "structured"] {
        if let Some(meta) = state.get(key) {
            if let Some(files) = meta.get("files").and_then(|f| f.as_array()) {
                let kept: Vec<Value> = files
                    .iter()
                    .map(|file| {
                        keep_fields(file, &["filePath", "file", "type", "additions", "deletions"])
                    })
                    .collect();
                result.insert(key.to_string(), json!({ "files": kept }));
            }
        }
    }

    if let Some(attachments) = state.get("attachments").and_then(|a| a.as_array()) {
        let filtered: Vec<Value> = attachments.iter().map(truncate_attachment).collect();
        result.insert(String::from("attachments"), Value::Array(filtered));
    }

    Value::Object(result)
}

fn truncate_tool_unknown(state: &Value) -> Value {
    let mut result = Map::new();

    if let Some(status) = state.get("status") {
        result.insert(String::from("status"), status.clone());
    }
    if let Some(error) = state.get("error") {
        result.insert(String::from("error"), cap_value(error));
    }
    if let Some(input) = state.get("input") {
        result.insert(String::from("input"), cap_value(input));
    }
    if let Some(metadata) = state.get("metadata") {
        let filtered = keep_fields(metadata, &["truncated"]);
        if !filtered.as_object().map_or(true, |m| m.is_empty()) {
            result.insert(String::from("metadata"), filtered);
        }
    }
    if let Some(content) = state.get("content").and_then(|c| c.as_array()) {
        let items: Vec<Value> = content
            .iter()
            .map(|item| match item.get("type").and_then(|t| t.as_str()) {
                Some("text") => {
                    let text = item.get("text").and_then(|t| t.as_str()).unwrap_or("");
                    json!({"type": "text", "text": truncate_bash_output(text, 5, 5)})
                }
                _ => sanitize_file_item(item),
            })
            .collect();
        result.insert(String::from("content"), Value::Array(items));
    }

    Value::Object(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn test_truncate_bash_output_short() {
        let output = "line1\nline2\nline3";
        let result = truncate_bash_output(output, 5, 5);
        assert_eq!(result, output);
    }

    #[test]
    fn test_truncate_bash_output_long() {
        let output = (1..=20).map(|i| format!("line{i}")).collect::<Vec<_>>().join("\n");
        let result = truncate_bash_output(&output, 5, 5);
        assert!(result.contains("line1"));
        assert!(result.contains("line5"));
        assert!(result.contains("line16"));
        assert!(result.contains("line20"));
        assert!(result.contains("10 lines truncated"));
        assert!(!result.contains("line6"));
        assert!(!result.contains("line15"));
    }

    #[test]
    fn test_truncate_ends_chars_short() {
        let text = "hello";
        assert_eq!(truncate_ends_chars(text, 100), text);
    }

    #[test]
    fn test_truncate_ends_chars_long() {
        let text: String = (0..300).map(|i| char::from(b'a' + (i % 26) as u8)).collect();
        let result = truncate_ends_chars(&text, 100);
        assert!(result.contains("...[truncated]..."));
        let parts: Vec<&str> = result.split("...[truncated]...").collect();
        assert_eq!(parts.len(), 2);
        assert_eq!(parts[0].chars().count(), 100);
        assert_eq!(parts[1].chars().count(), 100);
    }

    #[test]
    fn test_truncate_user_removes_file_source_text() {
        let data = json!({
            "text": "hello",
            "files": [
                {"name": "foo.rs", "uri": "file:///foo.rs", "source": {"text": "big content"}, "description": "desc"}
            ],
            "agents": [{"id": "a1", "source": {"text": "agent source"}}]
        });
        let result = truncate_user(&data);
        assert_eq!(result["text"], "hello");
        assert_eq!(result["files"][0]["name"], "foo.rs");
        assert_eq!(result["files"][0]["uri"], "file:///foo.rs");
        assert!(result["files"][0]["source"]["text"].is_null());
        assert!(result["files"][0]["description"].is_null());
        assert!(result["agents"][0].get("source").is_none());
    }

    #[test]
    fn test_truncate_user_long_text() {
        let long_text: String = (0..5000).map(|i| char::from(b'a' + (i % 26) as u8)).collect();
        let data = json!({"text": long_text, "files": [], "agents": []});
        let result = truncate_user(&data);
        let text = result["text"].as_str().unwrap();
        assert!(text.contains("...[truncated]..."));
        assert!(text.chars().count() < 5000);
    }

    #[test]
    fn test_truncate_assistant_removes_snapshot() {
        let data = json!({
            "content": [{"type": "text", "text": "hello"}],
            "snapshot": {"big": "data"},
            "metadata": {"meta": "data"},
            "diagnostics": ["diag"],
            "tokens": {"total": 100}
        });
        let result = truncate_assistant(&data);
        assert!(result.get("snapshot").is_none());
        assert!(result.get("metadata").is_none());
        assert!(result.get("diagnostics").is_none());
        assert_eq!(result["tokens"]["total"], 100);
    }

    #[test]
    fn test_truncate_assistant_reasoning_short_not_truncated() {
        let short_text: String = (0..300).map(|i| char::from(b'a' + (i % 26) as u8)).collect();
        let data = json!({
            "content": [{"type": "reasoning", "text": short_text}]
        });
        let result = truncate_assistant(&data);
        let text = result["content"][0]["text"].as_str().unwrap();
        assert!(!text.contains("...[truncated]..."));
        assert_eq!(text, short_text);
    }

    #[test]
    fn test_truncate_assistant_long_text() {
        let long_text: String = (0..5000).map(|i| char::from(b'a' + (i % 26) as u8)).collect();
        let data = json!({
            "content": [{"type": "text", "text": long_text}]
        });
        let result = truncate_assistant(&data);
        let text = result["content"][0]["text"].as_str().unwrap();
        assert!(text.contains("...[truncated]..."));
    }

    #[test]
    fn test_truncate_assistant_long_reasoning() {
        let long_text: String = (0..5000).map(|i| char::from(b'a' + (i % 26) as u8)).collect();
        let data = json!({
            "content": [{"type": "reasoning", "text": long_text}]
        });
        let result = truncate_assistant(&data);
        let text = result["content"][0]["text"].as_str().unwrap();
        assert!(text.contains("...[truncated]..."));
    }

    #[test]
    fn test_truncate_shell_output() {
        let data = json!({
            "command": "ls",
            "output": (1..=20).map(|i| format!("line{i}")).collect::<Vec<_>>().join("\n"),
            "exit": 0
        });
        let result = truncate_shell(&data);
        let output = result["output"].as_str().unwrap();
        assert!(output.contains("10 lines truncated"));
        assert_eq!(result["command"], "ls");
        assert_eq!(result["exit"], 0);
    }

    #[test]
    fn test_truncate_compaction() {
        let lines: String = (1..=30).map(|i| format!("summary line {i}")).collect::<Vec<_>>().join("\n");
        let data = json!({"text": lines, "include": ["file1.rs", "file2.rs"]});
        let result = truncate_compaction(&data);
        assert!(result.get("include").is_none());
        let text = result["text"].as_str().unwrap();
        assert!(text.contains("...[truncated]..."));
        assert!(text.contains("summary line 1"));
        assert!(text.contains("summary line 10"));
        assert!(text.contains("summary line 21"));
        assert!(text.contains("summary line 30"));
    }

    #[test]
    fn test_truncate_tool_bash() {
        let state = json!({
            "input": {"command": "ls -la", "timeout": 120, "workdir": "/tmp", "description": "list"},
            "structured": {"exit": 0, "truncated": false, "outputPath": "/tmp/out"},
            "content": [{"type": "text", "text": (1..=20).map(|i| format!("line{i}")).collect::<Vec<_>>().join("\n")}]
        });
        let result = truncate_tool("bash", &state);
        assert_eq!(result["input"]["command"], "ls -la");
        assert_eq!(result["input"]["description"], "list");
        assert!(result["input"]["timeout"].is_null());
        assert!(result["input"]["workdir"].is_null());
        assert_eq!(result["structured"]["exit"], 0);
        assert!(result["structured"]["outputPath"].is_null());
        let text = result["content"][0]["text"].as_str().unwrap();
        assert!(text.contains("lines truncated"));
    }

    #[test]
    fn test_truncate_tool_read() {
        let state = json!({
            "status": "completed",
            "input": {"path": "/src/main.rs", "offset": 0, "limit": 100},
            "metadata": {"truncated": true, "preview": "big preview"},
            "content": [{"type": "text", "text": "file content"}]
        });
        let result = truncate_tool("read", &state);
        assert_eq!(result["status"], "completed");
        assert_eq!(result["input"]["path"], "/src/main.rs");
        assert!(result["input"]["offset"].is_null());
        assert_eq!(result["metadata"]["truncated"], true);
        assert!(result["metadata"]["preview"].is_null());
        assert_eq!(result["content"][0]["text"], "file content");
    }

    #[test]
    fn test_truncate_tool_write() {
        let state = json!({
            "input": {"filePath": "/src/main.rs", "content": "big file content"},
            "structured": {"filepath": "/src/main.rs", "exists": true, "diagnostics": ["warn"]}
        });
        let result = truncate_tool("write", &state);
        assert_eq!(result["input"]["filePath"], "/src/main.rs");
        assert!(result["input"]["content"].is_null());
        assert_eq!(result["structured"]["filepath"], "/src/main.rs");
        assert!(result["structured"]["diagnostics"].is_null());
    }

    #[test]
    fn test_truncate_tool_edit() {
        let state = json!({
            "input": {"filePath": "/src/main.rs", "oldString": "old", "newString": "new", "replaceAll": false},
            "structured": {"additions": 5, "deletions": 3, "diff": "big diff", "filediff": "big filediff"}
        });
        let result = truncate_tool("edit", &state);
        assert_eq!(result["input"]["filePath"], "/src/main.rs");
        assert!(result["input"]["oldString"].is_null());
        assert_eq!(result["structured"]["additions"], 5);
        assert_eq!(result["structured"]["deletions"], 3);
        assert!(result["structured"]["diff"].is_null());
    }

    #[test]
    fn test_truncate_tool_apply_patch() {
        let state = json!({
            "input": {"patchText": "big patch"},
            "structured": {
                "files": [
                    {"filePath": "a.rs", "type": "modify", "additions": 10, "deletions": 5, "patch": "big patch"},
                    {"filePath": "b.rs", "type": "add", "additions": 20, "deletions": 0, "patch": "big patch"}
                ]
            }
        });
        let result = truncate_tool("apply_patch", &state);
        assert!(result["input"]["patchText"].is_null());
        let files = result["structured"]["files"].as_array().unwrap();
        assert_eq!(files.len(), 2);
        assert_eq!(files[0]["filePath"], "a.rs");
        assert_eq!(files[0]["additions"], 10);
        assert!(files[0]["patch"].is_null());
    }

    #[test]
    fn test_truncate_tool_glob() {
        let state = json!({
            "input": {"pattern": "**/*.rs", "path": "/src"},
            "structured": {"count": 42, "truncated": true},
            "content": [{"type": "text", "text": "file1.rs\nfile2.rs\n..."}]
        });
        let result = truncate_tool("glob", &state);
        assert_eq!(result["input"]["pattern"], "**/*.rs");
        assert_eq!(result["structured"]["count"], 42);
    }

    #[test]
    fn test_truncate_tool_unknown() {
        let state = json!({
            "input": {"name": "myCustomTool", "bigArg": "lots of data"},
            "structured": {"small": "ok"},
            "content": [{"type": "text", "text": "big output"}]
        });
        let result = truncate_tool("custom_tool", &state);
        assert_eq!(result["input"]["name"], "myCustomTool");
        assert_eq!(result["input"]["bigArg"], "lots of data");
        assert!(result.get("structured").is_none());
        assert_eq!(result["content"][0]["text"], "big output");
    }

    #[test]
    fn test_truncate_tool_read_strips_image_payload() {
        let state = json!({
            "status": "completed",
            "input": {"path": "D:\\shots\\current1.png", "offset": 0, "limit": 0},
            "metadata": {"truncated": false},
            "content": [
                {"type": "text", "text": "Image read successfully"},
                {"type": "file", "mime": "image/png", "name": "current1.png", "uri": "data:image/png;base64,AAAA"}
            ]
        });
        let result = truncate_tool("read", &state);
        assert_eq!(result["input"]["path"], "D:\\shots\\current1.png");
        assert_eq!(result["content"][0]["text"], "Image read successfully");
        assert_eq!(result["content"][1]["mime"], "image/png");
        assert_eq!(result["content"][1]["name"], "current1.png");
        assert!(result["content"][1].get("uri").is_none());
    }

    #[test]
    fn test_truncate_tool_shell_v2_metadata() {
        let state = json!({
            "status": "completed",
            "input": {"command": "ls -la", "workdir": "/tmp", "timeout": 120, "background": true},
            "metadata": {"status": "completed", "truncated": false, "exit": 0},
            "content": [{"type": "text", "text": "a\nb\nc"}]
        });
        let result = truncate_tool("shell", &state);
        assert_eq!(result["input"]["command"], "ls -la");
        assert_eq!(result["input"]["background"], true);
        assert!(result["input"]["workdir"].is_null());
        assert_eq!(result["metadata"]["exit"], 0);
    }

    #[test]
    fn test_truncate_tool_subagent_keeps_session_metadata() {
        let state = json!({
            "status": "completed",
            "input": {"agent": "explore", "description": "find x", "prompt": "x".repeat(50000)},
            "metadata": {"sessionId": "ses_child", "model": "m", "other": "drop"}
        });
        let result = truncate_tool("subagent", &state);
        assert_eq!(result["input"]["agent"], "explore");
        assert_eq!(result["input"]["description"], "find x");
        assert_eq!(result["metadata"]["sessionId"], "ses_child");
        assert!(result["metadata"]["other"].is_null());
    }

    #[test]
    fn test_truncate_tool_execute_keeps_tool_calls() {
        let state = json!({
            "status": "completed",
            "input": {"code": "const x = 1"},
            "metadata": {"toolCalls": [{"tool": "search", "status": "completed"}], "truncated": false},
            "content": [{"type": "text", "text": "result"}]
        });
        let result = truncate_tool("execute", &state);
        assert_eq!(result["input"]["code"], "const x = 1");
        assert_eq!(result["metadata"]["toolCalls"][0]["tool"], "search");
        assert!(result.get("content").is_none());
    }

    #[test]
    fn test_truncate_tool_skill_keeps_name() {
        let state = json!({
            "status": "completed",
            "input": {"id": "release"},
            "metadata": {"name": "release", "directory": "/x", "junk": "drop"},
            "content": [{"type": "text", "text": "skill body"}]
        });
        let result = truncate_tool("skill", &state);
        assert_eq!(result["input"]["id"], "release");
        assert_eq!(result["metadata"]["name"], "release");
        assert!(result["metadata"]["junk"].is_null());
        assert!(result.get("content").is_none());
    }

    #[test]
    fn test_truncate_assistant_strips_top_level_file_payload() {
        let data = json!({
            "content": [
                {"type": "text", "text": "hi"},
                {"type": "file", "mime": "image/png", "name": "a.png", "uri": "data:image/png;base64,AAAA"}
            ]
        });
        let result = truncate_assistant(&data);
        assert_eq!(result["content"][1]["mime"], "image/png");
        assert_eq!(result["content"][1]["name"], "a.png");
        assert!(result["content"][1].get("uri").is_none());
    }

    #[test]
    fn test_truncate_user_strips_file_payload() {
        let data = json!({
            "text": "hi",
            "files": [
                {"name": "a.png", "mime": "image/png", "uri": "data:image/png;base64,AAAA", "source": {"text": "big"}}
            ]
        });
        let result = truncate_user(&data);
        assert_eq!(result["files"][0]["name"], "a.png");
        assert!(result["files"][0].get("uri").is_none());
        assert!(result["files"][0].get("source").is_none());
    }

    #[test]
    fn test_truncate_event_tool_success_strips_file_uri() {
        let data = json!({
            "sessionID": "s1",
            "content": [{"type": "file", "mime": "image/png", "name": "a.png", "uri": "data:image/png;base64,AAAA"}]
        });
        let result = truncate_event("session.next.tool.success.1", &data);
        assert_eq!(result["content"][0]["name"], "a.png");
        assert!(result["content"][0].get("uri").is_none());
    }

    #[test]
    fn test_truncate_event_part_updated_tool_v2() {
        let data = json!({
            "part": {
                "type": "tool",
                "name": "read",
                "state": {
                    "status": "completed",
                    "input": {"path": "/a.png", "offset": 0},
                    "metadata": {"truncated": false, "extra": "drop"},
                    "content": [{"type": "file", "mime": "image/png", "uri": "data:image/png;base64,AAAA"}]
                }
            }
        });
        let result = truncate_event("message.part.updated.1", &data);
        let state = &result["part"]["state"];
        assert_eq!(state["status"], "completed");
        assert_eq!(state["input"]["path"], "/a.png");
        assert!(state["metadata"]["extra"].is_null());
        assert!(state["content"][0].get("uri").is_none());
    }

    #[test]
    fn test_truncate_tool_unknown_large_structured() {
        let big_val = "x".repeat(501);
        let state = json!({
            "input": {"name": "tool"},
            "structured": {"data": big_val}
        });
        let result = truncate_tool("unknown", &state);
        assert!(result.get("structured").is_none());
    }

    #[test]
    fn test_passthrough_types() {
        let data = json!({"anything": "goes"});
        assert_eq!(truncate_message("agent-switched", &data), data);
        assert_eq!(truncate_message("model-switched", &data), data);
        assert_eq!(truncate_message("synthetic", &data), data);
    }

    #[test]
    fn test_truncate_tool_todowrite_and_question() {
        let state = json!({"input": {"todos": [{"text": "do stuff"}]}, "content": []});
        assert_eq!(truncate_tool("todowrite", &state), state);
        let state2 = json!({"input": {"questions": ["q1"]}, "content": []});
        assert_eq!(truncate_tool("question", &state2), state2);
    }

    #[test]
    fn test_truncate_tool_lsp() {
        let state = json!({
            "input": {"operation": "hover", "filePath": "/a.rs", "line": 1, "character": 1, "query": ""},
            "structured": {"result": "big hover data"},
            "content": [{"type": "text", "text": "hover info"}]
        });
        let result = truncate_tool("lsp", &state);
        assert_eq!(result["input"]["operation"], "hover");
        assert_eq!(result["input"]["filePath"], "/a.rs");
        assert!(result.get("structured").is_none());
    }

    #[test]
    fn test_base_event_type_strips_version() {
        assert_eq!(base_event_type("session.next.tool.called.1"), "session.next.tool.called");
        assert_eq!(base_event_type("message.part.updated.1"), "message.part.updated");
        assert_eq!(base_event_type("session.updated.1"), "session.updated");
        assert_eq!(base_event_type("nodotsuffix"), "nodotsuffix");
    }

    #[test]
    fn test_truncate_event_step_removes_snapshot() {
        let data = json!({
            "sessionID": "s1",
            "agent": "code",
            "model": {"id": "gpt-4"},
            "snapshot": {"big": "data"},
            "timestamp": 1234
        });
        let result = truncate_event("session.next.step.started.1", &data);
        assert!(result.get("snapshot").is_none());
        assert_eq!(result["agent"], "code");
        assert_eq!(result["model"]["id"], "gpt-4");
    }

    #[test]
    fn test_truncate_event_reasoning_ended() {
        let long_text: String = (0..5000).map(|i| char::from(b'a' + (i % 26) as u8)).collect();
        let data = json!({
            "sessionID": "s1",
            "reasoningID": "r1",
            "text": long_text,
            "timestamp": 1234
        });
        let result = truncate_event("session.next.reasoning.ended.1", &data);
        let text = result["text"].as_str().unwrap();
        assert!(text.contains("...[truncated]..."));
        assert_eq!(result["reasoningID"], "r1");
    }

    #[test]
    fn test_truncate_event_tool_called() {
        let data = json!({
            "sessionID": "s1",
            "callID": "c1",
            "tool": "bash",
            "input": {"command": "ls -la", "timeout": 120, "workdir": "/tmp"},
            "provider": "openai",
            "timestamp": 1234
        });
        let result = truncate_event("session.next.tool.called.1", &data);
        assert_eq!(result["input"]["command"], "ls -la");
        assert!(result["input"]["timeout"].is_null());
        assert!(result["input"]["workdir"].is_null());
        assert_eq!(result["tool"], "bash");
    }

    #[test]
    fn test_truncate_event_tool_success_skips_large_structured() {
        let big_val = "x".repeat(501);
        let data = json!({
            "sessionID": "s1",
            "callID": "c1",
            "structured": {"data": big_val},
            "content": [{"type": "text", "text": "ok"}],
            "provider": "openai",
            "timestamp": 1234
        });
        let result = truncate_event("session.next.tool.success.1", &data);
        assert!(result.get("structured").is_none());
        assert_eq!(result["callID"], "c1");
    }

    #[test]
    fn test_truncate_event_tool_success_truncates_large_text_content() {
        let long_output: String = (1..=200).map(|i| format!("line{i}")).collect::<Vec<_>>().join("\n");
        let data = json!({
            "sessionID": "s1",
            "callID": "c1",
            "structured": {"exit": 0},
            "content": [{"type": "text", "text": long_output}],
            "provider": "openai"
        });
        let result = truncate_event("session.next.tool.success.1", &data);
        let text = result["content"][0]["text"].as_str().unwrap();
        assert!(text.contains("lines truncated"));
    }

    #[test]
    fn test_truncate_event_tool_success_removes_file_binary_data() {
        let data = json!({
            "sessionID": "s1",
            "callID": "c1",
            "structured": {"truncated": true},
            "content": [{"type": "file", "uri": "file:///a.rs", "name": "a.rs", "data": "base64..."}],
            "provider": "openai"
        });
        let result = truncate_event("session.next.tool.success.1", &data);
        assert_eq!(result["content"][0]["name"], "a.rs");
        assert!(result["content"][0].get("data").is_none());
    }

    #[test]
    fn test_truncate_event_compaction_ended() {
        let lines: String = (1..=30).map(|i| format!("summary line {i}")).collect::<Vec<_>>().join("\n");
        let data = json!({
            "sessionID": "s1",
            "text": lines,
            "include": ["file1.rs", "file2.rs"],
            "timestamp": 1234
        });
        let result = truncate_event("session.next.compaction.ended.1", &data);
        assert!(result.get("include").is_none());
        let text = result["text"].as_str().unwrap();
        assert!(text.contains("...[truncated]..."));
    }

    #[test]
    fn test_truncate_event_shell_ended() {
        let output: String = (1..=20).map(|i| format!("line{i}")).collect::<Vec<_>>().join("\n");
        let data = json!({
            "sessionID": "s1",
            "callID": "c1",
            "output": output,
            "timestamp": 1234
        });
        let result = truncate_event("session.next.shell.ended.1", &data);
        let output = result["output"].as_str().unwrap();
        assert!(output.contains("10 lines truncated"));
    }

    #[test]
    fn test_truncate_event_prompted() {
        let data = json!({
            "sessionID": "s1",
            "prompt": {
                "text": "hello",
                "files": [{"name": "foo.rs", "uri": "file:///foo.rs", "source": {"text": "big"}, "description": "desc"}],
                "agents": [{"id": "a1", "source": {"text": "agent src"}}]
            },
            "timestamp": 1234
        });
        let result = truncate_event("session.next.prompted.1", &data);
        assert_eq!(result["prompt"]["text"], "hello");
        assert!(result["prompt"]["files"][0]["source"]["text"].is_null());
        assert!(result["prompt"]["files"][0]["description"].is_null());
        assert!(result["prompt"]["agents"][0].get("source").is_none());
    }

    #[test]
    fn test_truncate_event_part_updated_tool() {
        let data = json!({
            "sessionID": "s1",
            "part": {
                "type": "tool",
                "callID": "c1",
                "name": "bash",
                "state": {
                    "status": "completed",
                    "name": "bash",
                    "input": {"command": "ls", "timeout": 120},
                    "structured": {"exit": 0},
                    "content": [{"type": "text", "text": (1..=200).map(|i| format!("line{i}")).collect::<Vec<_>>().join("\n")}]
                }
            },
            "time": {}
        });
        let result = truncate_event("message.part.updated.1", &data);
        let state = &result["part"]["state"];
        assert_eq!(state["input"]["command"], "ls");
        assert!(state["input"]["timeout"].is_null());
        assert_eq!(state["structured"]["exit"], 0);
        let text = state["content"][0]["text"].as_str().unwrap();
        assert!(text.contains("lines truncated"));
    }

    #[test]
    fn test_truncate_event_part_updated_patch() {
        let data = json!({
            "sessionID": "s1",
            "part": {
                "type": "patch",
                "hash": "abc",
                "files": [
                    {"filePath": "a.rs", "type": "modify", "additions": 10, "patch": "big patch content"}
                ]
            }
        });
        let result = truncate_event("message.part.updated.1", &data);
        let files = result["part"]["files"].as_array().unwrap();
        assert_eq!(files[0]["filePath"], "a.rs");
        assert_eq!(files[0]["additions"], 10);
        assert!(files[0]["patch"].is_null());
    }

    #[test]
    fn test_truncate_event_passthrough_small_types() {
        let data = json!({"sessionID": "s1", "agent": "code", "timestamp": 1234});
        assert_eq!(truncate_event("session.next.agent.switched.1", &data), data);
        let data2 = json!({"sessionID": "s1", "error": "fail", "timestamp": 1234});
        assert_eq!(truncate_event("session.next.step.failed.1", &data2), data2);
        let data3 = json!({"sessionID": "s1", "callID": "c1", "name": "bash", "timestamp": 1234});
        assert_eq!(truncate_event("session.next.tool.input.started.1", &data3), data3);
    }
}
