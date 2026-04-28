# Task 7: Feature Instance (Connection Management)

## Goal

Build the instance management feature: add/edit/delete server profiles, connect to a server, display connection status. This is the entry point of the app.

## Package

`com.openmate.feature.instance`

## Screens

### InstanceListScreen

The home screen. Shows saved server profiles with connection status.

**Layout:**
- Top bar: "OpenMate" title, + button to add instance
- List of profile cards, each showing:
  - Profile name (e.g., "Work Desktop")
  - Address:port
  - Connection status indicator (green dot = connected, gray = disconnected, yellow = connecting, red = error)
  - Last connected time
- Tap a card → connect and navigate to session list
- Long press → edit/delete options
- Empty state: "Add your first opencode instance" with + button

**ViewModel: InstanceListViewModel**
- `profiles: StateFlow<List<ServerProfileWithStatus>>` — profiles + their connection status
- `connect(profile: ServerProfile)` — triggers SSE connection + initial sync
- `deleteProfile(id: String)` — removes profile + its database
- `addProfile()` / `editProfile(id: String)` — navigation events

### AddInstanceScreen

Form to add or edit a server connection profile.

**Layout:**
- Name field (e.g., "Work Desktop")
- Address field (e.g., "100.64.0.1" or "my-machine.tail-xxxxx.ts.net")
- Port field (default: 4096)
- Password field (optional, toggle visibility)
- "Test Connection" button — calls health check endpoint
- "Save" button — validates and saves profile

**Validation:**
- Name: non-empty
- Address: non-empty, valid hostname or IP
- Port: 1-65535

**ViewModel: AddInstanceViewModel**
- Form state fields
- `testConnection()` — calls health check, shows result
- `save()` — validates + saves via `ServerProfileRepository`

## Navigation

From this module:
- `InstanceListScreen` → `SessionListScreen` (in feature/session) on successful connect
- `InstanceListScreen` → `AddInstanceScreen` for add
- `InstanceListScreen` → `AddInstanceScreen(profileId)` for edit

## Connection Lifecycle

When user taps a profile to connect:
1. Set active profile in `ActiveDatabaseProvider`
2. Call `SseEventRepository.connect(address, port, password)`
3. `SseSyncManager` runs initial sync
4. On success → navigate to session list
5. On failure → show error, stay on instance list

When user navigates back to instance list from session list:
- SSE connection stays alive in background
- Status indicator updates on instance list

## Hilt

- `InstanceModule` providing ViewModels

## Files

| File | Purpose |
|------|---------|
| `InstanceListScreen.kt` | Instance list UI |
| `InstanceListViewModel.kt` | |
| `AddInstanceScreen.kt` | Add/edit form UI |
| `AddInstanceViewModel.kt` | |
| `InstanceNavigation.kt` | NavGraph routes for this feature |
| `InstanceModule.kt` | Hilt DI |

## Verification

1. `./gradlew :feature:instance:test` passes
2. ViewModel unit tests with fake repositories
3. Test: add profile → appears in list
4. Test: delete profile → removed from list + DB deleted
5. Test: test connection success/failure
6. Test: connect → sets active DB + starts SSE
7. Compose UI tests for list rendering, form validation
