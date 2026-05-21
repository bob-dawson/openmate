# Connection Recovery Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce the foundation of the new connection recovery architecture: explicit machine state, explicit events, recovery generation, and stable legacy status mapping without yet implementing route evidence or SSE transport integration.

**Architecture:** This plan builds the domain and app-layer scaffolding for the connection recovery state machine. It adds explicit machine types and reducer behavior first, then makes `ConnectionManager` own a reducer-driven loop while still using temporary placeholders for route-evidence and SSE integration that later plans will replace.

**Tech Stack:** Kotlin, Coroutines, StateFlow, JUnit4, Robolectric, Google Truth, Gradle `--no-daemon`

---

## File Map

- Create: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionRoute.kt`
  - Route type for direct and gateway.
- Create: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionPhase.kt`
  - Richer phase model backing recovery semantics.
- Create: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionSnapshot.kt`
  - Aggregated snapshot for future UI and repository consumers.
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionEvent.kt`
  - Explicit event set for the state machine foundation.
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionMachineState.kt`
  - Internal machine state including recovery generation.
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionAction.kt`
  - Side effects emitted by the reducer.
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionReducer.kt`
  - Pure reducer for the foundational transitions.
- Modify: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionStatus.kt`
  - Keep legacy enum while formalizing mapping expectations.
- Modify: `android/core/domain/src/main/java/com/openmate/core/domain/repository/ConnectionRepository.kt`
  - Add future-facing snapshot exposure or document mapping boundary.
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
  - Replace ad hoc state mutation with reducer-driven internal state storage.
- Create: `android/app/src/test/java/com/openmate/app/connection/ConnectionReducerTest.kt`
  - Reducer tests for core transitions and recovery generation behavior.
- Modify: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`
  - Validate legacy status mapping and reducer-driven state ownership.

### Task 1: Add route, phase, and snapshot domain models

**Files:**
- Create: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionRoute.kt`
- Create: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionPhase.kt`
- Create: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionSnapshot.kt`
- Test: `android/core/domain/src/test/java/com/openmate/core/domain/model/ConnectionStatusTest.kt`

- [ ] **Step 1: Write the failing domain test for degraded direct vs gateway semantics**

```kotlin
@Test
fun connectionSnapshot_gatewayUsable_isDegradedNotDisconnected() {
    val snapshot = ConnectionSnapshot(
        phase = ConnectionPhase.CONNECTED,
        activeRoute = ConnectionRoute.Gateway(instanceId = "iid-1"),
        desiredProfileId = "p1",
        isUsable = true,
        needsUserRepair = false,
        message = null,
    )

    assertThat(snapshot.isUsable).isTrue()
    assertThat(snapshot.activeRoute).isEqualTo(ConnectionRoute.Gateway("iid-1"))
}
```

- [ ] **Step 2: Run the domain test to verify it fails**

Run: `./gradlew.bat :core:domain:testDebugUnitTest --tests "com.openmate.core.domain.model.ConnectionStatusTest" --no-daemon`
Expected: FAIL with unresolved references to `ConnectionSnapshot`, `ConnectionPhase`, or `ConnectionRoute`.

- [ ] **Step 3: Add the route model**

```kotlin
package com.openmate.core.domain.model

sealed interface ConnectionRoute {
    data class Direct(val address: String, val port: Int) : ConnectionRoute
    data class Gateway(val instanceId: String) : ConnectionRoute
}
```

- [ ] **Step 4: Add the phase model**

```kotlin
package com.openmate.core.domain.model

enum class ConnectionPhase {
    DISCONNECTED,
    EVALUATING,
    CONNECTING,
    CONNECTED,
    RECOVERING,
    NEEDS_REPAIR,
    FAILED,
}
```

- [ ] **Step 5: Add the snapshot model**

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

- [ ] **Step 7: Commit the domain model foundation**

```bash
git add android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionRoute.kt android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionPhase.kt android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionSnapshot.kt android/core/domain/src/test/java/com/openmate/core/domain/model/ConnectionStatusTest.kt
git commit -m "feat: add connection recovery foundation models"
```

### Task 2: Add explicit state-machine event and state types

