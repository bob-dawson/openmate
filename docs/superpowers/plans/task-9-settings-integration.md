# Task 9: Settings + App Navigation + Integration

## Goal

Build the settings screen, wire up app-level navigation, and integrate all features into a working app.

## Package

- `com.openmate.feature.settings` — settings screen
- `com.openmate.app` — navigation + Application class

## Settings Screen

### SettingsScreen

Simple settings page for the connected server.

**Layout:**
- Top bar: "Settings" with back navigation
- Sections:
  - **Connection**: Current server name/address, disconnect button
  - **Sync**: Sync time range (7d/14d/30d/90d radio buttons), "Clear cached data" button
  - **About**: App version, opencode server version (from health check)
  - **Advanced**: Clear all data, reset app

**ViewModel: SettingsViewModel**
- `activeProfile: StateFlow<ServerProfile?>`
- `serverVersion: StateFlow<String?>`
- `syncTimeRange: StateFlow<SyncTimeRange>` — stored in DataStore
- `clearCache()` — deletes and recreates active DB
- `clearAllData()` — deletes all DBs + profiles
- `disconnect()` — stops SSE, clears active DB

## App Navigation

### NavHost structure

```
NavHost(startDestination = "instance_list") {
    composable("instance_list") { InstanceListScreen(...) }
    composable("add_instance?profileId={profileId}") { AddInstanceScreen(...) }
    composable("session_list") { SessionListScreen(...) }
    composable("session_detail/{sessionID}") { SessionDetailScreen(...) }
    composable("settings") { SettingsScreen(...) }
}
```

### Navigation from any screen to Settings
- SessionListScreen top bar has settings icon → `settings` route
- SessionDetailScreen overflow menu → settings

### Navigation flow
1. App opens → InstanceListScreen
2. User connects → SessionListScreen
3. User taps session → SessionDetailScreen
4. Back from SessionDetail → SessionList
5. Back from SessionList → InstanceList (disconnects SSE)

## App-Level Integration

### OpenMateApp (Hilt Application)

- `@HiltAndroidApp` Application class
- No special init needed (Hilt handles DI)

### MainActivity

- `@AndroidEntryPoint` Activity
- Sets content to `OpenMateApp()` composable
- `OpenMateApp()` sets up Theme + NavHost

### Connection Lifecycle Management

When navigating from InstanceList to SessionList:
1. `ActiveDatabaseProvider.setActive(profileId)` — opens the right DB
2. `SseEventRepository.connect(address, port, password)` — starts SSE
3. `SseSyncManager` runs initial data sync
4. Navigate to session list

When navigating back from SessionList to InstanceList:
1. `SseEventRepository.disconnect()` — stops SSE
2. `ActiveDatabaseProvider.clearActive()` — closes DB

### Notification Channel (optional for MVP)

If time permits, create a notification channel for when permissions/questions arrive while the app is in the background. Otherwise, these only appear when the user is actively viewing the session detail screen.

## Files

| File | Purpose |
|------|---------|
| `SettingsScreen.kt` | Settings UI |
| `SettingsViewModel.kt` | |
| `SettingsNavigation.kt` | Route definition |
| `SettingsModule.kt` | Hilt DI |
| `app/OpenMateApp.kt` | Hilt Application (update from Task 1) |
| `app/MainActivity.kt` | Main Activity with NavHost (update from Task 1) |
| `app/OpenMateNavHost.kt` | Navigation graph |
| `app/ConnectionManager.kt` | Manages connect/disconnect lifecycle |

## Verification

1. `./gradlew :feature:settings:test` passes
2. `./gradlew :app:test` passes
3. `./gradlew :app:connectedDebugAndroidTest` passes
4. Full end-to-end manual test:
   - Add instance → connect → see sessions
   - Open session → send message → see streaming response
   - Trigger permission request → approve/deny
   - Trigger question → answer/reject
   - Navigate to settings → clear cache
   - Disconnect → return to instance list
   - Reconnect → data loads from cache + sync
5. App survives configuration changes (rotation)
6. App handles server disconnect gracefully (shows status, auto-reconnects)
