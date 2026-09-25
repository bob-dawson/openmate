use serde_json::{Map, Value, json};

use crate::sync::truncate::truncate_event;

#[derive(Clone, Debug, PartialEq)]
pub struct SharedEvent {
    pub bridge_event: Option<Value>,
    pub sync_notification: Option<Value>,
}

pub fn filter_event(input: &Value) -> Option<Value> {
    let normalized = normalize_event(input)?;
    filter_normalized_event(&normalized)
}

pub fn build_shared_event(input: &Value) -> Option<SharedEvent> {
    let normalized = normalize_event(input)?;
    let bridge_event = filter_event_from_normalized(&normalized);
    let sync_notification = build_sync_notification(input, &normalized);

    if bridge_event.is_none() && sync_notification.is_none() {
        return None;
    }

    Some(SharedEvent {
        bridge_event,
        sync_notification,
    })
}

fn filter_event_from_normalized(normalized: &Value) -> Option<Value> {
    filter_normalized_event(normalized)
}

fn filter_normalized_event(normalized: &Value) -> Option<Value> {
    let event_type = normalized.get("type")?.as_str()?;
    let properties = normalized.get("properties")?.as_object()?;

    let base_type = strip_event_version(event_type);

    match base_type {
        "session.created"
        | "session.updated"
        | "session.deleted"
        | "session.status"
        | "session.error"
        | "session.renamed"
        | "permission.asked"
        | "permission.replied"
        | "question.asked"
        | "question.replied"
        | "question.rejected"
        | "form.created"
        | "form.replied"
        | "form.cancelled"
        | "todo.updated"
        | "message.removed" => Some(json!({
            "type": base_type,
            "properties": Value::Object(properties.clone()),
        })),
        "message.updated" => Some(json!({
            "type": base_type,
            "properties": trim_message_updated_identifier_only(properties),
        })),
        _ if retain_session_event(&base_type) => Some(json!({
            "type": base_type,
            "properties": trim_session_next_event(&base_type, properties),
        })),
        _ => None,
    }
}

fn normalize_event(input: &Value) -> Option<Value> {
    if let Some(payload) = input.get("payload") {
        if payload.get("type")?.as_str()? == "sync" {
            let mut sync_event = payload.get("syncEvent")?.clone();
            if let Some(directory) = input.get("directory") {
                sync_event
                    .as_object_mut()?
                    .insert(String::from("directory"), directory.clone());
            }
            return normalize_sync_event(&sync_event);
        }

        if payload.get("type").is_some() && payload.get("properties").is_some() {
            let mut normalized = payload.clone();
            if let Some(directory) = input.get("directory") {
                normalized
                    .get_mut("properties")?
                    .as_object_mut()?
                    .insert(String::from("directory"), directory.clone());
            }
            return Some(normalized);
        }
    }

    if input.get("type").is_some() && input.get("properties").is_some() {
        return Some(input.clone());
    }

    if let Some(event_type) = input.get("type").and_then(|t| t.as_str()) {
        if let Some(data) = input.get("data") {
            if let Some(data_obj) = data.as_object() {
                let mut properties = data_obj.clone();
                if let Some(event_id) = input.get("id") {
                    properties.insert(String::from("eventID"), event_id.clone());
                }
                return Some(json!({
                    "type": event_type,
                    "properties": Value::Object(properties),
                }));
            }
        }
    }

    None
}

fn build_sync_notification(input: &Value, normalized: &Value) -> Option<Value> {
    if let Some(notification) = build_legacy_sync_notification(input) {
        return Some(notification);
    }

    build_compat_sync_notification(normalized)
}

fn build_legacy_sync_notification(input: &Value) -> Option<Value> {
    let payload = input.get("payload")?;
    if payload.get("type")?.as_str()? != "sync" {
        return None;
    }

    let sync_event = payload.get("syncEvent")?;
    let session_id = sync_event.get("aggregateID")?.as_str()?;
    let seq = sync_event.get("seq")?.as_i64()?;

    Some(json!({
        "sessionID": session_id,
        "seq": seq,
    }))
}

