# Sub-session Permission/Question Escalation to Parent Session

## Problem

When a sub-session (task tool) requires permission or question confirmation, the request only appears inside the `SubtaskDetailScreen`. Users viewing the parent session are unaware and the conversation appears stuck — the sub-task spinner keeps running with no visible action item.

## Solution

When a `TaskToolLine` (toolName="task") is in RUNNING state, filter `pendingPermissions`/`pendingQuestions` by `sessionID` matching the sub-session, and render `PermissionCard`/`QuestionCard` inline below the TaskToolLine in the parent session chat.

## Key Insight

The API `GET /permission?directory=...` and `GET /question?directory=...` are directory-scoped. Since parent and sub-sessions share the same directory, the parent session's `pendingPermissions`/`pendingQuestions` already contain sub-session requests. The `sessionID` field on each request identifies which session it belongs to. No API or repository changes needed.

## Data Flow

```
1. SSE event: permission.asked (sub-session)
2. PermissionEventHandler → DB insert (sessionID = sub-session ID)
3. PermissionRepository.observePending() → Flow<List<PermissionRequest>>
4. SessionDetailViewModel._pendingPermissions (already contains sub-session requests)
5. PartColumn renders TaskToolLine for running "task" tool
6. Filter pendingPermissions by sessionID == subtaskSessionID
7. Render PermissionCard inline below TaskToolLine
8. User taps Allow/Deny → ViewModel.replyPermission() → API call
9. SSE event: permission.replied → DB delete → Flow update → Card disappears
```

Same flow applies for QuestionRequest.

## Implementation

### File Changes

| File | Change |
|------|--------|
| `PartRenderer.kt` | `TaskToolLine` gains parameters for sub-session pending perms/questions + callbacks; renders cards below the task row when `isRunning` |
| `PartRenderer.kt` | `PartColumn` filters `pendingPermissions`/`pendingQuestions` by `subtaskSessionID` when calling `TaskToolLine` |

No changes to ViewModel, Repository, DAO, or API layers.

### `TaskToolLine` Signature Change

```kotlin
@Composable
private fun TaskToolLine(
    item: DisplayItem.ToolItem,
    summary: ToolSummary,
    onNavigate: (subtaskSessionID: String, title: String) -> Unit,
    subtaskPermissions: List<PermissionRequest>,
    subtaskQuestions: List<QuestionRequest>,
    onReplyPermission: (String, PermissionReply, String?) -> Unit,
    onReplyQuestion: (String, List<List<String>>) -> Unit,
    onRejectQuestion: (String) -> Unit,
)
```

### Rendering Logic

```
TaskToolLine(...) {
    // Existing row: spinner + "subtask" + description + "→"
    Row { ... }

    // New: inline cards for sub-session pending requests
    if (isRunning) {
        subtaskPermissions.forEach { perm ->
            PermissionCard(request = perm, onReply = { reply, msg ->
                onReplyPermission(perm.id, reply, msg)
            })
        }
        subtaskQuestions.forEach { question ->
            QuestionCard(
                questions = question.questions,
                matchedRequest = question,
                onReply = { answers -> onReplyQuestion(question.id, answers) },
                onReject = { onRejectQuestion(question.id) },
            )
        }
    }
}
```

### `PartColumn` Filtering

When rendering a `ToolItem` with `toolName == "task"` that is RUNNING or COMPLETED, extract `subtaskSessionID` from metadata/result, then filter:

```kotlin
val subtaskPerms = subtaskSessionID?.let { sid ->
    pendingPermissions.filter { it.sessionID == sid }
} ?: emptyList()

val subtaskQuestions = subtaskSessionID?.let { sid ->
    pendingQuestions.filter { it.sessionID == sid }
} ?: emptyList()
```

## Edge Cases

- **Sub-session ID not yet available** (metadata doesn't contain sessionId yet): Skip filtering, no cards shown. Once metadata updates and recomposition happens, cards appear automatically.
- **Permission already replied**: SSE `permission.replied` deletes from DB, Flow updates, card disappears.
- **Parent session's own permission requests**: Still matched by `callID` in the existing logic. Sub-session requests matched by `sessionID` in TaskToolLine are a separate, non-overlapping set.
- **Multiple concurrent sub-tasks**: Each TaskToolLine filters by its own `subtaskSessionID`, so requests are attributed correctly.
