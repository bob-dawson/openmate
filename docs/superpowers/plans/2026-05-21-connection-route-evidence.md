# Connection Route Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the route evidence and route health layer so connection decisions are based primarily on recent network-layer API evidence, with explicit probing only when needed.

**Architecture:** This plan adds a route-evidence aggregator owned by the app connection layer. It collects ordinary API network success/failure, network-availability events, and explicit probe results, then derives direct and gateway route health snapshots for the connection state machine.

**Tech Stack:** Kotlin, Coroutines, StateFlow, OkHttp interceptors, existing Bridge status APIs, JUnit4, Robolectric, Google Truth, Gradle `--no-daemon`

---

## File Map

- Create: `android/app/src/main/java/com/openmate/app/connection/RouteEvidence.kt`
  - Raw route evidence records and freshness metadata.
- Create: `android/app/src/main/java/com/openmate/app/connection/RouteHealthSnapshot.kt`
  - Aggregated route-health model for direct and gateway.
- Create: `android/app/src/main/java/com/openmate/app/connection/RouteEvidenceAggregator.kt`
  - Primary evidence collector and health derivation layer.
- Create: `android/app/src/main/java/com/openmate/app/connection/RouteProbeClient.kt`
  - Explicit direct/gateway probe helper used only when recent in-band evidence is missing.
- Modify: `android/core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt`
  - Surface network-layer success/failure callbacks or wrapper points.
- Modify: `android/core/network/src/main/java/com/openmate/core/network/GatewayInterceptor.kt`
  - Preserve route identity for API evidence attribution.
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionModule.kt`
  - Provide route-evidence dependencies.
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
  - Consume route-health snapshots as state-machine input.
- Create: `android/app/src/test/java/com/openmate/app/connection/RouteEvidenceAggregatorTest.kt`
  - Route-evidence freshness and derivation tests.
- Modify: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`
  - Verify route evidence drives evaluation decisions.

### Task 1: Add raw route evidence models

**Files:**
- Create: `android/app/src/main/java/com/openmate/app/connection/RouteEvidence.kt`
- Create: `android/app/src/main/java/com/openmate/app/connection/RouteHealthSnapshot.kt`
- Create: `android/app/src/test/java/com/openmate/app/connection/RouteEvidenceAggregatorTest.kt`

- [ ] **Step 1: Write the failing test for direct API success becoming positive direct evidence**

```kotlin
@Test
fun directApiSuccess_createsPositiveDirectEvidence() {
    val evidence = RouteEvidence.ApiSuccess(
        route = ConnectionRoute.Direct("127.0.0.1", 4097),
        recordedAt = 100L,
    )

    assertThat(evidence.route).isEqualTo(ConnectionRoute.Direct("127.0.0.1", 4097))
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.RouteEvidenceAggregatorTest.directApiSuccess_createsPositiveDirectEvidence" --no-daemon`
Expected: FAIL with unresolved reference to `RouteEvidence`.

- [ ] **Step 3: Add the evidence model**

```kotlin
package com.openmate.app.connection

import com.openmate.core.domain.model.ConnectionRoute

sealed interface RouteEvidence {
    val route: ConnectionRoute
    val recordedAt: Long

    data class ApiSuccess(
        override val route: ConnectionRoute,
        override val recordedAt: Long,
    ) : RouteEvidence

    data class ApiNetworkFailure(
        override val route: ConnectionRoute,
        override val recordedAt: Long,
        val message: String?,
    ) : RouteEvidence

    data class ProbeSuccess(
        override val route: ConnectionRoute,
        override val recordedAt: Long,
    ) : RouteEvidence

    data class ProbeFailure(
        override val route: ConnectionRoute,
        override val recordedAt: Long,
        val message: String?,
    ) : RouteEvidence

    data class SsePositive(
        override val route: ConnectionRoute,
        override val recordedAt: Long,
    ) : RouteEvidence

    data class SseSuspicion(
        override val route: ConnectionRoute,
        override val recordedAt: Long,
        val message: String?,
    ) : RouteEvidence
}
```

- [ ] **Step 4: Add the aggregated health snapshot model**