fn build_compat_sync_notification(normalized: &Value) -> Option<Value> {
    let event_type = normalized.get("type")?.as_str()?;
    let properties = normalized.get("properties")?.as_object()?;

    let base_type = strip_event_version(event_type);

    let should_notify = matches!(
        base_type,
        "session.created"
            | "session.updated"
            | "session.deleted"
            | "session.status"
            | "session.error"
            | "session.renamed"
            | "message.updated"
            | "message.removed"
            | "todo.updated"
            | "session.step.started"
            | "session.step.ended"
            | "session.step.failed"
            | "session.tool.called"
            | "session.tool.progress"
            | "session.tool.success"
            | "session.tool.failed"
            | "session.compaction.started"
            | "session.compaction.ended"
            | "session.shell.started"
            | "session.shell.ended"
            | "session.text.started"
            | "session.text.ended"
            | "session.reasoning.started"
            | "session.reasoning.ended"
            | "session.revert.committed"
            | "session.revert.staged"
            | "session.input.admitted"
            | "session.input.promoted"
            | "session.execution.succeeded"
            | "session.execution.failed"
    );

    if !should_notify {
        return None;
    }

    let session_id = properties.get("sessionID").and_then(|v| v.as_str())?;
    let seq = properties.get("seq").and_then(|v| v.as_i64());

    let mut notification = json!({
        "sessionID": session_id,
    });
    if let Some(seq) = seq {
        notification["seq"] = json!(seq);
    }

    Some(notification)
}

fn normalize_sync_event(sync_event: &Value) -> Option<Value> {
    let event_type = strip_event_version(sync_event.get("type")?.as_str()?);
    let mut properties = sync_event.get("data")?.as_object()?.clone();

    if let Some(seq) = sync_event.get("seq") {
        properties.insert(String::from("seq"), seq.clone());
    }

    if let Some(aggregate_id) = sync_event.get("aggregateID") {
        properties.insert(String::from("aggregateID"), aggregate_id.clone());
    }

    if let Some(directory) = sync_event.get("directory") {
        properties.insert(String::from("directory"), directory.clone());
    }

    Some(json!({
        "type": event_type,
        "properties": Value::Object(properties),
    }))
}

fn strip_event_version(event_type: &str) -> &str {
    if let Some((base, suffix)) = event_type.rsplit_once('.')
        && suffix.chars().all(|c| c.is_ascii_digit())
    {
        return base;
    }

    event_type
}

fn retain_session_event(event_type: &str) -> bool {
    matches!(
        event_type,
        "session.agent.switched"
            | "session.model.switched"
            | "session.prompted"
            | "session.synthetic"
            | "session.shell.started"
            | "session.shell.ended"
            | "session.step.started"
            | "session.step.ended"
            | "session.step.failed"
            | "session.text.started"
            | "session.text.ended"
            | "session.reasoning.started"
            | "session.reasoning.ended"
            | "session.tool.input.started"
            | "session.tool.called"
            | "session.tool.progress"
            | "session.tool.success"
            | "session.tool.failed"
            | "session.retried"
            | "session.compaction.started"
            | "session.compaction.ended"
            | "session.revert.staged"
            | "session.revert.committed"
            | "session.input.admitted"
            | "session.input.promoted"
            | "session.execution.succeeded"
            | "session.execution.failed"
    )
}

// Bridge /api/bridge/events intentionally uses a stricter message.updated contract
// than sync::truncate: only stable identifiers survive, so clients must refetch
// full message state through sync APIs instead of relying on SSE payload bodies.
fn trim_message_updated_identifier_only(properties: &Map<String, Value>) -> Value {
    let mut trimmed = Map::new();

    if let Some(session_id) = properties.get("sessionID") {
        trimmed.insert(String::from("sessionID"), session_id.clone());
    }

    if let Some(message_id) = properties.get("messageID") {
        trimmed.insert(String::from("messageID"), message_id.clone());
    } else if let Some(info_id) = properties
        .get("info")
        .and_then(|info| info.get("id"))
    {
        trimmed.insert(String::from("messageID"), info_id.clone());
    } else if let Some(message_id) = properties
        .get("message")
        .and_then(|message| message.get("id"))
    {
        trimmed.insert(String::from("messageID"), message_id.clone());
    }

    Value::Object(trimmed)
}

fn trim_session_next_event(event_type: &str, properties: &Map<String, Value>) -> Value {
    truncate_event(event_type, &Value::Object(properties.clone()))
}

