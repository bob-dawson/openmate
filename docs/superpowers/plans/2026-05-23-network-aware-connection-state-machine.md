# Network-Aware Connection State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add ProbingNetwork and WaitingForNetwork states to the connection state machine so that WiFi-only direct probing is skipped on mobile networks, and no connection attempts are made when no network is available.

**Architecture:** Two new states inserted before ProbingDirect. ProbingNetwork checks network type via ConnectivityManager and immediately transitions. WaitingForNetwork is a passive state that only responds to NetworkAvailable/AppForegrounded events. All existing transitions that previously went to ProbingDirect now go to ProbingNetwork instead.

**Tech Stack:** Kotlin, KStateMachine, Android ConnectivityManager

---

### Task 1: Add ProbingNetwork and WaitingForNetwork states to ConnState

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/connection/v2/ConnectionActor.kt:15-58`

- [ ] **Step 1: Add new state data classes**

Add two new states inside `ConnState` sealed class, before `ProbingDirect`:

```kotlin
data class ProbingNetwork(
    val profile: ServerProfile,
    val attempt: Int = 0,
) : ConnState("ProbingNetwork")

data class WaitingForNetwork(
    val profile: ServerProfile,
    val attempt: Int = 0,
) : ConnState("WaitingForNetwork")
```

- [ ] **Step 2: Add logText entries**

Add to `logText()`:

```kotlin
is ProbingNetwork -> "ProbingNetwork(profile=${profile.id}, attempt=$attempt)"
is WaitingForNetwork -> "WaitingForNetwork(profile=${profile.id}, attempt=$attempt)"
```

- [ ] **Step 3: Add dummy state instances**

Add to `ConnectionActor` class body:

```kotlin
private val probingNetwork = ConnState.ProbingNetwork(DUMMY_PROFILE)
private val waitingForNetwork = ConnState.WaitingForNetwork(DUMMY_PROFILE)
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/openmate/app/connection/v2/ConnectionActor.kt
git commit -m "feat(connection): add ProbingNetwork and WaitingForNetwork state definitions"
```

---

### Task 2: Add NetworkCheck effect and event

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/connection/v2/ConnEffect.kt`
- Modify: `android/app/src/main/java/com/openmate/app/connection/v2/ConnEvent.kt`
- Modify: `android/app/src/main/java/com/openmate/app/connection/v2/EffectExecutor.kt`

- [ ] **Step 1: Add CheckNetwork effect**

Add to `ConnEffect` sealed class:

```kotlin
data class CheckNetwork(val profile: ServerProfile, val attempt: Int) : ConnEffect()
```

- [ ] **Step 2: Add NetworkCheckResult event**

Add to `ConnEvent` sealed class:

```kotlin
data class NetworkCheckResult(val isWifi: Boolean, val hasNetwork: Boolean) : ConnEvent()
```

Add to `logText()`:

```kotlin
is NetworkCheckResult -> "NetworkCheckResult isWifi=$isWifi hasNetwork=$hasNetwork"
```

- [ ] **Step 3: Implement CheckNetwork in EffectExecutor**

Add `ConnectivityManager` dependency to `EffectExecutor` constructor:

```kotlin
private val connectivityManager: ConnectivityManager,
```

Add `execute` branch:

```kotlin
is ConnEffect.CheckNetwork -> checkNetwork(effect.profile, effect.attempt)
```

Add implementation:

```kotlin
private fun checkNetwork(profile: ServerProfile, attempt: Int) {
    scope.launch {
        val activeNetwork = connectivityManager.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val hasNetwork = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        logStore.log(SyncLogLevel.Info, SyncLogCategory.Connection, title = "网络检测", message = "hasNetwork=$hasNetwork isWifi=$isWifi")
        sendEvent(ConnEvent.NetworkCheckResult(isWifi = isWifi, hasNetwork = hasNetwork))
    }
}
```

Add imports:

```kotlin
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/openmate/app/connection/v2/ConnEffect.kt android/app/src/main/java/com/openmate/app/connection/v2/ConnEvent.kt android/app/src/main/java/com/openmate/app/connection/v2/EffectExecutor.kt
git commit -m "feat(connection): add CheckNetwork effect and NetworkCheckResult event"
```

---

