# Bridge Single SSE Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current Android dual-SSE runtime with one Bridge-backed SSE stream, add a new trimmed Bridge events endpoint, and retire the old split event/sync path without changing the existing seq-based message source of truth.

**Architecture:** Bridge will add a new trimmed Android-facing events endpoint at `/api/bridge/events`, applying an allowlist plus payload trimming while leaving the existing raw `/global/event` proxy behavior unchanged. Android will replace the current `SseClient` + `SyncSseClient` split with a single Bridge SSE client and one dispatch path that drives connection state, sync triggers, session errors, and permission/question/todo updates.

**Tech Stack:** Rust, Axum, Tokio, reqwest SSE forwarding, Kotlin, Coroutines, StateFlow, Hilt, Room, JUnit4, Robolectric, Google Truth, Gradle `--no-daemon`

---

## File Map

- Create: `opencode-bridge/src/events/mod.rs`
  - Shared Bridge event filtering, trimming, and forwarding logic for the new endpoint.
- Create: `opencode-bridge/src/events/router.rs`
  - Axum handler for `GET /api/bridge/events`.
- Create: `opencode-bridge/src/events/filter.rs`
  - Event allowlist and per-event payload trimming rules.
- Create: `opencode-bridge/tests/events_endpoint.rs`
  - Integration tests for retained/dropped event behavior.
- Modify: `opencode-bridge/src/lib.rs`
  - Export new `events` module.
- Modify: `opencode-bridge/src/server.rs`
  - Register `/api/bridge/events`.
- Modify: `opencode-bridge/src/sync/sse.rs`
  - Reuse trimmed-event parsing for the compatibility sync endpoint without changing raw `/global/event` proxy semantics.
- Modify: `android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt`
  - Convert from seq-only client to unified Bridge events client.
- Create: `android/core/network/src/main/java/com/openmate/core/network/BridgeEvent.kt`
  - Trimmed opencode-compatible event DTO for Android consumption.
- Create: `android/core/network/src/main/java/com/openmate/core/network/BridgeEventParser.kt`
  - Minimal parser for new Bridge event stream.
- Create: `android/core/network/src/test/java/com/openmate/core/network/SyncSseClientTest.kt`
  - Replace/add tests for unified event parsing and connection status behavior.
- Modify: `android/core/data/src/main/java/com/openmate/core/data/repository/SseEventRepositoryImpl.kt`
  - Read from unified Bridge stream instead of raw `SseClient`.
- Modify: `android/core/data/src/main/java/com/openmate/core/data/sse/EventDispatcher.kt`
  - Continue dispatching opencode-compatible events from unified Bridge stream.
- Modify: `android/core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt`
  - Consume unified events rather than seq-only notifications.
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
  - Remove two-client orchestration and manage one Bridge SSE lifecycle.
