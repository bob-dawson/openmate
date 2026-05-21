# Connection Recovery State Machine Design

## Goal

Define the concrete event-driven state machine for Android connection management.

This document turns the ideal recovery standard into an explicit state model with:

- machine states
- input events
- state transitions
- side-effect boundaries
- route-health semantics
- SSE transport semantics
- sync boundary switching rules
- legacy status mapping rules

This document still does not define implementation steps.

## Update Note

This design intentionally no longer models sync batch boundary as a top-level machine state.

Incremental sync remains an evidence source and a recovery consumer, but not a core machine-state dimension.

## Design Intent

The state machine exists to solve three problems at once:

1. make recovery behavior explicit and predictable
2. separate route availability from SSE transport health
3. ensure route switching does not destabilize sync or UI semantics

The state machine is user-availability-first.

It is not a thin wrapper around current transport callbacks.

## Core Separation

The machine must keep these concepts distinct:

- desired connection target
- route evidence and route health
- active route ownership
- SSE transport state
- recovery generation
- UI-facing connection phase

If these are collapsed into one status value, the machine will become ambiguous and unstable again.

## Desired Connection Target

At any moment, the app has exactly one desired target:

- `NoTarget`
- `Target(profileId)`

Meaning:

- `NoTarget`: the app should not maintain an active connection
- `Target(profileId)`: the app should keep that profile usable if possible

Unexpected transport failure does not change the desired target.

Only user intent or explicit profile replacement changes the desired target.

## Route Model

The machine recognizes exactly two route classes:

- `Direct(address, port)`
- `Gateway(instanceId)`

Route preference is:

- direct preferred
- gateway acceptable when it restores availability faster

But route preference is not itself machine state.

It is one input into routing decisions.

## Route Evidence Model

The machine should prefer in-band network evidence over dedicated special-case probing.

Each route accumulates recent network-layer evidence from:

- ordinary API call success
- ordinary API call network-layer failure
- explicit route checks when needed
- network-path change events
- SSE transport connection and failure events

Important rule:

- business semantics do not matter for route evidence
- only network-layer success or failure matters here

So incremental sync, status refresh, session list fetch, and other API calls are all valid route evidence producers.

## Route Health Model

Route health must be evaluated independently from SSE transport state.

Each route has its own current health assessment.

That assessment should be derived primarily from recent route evidence.

Explicit probing is a fallback, not the preferred primary source.

### Direct Route Health

Direct health answers:

- can the app reach the Bridge endpoint over direct network path?
- does the Bridge endpoint respond as an actual usable Bridge instance?

Direct health is not defined by whether the current SSE stream happens to be connected.

If recent direct-route API evidence already exists, the machine should prefer that evidence over redundant extra probing.

### Gateway Route Health

Gateway health answers two separate questions that must both be positive before the route is considered usable:

- is the gateway network path reachable?
- is the target Bridge instance actually online and usable through that gateway?

So gateway usable means:

- gateway reachable
- target Bridge online through gateway

Checking raw gateway reachability alone is not sufficient.

If recent gateway-routed API evidence already proves usability, the machine should prefer that evidence over redundant extra probing.

## SSE Transport Model

SSE transport is a transport signal and a source of route suspicion, not the global source of truth for app availability.

The machine should treat SSE transport as having these conceptual states:

- `Idle`
- `Connecting`
- `Streaming`
- `Restarting`
- `Failed`

Important rules:

- `Streaming` means the SSE transport is connected and reading a stream
- `Failed` means the SSE transport failed for the current route attempt
- `Failed` does not automatically mean the route itself is globally unusable
- `Restarting` during route handoff does not mean network availability was lost

This separation is required so short SSE restarts do not create false app-level disconnection signals.

Additional rule:

- `SseStreamClosed` or `SseFailed` should be treated as "re-evaluate route evidence now", not "declare network unavailable now"
- `SseEventReceived(route)` should be treated as a strong positive signal that the current route is currently alive enough to deliver traffic

## Sync Execution Model

Sync execution is distinct from both route health and SSE transport.

Incremental sync is not a primary connection-state dimension.

