# Connection Recovery Current Gap Analysis

## Goal

Compare the current Android connection behavior against the ideal connection recovery standard.

This document focuses on current behavior, observed symptoms, ambiguous semantics, and design gaps.

It does not define implementation steps yet.

## Reference Standard

This analysis uses the following document as the target standard:

- `docs/superpowers/specs/2026-05-21-connection-recovery-ideal-design.md`

## High-Level Conclusion

The current system has moved closer to a single business SSE path, but it still does not have a clear and trustworthy connection state model.

The main issue is not only that some reconnect behaviors are buggy.

The deeper issue is that connection availability, SSE stream health, route choice, fallback policy, and user-visible state are still coupled too loosely in some places and too implicitly in others.

This leads to behavior that feels unstable even when the app could in principle recover quickly.

## User-Visible Symptoms Reported So Far

The current behavior has at least these user-visible failure patterns:

1. The app may switch to gateway even though direct connectivity is actually still available.
2. After switching to gateway, the app may fail because Bridge is not registered to the gateway, which makes the fallback path feel arbitrary instead of deliberate.
3. SSE state often acts as a misleading indicator of total network availability.
4. Recovery can be very slow even when the network is healthy again.
5. In some cases the app shows disconnected and cannot recover until the app is restarted.
6. Network problems are surfaced in ways that do not feel like a stable offline-capable product.

These symptoms strongly suggest both implementation bugs and a deeper lack of unified connection-state semantics.

## Current Architecture Snapshot

### Connection Ownership

Current owner:

- `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`

This class currently owns:

- restore on app startup
- direct connection probing
- fallback to gateway
- switching back from gateway to direct
- starting the current Bridge SSE stream
- exposing app-level connection status

This matches the intended single-owner direction, but the semantics inside that owner are still incomplete.

### Business SSE Path

Current business SSE path:

- `SyncSseClient` connects to `/api/bridge/events`
- `SseEventRepositoryImpl` consumes `syncSseClient.notifications`
- `SyncSseHandler` also consumes `syncSseClient.notifications`

So the app does already have one business SSE source.

This is a good architectural simplification compared with the old dual-SSE runtime.

### Startup Restore

Startup restore currently happens from:

- `android/app/src/main/java/com/openmate/app/OpenMateApp.kt`

On app process start, `connectionManager.restoreLastConnection()` is called.

This means the app does have a startup recovery entrypoint, but current semantics around later recovery remain unclear.

## Current Status Model Is Too Coarse

Current enum:

- `DISCONNECTED`
- `CONNECTING`
- `CONNECTED`
- `GATEWAY_CONNECTED`
- `ERROR`
- `NOT_BRIDGE`
- `PAIRING`

Problems with this model:

- it has no explicit recovering state
- it has no explicit degraded-but-usable state except the special-case `GATEWAY_CONNECTED`
- it has no distinction between transport failure and route failure
- it has no distinction between retryable failure and repair-required blocking state beyond a separate `needsRepairing` flow
- it gives no semantic place for foreground-triggered recovery, passive retry, or stale-route reevaluation

This is one of the main reasons the UI and logs can describe the app as failed even when the system should still be in an automatic recovery phase.

## Current SSE Status Semantics

### `SyncSseClient` Behavior

Current `SyncSseClient` behavior, as implemented:

- if `currentBaseUrl == baseUrl`, `connect()` returns immediately
- on new connect it calls `disconnect()` first
- once HTTP response is successful, it sets state to `CONNECTED`
- it then enters a blocking read loop over SSE lines
- if HTTP connect fails or an exception is thrown, it sets state to `ERROR`
- if the stream closes while still targeting the same URL, it logs closure and sleeps before reconnecting
- reconnect delay is fixed inside the loop

### Key Semantic Gaps

1. Same-URL reconnect is not guaranteed to produce a fresh recovery attempt.

Because `connect()` returns early when the URL matches, there is a real risk that a user-visible reconnect request or resume-triggered reconnect is skipped even when a fresh attempt is needed.

2. `CONNECTED` means "HTTP SSE stream was successfully opened", not "current route is definitely healthy for app use".

3. `ERROR` means "the SSE loop hit an HTTP or stream exception", not necessarily "the route is unusable for all traffic".

4. There is no separate liveness model beyond stream read success and exception behavior.

This means the current SSE state is not a trustworthy definition of connection usability.

## Comparison With Old `SseClient`

The older `SseClient` had an explicit heartbeat-style timeout based on time since last received line.

