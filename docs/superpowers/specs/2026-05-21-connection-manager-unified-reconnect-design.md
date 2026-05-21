# ConnectionManager Unified Reconnect Design

## Summary

This change makes `ConnectionManager` the only owner of Android connection lifecycle decisions.

Today, connection behavior is split across `ConnectionManager` and `InstanceListViewModel.autoReconnect()`. That creates duplicate `connect(profile)` calls, overlapping disconnect/connect cycles inside `SseClient`, and status races where UI can briefly show gateway-connected and then flip to disconnected even though HTTP requests still work.

The new design removes page-level auto reconnect and moves startup restore, profile switching, and reconnect ownership into `ConnectionManager`.

## Goal

- Keep exactly one active profile connection at a time.
- Let `ConnectionManager` own startup restore, reconnect, gateway fallback, and direct recovery.
- Ensure ViewModels only request connection changes and consume published state.
- Eliminate duplicate connect attempts caused by screen lifecycle.

## Non-Goals

- No change to bridge, gateway, or opencode server SSE protocol.
- No new background service or foreground service behavior.
- No attempt to keep multiple profiles connected simultaneously.
- No new persistence schema; existing `ServerProfile.lastConnectedAt` remains the source for “most recent profile”.

## Current Problems

### Duplicate connection owners

- `InstanceListViewModel` runs `autoReconnect()` on init.
- User actions can also call `connectionManager.connect(profile)`.
- `ConnectionManager` separately reacts to SSE state and can trigger fallback flows.

This means connection lifecycle is controlled by both presentation and application layers.

### Status races

Repeated `connect(profile)` calls can:

- trigger `disconnect()` inside lower networking layers,
- start a new connection attempt before the previous one fully stops,
- let an old attempt publish `ERROR` or `DISCONNECTED` after a newer attempt already became healthy.

### Startup restore tied to screen lifecycle

Automatic restore currently depends on the instance list screen being created, rather than application-level connection policy.

## Target Design

### Single owner

`ConnectionManager` becomes the only component allowed to decide:

- when to restore the latest profile,
- when to disconnect the previous profile,
- when to start a new connection attempt,
- when to retry or fallback between direct and gateway modes.

ViewModels are limited to:

- requesting `connect(profile)` for explicit user selection,
- requesting `disconnect()` where appropriate,
- observing `connectionStatus`, `activeProfile`, and related published state.

### Startup restore

Application startup will call a new `ConnectionManager.restoreLastConnection()` entrypoint.

Behavior:

1. Load all profiles from `ServerProfileRepository`.
2. Select the profile with the latest non-null `lastConnectedAt`.
3. If no profile qualifies, do nothing.
4. If a profile is found, call the same internal connection path used for explicit user connection.

This restores the most recently connected instance without relying on any screen to be opened first.

### One active profile at a time

When `connect(profile)` is called:

1. If the requested profile is already the active profile and a compatible connection attempt is already in progress or established, ignore the duplicate request.
2. If the requested profile differs from the current active profile, fully tear down the previous profile connection first.
3. Switch active database and request context to the new profile.
4. Start exactly one new SSE/sync connection attempt for the new profile.

The app never keeps two profiles alive concurrently.

### Serialized connection attempts

`ConnectionManager` will track connection attempts so stale attempts cannot overwrite the status of a newer one.

Required behavior:

- Each new connect request invalidates the previous attempt.
- Disconnect/cleanup for the previous attempt happens before new transport startup.
- Status updates from old attempts are ignored once a newer attempt becomes current.

This does not require changing the public repository contract. The guard can live entirely inside `ConnectionManager`.

### Existing fallback logic stays centralized

Current behavior remains conceptually the same:

- prefer gateway when `instanceId` is present,
- use direct mode when available and appropriate,
- fallback to gateway after direct SSE failure,
- periodically probe direct reachability and switch back.

The difference is that all of this remains under one owner and is no longer raced by ViewModel-level reconnect behavior.

## Responsibilities by File

### `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`

Add or change responsibilities:

- own application-level restore entrypoint,
- deduplicate repeated `connect(profile)` requests,
- serialize profile switching,
- ensure old connection attempts cannot publish stale state,
- keep `activeProfile`, `connectionStatus`, `isConnected`, and DB activation as the single source of truth.

### `android/app/src/main/java/com/openmate/app/OpenMateApp.kt`

Add application startup hook that triggers `ConnectionManager.restoreLastConnection()`.

This is the earliest reasonable owner that fits current app structure and does not depend on Compose navigation.

### `android/feature/instance/src/main/java/com/openmate/feature/instance/InstanceListViewModel.kt`

Remove `autoReconnect()`.

Keep responsibilities limited to:

- observe profiles + current connection status,
- map active profile state for the list,
- forward user-initiated `connect(profile)` requests,
- handle deletion.

## Data Flow

### Startup path

1. `OpenMateApp.onCreate()` runs.
2. `ConnectionManager.restoreLastConnection()` selects the latest profile by `lastConnectedAt`.
3. `ConnectionManager` activates DB/client context and starts the single connection attempt.
4. UI screens later subscribe to already-owned state.

### Explicit profile switch path

1. User taps a profile in instance list.
2. ViewModel calls `connectionManager.connect(profile)`.
3. `ConnectionManager` compares with current active profile.
4. If different, previous profile transport is torn down first.
5. New profile becomes active and publishes connection progress.

## Error Handling

- If restore fails because no profile exists or all `lastConnectedAt` values are null, startup does nothing.
- If restore picks a profile but connection fails, `ConnectionManager` publishes the normal error state.
- If a duplicate same-profile connect request arrives while already connecting or connected, it is ignored rather than forcing a disconnect/reconnect cycle.
- If a new profile is chosen during an older attempt, stale updates from the older attempt must not override the new profile state.

## Testing Strategy

### ConnectionManager coverage

Add tests for:

- restore picks the profile with highest `lastConnectedAt`,
- restore does nothing when there is no previous profile,
- duplicate same-profile connect does not trigger a second teardown/reconnect cycle,
- switching from profile A to profile B disconnects A before starting B,
- stale status from an older attempt does not overwrite the latest active attempt.

### ViewModel coverage

Add tests for:

- `InstanceListViewModel` no longer auto-connects during init,
- explicit connect still forwards the selected profile,
- list item status still follows `ConnectionRepository.connectionStatus` for the active profile.

## Risks

### Application startup timing

Running restore from `Application.onCreate()` means connection setup begins before any screen appears. That is intended, but tests must confirm there is no crash from early dependency usage.

### Existing test coverage gaps

There is no dedicated `ConnectionManager` test file today, so part of this change may require introducing focused tests around connection orchestration.

### Persisted “latest profile” semantics

Using `lastConnectedAt` means the latest successful connection remains the restore target. This matches current persisted data and avoids adding a new preference just for active profile restore.

## Acceptance Criteria

- Opening the instance list no longer triggers its own reconnect logic.
- App startup restores the most recently connected profile through `ConnectionManager`.
- Selecting a different profile disconnects the old profile and starts exactly one connection for the new profile.
- UI connection state is driven by `ConnectionManager` only.
- Repeated same-profile connect requests do not cause a disconnect/reconnect loop.
- The observed “briefly connected, then disconnected while API still works” race is removed or reduced to a reproducible lower-layer issue.