For state-machine purposes, sync has only two roles:

1. its API outcomes contribute route evidence like any other API call
2. it should be triggered promptly when network or route availability returns

The machine does not need fine-grained sync internals.

Route switching should use make-before-break semantics where possible, rather than relying on sync as a state-machine-owned boundary.

## Machine States

The machine should expose the following top-level states.

### 1. `Idle`

Meaning:

- desired target is `NoTarget`
- no active route should be maintained
- no recovery work should continue

### 2. `Evaluating`

Meaning:

- desired target is `Target(profileId)`
- the machine is selecting or re-selecting the best usable route
- route health is being refreshed or interpreted

This is entered after:

- user connect
- user retry
- foreground recovery trigger
- network change trigger
- route-evidence update trigger
- route-health update trigger
- post-failure reevaluation

### 3. `Connecting(route)`

Meaning:

- a route has been chosen
- the machine is establishing SSE transport ownership on that route

This is not yet user-visible success.

### 4. `ConnectedDirect`

Meaning:

- desired target exists
- direct route currently owns the connection
- route is considered usable
- SSE transport is active on direct

### 5. `ConnectedGateway`

Meaning:

- desired target exists
- gateway route currently owns the connection
- route is considered usable
- SSE transport is active on gateway

This is usable but degraded relative to direct preference.

### 6. `Recovering(route?)`

Meaning:

- desired target still exists
- current transport ownership is lost, uncertain, or being refreshed
- machine is actively trying to restore usable state

This is not the same as user-disconnected and not yet the same as hard failure.

### 7. `NeedsRepair(profileId)`

Meaning:

- automatic recovery cannot succeed until user-provided repair is completed
- example: missing or invalid token

### 8. `FailedRetryable(lastReason)`

Meaning:

- desired target still exists
- no currently usable route was established
- automatic recovery is no longer immediately progressing
- manual retry or later triggers may restart evaluation

This is a failure state, but not a user-disconnected state.

## Input Events

The machine accepts only explicit events.

### User Intent Events

- `UserConnect(profile)`
- `UserDisconnect`
- `UserRetry`
- `RepairCompleted(profileId)`

### Lifecycle Events

- `AppForegrounded`
- `AppBackgrounded`

### Network and Health Events

- `NetworkAvailable`
- `NetworkLost`
- `NetworkPathChanged`
- `RouteEvidenceUpdated(route)`
- `RouteHealthUpdated(snapshot)`
- `BackoffExpired`

### SSE Transport Events

- `SseConnectStarted(route)`
- `SseConnected(route)`
- `SseEventReceived(route)`
- `SseStreamClosed(route)`
- `SseFailed(route, reason)`

## Event Priority Rules

When multiple conditions compete, event handling priority is:

1. user disconnect
2. user connect / user retry / repair completed
3. app foregrounded
4. network available / network lost / network path changed
5. route evidence updated / route health updated
6. SSE transport failure / closure
7. passive backoff expiry

This guarantees that user intent and strong recovery signals interrupt stale waiting.

## Transition Rules

Below are the required transitions.

### From `Idle`

- `UserConnect(profile)` -> `Evaluating`
- `UserDisconnect` -> stay `Idle`
- all other events -> stay `Idle`

### From `Evaluating`

- if direct usable and preferred -> `Connecting(Direct)`
- else if gateway usable -> `Connecting(Gateway)`
- else if repair required -> `NeedsRepair(profileId)`
- else if no usable route -> `Recovering(null)` or `FailedRetryable(reason)` depending on retry policy window
- `UserDisconnect` -> `Idle`

### From `Connecting(route)`

- `SseConnected(route)` ->
  - `ConnectedDirect` if route is direct
  - `ConnectedGateway` if route is gateway
- `SseFailed(route, reason)` -> `Recovering(route)`
- `SseStreamClosed(route)` -> `Recovering(route)`
- `UserDisconnect` -> `Idle`
- `UserRetry` -> `Evaluating`

### From `ConnectedDirect`

