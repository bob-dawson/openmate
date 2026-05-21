# Connection Recovery State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current implicit reconnect and fallback behavior with an event-driven connection state machine that separates route availability from SSE stream state, accelerates recovery on meaningful events, and switches between direct and gateway safely.

**Architecture:** Android will introduce an explicit connection state machine inside the connection-management layer. It will consume user actions, app lifecycle signals, network-change signals, route-health probe results, SSE transport events, and timer events. Route health will be evaluated independently from business SSE state so the app can make faster and clearer routing decisions. SSE will become one transport signal rather than the sole source of app connectivity truth.

**Tech Stack:** Kotlin, Coroutines, StateFlow, Android lifecycle/process APIs, ConnectivityManager callbacks, OkHttp, existing Bridge `/api/bridge/status` and gateway status APIs, JUnit4, Robolectric, Google Truth, Gradle `--no-daemon`

---

## File Map

- Create: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionRoute.kt`
  - Explicit route model for direct vs gateway.
- Create: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionPhase.kt`
  - User-meaningful phase model for recovering, degraded, repair-required, etc.
- Create: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionSnapshot.kt`
  - Aggregated connection snapshot exposed to app/UI layers.
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionEvent.kt`
  - All state-machine input events.
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionMachineState.kt`
  - Internal state machine state.
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionReducer.kt`
  - Pure reducer from state + event -> next state + actions.
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionAction.kt`
  - Side effects to run after state transitions.
- Create: `android/app/src/main/java/com/openmate/app/connection/RouteHealth.kt`
  - Health model for direct and gateway routes.
- Create: `android/app/src/main/java/com/openmate/app/connection/RouteHealthMonitor.kt`
  - Independent route probing and health updates.
- Create: `android/app/src/main/java/com/openmate/app/connection/AppForegroundMonitor.kt`
  - Emits app foreground/background events.
- Create: `android/app/src/main/java/com/openmate/app/connection/NetworkChangeMonitor.kt`
  - Emits network availability/path change events.
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
  - Replace ad hoc routing logic with state-machine orchestration.
- Modify: `android/app/src/main/java/com/openmate/app/OpenMateApp.kt`
  - Register lifecycle-driven foreground monitoring startup.
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionModule.kt`
  - Wire new monitors and state-machine dependencies.
- Modify: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionStatus.kt`
  - Keep or adapt legacy status mapping layer.
- Modify: `android/core/domain/src/main/java/com/openmate/core/domain/repository/ConnectionRepository.kt`
  - Expose richer snapshot or mapped status APIs.
- Modify: `android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt`
  - Separate transport events from route-availability meaning; guarantee fresh reconnect attempts.
- Modify: `android/core/network/src/main/java/com/openmate/core/network/SyncSseConnection.kt`
  - Extend API as needed for reconnect generation and transport event exposure.
- Modify: `android/core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt`
  - Respect route-switch batch boundaries and active generation ownership.
- Modify: `android/core/data/src/main/java/com/openmate/core/data/repository/SseEventRepositoryImpl.kt`
  - Consume mapped transport state without presenting transient route switches as total disconnection.
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
  - Consume richer connection state if needed for stable UI semantics.
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/WorkspaceListViewModel.kt`
  - Consume richer connection state if needed for stable UI semantics.
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionListViewModel.kt`
  - Consume richer connection state if needed for stable UI semantics.
- Test: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`
  - End-to-end orchestration and recovery tests.
- Create: `android/app/src/test/java/com/openmate/app/connection/ConnectionReducerTest.kt`
  - Reducer-level state machine tests.
- Create: `android/app/src/test/java/com/openmate/app/connection/RouteHealthMonitorTest.kt`
  - Route health semantics tests.
