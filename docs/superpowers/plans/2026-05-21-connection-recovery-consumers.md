# Connection Recovery Consumers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect the new recovery architecture to app lifecycle, network events, incremental-sync catch-up behavior, and UI-facing connection semantics so recovery feels fast and stable to users.

**Architecture:** This plan wires foreground/background monitoring, network-availability events, manual retry handling, and consumer-facing state mapping into the previously added connection machine. It keeps incremental sync as a recovery consumer and evidence source, not as a primary machine dimension, and ensures the UI sees stable semantic states rather than raw transport churn.

**Tech Stack:** Kotlin, Coroutines, StateFlow, Android lifecycle/process APIs, ConnectivityManager callbacks, JUnit4, Robolectric, Google Truth, Gradle `--no-daemon`

---

## File Map

- Create: `android/app/src/main/java/com/openmate/app/connection/AppForegroundMonitor.kt`
  - Emits foreground/background signals for the connection machine.
- Create: `android/app/src/main/java/com/openmate/app/connection/NetworkChangeMonitor.kt`
  - Emits `NetworkAvailable`, `NetworkLost`, and `NetworkPathChanged` triggers.
- Modify: `android/app/src/main/java/com/openmate/app/OpenMateApp.kt`
  - Starts runtime monitoring at app startup.
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionModule.kt`
  - Provides lifecycle and network monitoring dependencies.
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
  - Subscribes to monitors, handles manual retry, and triggers catch-up sync on recovered connectivity.
- Modify: `android/core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt`
  - Expose or trigger catch-up sync on network-restored recovery paths.
- Modify: `android/core/data/src/main/java/com/openmate/core/data/repository/SseEventRepositoryImpl.kt`
  - Preserve stable dispatch behavior while machine handles consumer-facing semantics.
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
  - Consume stable recovery semantics.
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/WorkspaceListViewModel.kt`
  - Consume stable recovery semantics.
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionListViewModel.kt`
  - Consume stable recovery semantics.
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/WorkspaceAndSessionConnectionStatusTest.kt`
  - Verify user-facing semantics remain stable.
- Modify: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`
  - Verify lifecycle and network-driven recovery.

### Task 1: Add lifecycle and network monitors

**Files:**
- Create: `android/app/src/main/java/com/openmate/app/connection/AppForegroundMonitor.kt`
- Create: `android/app/src/main/java/com/openmate/app/connection/NetworkChangeMonitor.kt`
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionModule.kt`
- Test: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Write the failing test for foreground event driving immediate reevaluation**

```kotlin
@Test
fun appForegrounded_dispatchesImmediateRecoveryEvent() {
    val env = createEnvironment()

    env.emitAppForegrounded()

    assertThat(env.recordedEvents).contains(ConnectionEvent.AppForegrounded)
}
```

- [ ] **Step 2: Write the failing test for network available event driving immediate reevaluation**

```kotlin
@Test
fun networkAvailable_dispatchesImmediateRecoveryEvent() {
    val env = createEnvironment()

    env.emitNetworkAvailable()

    assertThat(env.recordedEvents).contains(ConnectionEvent.NetworkAvailable)
}
```

- [ ] **Step 3: Run the ConnectionManager test suite to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: FAIL because lifecycle and network monitors are not yet wired.

- [ ] **Step 4: Add the foreground monitor contract**

```kotlin
package com.openmate.app.connection

import kotlinx.coroutines.flow.StateFlow

interface AppForegroundMonitor {
    val isForeground: StateFlow<Boolean>
}
```

- [ ] **Step 5: Add the network monitor contract**

```kotlin
package com.openmate.app.connection

import kotlinx.coroutines.flow.Flow

sealed interface NetworkChangeEvent {
    data object Available : NetworkChangeEvent
    data object Lost : NetworkChangeEvent
    data object PathChanged : NetworkChangeEvent
}

interface NetworkChangeMonitor {
    val events: Flow<NetworkChangeEvent>
}
```