**Files:**
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionEvent.kt`
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionMachineState.kt`
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionAction.kt`
- Test: `android/app/src/test/java/com/openmate/app/connection/ConnectionReducerTest.kt`

- [ ] **Step 1: Write the failing reducer test for recovery generation increment on retry**

```kotlin
@Test
fun userRetry_startsNewRecoveryGeneration() {
    val state = ConnectionMachineState(
        desiredProfileId = "p1",
        activeRoute = null,
        phase = ConnectionPhase.RECOVERING,
        recoveryGeneration = 4L,
    )

    val result = ConnectionReducer.reduce(state, ConnectionEvent.UserRetry)

    assertThat(result.nextState.recoveryGeneration).isEqualTo(5L)
    assertThat(result.nextState.phase).isEqualTo(ConnectionPhase.EVALUATING)
}
```

- [ ] **Step 2: Run the reducer test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.ConnectionReducerTest.userRetry_startsNewRecoveryGeneration" --no-daemon`
Expected: FAIL with unresolved references to reducer types.

- [ ] **Step 3: Add the event types**

```kotlin
package com.openmate.app.connection

import com.openmate.core.domain.model.ConnectionRoute
import com.openmate.core.domain.model.ServerProfile

sealed interface ConnectionEvent {
    data class UserConnect(val profile: ServerProfile) : ConnectionEvent
    data object UserDisconnect : ConnectionEvent
    data object UserRetry : ConnectionEvent
    data class RepairCompleted(val profileId: String) : ConnectionEvent
    data object AppForegrounded : ConnectionEvent
    data object AppBackgrounded : ConnectionEvent
    data object NetworkAvailable : ConnectionEvent
    data object NetworkLost : ConnectionEvent
    data object NetworkPathChanged : ConnectionEvent
    data class RouteEvidenceUpdated(val route: ConnectionRoute) : ConnectionEvent
    data object BackoffExpired : ConnectionEvent
}
```

- [ ] **Step 4: Add the machine state type**

```kotlin
package com.openmate.app.connection

import com.openmate.core.domain.model.ConnectionPhase
import com.openmate.core.domain.model.ConnectionRoute

data class ConnectionMachineState(
    val desiredProfileId: String?,
    val activeRoute: ConnectionRoute?,
    val phase: ConnectionPhase,
    val recoveryGeneration: Long,
)
```

- [ ] **Step 5: Add the action type**

```kotlin
package com.openmate.app.connection

sealed interface ConnectionAction {
    data object ReevaluateRoutes : ConnectionAction
    data object StartBackoff : ConnectionAction
    data object StopActiveTransport : ConnectionAction
}
```

- [ ] **Step 6: Run the reducer test to verify it still fails at missing reducer implementation**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.ConnectionReducerTest.userRetry_startsNewRecoveryGeneration" --no-daemon`
Expected: FAIL with unresolved reference to `ConnectionReducer`.

- [ ] **Step 7: Commit the event/state/action scaffolding**

```bash
git add android/app/src/main/java/com/openmate/app/connection/ConnectionEvent.kt android/app/src/main/java/com/openmate/app/connection/ConnectionMachineState.kt android/app/src/main/java/com/openmate/app/connection/ConnectionAction.kt android/app/src/test/java/com/openmate/app/connection/ConnectionReducerTest.kt
git commit -m "feat: add connection recovery machine scaffolding"
```

### Task 3: Add the foundational reducer transitions

**Files:**
- Create: `android/app/src/main/java/com/openmate/app/connection/ConnectionReducer.kt`
- Test: `android/app/src/test/java/com/openmate/app/connection/ConnectionReducerTest.kt`

- [ ] **Step 1: Extend the reducer tests for disconnect and network-available transitions**