- Modify: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`
  - Update orchestration tests for one SSE runtime path.
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`
  - Keep current behavior tests passing with unified event source.
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/WorkspaceAndSessionConnectionStatusTest.kt`
  - Ensure connection state still comes from the unified source.

### Task 1: Add Bridge event filtering and trimming tests first

**Files:**
- Create: `opencode-bridge/tests/events_endpoint.rs`
- Reference: `opencode-bridge/src/server.rs`
- Reference: `opencode-bridge/src/sync/sse.rs`
- Reference: `docs/superpowers/specs/2026-05-21-bridge-single-sse-design.md`

- [ ] **Step 1: Write the failing retained-event integration test**

```rust
#[tokio::test]
async fn bridge_events_keeps_session_error_event() {
    let input = serde_json::json!({
        "type": "session.error",
        "properties": {
            "sessionID": "ses_1",
            "error": {
                "message": "boom"
            }
        }
    });

    let output = openmate::events::filter::filter_event(input.clone())
        .expect("session.error should be retained");

    assert_eq!(output["type"], "session.error");
    assert_eq!(output["properties"]["sessionID"], "ses_1");
    assert_eq!(output["properties"]["error"]["message"], "boom");
}
```

- [ ] **Step 2: Write the failing dropped-event integration test**

```rust
#[tokio::test]
async fn bridge_events_drops_message_part_delta_event() {
    let input = serde_json::json!({
        "type": "message.part.delta",
        "properties": {
            "sessionID": "ses_1",
            "partID": "part_1",
            "text": "very large delta"
        }
    });

    let output = openmate::events::filter::filter_event(input);

    assert!(output.is_none());
}
```

- [ ] **Step 3: Write the failing trimmed-event test for large payload removal**

```rust
#[tokio::test]
async fn bridge_events_trims_large_message_content_from_message_updated() {
    let input = serde_json::json!({
        "type": "message.updated",
        "properties": {
            "sessionID": "ses_1",
            "messageID": "msg_1",
            "message": {
                "id": "msg_1",
                "content": [
                    {
                        "type": "text",
                        "text": "large body"
                    }
                ]
            }
        }
    });

    let output = openmate::events::filter::filter_event(input)
        .expect("message.updated should be retained");

    assert_eq!(output["properties"]["sessionID"], "ses_1");
    assert_eq!(output["properties"]["messageID"], "msg_1");
    assert!(output["properties"].get("message").is_none());
}
```

- [ ] **Step 4: Run the new Bridge tests to verify they fail**

Run: `cargo test --test events_endpoint`

Expected: FAIL with unresolved module/function errors such as `could not find events in openmate` or `cannot find function filter_event`.

- [ ] **Step 5: Commit the failing tests**

```bash
git add opencode-bridge/tests/events_endpoint.rs
git commit -m "test: add bridge events filtering coverage"
```

### Task 2: Implement Bridge `/api/bridge/events` with allowlist and trimming

**Files:**
- Create: `opencode-bridge/src/events/mod.rs`
- Create: `opencode-bridge/src/events/router.rs`
- Create: `opencode-bridge/src/events/filter.rs`
- Modify: `opencode-bridge/src/lib.rs`
- Modify: `opencode-bridge/src/server.rs`
- Test: `opencode-bridge/tests/events_endpoint.rs`

- [ ] **Step 1: Add the new events module export**

```rust
pub mod events;
```

- [ ] **Step 2: Create the filter function with the first-pass allowlist**

```rust
use serde_json::{Map, Value};

pub fn filter_event(input: Value) -> Option<Value> {
    let event_type = input.get("type")?.as_str()?;
    let properties = input.get("properties")?.as_object()?.clone();

    match event_type {
        "session.created"
        | "session.updated"
        | "session.deleted"
        | "session.status"
        | "session.error"
        | "permission.asked"
        | "permission.replied"
        | "question.asked"
        | "question.replied"
        | "question.rejected"
        | "todo.updated"
        | "message.updated"
        | "message.removed" => {
            let trimmed = trim_properties(event_type, properties);
            Some(Value::Object(Map::from_iter([
                ("type".into(), Value::String(event_type.to_string())),
                ("properties".into(), Value::Object(trimmed)),
            ])))
        }
        _ if event_type.starts_with("session.next.") => {
            let trimmed = trim_properties(event_type, properties);
            if trimmed.is_empty() {
                None
            } else {
                Some(Value::Object(Map::from_iter([
                    ("type".into(), Value::String(event_type.to_string())),
                    ("properties".into(), Value::Object(trimmed)),
                ])))
            }
        }
        _ => None,
    }
}
```

- [ ] **Step 3: Add minimal per-event trimming rules**

```rust
use serde_json::{Map, Value};

pub fn trim_properties(event_type: &str, mut properties: Map<String, Value>) -> Map<String, Value> {
    match event_type {
        "message.updated" => {
            properties.remove("message");
        }
        "session.next.text.delta" | "session.next.reasoning.delta" | "session.next.tool.input.delta" => {
            return Map::new();
        }
        _ => {}
    }

    properties.retain(|key, _| {
        matches!(
            key.as_str(),
            "id" | "sessionID" | "directory" | "messageID" | "partID" | "error" | "status" | "info" | "permission" | "patterns" | "always" | "metadata" | "tool" | "questions"
        )
    });
    properties
}
```

- [ ] **Step 4: Add the new router handler that forwards filtered events to the Android-facing Bridge endpoint**

```rust
use axum::extract::State;
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::response::IntoResponse;
use futures::StreamExt;
use std::convert::Infallible;
use std::pin::Pin;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio_stream::wrappers::ReceiverStream;