- [ ] **Step 6: Run the ConnectionManager test suite to verify it compiles further and still fails only at missing wiring**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: FAIL because the new monitors are not yet consumed.

- [ ] **Step 7: Commit the monitor contracts**

```bash
git add android/app/src/main/java/com/openmate/app/connection/AppForegroundMonitor.kt android/app/src/main/java/com/openmate/app/connection/NetworkChangeMonitor.kt android/app/src/main/java/com/openmate/app/ConnectionModule.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt
git commit -m "feat: add recovery consumer monitor contracts"
```

### Task 2: Wire lifecycle and network events into `ConnectionManager`

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Modify: `android/app/src/main/java/com/openmate/app/OpenMateApp.kt`
- Modify: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Write the failing test that `OpenMateApp` starts runtime monitoring**

```kotlin
@Test
fun openMateApp_startsRuntimeMonitoringOnCreate() {
    val app = OpenMateApp()
    val manager = FakeConnectionManager()
    app.connectionManager = manager

    app.onCreate()

    assertThat(manager.startRuntimeMonitoringCalled).isTrue()
}
```

- [ ] **Step 2: Run the app test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest.openMateApp_startsRuntimeMonitoringOnCreate" --no-daemon`
Expected: FAIL because `startRuntimeMonitoring()` does not exist yet.

- [ ] **Step 3: Add `startRuntimeMonitoring()` to `ConnectionManager` and subscribe to monitor flows**

```kotlin
fun startRuntimeMonitoring() {
    scope.launch {
        appForegroundMonitor.isForeground.collect { if (it) dispatch(ConnectionEvent.AppForegrounded) }
    }
    scope.launch {
        networkChangeMonitor.events.collect { event ->
            when (event) {
                NetworkChangeEvent.Available -> dispatch(ConnectionEvent.NetworkAvailable)
                NetworkChangeEvent.Lost -> dispatch(ConnectionEvent.NetworkLost)
                NetworkChangeEvent.PathChanged -> dispatch(ConnectionEvent.NetworkPathChanged)
            }
        }
    }
}
```

- [ ] **Step 4: Start runtime monitoring from `OpenMateApp`**

```kotlin
override fun onCreate() {
    super.onCreate()
    CrashHandler.install(this)
    connectionManager.restoreLastConnection()
    connectionManager.startRuntimeMonitoring()
}
```

- [ ] **Step 5: Run the app and ConnectionManager tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit lifecycle/network wiring**

```bash
git add android/app/src/main/java/com/openmate/app/ConnectionManager.kt android/app/src/main/java/com/openmate/app/OpenMateApp.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt
git commit -m "feat: wire recovery monitors into connection manager"
```

### Task 3: Trigger catch-up incremental sync on recovered connectivity

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Modify: `android/core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt`
- Modify: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Write the failing test for network-available after recovery triggering catch-up sync**

```kotlin
@Test
fun networkAvailable_afterRecovery_requestsCatchUpSync() {
    val env = createEnvironment()
    val profile = profile(name = "catchup", instanceId = "iid-catchup")

    env.manager.connect(profile)
    env.emitNetworkAvailable()

    assertThat(env.syncSseHandler.catchUpRequests).contains(profile.id)
}
```

- [ ] **Step 2: Run the ConnectionManager test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest.networkAvailable_afterRecovery_requestsCatchUpSync" --no-daemon`
Expected: FAIL because connectivity-restored catch-up sync does not exist yet.

- [ ] **Step 3: Add a catch-up sync entrypoint to the sync layer**

```kotlin
interface SyncRecoveryTrigger {
    fun requestCatchUpSync(sessionId: String? = null)
}
```

- [ ] **Step 4: Call catch-up sync when machine observes recovered availability for the active profile**

```kotlin
private fun onRecoveredConnectivity() {
    syncRecoveryTrigger.requestCatchUpSync()
}
```

- [ ] **Step 5: Run the ConnectionManager test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest.networkAvailable_afterRecovery_requestsCatchUpSync" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit the catch-up sync trigger**