```kotlin
@Test
fun userDisconnect_movesMachineToDisconnected() {
    val state = ConnectionMachineState(
        desiredProfileId = "p1",
        activeRoute = ConnectionRoute.Direct("127.0.0.1", 4097),
        phase = ConnectionPhase.CONNECTED,
        recoveryGeneration = 2L,
    )

    val result = ConnectionReducer.reduce(state, ConnectionEvent.UserDisconnect)

    assertThat(result.nextState.desiredProfileId).isNull()
    assertThat(result.nextState.phase).isEqualTo(ConnectionPhase.DISCONNECTED)
    assertThat(result.actions).contains(ConnectionAction.StopActiveTransport)
}

@Test
fun networkAvailable_duringRecovery_restartsEvaluationWithNewGeneration() {
    val state = ConnectionMachineState(
        desiredProfileId = "p1",
        activeRoute = null,
        phase = ConnectionPhase.RECOVERING,
        recoveryGeneration = 8L,
    )

    val result = ConnectionReducer.reduce(state, ConnectionEvent.NetworkAvailable)

    assertThat(result.nextState.phase).isEqualTo(ConnectionPhase.EVALUATING)
    assertThat(result.nextState.recoveryGeneration).isEqualTo(9L)
    assertThat(result.actions).contains(ConnectionAction.ReevaluateRoutes)
}
```

- [ ] **Step 2: Run the reducer test suite to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.ConnectionReducerTest" --no-daemon`
Expected: FAIL with unresolved reference to `ConnectionReducer`.

- [ ] **Step 3: Add the minimal reducer implementation**

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
            ConnectionEvent.UserDisconnect -> Result(
                nextState = ConnectionMachineState(
                    desiredProfileId = null,
                    activeRoute = null,
                    phase = ConnectionPhase.DISCONNECTED,
                    recoveryGeneration = state.recoveryGeneration,
                ),
                actions = listOf(ConnectionAction.StopActiveTransport),
            )
            ConnectionEvent.UserRetry,
            ConnectionEvent.NetworkAvailable,
            ConnectionEvent.NetworkPathChanged,
            ConnectionEvent.AppForegrounded -> Result(
                nextState = state.copy(
                    phase = ConnectionPhase.EVALUATING,
                    recoveryGeneration = state.recoveryGeneration + 1,
                ),
                actions = listOf(ConnectionAction.ReevaluateRoutes),
            )
            else -> Result(state, emptyList())
        }
    }
}
```

- [ ] **Step 4: Run the reducer test suite to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.ConnectionReducerTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit the reducer foundation**

```bash
git add android/app/src/main/java/com/openmate/app/connection/ConnectionReducer.kt android/app/src/test/java/com/openmate/app/connection/ConnectionReducerTest.kt
git commit -m "feat: add foundational connection reducer"
```

### Task 4: Add stable legacy status mapping rules

**Files:**
- Modify: `android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionStatus.kt`
- Modify: `android/core/domain/src/main/java/com/openmate/core/domain/repository/ConnectionRepository.kt`
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Test: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Write the failing ConnectionManager test for recovery mapping to CONNECTING instead of DISCONNECTED**

```kotlin
@Test
fun recoveringPhase_mapsToConnectingNotDisconnected() {
    val env = createEnvironment()

    env.manager.forceMachineStateForTest(
        ConnectionMachineState(
            desiredProfileId = "p1",
            activeRoute = null,
            phase = ConnectionPhase.RECOVERING,
            recoveryGeneration = 1L,
        )
    )

    assertThat(env.manager.connectionStatus.value).isEqualTo(ConnectionStatus.CONNECTING)
}
```

- [ ] **Step 2: Run the ConnectionManager test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest.recoveringPhase_mapsToConnectingNotDisconnected" --no-daemon`
Expected: FAIL because machine-state mapping does not exist yet.

- [ ] **Step 3: Add the mapping helper inside `ConnectionManager`**

```kotlin
private fun ConnectionMachineState.toLegacyStatus(): ConnectionStatus {
    return when (phase) {
        ConnectionPhase.DISCONNECTED -> ConnectionStatus.DISCONNECTED
        ConnectionPhase.EVALUATING, ConnectionPhase.CONNECTING, ConnectionPhase.RECOVERING -> ConnectionStatus.CONNECTING
        ConnectionPhase.CONNECTED -> when (activeRoute) {
            is ConnectionRoute.Gateway -> ConnectionStatus.GATEWAY_CONNECTED
            else -> ConnectionStatus.CONNECTED
        }
        ConnectionPhase.NEEDS_REPAIR -> ConnectionStatus.PAIRING
        ConnectionPhase.FAILED -> ConnectionStatus.ERROR
    }
}
```