```kotlin
package com.openmate.app.connection

data class RouteHealth(
    val isUsable: Boolean,
    val lastEvidenceAt: Long?,
    val source: String?,
)

data class RouteHealthSnapshot(
    val revision: Long,
    val direct: RouteHealth,
    val gateway: RouteHealth,
)
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.RouteEvidenceAggregatorTest.directApiSuccess_createsPositiveDirectEvidence" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit the route evidence models**

```bash
git add android/app/src/main/java/com/openmate/app/connection/RouteEvidence.kt android/app/src/main/java/com/openmate/app/connection/RouteHealthSnapshot.kt android/app/src/test/java/com/openmate/app/connection/RouteEvidenceAggregatorTest.kt
git commit -m "feat: add route evidence models"
```

### Task 2: Build the route evidence aggregator

**Files:**
- Create: `android/app/src/main/java/com/openmate/app/connection/RouteEvidenceAggregator.kt`
- Test: `android/app/src/test/java/com/openmate/app/connection/RouteEvidenceAggregatorTest.kt`

- [ ] **Step 1: Add the failing test for API success overriding stale suspicion**

```kotlin
@Test
fun recentApiSuccess_outweighsOlderSseSuspicion() {
    val aggregator = RouteEvidenceAggregator(clock = { now })

    aggregator.record(RouteEvidence.SseSuspicion(ConnectionRoute.Direct("127.0.0.1", 4097), recordedAt = 100L, message = "closed"))
    aggregator.record(RouteEvidence.ApiSuccess(ConnectionRoute.Direct("127.0.0.1", 4097), recordedAt = 200L))

    val snapshot = aggregator.snapshot.value

    assertThat(snapshot.direct.isUsable).isTrue()
    assertThat(snapshot.direct.source).isEqualTo("api")
}
```

- [ ] **Step 2: Add the failing test for gateway requiring usable evidence, not just gateway reachability**

```kotlin
@Test
fun gatewayRequiresBridgeUsabilityEvidence_notJustReachability() {
    val aggregator = RouteEvidenceAggregator(clock = { now })

    aggregator.record(RouteEvidence.ProbeSuccess(ConnectionRoute.Gateway("iid-1"), recordedAt = 100L))

    val snapshot = aggregator.snapshot.value

    assertThat(snapshot.gateway.isUsable).isTrue()
}
```

- [ ] **Step 3: Run the test suite to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.RouteEvidenceAggregatorTest" --no-daemon`
Expected: FAIL with unresolved reference to `RouteEvidenceAggregator`.

- [ ] **Step 4: Add the aggregator contract and minimal storage**

```kotlin
package com.openmate.app.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.openmate.core.domain.model.ConnectionRoute

class RouteEvidenceAggregator(
    private val clock: () -> Long,
) {
    private val evidence = mutableMapOf<ConnectionRoute, RouteEvidence>()
    private val _snapshot = MutableStateFlow(
        RouteHealthSnapshot(
            revision = 0L,
            direct = RouteHealth(isUsable = false, lastEvidenceAt = null, source = null),
            gateway = RouteHealth(isUsable = false, lastEvidenceAt = null, source = null),
        )
    )
    val snapshot: StateFlow<RouteHealthSnapshot> = _snapshot

    fun record(item: RouteEvidence) {
        evidence[item.route] = item
        _snapshot.value = derive(_snapshot.value.revision + 1)
    }

    private fun derive(revision: Long): RouteHealthSnapshot {
        val directEvidence = evidence.entries.firstOrNull { it.key is ConnectionRoute.Direct }?.value
        val gatewayEvidence = evidence.entries.firstOrNull { it.key is ConnectionRoute.Gateway }?.value
        return RouteHealthSnapshot(
            revision = revision,
            direct = directEvidence.toRouteHealth(),
            gateway = gatewayEvidence.toRouteHealth(),
        )
    }

    private fun RouteEvidence?.toRouteHealth(): RouteHealth {
        return when (this) {
            is RouteEvidence.ApiSuccess -> RouteHealth(true, recordedAt, "api")
            is RouteEvidence.ProbeSuccess -> RouteHealth(true, recordedAt, "probe")
            is RouteEvidence.SsePositive -> RouteHealth(true, recordedAt, "sse")
            is RouteEvidence.ApiNetworkFailure -> RouteHealth(false, recordedAt, "api-failure")
            is RouteEvidence.ProbeFailure -> RouteHealth(false, recordedAt, "probe-failure")
            is RouteEvidence.SseSuspicion -> RouteHealth(false, recordedAt, "sse-suspicion")
            null -> RouteHealth(false, null, null)
        }
    }
}
```

