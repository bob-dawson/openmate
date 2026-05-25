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
        "bash" => keep_fields(input, &["command", "description"]),
        "read" => keep_fields(input, &["filePath"]),
        "write" => keep_fields(input, &["filePath"]),
        "edit" => keep_fields(input, &["filePath"]),
        "glob" => keep_fields(input, &["pattern", "path"]),
        "grep" => keep_fields(input, &["pattern", "path", "include"]),
        "task" => keep_fields(input, &["description", "subagent_type"]),
        "webfetch" => keep_fields(input, &["url", "format"]),
        "websearch" => keep_fields(input, &["query"]),
        "skill" => keep_fields(input, &["name"]),
        "lsp" => keep_fields(input, &["operation", "filePath", "line", "character", "query"]),
        "question" | "todowrite" => input.clone(),
        _ => keep_fields(input, &["name"]),
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
                    if let Some(io) = item.as_object_mut() {
                        io.remove("data");
                        io.remove("content");
                        io.remove("source");
                    }
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
                    if let Some(state) = part.get_mut("state").and_then(|s| s.as_object_mut()) {
                        let tool_name = state
                            .get("name")
                            .and_then(|n| n.as_str())
                            .unwrap_or("");
                        if let Some(input) = state.get("input").cloned() {
                            state.insert(
                                String::from("input"),
                                truncate_tool_input(tool_name, &input),
                            );
                        }
                        if let Some(structured) = state.get("structured") {
                            if structured.to_string().len() > 500 {
                                state.remove("structured");
                            }
                        }
                        if let Some(content) =
                            state.get_mut("content").and_then(|c| c.as_array_mut())
                        {
                            for item in content.iter_mut() {
                                match item.get("type").and_then(|t| t.as_str()) {
                                    Some("text") => {
                                        if let Some(text) =
                                            item.get("text").and_then(|t| t.as_str())
                                        {
                                            if text.len() > 500 {
                                                let truncated =
                                                    truncate_bash_output(text, 5, 5);
                                                if let Some(io) = item.as_object_mut() {
                                                    io.insert(
                                                        String::from("text"),
                                                        Value::String(truncated),
                                                    );
                                                }
                                            }
                                        }
                                    }
                                    Some("file") | Some("image") => {
                                        if let Some(io) = item.as_object_mut() {
                                            io.remove("data");
                                            io.remove("content");
                                            io.remove("source");
                                        }
                                    }
                                    _ => {}
                                }
                            }
                        }
                        if let Some(attachments) =
                            state.get_mut("attachments").and_then(|a| a.as_array_mut())
                        {
                            for att in attachments.iter_mut() {
                                if let Some(ao) = att.as_object_mut() {
                                    ao.remove("url");
                                }
                            }
                        }
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
            if let Some(fo) = file.as_object_mut() {
                if let Some(source) = fo.get_mut("source").and_then(|s| s.as_object_mut()) {
                    source.remove("text");
                }
                fo.remove("description");
            }
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

fn keep_file_metadata(item: &Value) -> Value {
    keep_fields(item, &["type", "uri", "mime", "name"])
}

fn truncate_tool(tool_name: &str, state: &Value) -> Value {
    match tool_name {
        "bash" => truncate_tool_bash(state),
        "read" => truncate_tool_read(state),
        "write" => truncate_tool_write(state),
        "edit" => truncate_tool_edit(state),
        "apply_patch" => truncate_tool_apply_patch(state),
        "glob" => truncate_tool_glob(state),
        "grep" => truncate_tool_grep(state),
        "task" => truncate_tool_task(state),
        "todowrite" => state.clone(),
        "webfetch" => truncate_tool_webfetch(state),
        "websearch" => truncate_tool_websearch(state),
        "skill" => truncate_tool_skill(state),
        "question" => state.clone(),
        "lsp" => truncate_tool_lsp(state),
        "plan_exit" | "invalid" => truncate_tool_minimal(state),
        _ => truncate_tool_unknown(state),
    }
}

fn build_state(
    state: &Value,
    input_keep: &[&str],
    structured_keep: &[&str],
    content_mode: ContentMode,
) -> Value {
    let mut result = Map::new();

    if let Some(input) = state.get("input") {
        result.insert(String::from("input"), keep_fields(input, input_keep));
    }

    if let Some(structured) = state.get("structured") {
        let filtered = keep_fields(structured, structured_keep);
        if !filtered.as_object().map_or(true, |m| m.is_empty()) {
            result.insert(String::from("structured"), filtered);
        }
    }

    if let Some(content) = state.get("content").and_then(|c| c.as_array()) {
        match content_mode {
            ContentMode::Skip => {}
            ContentMode::Keep => {
                result.insert(String::from("content"), Value::Array(content.clone()));
            }
            ContentMode::TruncateBashOutput => {
                let filtered: Vec<Value> = content
                    .iter()
                    .map(|item| match item.get("type").and_then(|t| t.as_str()) {
                        Some("text") => {
                            let text =
                                item.get("text").and_then(|t| t.as_str()).unwrap_or("");
                            json!({"type": "text", "text": truncate_bash_output(text, 5, 5)})
                        }
                        _ => keep_file_metadata(item),
                    })
                    .collect();
                result.insert(String::from("content"), Value::Array(filtered));
            }
            ContentMode::KeepFileMetadata => {
                let filtered: Vec<Value> =
                    content.iter().map(|item| keep_file_metadata(item)).collect();
                result.insert(String::from("content"), Value::Array(filtered));
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
#[allow(dead_code)]
enum ContentMode {
    Keep,
    Skip,
    TruncateBashOutput,
    KeepFileMetadata,
}

fn truncate_attachment(attachment: &Value) -> Value {
    keep_fields(attachment, &["type", "mime", "id", "sessionID", "messageID"])
}

fn truncate_tool_bash(state: &Value) -> Value {
    build_state(
        state,
        &["command", "description"],
        &["exit", "truncated"],
        ContentMode::TruncateBashOutput,
    )
}

fn truncate_tool_read(state: &Value) -> Value {
    build_state(state, &["filePath"], &["truncated"], ContentMode::Skip)
}

fn truncate_tool_write(state: &Value) -> Value {
    build_state(
        state,
        &["filePath"],
        &["filepath", "exists"],
        ContentMode::Skip,
    )
}

fn truncate_tool_edit(state: &Value) -> Value {
    build_state(
        state,
        &["filePath"],
        &["additions", "deletions"],
        ContentMode::Skip,
    )
}

fn truncate_tool_apply_patch(state: &Value) -> Value {
    let mut result = Map::new();

    if let Some(structured) = state.get("structured") {
        let mut filtered = Map::new();
        if let Some(files) = structured.get("files").and_then(|f| f.as_array()) {
            let kept: Vec<Value> = files
                .iter()
                .map(|file| keep_fields(file, &["filePath", "type", "additions", "deletions"]))
                .collect();
            filtered.insert(String::from("files"), Value::Array(kept));
        }
        result.insert(String::from("structured"), Value::Object(filtered));
    }

    if let Some(attachments) = state.get("attachments").and_then(|a| a.as_array()) {
        let filtered: Vec<Value> = attachments.iter().map(truncate_attachment).collect();
        result.insert(String::from("attachments"), Value::Array(filtered));
    }

    Value::Object(result)
}

fn truncate_tool_glob(state: &Value) -> Value {
    build_state(
        state,
        &["pattern", "path"],
        &["count", "truncated"],
        ContentMode::Skip,
    )
}

fn truncate_tool_grep(state: &Value) -> Value {
    build_state(
        state,
        &["pattern", "path", "include"],
        &["matches", "truncated"],
        ContentMode::Skip,
    )
}

fn truncate_tool_task(state: &Value) -> Value {
    build_state(
        state,
        &["description", "subagent_type"],
        &["sessionId", "model"],
        ContentMode::Skip,
    )
}

fn truncate_tool_webfetch(state: &Value) -> Value {
    build_state(state, &["url", "format"], &[], ContentMode::Skip)
}

fn truncate_tool_websearch(state: &Value) -> Value {
    build_state(state, &["query"], &["summary"], ContentMode::Skip)
}

fn truncate_tool_skill(state: &Value) -> Value {
    build_state(
        state,
        &["name"],
        &["name", "dir"],
        ContentMode::Skip,
    )
}

fn truncate_tool_lsp(state: &Value) -> Value {
    build_state(
        state,
        &["operation", "filePath", "line", "character", "query"],
        &[],
        ContentMode::Skip,
    )
}

fn truncate_tool_minimal(state: &Value) -> Value {
    build_state(state, &["tool", "error"], &[], ContentMode::Skip)
}

fn truncate_tool_unknown(state: &Value) -> Value {
    let mut result = Map::new();

    if let Some(input) = state.get("input") {
        result.insert(String::from("input"), keep_fields(input, &["name"]));
    }

    if let Some(structured) = state.get("structured") {
        if structured.to_string().len() <= 500 {
            result.insert(String::from("structured"), structured.clone());
        }
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
            "input": {"filePath": "/src/main.rs", "offset": 0, "limit": 100},
            "structured": {"truncated": true, "preview": "big preview"},
            "content": [{"type": "text", "text": "file content"}]
        });
        let result = truncate_tool("read", &state);
        assert_eq!(result["input"]["filePath"], "/src/main.rs");
        assert!(result["input"]["offset"].is_null());
        assert_eq!(result["structured"]["truncated"], true);
        assert!(result["structured"]["preview"].is_null());
        assert!(result.get("content").is_none());
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
        assert!(result.get("input").is_none());
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
        assert!(result["input"]["bigArg"].is_null());
        assert_eq!(result["structured"]["small"], "ok");
        assert!(result.get("content").is_none());
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