- `SseEventReceived(Direct)` -> stay `ConnectedDirect` and refresh direct route evidence
- `SseStreamClosed(Direct)` -> `Recovering(Direct)`
- `SseFailed(Direct, reason)` -> `Recovering(Direct)`
- `NetworkAvailable` -> `Evaluating`
- `NetworkLost` -> `Recovering(Direct)`
- `NetworkPathChanged` -> `Evaluating`
- `AppForegrounded` -> `Evaluating`
- `RouteEvidenceUpdated(Direct)` -> stay `ConnectedDirect` if direct evidence remains healthy
- `RouteHealthUpdated(snapshot)` ->
  - stay `ConnectedDirect` if direct still usable
  - `Evaluating` if direct no longer sufficiently usable
- `UserDisconnect` -> `Idle`
- `UserRetry` -> `Evaluating`

### From `ConnectedGateway`

- `SseEventReceived(Gateway)` -> stay `ConnectedGateway` and refresh gateway route evidence
- `SseStreamClosed(Gateway)` -> `Recovering(Gateway)`
- `SseFailed(Gateway, reason)` -> `Recovering(Gateway)`
- `NetworkAvailable` -> `Evaluating`
- `NetworkLost` -> `Recovering(Gateway)`
- `NetworkPathChanged` -> `Evaluating`
- `AppForegrounded` -> `Evaluating`
- `RouteEvidenceUpdated(Gateway)` -> stay `ConnectedGateway` if gateway remains current best usable route
- `RouteHealthUpdated(snapshot)` ->
  - stay `ConnectedGateway` if gateway still best currently usable route
  - `Connecting(Direct)` if direct is now preferred and no sync batch is active
- `UserDisconnect` -> `Idle`
- `UserRetry` -> `Evaluating`

### From `Recovering(route?)`

- `UserRetry` -> `Evaluating`
- `AppForegrounded` -> `Evaluating`
- `NetworkAvailable` -> `Evaluating`
- `NetworkLost` -> stay `Recovering(route?)`
- `NetworkPathChanged` -> `Evaluating`
- `RouteEvidenceUpdated(route)` -> `Evaluating`
- `RouteHealthUpdated(snapshot)` -> `Evaluating`
- `BackoffExpired` -> `Evaluating`
- `UserDisconnect` -> `Idle`

### From `NeedsRepair(profileId)`

- `RepairCompleted(profileId)` -> `Evaluating`
- `UserDisconnect` -> `Idle`
- `UserRetry` -> stay `NeedsRepair(profileId)` unless repair condition is resolved

### From `FailedRetryable(reason)`

- `UserRetry` -> `Evaluating`
- `AppForegrounded` -> `Evaluating`
- `NetworkAvailable` -> `Evaluating`
- `NetworkLost` -> stay `FailedRetryable(reason)`
- `NetworkPathChanged` -> `Evaluating`
- `RouteHealthUpdated(snapshot)` -> `Evaluating`
- `UserDisconnect` -> `Idle`

## Recovery Policy

The machine should use passive backoff only while in recovery-related states.

Passive backoff must never block these from triggering immediate reevaluation:

- user retry
- app foreground
- network available
- network path change
- route evidence update indicating fresh usable traffic
- route health update indicating newly usable route

So the rule is:

- timers are fallback triggers
- meaningful events are immediate triggers

## Route Selection Rules

When `Evaluating`, selection must follow this logic:

1. If repair is required, select no route and enter `NeedsRepair`.
2. If direct is healthy enough, prefer direct.
3. Else if gateway is healthy enough, use gateway.
4. Else remain unrecovered and continue recovery policy.

"Healthy enough" must not mean merely "last SSE did not fail".

It must come from independent route-health assessment derived primarily from recent route evidence.

## Safe Switch Contract

When current route is gateway and direct becomes preferable, the machine should prefer make-before-break behavior:

- prepare direct takeover first
- do not tear down the currently serving route before the new route is ready to serve
- once direct is ready, hand ownership over and allow future traffic to converge on direct

The machine should not rely on a dedicated sync-boundary state to make this safe.

## SSE Failure Semantics

The machine must treat SSE transport failure carefully.

Required rule:

- SSE failure means the current transport instance is no longer trustworthy
- SSE failure does not, by itself, prove that the route is globally unavailable

