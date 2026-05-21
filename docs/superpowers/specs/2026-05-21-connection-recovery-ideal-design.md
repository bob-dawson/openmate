# Connection Recovery Ideal Design

## Goal

Define the ideal connection management standard for Android connection startup, reconnect, fallback, recovery, and route switching.

This document defines what the mechanism should do for user experience and system behavior.

It intentionally does not describe the current implementation, migration steps, or file-level changes.

## Primary Principle

Connection management is successful when the app becomes usable again as quickly as possible after interruption, without corrupting in-flight sync or forcing the user to wait for arbitrary retry timers.

In short:

- availability is more important than path preference
- recovery should be accelerated by meaningful events
- route switching must not break in-flight sync work

## Scope

This standard covers:

- cold-start restore
- reconnect after unexpected disconnection
- manual user retry
- app resume recovery
- network change recovery
- direct to gateway fallback
- gateway back to direct recovery
- collaboration between SSE and incremental sync during switching

This standard does not define:

- the exact current implementation gaps
- whether SSE health alone is sufficient or whether route health must be modeled separately
- concrete class names, APIs, or code structure
- step-by-step implementation planning

## User Experience Objectives

The ideal mechanism should optimize for these user-visible outcomes:

1. If the user opens the app while a profile is expected to stay connected, the app should quickly attempt to restore usable connectivity.
2. If the user taps retry, the app should immediately start a new recovery attempt instead of waiting for an existing backoff window.
3. If the network changes in a way that likely invalidates the existing route, the app should react quickly rather than waiting for a long passive timeout.
4. If direct is temporarily unavailable but gateway is available, the app should prefer becoming usable through gateway quickly rather than staying unavailable while insisting on direct.
5. If direct becomes healthy again while gateway is serving traffic, the app should return to direct only at a safe switching boundary.
6. Path optimization must never make the app feel less available than before the optimization attempt.

## Desired Connection Model

The app always has a desired connection target:

- no profile is expected to remain connected
- one specific profile should remain connected

The connection system should continuously try to satisfy that desired target.

Unexpected transport interruption does not automatically mean the desired state has changed.

This distinction is critical:

- desired connection target answers what the app is trying to maintain
- observed runtime state answers how well the system is currently satisfying that target

## Runtime Behavior Model

The ideal runtime should expose user-meaningful states rather than raw transport details.

At minimum the system should be able to distinguish these situations:

- disconnected by user intent
- connecting through direct
- connected through direct
- connecting through gateway
- connected through gateway
- recovering automatically
- degraded but usable through gateway
- waiting for user repair
- temporarily failed but retryable

The mechanism must not collapse all of these into a single generic error state.

## Recovery Triggers

Recovery should be event-prioritized, not timer-prioritized.

Timer-based retry is only the lowest-priority fallback when no stronger signal is available.

The ideal mechanism should react immediately to these recovery triggers:

- user taps retry
- app returns to foreground
- network becomes available after being unavailable
- network path changes in a way that may invalidate the current route
- current preferred route becomes reachable again
- fallback route becomes available

The priority rule is:

- explicit user action should interrupt waiting and trigger immediate reevaluation
- foreground return should interrupt waiting and trigger immediate reevaluation
- network/path changes should interrupt waiting and trigger immediate reevaluation
- passive backoff should apply only when none of the above occurs

## Foreground and Background Strategy

The ideal mechanism should not treat foreground and background identically.

Foreground behavior:

- recover aggressively
- reevaluate quickly on resume
- react quickly to network changes
- allow user-triggered retry to start immediately

Background behavior:

- recover conservatively
- avoid unnecessary repeated reconnect loops
- defer aggressive reevaluation until the app becomes foreground again unless a strong system signal arrives

This keeps the app responsive when the user is looking at it while avoiding wasteful churn when they are not.

## Manual Retry Semantics

The user must have a reliable way to accelerate recovery.

Manual retry must:

- cancel passive waiting
- create a fresh recovery attempt even if the route URL is unchanged
- re-run route evaluation instead of reusing stale assumptions

Manual retry must never be ignored solely because the app appears to already be trying to reconnect.

If the user asks the app to retry, the system should treat that as a new attempt generation.

## Route Policy

The ideal system supports two route classes:

- direct
- gateway

The route preference policy is:

- prefer direct when it is healthy enough to serve traffic reliably
- if direct is not sufficiently available and gateway is available, degrade to gateway quickly
- once degraded to gateway, keep the app usable first and optimize route second
- switch back to direct only after direct is healthy enough and the switch can be performed safely