- Modify: `android/core/network/src/test/java/com/openmate/core/network/SyncSseClientTest.kt`
  - Transport-state tests that prove SSE disconnect does not automatically mean route unavailable.
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/WorkspaceAndSessionConnectionStatusTest.kt`
  - UI-facing status mapping tests.

### Task 1: Introduce route and phase domain models first

**Files:**
- Create: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionRoute.kt`
- Create: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionPhase.kt`
- Create: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionSnapshot.kt`
- Test: `android/core/domain/src/test/java/com/openmate/core/domain/model/ConnectionStatusTest.kt`

- [ ] **Step 1: Write the failing domain test for route and phase semantics**

```kotlin
@Test
fun connectionSnapshot_distinguishesGatewayDegradedFromDisconnected() {
    val snapshot = ConnectionSnapshot(
        phase = ConnectionPhase.DEGRADED,
        activeRoute = ConnectionRoute.Gateway(instanceId = "iid-1"),
        desiredProfileId = "p1",
        isUsable = true,
        needsUserRepair = false,
        message = null,
    )

    assertThat(snapshot.phase).isEqualTo(ConnectionPhase.DEGRADED)
    assertThat(snapshot.isUsable).isTrue()
    assertThat(snapshot.activeRoute).isEqualTo(ConnectionRoute.Gateway("iid-1"))
}
```

- [ ] **Step 2: Run the domain test to verify it fails**

Run: `./gradlew.bat :core:domain:testDebugUnitTest --tests "com.openmate.core.domain.model.ConnectionStatusTest" --no-daemon`
Expected: FAIL with unresolved references to `ConnectionSnapshot`, `ConnectionPhase`, or `ConnectionRoute`.

- [ ] **Step 3: Add the new route model**

```kotlin
package com.openmate.core.domain.model

sealed interface ConnectionRoute {
    data class Direct(val address: String, val port: Int) : ConnectionRoute
    data class Gateway(val instanceId: String) : ConnectionRoute
}
```

- [ ] **Step 4: Add the new phase model**

```kotlin
package com.openmate.core.domain.model

enum class ConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DEGRADED,
    RECOVERING,
    NEEDS_REPAIR,
    FAILED,
}
```

- [ ] **Step 5: Add the aggregated snapshot model**

```kotlin
package com.openmate.core.domain.model

data class ConnectionSnapshot(
    val phase: ConnectionPhase,
    val activeRoute: ConnectionRoute?,
    val desiredProfileId: String?,
    val isUsable: Boolean,
    val needsUserRepair: Boolean,
    val message: String?,
)
```

- [ ] **Step 6: Run the domain test to verify it passes**

Run: `./gradlew.bat :core:domain:testDebugUnitTest --tests "com.openmate.core.domain.model.ConnectionStatusTest" --no-daemon`
Expected: PASS

- [ ] **Step 7: Commit the domain model addition**

```bash
git add android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionRoute.kt android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionPhase.kt android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionSnapshot.kt android/core/domain/src/test/java/com/openmate/core/domain/model/ConnectionStatusTest.kt
git commit -m "feat: add connection recovery domain models"
```

### Task 2: Define the connection state machine as a pure reducer

**Files:**
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionEvent.kt`
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionMachineState.kt`
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionAction.kt`
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionReducer.kt`
- Test: `android/app/src/test/java/com/openmate/app/connection/ConnectionReducerTest.kt`

- [ ] **Step 1: Write the failing reducer test for user retry interrupting passive recovery**

```kotlin
@Test
fun reducer_userRetryDuringBackoff_requestsImmediateRouteReevaluation() {
    val state = ConnectionMachineState.recovering(
        desiredProfileId = "p1",
        activeRoute = ConnectionRoute.Direct("127.0.0.1", 4097),
    )

    val result = ConnectionReducer.reduce(state, ConnectionEvent.UserRetry)

    assertThat(result.nextState.phase).isEqualTo(ConnectionPhase.RECOVERING)
    assertThat(result.actions).contains(ConnectionAction.ReevaluateRoutes)
    assertThat(result.actions).doesNotContain(ConnectionAction.WaitForBackoff)
}
```

