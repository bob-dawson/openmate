# Bridge Single SSE Design

## Goal

Unify Android runtime SSE handling into a single Bridge-backed connection, reduce total SSE traffic, and simplify connection ownership and event management.

After this change:

- Android keeps exactly one business SSE connection.
- That connection only connects to Bridge.
- Bridge keeps exactly one upstream SSE connection to opencode `/global/event`.
- Bridge exposes a new Android-facing SSE endpoint that preserves opencode event structure where possible, while removing large unused payloads.
- Missing events currently relied on by Android are restored through Bridge instead of keeping a second Android SSE path.

This design does not address slow fallback from direct to gateway. That is explicitly deferred until after the single-connection architecture is complete.

## Constraints

- Keep compatibility with opencode SSE event naming and top-level structure whenever possible.
- Prefer subtraction over invention: only retain events that current Android behavior actually needs.
- Do not keep events only because they might be useful later.
- Message content remains sourced from seq-based incremental sync, not from SSE payload bodies.
- Bridge may add a new endpoint name. It does not need to overload the old sync endpoint.

## Current State

Android currently uses two SSE paths with split responsibilities:

1. `SseClient` -> original `/global/event`
   - connection status
   - `EventDispatcher`
   - `messageSyncNeeded`
   - `sessionErrors`
   - permission/question/session/todo related realtime handling

2. `SyncSseClient` -> Bridge `/api/bridge/sync/events`
   - only `{ sessionID, seq }` sync notifications

Bridge also maintains two separate upstream interpretations of opencode global SSE:

- raw proxy path for `/global/event`
- filtered sync path for `/api/bridge/sync/events`

This duplicates traffic and spreads connection behavior across multiple owners and code paths.

## Target Architecture

### Bridge

- Keep one upstream connection to opencode `/global/event`.
- Parse incoming opencode SSE events once.
- Apply an allowlist of currently-needed events.
- Trim large mobile-unused payload fields from retained events.
- Broadcast the retained trimmed events to a new endpoint:
  - `GET /api/bridge/events`

### Android

- Replace the dual-client runtime path with one Bridge SSE client.
- That client connects only to `GET /api/bridge/events`.
- Connection status, event dispatch, and sync triggering all derive from that single stream.
- Remove old business dependence on raw `SseClient` and the separate `SyncSseClient` event path.

### Ownership

- `ConnectionManager` remains the only connection owner.
- UI/ViewModel layers do not own SSE lifecycle.
- Sync triggering, session error surfacing, and permission/question/todo refresh signals are all driven from the single Bridge SSE stream.

## API Design

### New Endpoint

- `GET /api/bridge/events`

This is the only Android business SSE endpoint after migration.

### Event Shape

Bridge preserves the opencode SSE event envelope as much as possible.

Expected SSE `data:` format remains structurally compatible with opencode:

```json
{
  "type": "session.updated",
  "properties": {
    "sessionID": "ses_xxx",
    "info": {
      "title": "Example"
    }
  }
}
```

Bridge is allowed to:

- remove large payload subfields Android does not currently use
- remove oversized incremental text bodies
- keep event names unchanged
- keep the `properties` container unchanged where practical

Bridge should avoid:

- renaming opencode event types
- replacing all events with a brand-new domain protocol
- embedding full message bodies only for convenience

## Event Retention Principle

The default rule is: do not retain an event unless current Android behavior actually consumes it or would regress without it.

Selection order:

1. Retain only events with a current Android consumer or required side effect.
2. For retained events, trim payload down to the minimum needed fields.
3. Drop unused events entirely.
4. If a dropped event becomes needed later, add it back explicitly.

## Event Retention Plan

### Must Retain

These event types are required because current Android behavior depends on them directly or indirectly.

- `session.created`
- `session.updated`
- `session.deleted`
- `session.status`
- `session.error`
- `permission.asked`
- `permission.replied`
- `question.asked`
- `question.replied`
- `question.rejected`
- `todo.updated`
- `message.updated`
- `message.removed`
- selected `session.next.*` events that are still required to trigger sync or preserve current session runtime behavior

### Must Drop

These event types are not retained in the first single-SSE cut because current Android does not actually need them.

- `message.part.updated`
- `message.part.removed`
- `message.part.delta`

Reason:

- Android now treats V2 message roots plus incremental sync as the source of truth.
- These part-level SSE events are legacy-compatible at best and not required as first-class retained events.
- `message.part.delta` is especially high-volume and should not survive the mobile-trimmed Bridge stream.

### Conditionally Retain From `session.next.*`

`session.next.*` must be screened event-by-event. The rule is not to preserve the whole family automatically.

Retain only if current Android actually depends on the event to:

- trigger timely incremental sync
- preserve session busy/runtime behavior
- preserve failure/step boundary handling currently visible to the user