This means direct is preferred, but availability wins over preference.

## Fallback Standard

The ideal direct to gateway fallback behavior is:

- do not stay unavailable for a long period just because direct is preferred
- if gateway can make the app usable sooner, use gateway
- once gateway is active, the system may continue probing or reevaluating direct in the background

Fallback should feel like degraded service, not like service loss.

## Return-To-Direct Standard

When gateway is currently serving traffic and direct becomes healthy again, switching back to direct should happen at a safe boundary.

The ideal switching rule is:

- direct recovery may be detected eagerly
- actual route switch should happen between sync batches, not in the middle of a batch
- route optimization should not interrupt an in-flight batch that is already expected to complete quickly

This allows the system to return to the better path without turning a working session into a broken one.

## Safe Switching Boundary

The ideal mechanism should treat route switching as a coordinated handoff.

Safe switch requirements:

- stop treating the old route as the future source of truth once the new route takes over
- avoid interrupting an in-flight sync batch that is close to completion
- perform the route ownership change only at a batch boundary
- after switching, let the new route trigger any needed catch-up work

The core requirement is not "never switch during activity".

The real requirement is "switch only at a boundary where correctness and user-visible continuity are preserved".

## SSE and Sync Collaboration

The ideal mechanism must account for the fact that SSE and incremental sync are related but not identical.

Their responsibilities should remain conceptually distinct:

- SSE provides event-driven awareness and fast reaction signals
- incremental sync provides convergence toward the source of truth

The connection mechanism must not assume that route switching and sync execution are the same operation.

The ideal collaboration rule is:

- route switching may change where new requests and new event listening should go
- an already-running sync batch should be allowed to finish if it is still valid and near completion
- after the switch, the new route should own future event intake and future sync triggering
- if any event gap may have occurred around the boundary, the new route should trigger catch-up sync rather than relying on old SSE continuity

## Network Change Handling

The ideal mechanism should treat meaningful network path changes as strong recovery signals.

Examples include:

- Wi-Fi disconnecting while mobile data becomes active
- mobile data becoming available after full loss of connectivity
- the active path changing in a way that may invalidate the current socket or route assumptions

The system should not rely only on long passive read timeouts to discover that the current route is stale.

Instead, network change should trigger immediate reevaluation of route usability.

## App Resume Handling

Returning to foreground should be treated as a strong demand for fast usability.

If a profile is still expected to remain connected, app resume should trigger immediate reevaluation of connection usability.

This reevaluation should not be blocked behind a previously scheduled retry delay.

## Repair Boundary

User-repairable problems such as missing credentials or invalid authorization should be treated differently from normal transport instability.

The ideal behavior is:

- clearly surface that user action is required
- pause normal automatic recovery attempts that cannot succeed until repair happens
- resume through the same standard recovery mechanism once repair is complete

Repair-required state should not be confused with transient network failure.

## UI Status Semantics

The UI should reflect whether the system is usable, recovering, degraded, or blocked on user action.

At a semantic level, the user should be able to tell:

- whether the app is currently usable
- whether it is currently trying to recover automatically
- whether it is connected through a degraded path
- whether manual action is required

The UI should not imply total failure while the system is still actively recovering.

## Service-Level Expectations

The ideal mechanism should satisfy these expectations:

- manual retry starts a fresh recovery attempt immediately
- app resume triggers immediate recovery reevaluation when a connected profile is expected
- meaningful network restoration or route change triggers immediate reevaluation
- fallback to gateway happens quickly enough to reduce user-visible downtime when direct is unavailable
- switching from gateway back to direct does not break in-flight sync correctness
- route optimization never makes the app less available than staying on the current working route

These expectations are user-experience constraints, not optional implementation details.

## Open Decision Left For Current-State Analysis

This ideal standard intentionally leaves one question open for the next document:

- whether connection usability should continue to be primarily represented by SSE state, with improved liveness semantics
- or whether SSE health and route health should be modeled separately and then combined

That decision depends on the real behavior and limitations of the current implementation.

## Success Criteria

This ideal design is satisfied when all of the following are true:

- the app restores usable connectivity quickly after interruption
- users can always accelerate recovery through explicit retry
- foreground return and meaningful network changes cause prompt reevaluation
- gateway fallback minimizes downtime when direct is unavailable
- return to direct happens only at safe switching boundaries
- route switching does not corrupt or destabilize in-flight sync
- UI semantics reflect recovery, degradation, and repair states clearly