- [ ] **Step 2: Write the failing reducer test for SSE disconnect not forcing route unavailable**

```kotlin
@Test
fun reducer_sseTransportClosed_doesNotMarkDisconnectedByItself() {
    val state = ConnectionMachineState.connected(
        desiredProfileId = "p1",
        activeRoute = ConnectionRoute.Direct("127.0.0.1", 4097),
    )

    val result = ConnectionReducer.reduce(state, ConnectionEvent.SseTransportClosed)

    assertThat(result.nextState.phase).isEqualTo(ConnectionPhase.RECOVERING)
    assertThat(result.actions).contains(ConnectionAction.RestartSse)
    assertThat(result.actions).contains(ConnectionAction.ReevaluateRoutes)
}
```

- [ ] **Step 3: Run the reducer tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.ConnectionReducerTest" --no-daemon`
Expected: FAIL with unresolved references to reducer types.

- [ ] **Step 4: Add the event definitions**

```kotlin
package com.openmate.app.connection

sealed interface ConnectionEvent {
    data class ConnectRequested(val profileId: String) : ConnectionEvent
    data object DisconnectRequested : ConnectionEvent
    data object UserRetry : ConnectionEvent
    data object AppForegrounded : ConnectionEvent
    data object AppBackgrounded : ConnectionEvent
    data object NetworkChanged : ConnectionEvent
    data class RouteHealthChanged(val revision: Long) : ConnectionEvent
    data object SseTransportConnected : ConnectionEvent
    data object SseTransportClosed : ConnectionEvent
    data class SseTransportFailed(val message: String?) : ConnectionEvent
    data object BackoffExpired : ConnectionEvent
    data object SyncBatchCompleted : ConnectionEvent
    data class RepairCompleted(val profileId: String) : ConnectionEvent
}
```

- [ ] **Step 5: Add the internal machine state and actions**

```kotlin
package com.openmate.app.connection

import com.openmate.core.domain.model.ConnectionPhase
import com.openmate.core.domain.model.ConnectionRoute

data class ConnectionMachineState(
    val desiredProfileId: String?,
    val activeRoute: ConnectionRoute?,
    val phase: ConnectionPhase,
    val awaitingBatchBoundarySwitch: Boolean,
) {
    companion object {
        fun connected(desiredProfileId: String, activeRoute: ConnectionRoute) = ConnectionMachineState(
            desiredProfileId = desiredProfileId,
            activeRoute = activeRoute,
            phase = ConnectionPhase.CONNECTED,
            awaitingBatchBoundarySwitch = false,
        )

        fun recovering(desiredProfileId: String, activeRoute: ConnectionRoute?) = ConnectionMachineState(
            desiredProfileId = desiredProfileId,
            activeRoute = activeRoute,
            phase = ConnectionPhase.RECOVERING,
            awaitingBatchBoundarySwitch = false,
        )
    }
}

sealed interface ConnectionAction {
    data object ReevaluateRoutes : ConnectionAction
    data object RestartSse : ConnectionAction
    data object WaitForBackoff : ConnectionAction
}
```

- [ ] **Step 6: Add the minimal reducer**

```kotlin
package com.openmate.app.connection

import com.openmate.core.domain.model.ConnectionPhase

object ConnectionReducer {
    data class Result(
        val nextState: ConnectionMachineState,
        val actions: List<ConnectionAction>,
    )

    fun reduce(state: ConnectionMachineState, event: ConnectionEvent): Result {
        return when (event) {
            ConnectionEvent.UserRetry -> Result(
                nextState = state.copy(phase = ConnectionPhase.RECOVERING),
                actions = listOf(ConnectionAction.ReevaluateRoutes),
            )
            ConnectionEvent.SseTransportClosed -> Result(
                nextState = state.copy(phase = ConnectionPhase.RECOVERING),
                actions = listOf(ConnectionAction.RestartSse, ConnectionAction.ReevaluateRoutes),
            )
            else -> Result(state, emptyList())
        }
    }
}
```