So the transition after SSE failure is:

- enter `Recovering(route)`
- reevaluate route health
- choose the best route again

This avoids the incorrect shortcut of "SSE failed, therefore network unavailable".

## API Evidence Semantics

Any ordinary API call can update route evidence.

Required rule:

- successful API call on a route is positive route evidence for that route
- network-layer API failure on a route is negative route evidence for that route
- business-layer failures do not automatically count as route failures

This allows the system to learn route usability from real traffic rather than depending only on dedicated probes.

## Network Change Semantics

`NetworkPathChanged` is a strong reevaluation event.

It means:

- current route assumptions may now be stale
- current SSE transport may remain up briefly but still not be trustworthy long-term

The machine should react by reentering `Evaluating`, not by blindly declaring failure.

If route evidence becomes positive again after a network recovery, the machine should also trigger:

- immediate SSE reconnect attempt if needed
- immediate catch-up incremental sync attempt

## Recovery Generation

The machine should track a recovery generation counter.

Purpose:

- every strong recovery trigger starts a fresh recovery generation
- stale callbacks from older generations must not overwrite newer routing decisions

A new recovery generation should begin on:

- `UserConnect(profile)`
- `UserRetry`
- `AppForegrounded`
- `NetworkAvailable`
- `NetworkPathChanged`
- strong `RouteEvidenceUpdated(route)` that reopens route selection

This is required to prevent stale reconnect loops from keeping the app in a stuck state until restart.

## Foreground Semantics

`AppForegrounded` means the user is demanding fast usability again.

If a target profile exists, the machine should reevaluate immediately.

This event should bypass passive retry waiting.

## Legacy Status Mapping

The existing app still has legacy consumers of `ConnectionStatus`.

Until that API is replaced, machine states must be mapped carefully.

Recommended mapping:

- `Idle` -> `DISCONNECTED`
- `Evaluating` -> `CONNECTING`
- `Connecting(route)` -> `CONNECTING`
- `ConnectedDirect` -> `CONNECTED`
- `ConnectedGateway` -> `GATEWAY_CONNECTED`
- `Recovering(route?)` -> `CONNECTING`
- `NeedsRepair(profileId)` -> `PAIRING`
- `FailedRetryable(reason)` -> `ERROR`

Important rule:

- short-lived route handoff or SSE restart must not map to `DISCONNECTED`

## UI Semantics

UI should interpret mapped or richer state with these meanings:

- `CONNECTED`: usable direct
- `GATEWAY_CONNECTED`: usable but degraded
- `CONNECTING`: currently establishing or recovering, not necessarily hard-failed
- `PAIRING`: blocked on user repair
- `ERROR`: currently not usable, but retryable
- `DISCONNECTED`: user-intent disconnect or no target

This keeps the app from looking unstable during controlled recovery.

## Minimal UI Semantic Set

Even if internal machine state stays richer, the minimum UI semantic set should be kept small:

- user disconnected
- recovering / reconnecting
- usable direct
- usable gateway degraded
- temporarily unavailable but retryable
- needs user repair

## Non-Goals For This State Machine

This document does not define:

- exact probe cadence numbers
- exact backoff timing values
- exact Android framework classes for monitors
- exact Room or repository changes
- bridge-side protocol changes

Those belong to implementation planning.

## Open Questions Still Allowed Before Planning

Only these details remain open:

- exact thresholds for route health confidence
- exact cadence for direct and gateway health probing
- exact freshness window for in-band API evidence
- whether foreground and background use different probe intervals
- whether future UI should consume `ConnectionSnapshot` directly instead of legacy `ConnectionStatus`

These are tuning decisions, not core-state-definition decisions.

## Success Criteria

This state-machine design is successful when all of the following are true:

- all recovery behavior is expressible as explicit events and transitions
- route health is not inferred solely from SSE transport state
- SSE disconnect and route unavailability are no longer treated as the same thing
- gateway-to-direct return happens only at safe boundaries
- user retry, app foreground, and network changes can all force immediate reevaluation
- transient route handoff does not appear to the app as total disconnection