```bash
git add android/app/src/main/java/com/openmate/app/ConnectionManager.kt android/core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt
git commit -m "feat: trigger catch-up sync on recovered connectivity"
```

### Task 4: Stabilize user-facing connection semantics

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/WorkspaceListViewModel.kt`
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionListViewModel.kt`
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/WorkspaceAndSessionConnectionStatusTest.kt`

- [ ] **Step 1: Write the failing UI test for gateway degraded still being usable**

```kotlin
@Test
fun workspaceStatus_gatewayConnected_representsUsableDegradedState() = runTest(dispatcher) {
    val fixture = createFixture(
        connectionRepository = FakeConnectionRepository(ConnectionStatus.GATEWAY_CONNECTED),
    )

    assertThat(fixture.workspaceListViewModel.connectionStatus.value).isEqualTo(ConnectionStatus.GATEWAY_CONNECTED)
}
```

- [ ] **Step 2: Write the failing UI test for recovery not collapsing to disconnected**

```kotlin
@Test
fun sessionStatus_connecting_representsRecoveryNotUserDisconnect() = runTest(dispatcher) {
    val fixture = createFixture(
        connectionRepository = FakeConnectionRepository(ConnectionStatus.CONNECTING),
    )

    assertThat(fixture.sessionListViewModel.connectionStatus.value).isEqualTo(ConnectionStatus.CONNECTING)
}
```

- [ ] **Step 3: Run the session status tests to verify they fail if semantics regressed**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests "com.openmate.feature.session.WorkspaceAndSessionConnectionStatusTest" --no-daemon`
Expected: FAIL if consumer mapping is inconsistent.

- [ ] **Step 4: Update ViewModels to rely on stable repository semantics rather than transport-specific assumptions**

```kotlin
val connectionStatus = connectionRepository.connectionStatus
```

- [ ] **Step 5: Run the session status tests to verify they pass**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests "com.openmate.feature.session.WorkspaceAndSessionConnectionStatusTest" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit the UI semantic stabilization**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt android/feature/session/src/main/java/com/openmate/feature/session/WorkspaceListViewModel.kt android/feature/session/src/main/java/com/openmate/feature/session/SessionListViewModel.kt android/feature/session/src/test/java/com/openmate/feature/session/WorkspaceAndSessionConnectionStatusTest.kt
git commit -m "refactor: stabilize recovery consumer ui semantics"
```

### Task 5: Final verification for consumer scope

**Files:**
- Modify: any files above if verification exposes gaps

- [ ] **Step 1: Search for UI or manager logic still treating transient recovery as hard disconnect**

Run: `rg "DISCONNECTED|ERROR|NetworkAvailable|NetworkLost|AppForegrounded|catchUpSync|startRuntimeMonitoring" android/app android/feature/session`
Expected: only intended semantic mappings and triggers remain.

- [ ] **Step 2: Run ConnectionManager tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: PASS

- [ ] **Step 3: Run session connection status tests**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests "com.openmate.feature.session.WorkspaceAndSessionConnectionStatusTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Verify repository state**

Run: `git status --short --branch`
Expected: only intended tracked modifications remain.

- [ ] **Step 5: Commit the final consumer pass**

```bash
git add android
git commit -m "feat: wire recovery consumers and lifecycle triggers"
```

## Self-Review

- Spec coverage:
  - foreground and network events as strong triggers -> Tasks 1 and 2
  - network-restored catch-up sync -> Task 3
  - user-facing stable semantics -> Task 4
  - no overemphasis on sync as primary machine dimension -> Task 3 only uses sync as recovery consumer
- Placeholder scan:
  - no `TODO` or `TBD` placeholders
  - each task includes concrete code or command content
- Type consistency:
  - uses `AppForegroundMonitor`, `NetworkChangeMonitor`, `NetworkChangeEvent`, and `SyncRecoveryTrigger` consistently