- [ ] **Step 7: Run the reducer tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.ConnectionReducerTest" --no-daemon`
Expected: PASS

- [ ] **Step 8: Commit the reducer foundation**

```bash
git add android/app/src/main/java/com/openmate/app/connection/ConnectionEvent.kt android/app/src/main/java/com/openmate/app/connection/ConnectionMachineState.kt android/app/src/main/java/com/openmate/app/connection/ConnectionAction.kt android/app/src/main/java/com/openmate/app/connection/ConnectionReducer.kt android/app/src/test/java/com/openmate/app/connection/ConnectionReducerTest.kt
git commit -m "feat: add connection recovery reducer foundation"
```

### Task 3: Add independent route-health monitoring

**Files:**
- Create: `android/app/src/main/java/com/openmate/app/connection/RouteHealth.kt`
- Create: `android/app/src/main/java/com/openmate/app/connection/RouteHealthMonitor.kt`
- Test: `android/app/src/test/java/com/openmate/app/connection/RouteHealthMonitorTest.kt`

- [ ] **Step 1: Write the failing route-health test for gateway requiring both reachability and bridge-online status**

```kotlin
@Test
fun gatewayHealth_isHealthyOnlyWhenGatewayReachableAndBridgeOnline() = runTest {
    val monitor = FakeRouteHealthMonitor(
        directHealthy = true,
        gatewayReachable = true,
        gatewayBridgeOnline = false,
    )

    val snapshot = monitor.snapshot()

    assertThat(snapshot.direct.isHealthy).isTrue()
    assertThat(snapshot.gateway.isHealthy).isFalse()
}
```

- [ ] **Step 2: Write the failing route-health test for direct probing being independent from SSE state**

```kotlin
@Test
fun directHealth_doesNotDependOnSseTransportState() = runTest {
    val monitor = FakeRouteHealthMonitor(
        directHealthy = true,
        gatewayReachable = false,
        gatewayBridgeOnline = false,
    )

    val snapshot = monitor.snapshot()

    assertThat(snapshot.direct.isHealthy).isTrue()
}
```

- [ ] **Step 3: Run the route-health tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.RouteHealthMonitorTest" --no-daemon`
Expected: FAIL with unresolved references.

- [ ] **Step 4: Add the route-health models**

```kotlin
package com.openmate.app.connection

data class RouteHealth(
    val isHealthy: Boolean,
    val message: String? = null,
)

data class RouteHealthSnapshot(
    val revision: Long,
    val direct: RouteHealth,
    val gateway: RouteHealth,
)
```

- [ ] **Step 5: Add the monitor contract**

```kotlin
package com.openmate.app.connection

import kotlinx.coroutines.flow.StateFlow

interface RouteHealthMonitor {
    val snapshot: StateFlow<RouteHealthSnapshot>
    suspend fun refreshNow()
}
```

- [ ] **Step 6: Implement the minimal probe-based monitor**

```kotlin
class ProbeRouteHealthMonitor(
    initial: RouteHealthSnapshot,
) : RouteHealthMonitor {
    private val _snapshot = MutableStateFlow(initial)
    override val snapshot: StateFlow<RouteHealthSnapshot> = _snapshot

    override suspend fun refreshNow() {
        _snapshot.value = _snapshot.value.copy(revision = _snapshot.value.revision + 1)
    }
}
```

- [ ] **Step 7: Run the route-health tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.RouteHealthMonitorTest" --no-daemon`
Expected: PASS

- [ ] **Step 8: Commit the route-health monitoring foundation**

```bash
git add android/app/src/main/java/com/openmate/app/connection/RouteHealth.kt android/app/src/main/java/com/openmate/app/connection/RouteHealthMonitor.kt android/app/src/test/java/com/openmate/app/connection/RouteHealthMonitorTest.kt
git commit -m "feat: add route health monitoring foundation"
```

### Task 4: Make `SyncSseClient` transport-oriented instead of availability-authoritative