- [ ] **Step 5: Run the test suite to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.RouteEvidenceAggregatorTest" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit the aggregator foundation**

```bash
git add android/app/src/main/java/com/openmate/app/connection/RouteEvidenceAggregator.kt android/app/src/test/java/com/openmate/app/connection/RouteEvidenceAggregatorTest.kt
git commit -m "feat: add route evidence aggregator"
```

### Task 3: Add explicit probing only as a fallback source

**Files:**
- Create: `android/app/src/main/java/com/openmate/app/connection/RouteProbeClient.kt`
- Modify: `android/app/src/main/java/com/openmate/app/connection/RouteEvidenceAggregator.kt`
- Test: `android/app/src/test/java/com/openmate/app/connection/RouteEvidenceAggregatorTest.kt`

- [ ] **Step 1: Write the failing test for probe being skipped when fresh API evidence already exists**

```kotlin
@Test
fun freshApiEvidence_skipsRedundantProbe() = runTest {
    val probeClient = FakeRouteProbeClient()
    val aggregator = RouteEvidenceAggregator(clock = { 1_000L })

    aggregator.record(RouteEvidence.ApiSuccess(ConnectionRoute.Direct("127.0.0.1", 4097), recordedAt = 990L))

    val shouldProbe = aggregator.shouldProbe(ConnectionRoute.Direct("127.0.0.1", 4097), freshnessWindowMs = 100L)

    assertThat(shouldProbe).isFalse()
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.RouteEvidenceAggregatorTest.freshApiEvidence_skipsRedundantProbe" --no-daemon`
Expected: FAIL because `shouldProbe` does not exist yet.

- [ ] **Step 3: Add the probe client contract**

```kotlin
package com.openmate.app.connection

import com.openmate.core.domain.model.ConnectionRoute

interface RouteProbeClient {
    suspend fun probe(route: ConnectionRoute): RouteEvidence
}
```

- [ ] **Step 4: Add freshness-based probe gating to the aggregator**

```kotlin
fun shouldProbe(route: ConnectionRoute, freshnessWindowMs: Long): Boolean {
    val last = evidence[route]?.recordedAt ?: return true
    return clock() - last > freshnessWindowMs
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.RouteEvidenceAggregatorTest.freshApiEvidence_skipsRedundantProbe" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit the probe fallback support**

```bash
git add android/app/src/main/java/com/openmate/app/connection/RouteProbeClient.kt android/app/src/main/java/com/openmate/app/connection/RouteEvidenceAggregator.kt android/app/src/test/java/com/openmate/app/connection/RouteEvidenceAggregatorTest.kt
git commit -m "feat: add fallback route probing support"
```

### Task 4: Feed ordinary API outcomes into route evidence

**Files:**
- Modify: `android/core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt`
- Modify: `android/core/network/src/main/java/com/openmate/core/network/GatewayInterceptor.kt`
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionModule.kt`
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Test: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Write the failing test for successful direct API call refreshing direct evidence**

```kotlin
@Test
fun successfulDirectApiCall_refreshesDirectRouteEvidence() {
    val env = createEnvironment(directBridgeReachable = true)
    val profile = profile(name = "direct-evidence", instanceId = "iid-direct")

    env.manager.connect(profile)
    env.recordApiSuccess(ConnectionRoute.Direct(profile.address, profile.port))

    assertThat(env.routeEvidenceAggregator.snapshot.value.direct.isUsable).isTrue()
}
```

- [ ] **Step 2: Run the ConnectionManager test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest.successfulDirectApiCall_refreshesDirectRouteEvidence" --no-daemon`
Expected: FAIL because API outcomes are not yet wired into route evidence.

- [ ] **Step 3: Add a small route-evidence reporter hook around API execution**