use crate::events::filter::filter_event;
use crate::state::AppState;

type BoxStream<T> = Pin<Box<dyn tokio_stream::Stream<Item = T> + Send + 'static>>;

pub async fn events_sse(State(state): State<AppState>) -> impl IntoResponse {
    let opencode_url = state.config.opencode_url();
    let stream = create_events_stream(opencode_url);
    Sse::new(stream).keep_alive(KeepAlive::default())
}

fn create_events_stream(opencode_url: String) -> BoxStream<Result<Event, Infallible>> {
    let (tx, rx) = mpsc::channel(32);

    tokio::spawn(async move {
        let sse_url = format!("{}/global/event", opencode_url);
        loop {
            let _ = forward_events(&sse_url, &tx).await;
            if tx.is_closed() {
                break;
            }
            tokio::time::sleep(Duration::from_secs(3)).await;
        }
    });

    Box::pin(ReceiverStream::new(rx))
}
```

- [ ] **Step 5: Implement event forwarding by parsing opencode `data:` lines and applying `filter_event`**

```rust
async fn forward_events(
    sse_url: &str,
    tx: &mpsc::Sender<Result<Event, Infallible>>,
) -> Result<(), String> {
    let client = reqwest::Client::new();
    let resp = client.get(sse_url).send().await.map_err(|e| format!("Connect failed: {}", e))?;
    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }

    let mut stream = resp.bytes_stream();
    let mut buffer = String::new();

    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| format!("Read error: {}", e))?;
        buffer.push_str(&String::from_utf8_lossy(&chunk));

        while let Some(pos) = buffer.find('\n') {
            let line = buffer[..pos].to_string();
            buffer = buffer[pos + 1..].to_string();

            let trimmed = line.trim();
            let data = match trimmed.strip_prefix("data:") {
                Some(data) => data.trim(),
                None => continue,
            };

            let parsed = match serde_json::from_str::<serde_json::Value>(data) {
                Ok(value) => value,
                Err(_) => continue,
            };

            let Some(filtered) = filter_event(parsed) else {
                continue;
            };

            let event = Event::default().data(filtered.to_string());
            if tx.send(Ok(event)).await.is_err() {
                return Ok(());
            }
        }
    }
    Ok(())
}
```

- [ ] **Step 6: Register the new route in the Bridge server**

```rust
.route("/api/bridge/events", get(events::router::events_sse))
```

- [ ] **Step 7: Run the Bridge test to verify it passes**

Run: `cargo test --test events_endpoint`

Expected: PASS

- [ ] **Step 8: Run the broader Bridge test suite**

Run: `cargo test`

Expected: PASS with no new failures.

- [ ] **Step 9: Commit the Bridge endpoint implementation**

```bash
git add opencode-bridge/src/lib.rs opencode-bridge/src/server.rs opencode-bridge/src/events opencode-bridge/tests/events_endpoint.rs
git commit -m "feat: add trimmed bridge events sse"
```

### Task 3: Add Android unified Bridge event parsing tests first

**Files:**
- Create: `android/core/network/src/test/java/com/openmate/core/network/SyncSseClientTest.kt`
- Create: `android/core/network/src/main/java/com/openmate/core/network/BridgeEvent.kt`
- Create: `android/core/network/src/main/java/com/openmate/core/network/BridgeEventParser.kt`
- Reference: `android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt`

- [ ] **Step 1: Write the failing parser test for a retained event**

```kotlin
@Test
fun parseBridgeEvent_readsSessionUpdated() {
    val line = """
        {"type":"session.updated","properties":{"sessionID":"ses_1","info":{"title":"hello"}}}
    """.trimIndent()

    val event = BridgeEventParser.parse(line)

    assertThat(event?.type).isEqualTo("session.updated")
    assertThat(event?.sessionId).isEqualTo("ses_1")
}
```

- [ ] **Step 2: Write the failing parser test for a sync-trigger event**

```kotlin
@Test
fun parseBridgeEvent_readsMessageUpdatedIdentifiersOnly() {
    val line = """
        {"type":"message.updated","properties":{"sessionID":"ses_1","messageID":"msg_1"}}
    """.trimIndent()

    val event = BridgeEventParser.parse(line)

    assertThat(event?.type).isEqualTo("message.updated")
    assertThat(event?.sessionId).isEqualTo("ses_1")
    assertThat(event?.messageId).isEqualTo("msg_1")
}
```

- [ ] **Step 3: Run the network tests to verify they fail**

Run: `./gradlew.bat :core:network:testDebugUnitTest --tests "com.openmate.core.network.SyncSseClientTest" --no-daemon`

Expected: FAIL with unresolved references to `BridgeEvent` or `BridgeEventParser`.

- [ ] **Step 4: Commit the failing Android tests**

```bash
git add android/core/network/src/test/java/com/openmate/core/network/SyncSseClientTest.kt
git commit -m "test: add unified bridge event parsing coverage"
```

### Task 4: Implement Android unified Bridge event client

**Files:**
- Create: `android/core/network/src/main/java/com/openmate/core/network/BridgeEvent.kt`
- Create: `android/core/network/src/main/java/com/openmate/core/network/BridgeEventParser.kt`
- Modify: `android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt`
- Test: `android/core/network/src/test/java/com/openmate/core/network/SyncSseClientTest.kt`

- [ ] **Step 1: Add the minimal unified event model**

```kotlin
data class BridgeEvent(
    val type: String,
    val sessionId: String?,
    val directory: String?,
    val messageId: String?,
    val partId: String?,
    val rawJson: String,
)
```

- [ ] **Step 2: Add the parser that extracts key identifiers from the opencode-compatible event body**

```kotlin
object BridgeEventParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(data: String): BridgeEvent? {
        val obj = json.parseToJsonElement(data).jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val props = obj["properties"]?.jsonObject
        return BridgeEvent(
            type = type,
            sessionId = props?.get("sessionID")?.jsonPrimitive?.contentOrNull,
            directory = props?.get("directory")?.jsonPrimitive?.contentOrNull,
            messageId = props?.get("messageID")?.jsonPrimitive?.contentOrNull,
            partId = props?.get("partID")?.jsonPrimitive?.contentOrNull,
            rawJson = data,
        )
    }
}
```

- [ ] **Step 3: Convert `SyncSseClient` into the unified Bridge SSE client outputting events instead of seq-only notifications**

```kotlin
private val _events = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 64)
val events: SharedFlow<BridgeEvent> = _events
```

- [ ] **Step 4: Update the SSE URL and line handling to parse `/api/bridge/events` payloads**

```kotlin
val urlBuilder = Request.Builder().url("$baseUrl/api/bridge/events").get()