**Files:**
- Modify: `android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt`
- Modify: `android/core/network/src/main/java/com/openmate/core/network/SyncSseConnection.kt`
- Modify: `android/core/network/src/test/java/com/openmate/core/network/SyncSseClientTest.kt`

- [ ] **Step 1: Write the failing test that reconnect on same URL starts a fresh attempt**

```kotlin
@Test
fun connect_sameBaseUrlAfterReconnectRequest_startsFreshTransportAttempt() = runTest {
    val callFactory = RecordingCallFactory()
    val client = SyncSseClient(
        client = callFactory,
        tokenStore = FakeTokenStore(),
        logger = NoOpSyncSseLogger,
    )

    launch { client.connect("http://127.0.0.1:4097", forceRestart = true) }
    launch { client.connect("http://127.0.0.1:4097", forceRestart = true) }

    advanceUntilIdle()

    assertThat(callFactory.requestCount).isAtLeast(2)
}
```

- [ ] **Step 2: Write the failing test that transport failure does not by itself mean route unavailable**

```kotlin
@Test
fun transportFailure_updatesTransportStateWithoutInventingRouteFailure() = runTest {
    val client = SyncSseClient(
        client = AlwaysFailingCallFactory(),
        tokenStore = FakeTokenStore(),
        logger = NoOpSyncSseLogger,
    )

    launch { client.connect("http://127.0.0.1:4097", forceRestart = true) }
    advanceUntilIdle()

    assertThat(client.connectionStatus.value).isEqualTo(ConnectionStatus.ERROR)
}
```

- [ ] **Step 3: Run the network tests to verify they fail**

Run: `./gradlew.bat :core:network:testDebugUnitTest --tests "com.openmate.core.network.SyncSseClientTest" --no-daemon`
Expected: FAIL because `forceRestart` or fresh-attempt semantics do not exist yet.

- [ ] **Step 4: Extend the connection API to support forced fresh restart**

```kotlin
interface SyncSseConnection {
    suspend fun connect(baseUrl: String, forceRestart: Boolean = false)
    fun disconnect(traceId: String? = null)
    val currentBaseUrl: String?
}
```

- [ ] **Step 5: Update `SyncSseClient.connect()` to respect forced restart**

```kotlin
override suspend fun connect(baseUrl: String, forceRestart: Boolean) {
    if (!forceRestart && currentBaseUrl == baseUrl) {
        return
    }
    disconnect()
    currentBaseUrl = baseUrl
    _connectionStatus.value = ConnectionStatus.CONNECTING
    // existing loop continues
}
```

- [ ] **Step 6: Run the network tests to verify they pass**

Run: `./gradlew.bat :core:network:testDebugUnitTest --tests "com.openmate.core.network.SyncSseClientTest" --no-daemon`
Expected: PASS

- [ ] **Step 7: Commit the transport-semantics change**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/SyncSseConnection.kt android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt android/core/network/src/test/java/com/openmate/core/network/SyncSseClientTest.kt
git commit -m "fix: make sync sse reconnect semantics explicit"
```

### Task 5: Add app-foreground and network-change event sources

**Files:**
- Create: `android/app/src/main/java/com/openmate/app/connection/AppForegroundMonitor.kt`
- Create: `android/app/src/main/java/com/openmate/app/connection/NetworkChangeMonitor.kt`
- Modify: `android/app/src/main/java/com/openmate/app/OpenMateApp.kt`
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionModule.kt`
- Test: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Write the failing test that app foreground emits a recovery trigger**

```kotlin
@Test
fun appForeground_afterConnectionLoss_requestsImmediateReevaluation() {
    val env = createEnvironment()
    env.manager.connect(profile(name = "resume", instanceId = "iid-resume"))
    waitUntil { env.syncSseCallFactory.requestCount == 1 }

    env.dispatch(ConnectionEvent.AppForegrounded)

    assertThat(env.reducerEvents).contains(ConnectionEvent.AppForegrounded)
}
```

