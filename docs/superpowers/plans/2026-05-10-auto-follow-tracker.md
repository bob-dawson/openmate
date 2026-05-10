# AutoFollowTracker 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现一个 UI 无关的纯状态机 `AutoFollowTracker`，由外部驱动事件输入，输出是否跟随底部和是否需要滚动，通过单元测试覆盖所有场景。

**Architecture:** 纯 Kotlin 类，无 Android/Compose 依赖。事件方法驱动状态转换，输出 `shouldFollow` 和脉冲式 `consumeShouldScrollToBottom()`。放在 `core:common` 模块。

**Tech Stack:** Kotlin, JUnit 4, Google Truth

**Spec:** `D:\openmate\docs\消息自动跟随滚动设计.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `android/core/common/src/main/java/com/openmate/core/common/AutoFollowTracker.kt` | 纯状态机类 |
| Create | `android/core/common/src/test/java/com/openmate/core/common/AutoFollowTrackerTest.kt` | 30 个测试用例 |

---

### Task 1: 创建 AutoFollowTracker 类骨架 + 基础测试

**Files:**
- Create: `android/core/common/src/main/java/com/openmate/core/common/AutoFollowTracker.kt`
- Create: `android/core/common/src/test/java/com/openmate/core/common/AutoFollowTrackerTest.kt`

- [ ] **Step 1: 写失败测试（场景 1-2: 初始状态 + 消息数为0）**

```kotlin
package com.openmate.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoFollowTrackerTest {

    @Test
    fun initial_shouldFollowIsTrue() {
        val tracker = AutoFollowTracker()
        assertThat(tracker.shouldFollow).isTrue()
    }

    @Test
    fun initial_consumeShouldScrollToBottomIsFalse() {
        val tracker = AutoFollowTracker()
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun messagesChanged_zeroCount_noScrollRequest() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(0, isLoading = false)
        assertThat(tracker.shouldFollow).isTrue()
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :core:common:test --tests "com.openmate.core.common.AutoFollowTrackerTest" --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD|PASSED|FAILED"`

Expected: FAILED (class not found)

- [ ] **Step 3: 写最小实现**

```kotlin
package com.openmate.core.common

class AutoFollowTracker {

    var shouldFollow: Boolean = true
        private set

    private var prevMessageCount: Int = 0
    private var pendingScrollRequest: Boolean = false
    private var isAutoScrolling: Boolean = false
    private var isKeyboardAnimating: Boolean = false
    private var isUserNavigating: Boolean = false

    fun onMessagesChanged(count: Int, isLoading: Boolean) {
        if (count == 0) {
            prevMessageCount = 0
            return
        }
        if (prevMessageCount == 0) {
            prevMessageCount = count
            if (!isLoading) {
                requestScroll()
            }
            return
        }
        prevMessageCount = count
        if (shouldFollow && !isLoading && !isUserNavigating) {
            requestScroll()
        }
    }

    fun onScrollStarted(canScrollForward: Boolean) {
        if (isAutoScrolling || isKeyboardAnimating || isUserNavigating) return
        if (canScrollForward) {
            shouldFollow = false
        }
    }

    fun onScrollStopped(canScrollForward: Boolean) {
        if (isAutoScrolling || isKeyboardAnimating || isUserNavigating) return
        if (!canScrollForward) {
            shouldFollow = true
        }
    }

    fun onKeyboardAnimationStarted() {
        isKeyboardAnimating = true
    }

    fun onKeyboardAnimationEnded() {
        isKeyboardAnimating = false
        if (shouldFollow) {
            requestScroll()
        }
    }

    fun onNavigateToMessage() {
        shouldFollow = false
        isUserNavigating = true
    }

    fun onNavigateComplete() {
        isUserNavigating = false
    }

    fun onRequestFollow() {
        shouldFollow = true
        requestScroll()
    }

    fun onAutoScrollStarted() {
        isAutoScrolling = true
    }

    fun onAutoScrollEnded() {
        isAutoScrolling = false
    }

    fun onContentUpdated() {
        if (shouldFollow && !isUserNavigating) {
            requestScroll()
        }
    }

    fun consumeShouldScrollToBottom(): Boolean {
        if (pendingScrollRequest) {
            pendingScrollRequest = false
            return true
        }
        return false
    }

    private fun requestScroll() {
        pendingScrollRequest = true
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :core:common:test --tests "com.openmate.core.common.AutoFollowTrackerTest" --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD|PASSED|FAILED"`

Expected: PASSED

---

### Task 2: 初始加载测试（场景 3-5）

**Files:**
- Modify: `android/core/common/src/test/java/com/openmate/core/common/AutoFollowTrackerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test
    fun initialLoad_triggersScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        assertThat(tracker.shouldFollow).isTrue()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun initialLoad_loading_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = true)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun initialLoad_loadingThenContentUpdated_triggersScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = true)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
        tracker.onContentUpdated()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }
```

- [ ] **Step 2: 运行测试确认通过**

Run: `.\gradlew.bat :core:common:test --tests "com.openmate.core.common.AutoFollowTrackerTest" --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD|PASSED|FAILED"`

Expected: PASSED (实现已在 Task 1 完成)

---

### Task 3: 新消息自动跟随测试（场景 6-8）

**Files:**
- Modify: `android/core/common/src/test/java/com/openmate/core/common/AutoFollowTrackerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test
    fun newMessage_following_triggersScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onMessagesChanged(6, isLoading = false)
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun newMessage_loading_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onMessagesChanged(6, isLoading = true)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun newMessage_notFollowing_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onScrollStarted(canScrollForward = true)
        tracker.onMessagesChanged(6, isLoading = false)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }
```

- [ ] **Step 2: 运行测试确认通过**

Run: `.\gradlew.bat :core:common:test --tests "com.openmate.core.common.AutoFollowTrackerTest" --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD|PASSED|FAILED"`

Expected: PASSED

---

### Task 4: 用户滚动测试（场景 9-11）

**Files:**
- Modify: `android/core/common/src/test/java/com/openmate/core/common/AutoFollowTrackerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test
    fun userScrollUp_stopsFollowing() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onScrollStarted(canScrollForward = true)
        assertThat(tracker.shouldFollow).isFalse()
    }

    @Test
    fun userScrollBackToBottom_resumesFollowing() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onScrollStarted(canScrollForward = true)
        assertThat(tracker.shouldFollow).isFalse()
        tracker.onScrollStopped(canScrollForward = false)
        assertThat(tracker.shouldFollow).isTrue()
    }

    @Test
    fun userScrolledUp_newMessage_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onScrollStarted(canScrollForward = true)
        tracker.onMessagesChanged(6, isLoading = false)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }
```

- [ ] **Step 2: 运行测试确认通过**

Run: `.\gradlew.bat :core:common:test --tests "com.openmate.core.common.AutoFollowTrackerTest" --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD|PASSED|FAILED"`

Expected: PASSED

---

### Task 5: 自动滚动保护测试（场景 12-13）

**Files:**
- Modify: `android/core/common/src/test/java/com/openmate/core/common/AutoFollowTrackerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test
    fun autoScrollStarted_ignoresScrollStarted() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onAutoScrollStarted()
        tracker.onScrollStarted(canScrollForward = true)
        tracker.onAutoScrollEnded()
        assertThat(tracker.shouldFollow).isTrue()
    }

    @Test
    fun autoScrollStarted_ignoresScrollStopped() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onScrollStarted(canScrollForward = true)
        assertThat(tracker.shouldFollow).isFalse()

        tracker.onAutoScrollStarted()
        tracker.onScrollStopped(canScrollForward = false)
        tracker.onAutoScrollEnded()
        assertThat(tracker.shouldFollow).isFalse()
    }
```

- [ ] **Step 2: 运行测试确认通过**

Run: `.\gradlew.bat :core:common:test --tests "com.openmate.core.common.AutoFollowTrackerTest" --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD|PASSED|FAILED"`

Expected: PASSED

---

### Task 6: 键盘动画测试（场景 14-17）

**Files:**
- Modify: `android/core/common/src/test/java/com/openmate/core/common/AutoFollowTrackerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test
    fun keyboardAnimating_ignoresScrollStarted() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onKeyboardAnimationStarted()
        tracker.onScrollStarted(canScrollForward = true)
        tracker.onKeyboardAnimationEnded()
        assertThat(tracker.shouldFollow).isTrue()
    }

    @Test
    fun keyboardEnded_following_triggersScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onKeyboardAnimationStarted()
        tracker.onKeyboardAnimationEnded()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun keyboardEnded_notFollowing_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onScrollStarted(canScrollForward = true)
        assertThat(tracker.shouldFollow).isFalse()
        tracker.onKeyboardAnimationStarted()
        tracker.onKeyboardAnimationEnded()
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun keyboardAnimating_ignoresScrollStopped() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onKeyboardAnimationStarted()
        tracker.onScrollStopped(canScrollForward = false)
        tracker.onKeyboardAnimationEnded()
        assertThat(tracker.shouldFollow).isTrue()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }
```

- [ ] **Step 2: 运行测试确认通过**

Run: `.\gradlew.bat :core:common:test --tests "com.openmate.core.common.AutoFollowTrackerTest" --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD|PASSED|FAILED"`

Expected: PASSED

---

### Task 7: 消息定位测试（场景 18-23）

**Files:**
- Modify: `android/core/common/src/test/java/com/openmate/core/common/AutoFollowTrackerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test
    fun navigateToMessage_stopsFollowing() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onNavigateToMessage()
        assertThat(tracker.shouldFollow).isFalse()
    }

    @Test
    fun navigating_ignoresScrollStopped() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onNavigateToMessage()
        tracker.onScrollStopped(canScrollForward = false)
        assertThat(tracker.shouldFollow).isFalse()
    }

    @Test
    fun navigateComplete_staysNotFollowing() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onNavigateToMessage()
        tracker.onNavigateComplete()
        assertThat(tracker.shouldFollow).isFalse()
    }

    @Test
    fun afterNavigate_newMessage_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onNavigateToMessage()
        tracker.onNavigateComplete()
        tracker.onMessagesChanged(6, isLoading = false)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun afterNavigate_scrollBackToBottom_resumesFollowing() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onNavigateToMessage()
        tracker.onNavigateComplete()
        tracker.onScrollStopped(canScrollForward = false)
        assertThat(tracker.shouldFollow).isTrue()
    }

    @Test
    fun afterNavigate_requestFollow_resumesAndScrolls() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onNavigateToMessage()
        tracker.onNavigateComplete()
        tracker.onRequestFollow()
        assertThat(tracker.shouldFollow).isTrue()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }
```

- [ ] **Step 2: 运行测试确认通过**

Run: `.\gradlew.bat :core:common:test --tests "com.openmate.core.common.AutoFollowTrackerTest" --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD|PASSED|FAILED"`

Expected: PASSED

---

### Task 8: 内容更新测试（场景 24-26）

**Files:**
- Modify: `android/core/common/src/test/java/com/openmate/core/common/AutoFollowTrackerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test
    fun contentUpdated_following_triggersScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onContentUpdated()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun contentUpdated_notFollowing_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onScrollStarted(canScrollForward = true)
        tracker.onContentUpdated()
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun contentUpdated_navigating_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        tracker.consumeShouldScrollToBottom()
        tracker.onNavigateToMessage()
        tracker.onContentUpdated()
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }
```

- [ ] **Step 2: 运行测试确认通过**

Run: `.\gradlew.bat :core:common:test --tests "com.openmate.core.common.AutoFollowTrackerTest" --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD|PASSED|FAILED"`

Expected: PASSED

---

### Task 9: 脉冲消费 + 组合场景测试（场景 27-30）

**Files:**
- Modify: `android/core/common/src/test/java/com/openmate/core/common/AutoFollowTrackerTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
    @Test
    fun consumeScrollRequest_pulseResetsAfterConsume() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun fullFlow_load_scrollUp_newMessage_scrollBack_newMessage() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()

        tracker.onScrollStarted(canScrollForward = true)
        assertThat(tracker.shouldFollow).isFalse()

        tracker.onMessagesChanged(6, isLoading = false)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()

        tracker.onScrollStopped(canScrollForward = false)
        assertThat(tracker.shouldFollow).isTrue()

        tracker.onMessagesChanged(7, isLoading = false)
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun fullFlow_load_navigate_complete_newMessage_button_newMessage() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()

        tracker.onNavigateToMessage()
        tracker.onNavigateComplete()
        assertThat(tracker.shouldFollow).isFalse()

        tracker.onMessagesChanged(6, isLoading = false)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()

        tracker.onRequestFollow()
        assertThat(tracker.shouldFollow).isTrue()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()

        tracker.onMessagesChanged(7, isLoading = false)
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun nested_autoScrollAndKeyboard_stateUnchanged() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, isLoading = false)
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()

        tracker.onAutoScrollStarted()
        tracker.onKeyboardAnimationStarted()
        tracker.onScrollStarted(canScrollForward = true)
        tracker.onScrollStopped(canScrollForward = false)
        tracker.onKeyboardAnimationEnded()
        tracker.onAutoScrollEnded()

        assertThat(tracker.shouldFollow).isTrue()
    }
```

- [ ] **Step 2: 运行全部测试确认通过**

Run: `.\gradlew.bat :core:common:test --tests "com.openmate.core.common.AutoFollowTrackerTest" --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD|PASSED|FAILED|Tests"`

Expected: BUILD SUCCESSFUL, 30 tests PASSED

---

### Task 10: 最终全量测试验证

- [ ] **Step 1: 运行 core:common 全部测试**

Run: `.\gradlew.bat :core:common:test --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD|PASSED|FAILED|Tests"`

Expected: BUILD SUCCESSFUL, 所有测试 PASSED