if (trimmed.startsWith("data:")) {
    val data = trimmed.removePrefix("data:").trim()
    val event = BridgeEventParser.parse(data) ?: continue
    _events.tryEmit(event)
}
```

- [ ] **Step 5: Run the focused network tests to verify they pass**

Run: `./gradlew.bat :core:network:testDebugUnitTest --tests "com.openmate.core.network.SyncSseClientTest" --no-daemon`

Expected: PASS

- [ ] **Step 6: Commit the unified Bridge client implementation**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/BridgeEvent.kt android/core/network/src/main/java/com/openmate/core/network/BridgeEventParser.kt android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt android/core/network/src/test/java/com/openmate/core/network/SyncSseClientTest.kt
git commit -m "feat: unify android bridge sse client"
```

### Task 5: Move Android dispatch and sync triggering onto the unified event stream

**Files:**
- Modify: `android/core/data/src/main/java/com/openmate/core/data/repository/SseEventRepositoryImpl.kt`
- Modify: `android/core/data/src/main/java/com/openmate/core/data/sse/EventDispatcher.kt`
- Modify: `android/core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt`

- [ ] **Step 1: Adapt `SseEventRepositoryImpl` to collect unified Bridge events from the new client**

```kotlin
eventJob = CoroutineScope(Dispatchers.IO).launch {
    syncSseClient.events.collect { bridgeEvent ->
        eventDispatcher.dispatch(bridgeEvent)
    }
}
```