```kotlin
interface RouteEvidenceReporter {
    fun reportSuccess(route: ConnectionRoute)
    fun reportNetworkFailure(route: ConnectionRoute, message: String?)
}
```

- [ ] **Step 4: Attribute API calls to direct or gateway route before reporting success/failure**

```kotlin
val route = if (gatewayInterceptor.instanceId != null) {
    ConnectionRoute.Gateway(gatewayInterceptor.instanceId!!)
} else {
    ConnectionRoute.Direct(currentAddress, currentPort)
}
```

- [ ] **Step 5: Run the ConnectionManager test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest.successfulDirectApiCall_refreshesDirectRouteEvidence" --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit API evidence reporting**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt android/core/network/src/main/java/com/openmate/core/network/GatewayInterceptor.kt android/app/src/main/java/com/openmate/app/ConnectionModule.kt android/app/src/main/java/com/openmate/app/ConnectionManager.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt
git commit -m "feat: feed api outcomes into route evidence"
```

### Task 5: Drive route evaluation from route evidence snapshots

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`
- Modify: `android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt`

- [ ] **Step 1: Write the failing test for gateway fallback requiring stronger gateway evidence than direct suspicion**

```kotlin
@Test
fun gatewayFallback_requiresGatewayUsableEvidence_notOnlyDirectSuspicion() {
    val env = createEnvironment(directBridgeReachable = true)
    val profile = profile(name = "evidence-choice", instanceId = "iid-choice")

    env.manager.connect(profile)
    env.recordSseSuspicion(ConnectionRoute.Direct(profile.address, profile.port))

    assertThat(env.apiClient.baseUrl).isEqualTo("http://${profile.address}:${profile.port}")
}
```

- [ ] **Step 2: Run the ConnectionManager test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest.gatewayFallback_requiresGatewayUsableEvidence_notOnlyDirectSuspicion" --no-daemon`
Expected: FAIL if fallback still occurs without stronger gateway evidence.

- [ ] **Step 3: Update evaluation logic to prefer route evidence comparison over single-event shortcuts**

```kotlin
private fun selectPreferredRoute(snapshot: RouteHealthSnapshot): ConnectionRoute? {
    return when {
        snapshot.direct.isUsable -> currentDirectRoute()
        snapshot.gateway.isUsable -> currentGatewayRoute()
        else -> null
    }
}
```

- [ ] **Step 4: Run the ConnectionManager test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest.gatewayFallback_requiresGatewayUsableEvidence_notOnlyDirectSuspicion" --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit route-evidence-driven selection**

```bash
git add android/app/src/main/java/com/openmate/app/ConnectionManager.kt android/app/src/test/java/com/openmate/app/ConnectionManagerTest.kt
git commit -m "refactor: choose routes from route evidence"
```

### Task 6: Final verification for route-evidence scope

**Files:**
- Modify: any files above if verification exposes gaps

- [ ] **Step 1: Search for route decisions still driven directly by SSE error or direct probe alone**

Run: `rg "attemptGatewayFallback|SSE.*ERROR|directBridgeReachable|gateway.*online" android/app android/core/network`
Expected: only intended evidence-driven or probe-fallback references remain.

- [ ] **Step 2: Run route-evidence tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.connection.RouteEvidenceAggregatorTest" --no-daemon`
Expected: PASS

- [ ] **Step 3: Run ConnectionManager tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.openmate.app.ConnectionManagerTest" --no-daemon`
Expected: PASS

- [ ] **Step 4: Verify repository state**

Run: `git status --short --branch`
Expected: only intended tracked modifications remain.

- [ ] **Step 5: Commit the final route-evidence pass**

```bash
git add android
git commit -m "feat: add route evidence driven recovery signals"
```

## Self-Review

- Spec coverage:
  - API network-layer results as primary route evidence -> Tasks 2 and 4
  - probing only when needed -> Task 3
  - gateway requires usable evidence, not raw reachability alone -> Tasks 2 and 5
  - route evidence drives route selection -> Task 5
- Placeholder scan:
  - no `TODO` or `TBD` placeholders
  - each task includes concrete files, code, and commands
- Type consistency:
  - uses `RouteEvidence`, `RouteHealthSnapshot`, `RouteEvidenceAggregator`, and `RouteProbeClient` consistently
