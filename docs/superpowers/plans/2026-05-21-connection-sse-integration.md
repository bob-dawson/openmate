# Connection SSE Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the unified Bridge SSE transport into the new connection recovery architecture so SSE becomes a strong positive route-evidence source and a suspicion trigger, without acting as the sole authority for overall availability.

**Architecture:** This plan updates `SyncSseClient` and related glue so SSE emits explicit transport events into the state machine and route-evidence layer. It removes same-URL reconnect short-circuiting for explicit recovery generations, adds strong-positive `SseEventReceived` reporting, and makes route handoff use make-before-break semantics instead of interpreting SSE restart as full loss of network availability.

**Tech Stack:** Kotlin, Coroutines, StateFlow, OkHttp, JUnit4, Robolectric, Google Truth, Gradle `--no-daemon`

---

## File Map

- Modify: `android/core/network/src/main/java/com/openmate/core/network/SyncSseConnection.kt`
  - Extend the transport contract for explicit restart semantics.
- Modify: `android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt`
  - Emit transport lifecycle signals and strong-positive event receipts.
- Modify: `android/core/network/src/main/java/com/openmate/core/network/SyncSseLogger.kt`
  - Add logging points for stronger transport signal semantics if needed.
- Modify: `android/core/data/src/main/java/com/openmate/core/data/sync/SyncSseLoggerImpl.kt`
  - Align log messages with signal semantics.
- Modify: `android/core/data/src/main/java/com/openmate/core/data/repository/SseEventRepositoryImpl.kt`
  - Continue dispatching business events while not conflating short restarts with total disconnection.
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
  - Consume SSE lifecycle callbacks as machine events and support make-before-break handoff.
- Modify: `android/app/src/main/java/com/openmate/app/connection/ConnectionEvent.kt`
  - Add SSE-specific machine events.
- Modify: `android/app/src/main/java/com/openmate/app/connection/ConnectionReducer.kt`
  - Route SSE signals to reevaluation rather than direct failure assumptions.