- [ ] **Step 2: Change `EventDispatcher` input from `SseData` to `BridgeEvent` while preserving current event-type behavior**

```kotlin
suspend fun dispatch(event: BridgeEvent) {
    val type = event.type
    val dir = event.directory
    val sessionId = event.sessionId
    if (type == "server.connected" || type == "server.heartbeat" || type == "global.disposed") {
        return
    }
    if ((type.startsWith("message.") || type.startsWith("session.next.") || type.startsWith("todo.")) && messageSyncEnabled && sessionId != null) {
        _messageSyncNeeded.tryEmit(sessionId)
    }
}
```

- [ ] **Step 3: Preserve session error surfacing from the same single stream**

```kotlin
if (type.startsWith("session.")) {
    val result = sessionHandler.handle(type, event)
    if (result != null) {
        _sessionErrors.tryEmit(result)
    }
}
```

- [ ] **Step 4: Replace `SyncSseHandler` seq-only subscription with unified event subscription filtered to sync-trigger events**

```kotlin
collectJob = syncSseClient.events
    .onEach { event ->
        val sessionId = event.sessionId ?: return@onEach
        if (sessionId != activeSessionId) return@onEach
        if (event.type.startsWith("message.") || event.type.startsWith("session.next.") || event.type == "todo.updated") {
            performSync(sessionId)
        }
    }
    .launchIn(scope)
```

- [ ] **Step 5: Run the core data and session unit tests**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests "com.openmate.feature.session.SessionDetailViewModelTest" --tests "com.openmate.feature.session.WorkspaceAndSessionConnectionStatusTest" --no-daemon`

Expected: PASS after repository/dispatcher updates are complete.

- [ ] **Step 6: Commit the single dispatch path changes**

```bash
git add android/core/data/src/main/java/com/openmate/core/data/repository/SseEventRepositoryImpl.kt android/core/data/src/main/java/com/openmate/core/data/sse/EventDispatcher.kt android/core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt android/core/domain/src/main/java/com/openmate/core/domain/repository/SseEventRepository.kt
git commit -m "refactor: route android sse handling through single stream"
```

### Task 6: Update `ConnectionManager` to own one Bridge SSE lifecycle

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Modify: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Write the failing orchestration test that expects only one SSE lifecycle start**

```kotlin
@Test
fun connect_startsOnlyUnifiedBridgeSseLifecycle() = runTest(dispatcher) {
    val profile = profile(id = "p1", address = "127.0.0.1", port = 4097, lastConnectedAt = 1L)
    val fixture = ConnectionManagerFixture(listOf(profile))

    fixture.manager.connect(profile)
    advanceUntilIdle()

    assertThat(fixture.sseRepository.connectCalls).containsExactly("p1")
    assertThat(fixture.syncSseClientConnectCalls).isEmpty()
}
```

- [ ] **Step 2: Run the ConnectionManager test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest.connect_startsOnlyUnifiedBridgeSseLifecycle" --no-daemon`

Expected: FAIL because the current implementation still starts `syncSseClient.connect(...)` separately.

- [ ] **Step 3: Remove the second SSE lifecycle start from `ConnectionManager`**

```kotlin
private fun startSseConnections(profile: ServerProfile) {
    if (useGateway) {
        sseEventRepository.connectViaGateway(GATEWAY_URL)
    } else {
        sseEventRepository.connect(profile.address, profile.port, profile.password)
    }
}
```

- [ ] **Step 4: Remove obsolete `syncSseJob`, `startSyncSse()`, and second-stream reconnect wiring**

```kotlin
private fun teardownCurrentConnection(clearActiveProfile: Boolean) {
    useGateway = false
    stopDirectCheckLoop()
    sseEventRepository.disconnect()
    gatewayInterceptor.instanceId = null
    if (clearActiveProfile) {
        scope.launch { clearConnection() }
    }
}
```