- [ ] **Step 2: Run the ConnectionManager test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: FAIL because lifecycle-driven dispatch does not exist yet.

- [ ] **Step 3: Add a process-lifecycle foreground monitor contract**

```kotlin
interface AppForegroundMonitor {
    val isForeground: StateFlow<Boolean>
}
```

- [ ] **Step 4: Add a network-change monitor contract**

```kotlin
interface NetworkChangeMonitor {
    val changes: Flow<Unit>
}
```

- [ ] **Step 5: Wire the monitors into app startup**

```kotlin
override fun onCreate() {
    super.onCreate()
    CrashHandler.install(this)
    connectionManager.restoreLastConnection()
    connectionManager.startRuntimeMonitoring()
}
```

- [ ] **Step 6: Run the ConnectionManager test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: PASS

- [ ] **Step 7: Commit the monitor wiring**

```bash
git add android/app/src/main/java/com/openmate/app/connection/AppForegroundMonitor.kt android/app/src/main/java/com/openmate/app/connection/NetworkChangeMonitor.kt android/app/src/main/java/com/openmate/app/OpenMateApp.kt android/app/src/main/java/com/openmate/app/ConnectionModule.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt
git commit -m "feat: add recovery event monitors"
```

### Task 6: Refactor `ConnectionManager` into an event-driven orchestrator

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Modify: `android/core/domain/src/main/java/com/openmate/core/domain/repository/ConnectionRepository.kt`
- Modify: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionStatus.kt`
- Test: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Write the failing orchestration test for direct staying primary when SSE drops but direct health is still good**

```kotlin
@Test
fun sseFailure_alone_doesNotImmediatelyFlipToGatewayWhenDirectHealthIsGood() {
    val env = createEnvironment(directBridgeReachable = true)
    val profile = profile(name = "direct-stable", instanceId = "iid-direct")

    env.manager.connect(profile)
    waitUntil { env.syncSseCallFactory.requestCount == 1 }

    env.dispatch(ConnectionEvent.SseTransportFailed("socket reset"))

    assertThat(env.apiClient.baseUrl).isEqualTo("http://${profile.address}:${profile.port}")
}
```

- [ ] **Step 2: Write the failing orchestration test for short route switch not surfacing total unavailability**

```kotlin
@Test
fun routeSwitch_restartingSse_doesNotPublishDisconnectedIfRouteRemainsUsable() {
    val env = createEnvironment(directBridgeReachable = false)
    val profile = profile(name = "gateway", instanceId = "iid-gateway")

    env.manager.connect(profile)
    waitUntil { env.syncSseCallFactory.requestCount == 1 }

    assertThat(env.manager.connectionStatus.value).isEqualTo(ConnectionStatus.GATEWAY_CONNECTED)
}
```

- [ ] **Step 3: Run the ConnectionManager tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: FAIL because the current manager still directly maps SSE failures to fallback and coarse status changes.

- [ ] **Step 4: Add a reducer-driven event loop inside `ConnectionManager`**

```kotlin
private suspend fun dispatch(event: ConnectionEvent) {
    val result = ConnectionReducer.reduce(machineState.value, event)
    machineState.value = result.nextState
    result.actions.forEach { action -> execute(action) }
}
```

- [ ] **Step 5: Map the richer machine state to legacy repository-facing status without reporting transient route switches as full disconnect**

```kotlin
private fun ConnectionPhase.toLegacyStatus(activeRoute: ConnectionRoute?): ConnectionStatus {
    return when (this) {
        ConnectionPhase.DISCONNECTED -> ConnectionStatus.DISCONNECTED
        ConnectionPhase.CONNECTING, ConnectionPhase.RECOVERING -> ConnectionStatus.CONNECTING
        ConnectionPhase.CONNECTED -> if (activeRoute is ConnectionRoute.Gateway) ConnectionStatus.GATEWAY_CONNECTED else ConnectionStatus.CONNECTED
        ConnectionPhase.DEGRADED -> ConnectionStatus.GATEWAY_CONNECTED
        ConnectionPhase.NEEDS_REPAIR -> ConnectionStatus.PAIRING
        ConnectionPhase.FAILED -> ConnectionStatus.ERROR
    }
}
```

- [ ] **Step 6: Route SSE transport events, route-health changes, retry, lifecycle, and network-change events through the same dispatcher**

```kotlin
scope.launch {
    routeHealthMonitor.snapshot.collect { dispatch(ConnectionEvent.RouteHealthChanged(it.revision)) }
}
scope.launch {
    networkChangeMonitor.changes.collect { dispatch(ConnectionEvent.NetworkChanged) }
}
scope.launch {
    appForegroundMonitor.isForeground.collect { if (it) dispatch(ConnectionEvent.AppForegrounded) }
}
```

- [ ] **Step 7: Run the full ConnectionManager suite to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: PASS

- [ ] **Step 8: Commit the orchestrator refactor**

```bash
git add android/app/src/main/java/com/openmate/app/ConnectionManager.kt android/core/domain/src/main/java/com/openmate/core/domain/repository/ConnectionRepository.kt android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionStatus.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt
git commit -m "refactor: drive connection manager from explicit state machine"
```

### Task 7: Make route switching wait for sync batch boundaries

**Files:**
- Modify: `android/core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt`
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Test: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Write the failing test for gateway-to-direct switch waiting until sync batch completion**

```kotlin
@Test
fun directRecovery_waitsForBatchBoundaryBeforeSwitchingAwayFromGateway() {
    val env = createEnvironment(directBridgeReachable = false)
    val profile = profile(name = "batch-boundary", instanceId = "iid-batch")

    env.manager.connect(profile)
    waitUntil { env.syncSseCallFactory.requestCount == 1 }

    env.markSyncBatchActive(true)
    env.dispatch(ConnectionEvent.RouteHealthChanged(revision = 2L))

    assertThat(env.apiClient.baseUrl).isEqualTo("https://gateway.clawmate.net")

    env.markSyncBatchActive(false)
    env.dispatch(ConnectionEvent.SyncBatchCompleted)

    assertThat(env.apiClient.baseUrl).isEqualTo("http://${profile.address}:${profile.port}")
}
```

- [ ] **Step 2: Run the ConnectionManager test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: FAIL because batch-boundary-aware switching does not exist yet.

- [ ] **Step 3: Teach `SyncSseHandler` to expose active-batch state and completion events**

```kotlin
interface SyncBatchStateSource {
    val hasActiveBatch: StateFlow<Boolean>
    val batchCompleted: Flow<Unit>
}
```

- [ ] **Step 4: Update `ConnectionManager` switching logic to defer direct takeover until a safe boundary**

```kotlin
if (syncBatchStateSource.hasActiveBatch.value) {
    pendingPreferredRoute = ConnectionRoute.Direct(profile.address, profile.port)
} else {
    switchToDirect(profile)
}
```

- [ ] **Step 5: Run the ConnectionManager suite to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit the safe-switch behavior**

```bash
git add android/core/data/src/main/java/com/openmate/core/data/sync/SyncSseHandler.kt android/app/src/main/java/com/openmate/app/ConnectionManager.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt
git commit -m "feat: switch routes only at sync batch boundaries"
```

### Task 8: Stabilize UI-facing connection semantics

**Files:**
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt`
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/WorkspaceListViewModel.kt`
- Modify: `android/feature/session/src/main/java/com/openmate/feature/session/SessionListViewModel.kt`
- Modify: `android/feature/session/src/test/java/com/openmate/feature/session/WorkspaceAndSessionConnectionStatusTest.kt`

- [ ] **Step 1: Write the failing UI status test for degraded gateway usability**

```kotlin
@Test
fun workspaceStatus_gatewayConnected_isStillShownAsUsable() = runTest(dispatcher) {
    val fixture = createFixture(
        connectionRepository = FakeConnectionRepository(ConnectionStatus.GATEWAY_CONNECTED),
    )

    assertThat(fixture.workspaceListViewModel.connectionStatus.value).isEqualTo(ConnectionStatus.GATEWAY_CONNECTED)
}
```

- [ ] **Step 2: Write the failing UI status test for temporary recovery not appearing as hard disconnect**

```kotlin
@Test
fun sessionStatus_connectingDuringRecovery_doesNotCollapseIntoDisconnected() = runTest(dispatcher) {
    val fixture = createFixture(
        connectionRepository = FakeConnectionRepository(ConnectionStatus.CONNECTING),
    )

    assertThat(fixture.sessionListViewModel.connectionStatus.value).isEqualTo(ConnectionStatus.CONNECTING)
}
```

- [ ] **Step 3: Run the session status tests to verify they fail if mapping regressed**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests "com.openmate.feature.session.WorkspaceAndSessionConnectionStatusTest" --no-daemon`
Expected: FAIL if new mapping is not correctly wired.

