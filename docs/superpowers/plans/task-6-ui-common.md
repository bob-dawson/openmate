# Task 6: Core UI + Common

## Goal

Build shared UI components, theme, and common utilities used across feature modules. Lives in `core/ui` and `core/common`.

## core/ui — Package: `com.openmate.core.ui`

### Theme

Material 3 dark theme based on opencode's color palette:
- Primary: Blue/teal accent (from opencode UI)
- Background: Dark (#0a0a0a or similar)
- Surface: Slightly lighter dark
- On-surface: White/light gray text
- Error: Red accent

**Files:**
- `theme/Color.kt` — Color constants
- `theme/Type.kt` — Typography
- `theme/Theme.kt` — MaterialTheme composable (dark only for MVP)
- `theme/Shape.kt` — Shapes

### Shared Composables

| Component | Purpose |
|-----------|---------|
| `LoadingIndicator` | Centered circular progress |
| `ErrorView` | Error message with retry button |
| `EmptyStateView` | Icon + message for empty lists |
| `TopBar` | Standard top app bar with title + optional actions |
| `MessageBubble` | Chat message bubble (user vs assistant styling) |
| `StreamingText` | Text composable that animates streaming content |
| `PermissionCard` | Card showing permission request with approve/deny buttons |
| `QuestionCard` | Card showing question with selectable options |

### Preview Providers

- `OpenMatePreview` annotation for `@Preview`
- Sample data for previews

## core/common — Package: `com.openmate.core.common`

### Result wrapper

```
sealed interface Result<out T> {
    data class Success<T>(val data: T) : Result<T>
    data class Error(val exception: Throwable) : Result<Nothing>
}
```

### Extensions

- `Flow<T>.asResult(): Flow<Result<T>>` — converts Flow to Result-wrapped Flow
- Time formatting extensions (relative time strings)
- String sanitization for display

### Coroutine utilities

- `AppDispatchers` — injectable dispatcher wrappers (Main, IO, Default)
- `CoroutineScopeProvider` — for testing

## Files

| File | Module | Purpose |
|------|--------|---------|
| `theme/Color.kt` | core/ui | |
| `theme/Type.kt` | core/ui | |
| `theme/Theme.kt` | core/ui | |
| `theme/Shape.kt` | core/ui | |
| `component/LoadingIndicator.kt` | core/ui | |
| `component/ErrorView.kt` | core/ui | |
| `component/EmptyStateView.kt` | core/ui | |
| `component/TopBar.kt` | core/ui | |
| `component/MessageBubble.kt` | core/ui | |
| `component/StreamingText.kt` | core/ui | |
| `component/PermissionCard.kt` | core/ui | |
| `component/QuestionCard.kt` | core/ui | |
| `preview/OpenMatePreview.kt` | core/ui | |
| `Result.kt` | core/common | |
| `FlowExtensions.kt` | core/common | |
| `TimeExtensions.kt` | core/common | |
| `AppDispatchers.kt` | core/common | |

## Verification

1. `./gradlew :core:ui:test` passes
2. `./gradlew :core:common:test` passes
3. Preview composables render correctly (screenshot test or visual inspection)
4. `Result` and `Flow.asResult()` unit tests pass