- [ ] **Step 5: Update fallback and direct-switch code paths to restart only the unified stream**

```kotlin
sseEventRepository.disconnect()
sseEventRepository.connectViaGateway(GATEWAY_URL)
```

- [ ] **Step 6: Run the full ConnectionManager test class**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`

Expected: PASS

- [ ] **Step 7: Commit the ConnectionManager unification**

```bash
git add android/app/src/main/java/com/openmate/app/ConnectionManager.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt
git commit -m "refactor: make connection manager own one sse lifecycle"
```

### Task 7: Remove old split-path assumptions from session tests and verify behavior

**Files:**
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt`
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/WorkspaceAndSessionConnectionStatusTest.kt`

- [ ] **Step 1: Update fake SSE repositories and clients in session tests to model the unified Bridge stream**

```kotlin
private class FakeSseEventRepository(
    private val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
) : SseEventRepository {
    override fun connect(address: String, port: Int, password: String?) = emptyFlow<SseEvent>()
    override fun connectViaGateway(baseUrl: String) = emptyFlow<SseEvent>()
    override fun disconnect() = Unit
    override fun observeConnectionStatus() = flowOf(status)
    override fun isConnectedTo(address: String, port: Int): Boolean = false
    override fun setActiveSessionScope(directory: String?, enabled: Boolean) = Unit
    override fun observeMessageSyncNeeded() = emptyFlow<String>()
    override fun observeSessionErrors() = emptyFlow<Pair<String, String>>()
}
```

- [ ] **Step 2: Keep the existing connection-status test passing through the unified source**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests "com.openmate.feature.session.SessionDetailViewModelTest.connectionStatus_usesConnectionRepositoryStatus" --no-daemon`

Expected: PASS

- [ ] **Step 3: Run the targeted session verification suite**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests "com.openmate.feature.session.WorkspaceAndSessionConnectionStatusTest" --tests "com.openmate.feature.session.SessionDetailViewModelTest" --no-daemon`

Expected: PASS

- [ ] **Step 4: Commit the session test adjustments**

```bash
git add android/feature/session/src/test/java/com/openmate/feature/session/SessionDetailViewModelTest.kt android/feature/session/src/test/java/com/openmate/feature/session/WorkspaceAndSessionConnectionStatusTest.kt
git commit -m "test: align session tests with single bridge sse"
```

### Task 8: Final verification and cleanup of dual-SSE runtime assumptions

**Files:**
- Modify: any files touched above if verification reveals missed assumptions

- [ ] **Step 1: Search for remaining split-path assumptions**

Run: `rg "SyncSseClient|startSyncSse\(|syncSseJob|/api/bridge/sync/events|message\.part\.updated|message\.part\.removed|message\.part\.delta" android opencode-bridge`

Expected: only compatibility leftovers or test fixtures remain.

- [ ] **Step 2: Run the Android debug build**

Run: `./gradlew.bat :app:assembleDebug --no-daemon`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run the Bridge full test suite again**

Run: `cargo test`

Expected: PASS

- [ ] **Step 4: Verify repository state before completion**

Run: `git status --short --branch`

Expected: only intended tracked modifications remain.

- [ ] **Step 5: Commit the final cleanup pass**

```bash
git add opencode-bridge android
git commit -m "refactor: unify bridge and android sse path"
```

## Self-Review

- Spec coverage:
  - single Bridge endpoint -> Tasks 1-2
  - one Android business Bridge SSE endpoint and event source -> Tasks 2 and 8
  - one Android business SSE client -> Tasks 4-6
  - keep opencode-compatible event shape -> Tasks 2 and 4
  - subtraction-first allowlist -> Tasks 1-2
  - preserve current session/permission/question/todo behavior -> Tasks 5 and 7
  - defer fallback-slowness work -> no task included
- Placeholder scan:
  - no `TODO`/`TBD` placeholders in plan steps
  - every code step contains concrete code to start from
- Type consistency:
  - plan uses `BridgeEvent`, `BridgeEventParser`, unified `SyncSseClient.events`, and existing `SseEventRepository` naming consistently