That older behavior was still not ideal, because "no event arrived" and "network is broken" are not the same thing.

But it does highlight an important gap in the current `SyncSseClient` design:

- current unified business SSE no longer has an explicit time-based liveness layer
- but it also does not replace that with a better route-health model

This leaves the current system in an ambiguous middle ground.

## Open Technical Question: Can SSE Alone Represent Availability?

This is the central design question for the next phase.

Two possibilities exist:

1. SSE can remain the primary connection signal if its liveness semantics are fixed well enough.
2. SSE is fundamentally the wrong primary signal for route availability, and route health must be modeled separately.

Current evidence does not yet justify choosing one immediately.

What current evidence does show is:

- the present SSE semantics are too weak to serve as the only trustworthy availability signal
- the app currently confuses SSE failure with broader connection failure too often

So even if the final answer remains SSE-primary, the current SSE implementation is not sufficient.

The design direction is now more specific:

- SSE disconnect should be treated as a suspicion signal
- SSE event receipt should be treated as a strong positive signal for current-route liveness
- final route usability should still come from the broader route-evidence model, not SSE alone

## Current Fallback Logic Is Too Implicit

Current direct-to-gateway fallback behavior in `ConnectionManager` is driven mainly by:

- direct `/api/bridge/status` probe failure during connect
- SSE status reaching `ERROR` while not already using gateway
- gateway online check by instance ID

Problems with the current logic:

1. Direct failure and SSE failure are treated too similarly.
2. Fallback path selection is not expressed as a clear policy with explicit confidence and switching reasons.
3. A fallback may happen even when the user experiences the direct path as effectively available.
4. If gateway is not actually usable for that Bridge instance, the fallback feels arbitrary and harmful.

This is exactly the user-visible symptom where the app moves to gateway even though direct should have remained the chosen path.

## Current Return-To-Direct Logic Is Too Primitive

Current return-to-direct behavior is based on a background loop:

- while using gateway, every 30 seconds probe direct `/api/bridge/status`
- if direct is reachable, switch API base URL back to direct and restart SSE

Problems:

1. It is route-optimization-first rather than boundary-safe-first.
2. It does not explicitly coordinate with sync batch boundaries.
3. It does not use a stronger make-before-break handoff model.
4. It does not express whether the switch is safe for current user-visible activity.

The user expectation is not just "switch back eventually".

The user expectation is "switch back safely, without creating new instability".

## API Calls, Sync, and SSE Should Not Be Treated Equally

The earlier design discussion overemphasized incremental sync as a primary state-machine concern.

That is not the right abstraction.

The correct priority is:

- route availability should be inferred from recent network-layer evidence on that route
- ordinary API calls are valid availability evidence
- incremental sync calls are just one kind of API traffic
- SSE transport events are suspicion signals, not final availability truth

This means incremental sync does matter operationally, but not as a first-class availability model.

## Sync and SSE Are Operationally Separate but Semantically Coupled

Current observation:

- SSE and sync are separate connections and separate activities
- sync batches are initiated from event handling or repository logic
- route switching mainly affects current base URL and future event intake

This is good news because it means route switching does not automatically destroy an in-flight sync request.

However, the most important unresolved semantics are narrower than previously framed:

- what state owns future sync triggering after a switch
- how old-route events are invalidated after a switch
- whether gateway-to-direct takeover should wait for the current batch boundary
- how network-restored events should trigger a catch-up sync run

So the raw transport separation helps, but it does not solve the route-evidence and recovery-trigger problem by itself.

## Ordinary API Traffic Should Feed Route Availability

Current design direction now favors a simpler and stronger rule:

- any successful API call on a route is evidence that the route is currently usable at the network layer
- any network-layer API failure on a route is evidence that the route may currently be unusable
- if fresh in-band API evidence already exists for a route, the system should avoid redundant explicit probing for that same route

This makes route health less dependent on special-case probe code and more grounded in real traffic.

Under this rule:

- incremental sync is useful because it is frequent API traffic
- but it is not special because of business semantics
- it is useful because it produces route-health evidence naturally

So sync should not be elevated into a primary state-machine dimension.

## SSE Should Become a Suspicion Signal, Not an Availability Authority

Current behavior over-trusts SSE state.

The revised design direction is:

- SSE connect success is useful evidence that the route can currently establish transport
- SSE disconnect or stream failure is a suspicion event that the current route or transport may need reevaluation
- SSE failure does not by itself prove that the route is globally unavailable