- [ ] **Step 4: Add future-facing snapshot exposure to the repository contract**

```kotlin
val connectionSnapshot: StateFlow<ConnectionSnapshot?>
```

- [ ] **Step 5: Run the focused ConnectionManager test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest.recoveringPhase_mapsToConnectingNotDisconnected" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit the legacy mapping layer**

```bash
git add android/core/domain/src/main/java/com/openmate/core/domain/model/ConnectionStatus.kt android/core/domain/src/main/java/com/openmate/core/domain/repository/ConnectionRepository.kt android/app/src/main/java/com/openmate/app/ConnectionManager.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt
git commit -m "refactor: map machine state to legacy connection status"
```

### Task 5: Make `ConnectionManager` own a reducer-driven state store

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Modify: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Write the failing test that `connect(profile)` now dispatches a `UserConnect` event instead of directly mutating connection status**

```kotlin
@Test
fun connect_dispatchesUserConnectIntoReducerLoop() {
    val env = createEnvironment()
    val profile = profile(name = "foundation", instanceId = "iid-foundation")

    env.manager.connect(profile)

    assertThat(env.recordedEvents).contains(ConnectionEvent.UserConnect(profile))
}
```

- [ ] **Step 2: Run the ConnectionManager suite to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: FAIL because reducer-driven dispatch does not exist yet.

- [ ] **Step 3: Add an internal reducer-dispatch loop to `ConnectionManager`**

```kotlin
private suspend fun dispatch(event: ConnectionEvent) {
    val result = ConnectionReducer.reduce(machineState.value, event)
    machineState.value = result.nextState
    _connectionStatus.value = result.nextState.toLegacyStatus()
    _connectionSnapshot.value = result.nextState.toSnapshot()
    result.actions.forEach { execute(it) }
}
```

- [ ] **Step 4: Update `connect()`, `disconnect()`, and `clearError()` entrypoints to flow through reducer events where applicable**

```kotlin
override fun connect(profile: ServerProfile) {
    scope.launch { dispatch(ConnectionEvent.UserConnect(profile)) }
}

override fun disconnect() {
    scope.launch { dispatch(ConnectionEvent.UserDisconnect) }
}
```

- [ ] **Step 5: Run the ConnectionManager suite to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit the reducer-driven manager foundation**

```bash
git add android/app/src/main/java/com/openmate/app/ConnectionManager.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt
git commit -m "refactor: make connection manager reducer-driven"
```

### Task 6: Final verification for foundation-only scope

**Files:**
- Modify: any files above if verification exposes gaps

- [ ] **Step 1: Search for remaining direct connection-status mutation inside `ConnectionManager`**

Run: `rg "_connectionStatus\.value|_isConnected\.value|_errorMessage\.value|_needsRepairing\.value" android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
Expected: only intended mapping/output updates remain.

- [ ] **Step 2: Run domain tests**

Run: `./gradlew.bat :core:domain:testDebugUnitTest --tests "com.openmate.core.domain.model.ConnectionStatusTest" --no-daemon`
Expected: PASS

- [ ] **Step 3: Run reducer tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.ConnectionReducerTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Run ConnectionManager tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: PASS

- [ ] **Step 5: Verify repository state**

Run: `git status --short --branch`
Expected: only intended tracked modifications remain.

- [ ] **Step 6: Commit the final foundation pass**

```bash
git add android
git commit -m "feat: add connection recovery foundation"
```

## Self-Review

- Spec coverage:
  - explicit state machine skeleton -> Tasks 2 and 3
  - recovery generation -> Tasks 2 and 3
  - stable legacy mapping -> Task 4
  - reducer-driven ConnectionManager ownership -> Task 5
  - no route-evidence or SSE details yet -> intentionally deferred to later plans
- Placeholder scan:
  - no `TODO` or `TBD` placeholders
  - each step includes concrete code or command content
- Type consistency:
  - uses `ConnectionEvent`, `ConnectionMachineState`, `ConnectionAction`, `ConnectionReducer`, `ConnectionSnapshot` consistently