#[cfg(test)]
mod tests {
    use super::{build_shared_event, filter_event};
    use serde_json::json;

    #[test]
    fn drops_streaming_delta_sync_events() {
        let input = json!({
            "payload": {
                "type": "sync",
                "syncEvent": {
                    "type": "session.next.text.delta.1",
                    "data": {
                        "sessionID": "ses_1",
                        "delta": "hello"
                    }
                }
            }
        });

        assert!(filter_event(&input).is_none());
    }

    #[test]
    fn derives_message_id_from_info_when_needed() {
        let input = json!({
            "type": "message.updated",
            "properties": {
                "sessionID": "ses_1",
                "info": {
                    "id": "msg_1",
                    "parts": [
                        {
                            "type": "text",
                            "text": "large payload"
                        }
                    ]
                }
            }
        });

        let output = filter_event(&input).expect("event should be retained");

        assert_eq!(output, json!({
            "type": "message.updated",
            "properties": {
                "sessionID": "ses_1",
                "messageID": "msg_1"
            }
        }));
    }

    #[test]
    fn message_updated_identifier_only_contract_drops_body_fields() {
        let input = json!({
            "type": "message.updated",
            "properties": {
                "sessionID": "ses_1",
                "messageID": "msg_1",
                "info": {
                    "id": "msg_1",
                    "summary": {
                        "diffs": ["large diff"]
                    }
                },
                "message": {
                    "id": "msg_1",
                    "parts": [
                        {
                            "type": "text",
                            "text": "large payload"
                        }
                    ]
                }
            }
        });

        let output = filter_event(&input).expect("event should be retained");

        assert_eq!(output, json!({
            "type": "message.updated",
            "properties": {
                "sessionID": "ses_1",
                "messageID": "msg_1"
            }
        }));
    }

    #[test]
    fn derives_message_id_from_message_body_when_info_is_missing() {
        let input = json!({
            "type": "message.updated",
            "properties": {
                "sessionID": "ses_1",
                "message": {
                    "id": "msg_1",
                    "parts": [
                        {
                            "type": "text",
                            "text": "payload"
                        }
                    ]
                }
            }
        });

        let output = filter_event(&input).expect("event should be retained");

        assert_eq!(output, json!({
            "type": "message.updated",
            "properties": {
                "sessionID": "ses_1",
                "messageID": "msg_1"
            }
        }));
    }

    #[test]
    fn preserves_directory_from_non_sync_global_event_envelope() {
        let input = json!({
            "directory": "D:/repo",
            "payload": {
                "type": "session.error",
                "properties": {
                    "sessionID": "ses_1",
                    "error": { "message": "boom" }
                }
            }
        });

        let output = filter_event(&input).expect("event should be retained");

        assert_eq!(output, json!({
            "type": "session.error",
            "properties": {
                "sessionID": "ses_1",
                "error": { "message": "boom" },
                "directory": "D:/repo"
            }
        }));
    }

    #[test]
    fn forwards_form_created_event() {
        let input = json!({
            "type": "form.created",
            "properties": {
                "form": {
                    "id": "frm_1",
                    "sessionID": "ses_1",
                    "title": "Questions",
                    "metadata": {
                        "kind": "question",
                        "tool": { "messageID": "msg_1", "id": "call_1" }
                    },
                    "fields": [
                        { "key": "q0", "type": "string", "title": "h", "description": "d", "options": [], "custom": true }
                    ]
                }
            }
        });

        let output = filter_event(&input).expect("form.created should be retained");
        assert_eq!(output["type"], json!("form.created"));
        assert_eq!(output["properties"]["form"]["id"], json!("frm_1"));
    }

    #[test]
    fn build_shared_event_keeps_legacy_sync_notifications_for_unretained_sync_events() {
        let dropped = json!({
            "payload": {
                "type": "sync",
                "syncEvent": {
                    "type": "session.next.text.delta.1",
                    "seq": 10,
                    "aggregateID": "ses_1",
                    "data": {
                        "sessionID": "ses_1",
                        "delta": "drop"
                    }
                }
            }
        });

        let shared = build_shared_event(&dropped).expect("legacy sync notification should survive");
        assert!(shared.bridge_event.is_none());
        assert_eq!(shared.sync_notification, Some(json!({
            "sessionID": "ses_1",
            "seq": 10,
        })));
    }
}