This is a crucial simplification.

It means the machine no longer needs to let SSE define the entire app-level availability story.

Instead, SSE becomes one evidence source among several.

More specifically:

- `SSE event received` is a strong positive signal that the current route is alive enough to deliver traffic
- `SSE stream closed` or `SSE failure` is only a suspicion signal that should trigger reevaluation

## Resume and Network-Change Recovery Are Underdefined

The ideal standard requires foreground return and meaningful network change to be strong recovery triggers.

Current gaps:

- startup restore exists, but resume recovery is not clearly modeled as a first-class trigger
- network path changes are not clearly modeled as first-class recovery events
- the app appears able to remain stuck in a disconnected-looking state until restart

This is a strong sign that recovery triggers are too dependent on passive stream behavior rather than explicit event-driven reevaluation.

The revised design direction should make network-restored or route-restored events trigger both:

- immediate SSE reconnect attempt
- immediate catch-up incremental sync attempt

## User Retry Semantics Are Not Reliable Enough

Ideal standard requires manual retry to start a fresh attempt immediately.

Current gap:

- same-URL short-circuiting in `SyncSseClient.connect()` makes it possible for a reconnect request not to produce a truly fresh attempt

Even if this is partly an implementation bug, it is also evidence that retry semantics are not yet defined strongly enough at the mechanism level.

## Error Presentation Does Not Yet Match Offline-First Reliability Goals

The product goal is not just to reconnect eventually.

The product goal is to feel stable and reliable even across bad network conditions, including eventual offline-friendly behavior.

Current gap:

- network problems are still modeled too much like foreground failures
- state semantics are not rich enough to communicate degraded-but-recovering behavior cleanly
- the current system risks surfacing transient connectivity problems as hard failures

For an offline-capable product, connection behavior should look controlled and predictable, not noisy and brittle.

## Gap Summary Against Ideal Standard

### 1. Availability Priority

Ideal standard:

- become usable quickly, even if through gateway

Current gap:

- fallback can be triggered for the wrong reason
- route confidence is not clearly modeled

### 2. Event-Accelerated Recovery

Ideal standard:

- user retry, app resume, and network changes should interrupt waiting and trigger fresh reevaluation

Current gap:

- reconnect freshness is not guaranteed
- resume and network path changes are not clearly first-class triggers

### 3. Safe Route Switching

Ideal standard:

- switch back to direct only at safe boundaries

Current gap:

- current direct-check loop is too simple and not batch-boundary-aware

### 4. Distinct SSE and Route Semantics

Ideal standard:

- do not let business SSE health ambiguously stand in for total route health unless that contract is very clearly defined and trustworthy

Current gap:

- current SSE state is too weak to serve as a definitive availability model

### 5. Route Evidence Should Come From Real Traffic First

Ideal standard:

- route availability should primarily use recent network-layer evidence from ordinary API calls, with explicit probes only when needed

Current gap:

- the current design still leans too heavily on dedicated probe paths and SSE outcomes
- it does not yet formally treat ordinary API outcomes as the main route-health evidence source

### 6. Reliable Recovery UX

Ideal standard:

- the app should not require restart to recover from ordinary connectivity changes

Current gap:

- real behavior suggests the app can enter stuck states that do not self-heal correctly

## Provisional Recommendation

The next phase should not jump straight into implementation.

It should first decide, after one more focused investigation pass, between these two target models:

1. improved SSE-primary availability model
2. split model where SSE health and route health are modeled separately and then combined

Current evidence already rules out the status quo.

The current semantics are not sufficiently explicit, not sufficiently user-centered, and not sufficiently resilient for the intended offline-reliable experience.

## Questions To Resolve Before Implementation Planning

1. How should route-health evidence freshness be defined when using ordinary API results as the primary signal?
2. When there is no recent in-band traffic, what explicit probes are still required for direct and gateway?
3. What exact event sources should trigger immediate reevaluation: retry, resume, network change, route evidence update, or all of them?
4. What is the exact safe switching contract between route handoff and sync batch boundaries?
5. What UI state model should replace the current coarse status exposure so the app feels stable under poor connectivity?

## Success Criteria For This Analysis

This analysis is successful if it makes the following clear:

- which current symptoms are likely bugs versus deeper semantic design gaps
- why the current connection-state model is not sufficient
- which design decision still needs investigation before implementation planning
- why the final mechanism must be more explicit, more event-driven, and more user-availability-focused than the current one