- Modify: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`
  - Verify SSE failure and event receipt semantics.
- Modify: `android/core/network/src/test/java/com/openmate/core/network/SyncSseClientTest.kt`
  - Verify reconnect and transport signal behavior.

### Task 1: Extend the SSE transport contract with explicit signal semantics

**Files:**
- Modify: `android/core/network/src/main/java/com/openmate/core/network/SyncSseConnection.kt`
- Modify: `android/core/network/src/test/java/com/openmate/core/network/SyncSseClientTest.kt`

- [ ] **Step 1: Write the failing test for forced same-URL reconnect**

```kotlin
@Test
fun connect_forceRestart_sameBaseUrl_startsFreshAttempt() = runTest {
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

- [ ] **Step 2: Run the network test to verify it fails**

Run: `./gradlew.bat :core:network:testDebugUnitTest --tests "com.openmate.core.network.SyncSseClientTest.connect_forceRestart_sameBaseUrl_startsFreshAttempt" --no-daemon`
Expected: FAIL because `forceRestart` does not exist yet.

- [ ] **Step 3: Extend the transport contract**

```kotlin
package com.openmate.core.network

interface SyncSseConnection {
    suspend fun connect(baseUrl: String, forceRestart: Boolean = false)
    fun disconnect(traceId: String? = null)
    val currentBaseUrl: String?
}
```

- [ ] **Step 4: Update the test doubles and callers to compile against the new signature**

```kotlin
override suspend fun connect(baseUrl: String, forceRestart: Boolean) = Unit
```

- [ ] **Step 5: Run the network test again to verify it still fails at client behavior**

Run: `./gradlew.bat :core:network:testDebugUnitTest --tests "com.openmate.core.network.SyncSseClientTest.connect_forceRestart_sameBaseUrl_startsFreshAttempt" --no-daemon`
Expected: FAIL because `SyncSseClient` still short-circuits same-URL connect.

- [ ] **Step 6: Commit the contract update**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/SyncSseConnection.kt android/core/network/src/test/java/com/openmate/core/network/SyncSseClientTest.kt
git commit -m "refactor: extend sync sse connection contract"
```

### Task 2: Make `SyncSseClient` emit strong-positive and suspicion signals

**Files:**
- Modify: `android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt`
- Modify: `android/core/network/src/test/java/com/openmate/core/network/SyncSseClientTest.kt`

- [ ] **Step 1: Add the failing test for `SseEventReceived` signal on parsed event**

```kotlin
@Test
fun parsedSseEvent_emitsStrongPositiveSignal() = runTest {
    val callFactory = SingleResponseSseCallFactory(
        body = "data: {\"type\":\"session.updated\",\"properties\":{\"sessionID\":\"ses_1\"}}\n\n"
    )
    val client = SyncSseClient(
        client = callFactory,
        tokenStore = FakeTokenStore(),
        logger = NoOpSyncSseLogger,
    )

    launch { client.connect("http://127.0.0.1:4097", forceRestart = true) }
    val signal = client.transportSignals.first { it is SyncSseSignal.EventReceived }

    assertThat((signal as SyncSseSignal.EventReceived).routeBaseUrl).isEqualTo("http://127.0.0.1:4097")
}
```

- [ ] **Step 2: Add the failing test for stream failure emitting suspicion instead of hard availability conclusion**

```kotlin
@Test
fun streamFailure_emitsSuspicionSignal() = runTest {
    val client = SyncSseClient(
        client = AlwaysFailingCallFactory(),
        tokenStore = FakeTokenStore(),
        logger = NoOpSyncSseLogger,
    )

    launch { client.connect("http://127.0.0.1:4097", forceRestart = true) }
    val signal = client.transportSignals.first { it is SyncSseSignal.Failed }

    assertThat(signal).isInstanceOf(SyncSseSignal.Failed::class.java)
}
```

- [ ] **Step 3: Run the network test suite to verify it fails**

Run: `./gradlew.bat :core:network:testDebugUnitTest --tests "com.openmate.core.network.SyncSseClientTest" --no-daemon`
Expected: FAIL with unresolved reference to `SyncSseSignal` or `transportSignals`.

- [ ] **Step 4: Add the transport signal model**

```kotlin
sealed interface SyncSseSignal {
    data class ConnectStarted(val routeBaseUrl: String) : SyncSseSignal
    data class Connected(val routeBaseUrl: String) : SyncSseSignal
    data class EventReceived(val routeBaseUrl: String) : SyncSseSignal
    data class StreamClosed(val routeBaseUrl: String) : SyncSseSignal
    data class Failed(val routeBaseUrl: String, val message: String?) : SyncSseSignal
}
```

- [ ] **Step 5: Add a shared-flow signal stream to `SyncSseClient` and emit signals at transport lifecycle points**

```kotlin
private val _transportSignals = MutableSharedFlow<SyncSseSignal>(extraBufferCapacity = 32)
val transportSignals: SharedFlow<SyncSseSignal> = _transportSignals

_transportSignals.tryEmit(SyncSseSignal.ConnectStarted(baseUrl))
_transportSignals.tryEmit(SyncSseSignal.Connected(baseUrl))
_transportSignals.tryEmit(SyncSseSignal.EventReceived(baseUrl))
_transportSignals.tryEmit(SyncSseSignal.StreamClosed(baseUrl))
_transportSignals.tryEmit(SyncSseSignal.Failed(baseUrl, e.message))
```

- [ ] **Step 6: Respect `forceRestart` in `connect()`**

```kotlin
override suspend fun connect(baseUrl: String, forceRestart: Boolean) {
    if (!forceRestart && currentBaseUrl == baseUrl) {
        return
    }
    disconnect()
    currentBaseUrl = baseUrl
    _connectionStatus.value = ConnectionStatus.CONNECTING
    // existing loop
}
```

- [ ] **Step 7: Run the network test suite to verify it passes**

Run: `./gradlew.bat :core:network:testDebugUnitTest --tests "com.openmate.core.network.SyncSseClientTest" --no-daemon`
Expected: PASS

- [ ] **Step 8: Commit the transport signal integration**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/SyncSseClient.kt android/core/network/src/test/java/com/openmate/core/network/SyncSseClientTest.kt
git commit -m "feat: add sync sse transport signals"
```

### Task 3: Route SSE signals into the machine as evidence and suspicion

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/connection/ConnectionEvent.kt`
- Modify: `android/app/src/main/java/com/openmate/app/connection/ConnectionReducer.kt`
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Modify: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Write the failing test for `SseEventReceived` keeping the current route usable**

```kotlin
@Test
fun sseEventReceived_onDirect_keepsDirectUsable() {
    val env = createEnvironment(directBridgeReachable = true)
    val profile = profile(name = "sse-positive", instanceId = "iid-direct")

    env.manager.connect(profile)
    waitUntil { env.syncSseCallFactory.requestCount == 1 }

    env.emitSseSignal(SyncSseSignal.EventReceived("http://${profile.address}:${profile.port}"))

    assertThat(env.manager.connectionStatus.value).isEqualTo(ConnectionStatus.CONNECTED)
}
```

- [ ] **Step 2: Write the failing test for SSE failure only triggering reevaluation, not immediate gateway switch**

```kotlin
@Test
fun sseFailure_onDirect_triggersReevaluationNotImmediateGatewaySwitch() {
    val env = createEnvironment(directBridgeReachable = true)
    val profile = profile(name = "sse-fail", instanceId = "iid-direct")

    env.manager.connect(profile)
    waitUntil { env.syncSseCallFactory.requestCount == 1 }

    env.emitSseSignal(SyncSseSignal.Failed("http://${profile.address}:${profile.port}", "closed"))

    assertThat(env.recordedEvents).contains(ConnectionEvent.SseFailed(ConnectionRoute.Direct(profile.address, profile.port), "closed"))
    assertThat(env.apiClient.baseUrl).isEqualTo("http://${profile.address}:${profile.port}")
}
```

- [ ] **Step 3: Run the ConnectionManager test suite to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: FAIL because SSE transport signals are not yet routed into machine events.

- [ ] **Step 4: Extend machine events with SSE-specific signal events**

```kotlin
data class SseConnected(val route: ConnectionRoute) : ConnectionEvent
data class SseEventReceived(val route: ConnectionRoute) : ConnectionEvent
data class SseStreamClosed(val route: ConnectionRoute) : ConnectionEvent
data class SseFailed(val route: ConnectionRoute, val message: String?) : ConnectionEvent
```

- [ ] **Step 5: Update reducer semantics so `SseEventReceived` is positive evidence and `SseFailed` enters reevaluation rather than direct hard failure**

```kotlin
ConnectionEvent.SseEventReceived -> Result(
    nextState = state,
    actions = listOf(ConnectionAction.NotePositiveRouteEvidence),
)

is ConnectionEvent.SseFailed -> Result(
    nextState = state.copy(phase = ConnectionPhase.RECOVERING),
    actions = listOf(ConnectionAction.ReevaluateRoutes),
)
```

- [ ] **Step 6: Collect `SyncSseClient.transportSignals` in `ConnectionManager` and translate them into machine events**

```kotlin
scope.launch {
    syncSseClient.transportSignals.collect { signal ->
        dispatch(signal.toConnectionEvent())
    }
}
```

- [ ] **Step 7: Run the ConnectionManager test suite to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: PASS

- [ ] **Step 8: Commit SSE-to-machine integration**

```bash
git add android/app/src/main/java/com/openmate/app/connection/ConnectionEvent.kt android/app/src/main/java/com/openmate/app/connection/ConnectionReducer.kt android/app/src/main/java/com/openmate/app/ConnectionManager.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt
git commit -m "feat: route sse transport signals into connection machine"
```

### Task 4: Use make-before-break route handoff semantics

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Modify: `android/core/data/src/main/java/com/openmate/core/data/repository/SseEventRepositoryImpl.kt`
- Modify: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Write the failing test for gateway-to-direct handoff not surfacing transient disconnection**

```kotlin
@Test
fun gatewayToDirectHandoff_doesNotPublishDisconnectedDuringShortRestart() {
    val env = createEnvironment(directBridgeReachable = false)
    val profile = profile(name = "handoff", instanceId = "iid-gateway")

    env.manager.connect(profile)
    waitUntil { env.manager.connectionStatus.value == ConnectionStatus.GATEWAY_CONNECTED }

    env.markDirectEvidenceHealthy(profile)
    env.triggerRouteReevaluation()

    assertThat(env.manager.connectionStatus.value).isNotEqualTo(ConnectionStatus.DISCONNECTED)
}
```

- [ ] **Step 2: Run the ConnectionManager test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest.gatewayToDirectHandoff_doesNotPublishDisconnectedDuringShortRestart" --no-daemon`
Expected: FAIL if handoff still briefly collapses into disconnected semantics.

- [ ] **Step 3: Update `ConnectionManager` handoff flow to prepare new route before clearing the previous route-facing status**

```kotlin
private suspend fun handoffToRoute(nextBaseUrl: String) {
    syncSseClient.connect(nextBaseUrl, forceRestart = true)
    // keep machine in recovering/connecting semantics, never publish DISCONNECTED for this short handoff
}
```

- [ ] **Step 4: Ensure repository-facing disconnect is only used for true target removal, not short handoff restarts**

```kotlin
override fun disconnect() {
    eventDispatcher.activeDirectory = ""
    eventDispatcher.messageSyncEnabled = false
    syncSseClient.disconnect("sse-repository")
}
```

- [ ] **Step 5: Run the ConnectionManager suite to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit the handoff semantics change**

```bash
git add android/app/src/main/java/com/openmate/app/ConnectionManager.kt android/core/data/src/main/java/com/openmate/core/data/repository/SseEventRepositoryImpl.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt
git commit -m "refactor: use make-before-break sse handoff semantics"
```

### Task 5: Final verification for SSE-integration scope

**Files:**
- Modify: any files above if verification exposes gaps

- [ ] **Step 1: Search for same-URL short-circuit and direct SSE->gateway shortcut assumptions**

Run: `rg "currentBaseUrl == baseUrl|attemptGatewayFallback|SseFailed|SseStreamClosed|transportSignals" android`
Expected: only intended signal-driven references remain.

- [ ] **Step 2: Run network tests**

Run: `./gradlew.bat :core:network:testDebugUnitTest --tests "com.openmate.core.network.SyncSseClientTest" --no-daemon`
Expected: PASS

- [ ] **Step 3: Run ConnectionManager tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Verify repository state**

Run: `git status --short --branch`
Expected: only intended tracked modifications remain.

- [ ] **Step 5: Commit the final SSE integration pass**

```bash
git add android
git commit -m "feat: integrate sse transport with connection recovery"
```

## Self-Review

- Spec coverage:
  - same-url explicit reconnect -> Tasks 1 and 2
  - `SseEventReceived` as strong positive signal -> Tasks 2 and 3
  - `SseFailed` / `SseStreamClosed` as suspicion and reevaluation triggers -> Task 3
  - make-before-break handoff semantics -> Task 4
- Placeholder scan:
  - no `TODO` or `TBD` placeholders
  - each task includes concrete code, files, and commands
- Type consistency:
  - uses `SyncSseSignal`, `transportSignals`, and SSE machine events consistently