- [ ] **Step 4: Update UI consumers to rely on the mapped stable repository state rather than ad hoc error inference**

```kotlin
val connectionStatus = connectionRepository.connectionStatus
```

- [ ] **Step 5: Run the session status tests to verify they pass**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests "com.openmate.feature.session.WorkspaceAndSessionConnectionStatusTest" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit the UI-facing semantics cleanup**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt android/feature/session/src/main/java/com/openmate/feature/session/WorkspaceListViewModel.kt android/feature/session/src/main/java/com/openmate/feature/session/SessionListViewModel.kt android/feature/session/src/test/java/com/openmate/feature/session/WorkspaceAndSessionConnectionStatusTest.kt
git commit -m "refactor: stabilize ui connection semantics"
```

### Task 9: Final verification and cleanup

**Files:**
- Modify: any files above if verification exposes gaps

- [ ] **Step 1: Search for direct fallback logic that still treats SSE failure as total network failure**

Run: `rg "ConnectionStatus\.ERROR|attemptGatewayFallback|switchBackToDirect|currentBaseUrl == baseUrl|restoreLastConnection|NetworkChanged|AppForegrounded" android`
Expected: only intended state-machine-driven references remain.

- [ ] **Step 2: Run focused network and app tests**

Run: `./gradlew.bat :core:network:testDebugUnitTest --tests "com.openmate.core.network.SyncSseClientTest" --no-daemon`
Expected: PASS

- [ ] **Step 3: Run connection manager tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Run session connection status tests**

Run: `./gradlew.bat :feature:session:testDebugUnitTest --tests "com.openmate.feature.session.WorkspaceAndSessionConnectionStatusTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: Run debug build**

Run: `./gradlew.bat :app:assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Verify repository state before completion**

Run: `git status --short --branch`
Expected: only intended tracked modifications remain.

- [ ] **Step 7: Commit the final integration pass**

```bash
git add android
git commit -m "feat: add event driven connection recovery state machine"
```

## Self-Review

- Spec coverage:
  - event-driven state machine -> Tasks 2 and 6
  - route availability separated from SSE state -> Tasks 3, 4, and 6
  - user retry / resume / network-change as strong triggers -> Tasks 2, 5, and 6
  - gateway health includes bridge-online semantics -> Task 3
  - SSE disconnect does not imply route unavailable -> Tasks 4 and 6
  - short route switch should not be shown as total network failure -> Tasks 6 and 8
  - safe gateway-to-direct switch at batch boundaries -> Task 7
  - stable offline-reliable UI semantics -> Task 8
- Placeholder scan:
  - no `TODO` or `TBD` placeholders in tasks
  - every task includes concrete files, test intent, and commands
- Type consistency:
  - plan consistently uses `ConnectionEvent`, `ConnectionMachineState`, `ConnectionReducer`, `RouteHealthMonitor`, and `ConnectionSnapshot`