Do not retain if the event exists only to carry large streamed content or old fine-grained part behavior.

Expected first-pass drop candidates:

- text delta events
- reasoning delta events
- tool input delta events
- other large streaming payload fragments that do not need to be rendered from SSE directly

The exact per-event table belongs in implementation planning and test coverage.

### Optional Events

These are not part of the core retention goal, but may be passed through if they simplify the single-client connection state machine without adding meaningful cost:

- `server.connected`
- `server.heartbeat`
- `global.disposed`

If Android can manage connection liveness without consuming them semantically, they may also be omitted.

## Payload Trimming Rules

For retained events, Bridge should preserve only the fields needed for current Android behavior.

### Preserve

- `type`
- `properties`
- key identifiers used by current consumers, such as:
  - `sessionID`
  - `directory`
  - `messageID`
  - `partID`
  - `id`
  - status / error / tool locator fields currently parsed by Android

### Remove Aggressively

- large text delta bodies
- full message content blocks when sync can fetch final state
- full reasoning streams
- bulky nested payloads unused by current Android code

### Guiding Rule

If Android can still perform the same behavior by receiving the event signal and then using existing sync / repository refresh logic, the large payload should be removed.

## Android Design

### Single Client

Android moves to one Bridge SSE client responsible for:

- connection lifecycle
- connection status state
- receiving the trimmed opencode-compatible event stream

This client replaces the business role currently split between:

- `SseClient`
- `SyncSseClient`

### Single Dispatch Path

Android keeps one dispatch chain for retained Bridge events.

That dispatch chain drives:

- session list/workspace updates
- session error surfacing
- permission refresh / ask handling
- question refresh / ask handling
- todo refresh triggers
- message incremental sync triggers

### Sync Strategy

SSE is not the source of full message content.

SSE only provides enough signals to know when Android should:

- run message incremental sync
- refresh repositories / Room-backed state
- surface errors or pending actions

This keeps the existing seq-based sync architecture as the source of truth for message bodies.

### Connection Ownership

`ConnectionManager` remains the only runtime owner of the connection.

After migration:

- there is one active SSE lifecycle to start/stop
- there is one status source to expose to UI
- there is one stream to use later when improving fallback behavior

## Components To Retire Or Reshape

The exact class names may shift during implementation, but the target state is:

- no separate Android business dependence on raw `/global/event`
- no separate second sync-only SSE client as an independent runtime path
- no split between one SSE for status/events and another SSE for seq hints

Likely outcomes:

- old `SseClient` business role removed or reduced
- old `SyncSseClient` business role folded into the unified client
- `SseEventRepositoryImpl` reshaped to read from the single Bridge stream
- sync triggering updated to consume the same single stream

## One-Shot Migration Strategy

This change is intentionally a one-shot switch, not a long-lived dual-path rollout.

Implementation order:

1. Add Bridge `/api/bridge/events`.
2. Implement Bridge allowlist + trimming logic.
3. Switch Android to the new single Bridge SSE client.
4. Move event dispatch and sync triggering onto that client.
5. Remove old dual-SSE wiring.

Temporary adapters are acceptable during the refactor, but the merged result must expose only one business SSE path.

## Non-Goals

This design explicitly does not include:

- optimizing slow direct-to-gateway fallback timing
- redesigning retry/backoff policy in detail
- preserving events that current Android does not use
- adding speculative future-facing events
- changing message source of truth away from seq incremental sync

## Risks

- some currently implicit event dependencies may be missed in the first allowlist pass
- `session.next.*` may be over-retained if not screened carefully enough
- payload trimming may remove fields still indirectly relied upon by current handlers

These risks must be controlled through targeted event-by-event tests.

## Verification Requirements

### Bridge

- unit tests for event allowlist decisions
- unit tests for payload trimming rules
- tests proving dropped events are absent from `/api/bridge/events`
- tests proving retained events preserve required identifiers and fields

### Android

- unit tests for single-stream connection status propagation
- unit tests for single-stream sync triggering
- unit tests for `session.error` propagation
- unit tests for permission/question/todo behavior not regressing

### Integration

- verify Android runtime has only one business SSE connection
- verify Bridge runtime has only one upstream `/global/event` connection
- verify session create/update/delete/status flows still update UI
- verify session errors still surface
- verify permission/question/todo realtime behavior does not regress
- verify message updates still converge via incremental sync

## Success Criteria

The design is successful when all of the following are true:

- Android uses one business SSE connection only.
- Bridge uses one upstream opencode SSE connection only.
- current realtime behavior needed by Android is preserved.
- traffic is lower than the current two-SSE design.
- code ownership is clearer: one connection owner, one event stream, one dispatch path.
- the architecture is ready for a later focused pass on fallback slowness.