### Task 3: Wire ProbingNetwork and WaitingForNetwork states into the state machine

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/connection/v2/ConnectionActor.kt:82-358`

This is the core change. Replace all transitions that previously targeted `probingDirect` to target `probingNetwork` instead, and add the two new state definitions.

- [ ] **Step 1: Add ProbingNetwork state definition**

Add after `addInitialState(idle)` block, before `addState(probingDirect)`:

```kotlin
addState(probingNetwork) {
    onEntry {
        val s = _state.value as ConnState.ProbingNetwork
        onEffect(ConnEffect.CheckNetwork(s.profile, s.attempt))
    }
    transition<ConnEvent.NetworkCheckResult> {
        onTriggered {
            val s = _state.value as ConnState.ProbingNetwork
            val e = it.event as ConnEvent.NetworkCheckResult
            when {
                !e.hasNetwork -> {
                    _state.value = ConnState.WaitingForNetwork(s.profile, s.attempt)
                    targetState = waitingForNetwork
                }
                e.isWifi -> {
                    _state.value = ConnState.ProbingDirect(s.profile, s.attempt)
                    targetState = probingDirect
                }
                else -> {
                    if (s.profile.instanceId.isNotEmpty()) {
                        _state.value = ConnState.ProbingGateway(s.profile, s.attempt)
                        targetState = probingGateway
                    } else {
                        _state.value = ConnState.Failed(s.profile, reason = "No WiFi and no gateway configured")
                        targetState = failed
                    }
                }
            }
        }
    }
    transition<ConnEvent.Disconnect> {
        targetState = idle
        onTriggered {
            onEffect(ConnEffect.ClearApiClient)
            val s = _state.value as ConnState.ProbingNetwork
            _state.value = ConnState.Idle(s.profile)
        }
    }
}
```

- [ ] **Step 2: Add WaitingForNetwork state definition**

Add after the ProbingNetwork block:

```kotlin
addState(waitingForNetwork) {
    transition<ConnEvent.NetworkAvailable> {
        targetState = probingNetwork
        onTriggered {
            val s = _state.value as ConnState.WaitingForNetwork
            _state.value = ConnState.ProbingNetwork(s.profile, s.attempt)
        }
    }
    transition<ConnEvent.AppForegrounded> {
        targetState = probingNetwork
        onTriggered {
            val s = _state.value as ConnState.WaitingForNetwork
            _state.value = ConnState.ProbingNetwork(s.profile, s.attempt)
        }
    }
    transition<ConnEvent.Disconnect> {
        targetState = idle
        onTriggered {
            onEffect(ConnEffect.ClearApiClient)
            val s = _state.value as ConnState.WaitingForNetwork
            _state.value = ConnState.Idle(s.profile)
        }
    }
}
```

- [ ] **Step 3: Change Idle → Connect transition to target probingNetwork**

In the `idle` state, change:

```kotlin
transition<ConnEvent.Connect> {
    targetState = probingDirect
```

to:

```kotlin
transition<ConnEvent.Connect> {
    targetState = probingNetwork
    onTriggered {
        val e = it.event as ConnEvent.Connect
        _state.value = ConnState.ProbingNetwork(e.profile)
    }
}
```

- [ ] **Step 4: Change Recovering → BackoffExpired transition to target probingNetwork**

In the `recovering` state, change both `BackoffExpired` and `NetworkAvailable` transitions:

```kotlin
transition<ConnEvent.BackoffExpired> {
    targetState = probingNetwork
    onTriggered {
        val s = _state.value as ConnState.Recovering
        _state.value = ConnState.ProbingNetwork(s.profile, attempt = s.attempt)
    }
}
transition<ConnEvent.NetworkAvailable> {
    targetState = probingNetwork
    onTriggered {
        val s = _state.value as ConnState.Recovering
        _state.value = ConnState.ProbingNetwork(s.profile, attempt = s.attempt)
    }
}
```

- [ ] **Step 5: Change Failed state transitions to target probingNetwork**

In the `failed` state, change `NetworkAvailable`, `AppForegrounded`, and `Retry` transitions:

```kotlin
transition<ConnEvent.NetworkAvailable> {
    targetState = probingNetwork
    onTriggered {
        val s = _state.value as ConnState.Failed
        _state.value = ConnState.ProbingNetwork(s.profile)
    }
}
transition<ConnEvent.AppForegrounded> {
    targetState = probingNetwork
    onTriggered {
        val s = _state.value as ConnState.Failed
        _state.value = ConnState.ProbingNetwork(s.profile)
    }
}
transition<ConnEvent.Retry> {
    targetState = probingNetwork
    onTriggered {
        val s = _state.value as ConnState.Failed
        _state.value = ConnState.ProbingNetwork(s.profile)
    }
}
```

- [ ] **Step 6: Change NeedsRepair → RepairCompleted transition to target probingNetwork**

```kotlin
transition<ConnEvent.RepairCompleted> {
    guard = { (event as ConnEvent.RepairCompleted).profileId == (_state.value as ConnState.NeedsRepair).profile.id }
    targetState = probingNetwork
    onTriggered {
        val s = _state.value as ConnState.NeedsRepair
        _state.value = ConnState.ProbingNetwork(s.profile)
    }
}
```

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/openmate/app/connection/v2/ConnectionActor.kt
git commit -m "feat(connection): wire ProbingNetwork and WaitingForNetwork into state machine"
```

---

### Task 4: Provide ConnectivityManager to EffectExecutor via Hilt

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`

- [ ] **Step 1: Add ConnectivityManager parameter to EffectExecutor construction**

In `ConnectionManager`, add `ConnectivityManager` injection and pass it to `EffectExecutor`:

```kotlin
@ApplicationContext private val context: Context,
```

(already injected — use it to get ConnectivityManager)

Find where `EffectExecutor` is constructed and add:

```kotlin
val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
EffectExecutor(..., connectivityManager = cm)
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/openmate/app/ConnectionManager.kt
git commit -m "feat(connection): provide ConnectivityManager to EffectExecutor"
```

---

### Task 5: Update ConnectionManager event mapping for WaitingForNetwork

**Files:**
- Modify: `android/app/src/main/java/com/openmate/app/ConnectionManager.kt`

- [ ] **Step 1: Update SSE status mapping**

Find the `_connectionStatus` mapping from `ConnState` and add entries for the new states:

```kotlin
is ConnState.ProbingNetwork -> ConnectionStatus.CONNECTING
is ConnState.WaitingForNetwork -> ConnectionStatus.DISCONNECTED
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/openmate/app/ConnectionManager.kt
git commit -m "feat(connection): map ProbingNetwork/WaitingForNetwork to connection status"
```

---

### Task 6: Build and verify

**Files:**
- All modified files

- [ ] **Step 1: Build debug APK**

Run: `gradle_runner_run_gradle` with args `[:app:assembleDebug]`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Install and smoke test**

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Verify on device:
1. With WiFi: should go ProbingNetwork → ProbingDirect → ... (same as before)
2. With mobile data only: should go ProbingNetwork → ProbingGateway (skip direct probe)
3. With airplane mode: should go ProbingNetwork → WaitingForNetwork (wait for network)

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix(connection): fixes from smoke test"
```