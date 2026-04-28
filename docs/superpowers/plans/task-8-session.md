# Task 8: Feature Session (Chat + Permissions/Questions)

## Goal

Build the core chat experience: session list, message streaming, permission/question interaction. This is the main feature of the app.

## Package

`com.openmate.feature.session`

## Screens

### SessionListScreen

Shows sessions for the connected server.

**Layout:**
- Top bar: shows profile name, connection status dot
- "New Session" FAB or button
- List of session cards, each showing:
  - Session title (or "Untitled" if empty)
  - Directory/project path
  - Last updated time (relative)
  - Status indicator (compacting, idle)
- Pull-to-refresh: re-fetch from API
- Tap session → navigate to SessionDetailScreen
- Long press → delete option

**ViewModel: SessionListViewModel**
- `sessions: StateFlow<List<Session>>` — observed from DB via repository
- `connectionStatus: StateFlow<ConnectionStatus>`
- `refresh()` — force refresh from API
- `createSession()` — create new, navigate to it
- `deleteSession(id: String)`

### SessionDetailScreen

The main chat screen. Shows message history and input.

**Layout:**
- Top bar: session title, status indicator, overflow menu (abort, delete)
- Message list (LazyColumn):
  - User messages: right-aligned, colored bubble
  - Assistant messages: left-aligned, dark bubble
  - Each message renders its Parts in order:
    - `TextPart`: markdown-like text (basic formatting, not full renderer)
    - `ToolInvocationPart`: collapsible card showing tool name + state
    - `ReasoningPart`: dimmed/collapsible text
    - `StepStartPart`/`StepFinishPart`: subtle step indicators
    - `FilePart`/`SnapshotPart`: file path chip
    - `PatchPart`: collapsible diff view
    - Other parts: fallback text display
- Auto-scroll to bottom on new messages
- Load more: when scrolled to top, load older messages using cursor-based pagination (`MessageRepository.getMessages(sessionID, limit=80, before=cursor)`)
- Streaming text appears character-by-character
- Input bar at bottom:
  - Text field for message
  - Send button (disabled while streaming)
  - Abort button (visible during streaming)

**ViewModel: SessionDetailViewModel**
- `sessionID: String`
- `messages: StateFlow<List<Message>>` — observed from DB
- `pendingPermissions: StateFlow<List<PermissionRequest>>`
- `pendingQuestions: StateFlow<List<QuestionRequest>>`
- `isStreaming: StateFlow<Boolean>`
- `sendMessage(text: String)` — calls `MessageRepository.sendMessage()`, collects flow
- `abort()` — calls `SessionRepository.abortSession()`
- `replyPermission(id, reply, message?)` — calls `PermissionRepository.reply()`
- `replyQuestion(id, answers)` / `rejectQuestion(id)`

### PermissionDialog

Bottom sheet or dialog shown when a permission request arrives.

**Layout:**
- Tool name
- Tool input (scrollable JSON or formatted text)
- "Allow" and "Deny" buttons
- Optional: "Always allow this tool" checkbox (future)

**Triggered by:** `pendingPermissions` StateFlow becoming non-empty while on SessionDetailScreen.

### QuestionDialog

Bottom sheet or dialog shown when a question arrives.

**Layout:**
- Question text
- Options (radio buttons or chips for single-select, checkboxes for multi-select)
- "Submit" button
- "Reject" button

**Triggered by:** `pendingQuestions` StateFlow becoming non-empty.

## Message Rendering Strategy

Messages come as `List<Message>` where each `Message` has `List<Part>`. Render parts sequentially:

1. **TextPart**: Render as `Text` composable. For MVP, plain text with monospace for code-like content. No full markdown renderer needed yet.
2. **ToolInvocationPart**:
   - `CALL` state: "Using tool_name..." (dimmed)
   - `PARTIAL` state: "Running tool_name..." with spinner
   - `RESULT` state: Collapsible card with tool name, expandable to show args + result
3. **ReasoningPart**: Dimmed text, collapsed by default, tap to expand
4. **Other parts**: Simple text fallback

## Streaming UX

When `sendMessage()` is called:
1. Optimistically add a user message to DB
2. Call `MessageRepository.sendMessage()` which returns `Flow<Part>`
3. Set `isStreaming = true`
4. As Parts arrive via the flow, they're upserted into DB by the repository
5. The `messages` StateFlow auto-updates from DB, UI re-renders
6. When flow completes, set `isStreaming = false`

The SSE stream also delivers `message.part.delta` events that update existing parts' text in-place, creating the typing/streaming effect.

## Navigation

- From `SessionListScreen` → `SessionDetailScreen(sessionID)`
- Back from `SessionDetailScreen` → `SessionListScreen`
- Permission/Question dialogs appear as overlays on `SessionDetailScreen`

## Files

| File | Purpose |
|------|---------|
| `SessionListScreen.kt` | Session list UI |
| `SessionListViewModel.kt` | |
| `SessionDetailScreen.kt` | Chat UI |
| `SessionDetailViewModel.kt` | |
| `PermissionDialog.kt` | Permission request overlay |
| `QuestionDialog.kt` | Question request overlay |
| `component/MessageItem.kt` | Single message composable |
| `component/PartRenderer.kt` | Renders Part based on type |
| `component/ToolInvocationCard.kt` | Tool call collapsible card |
| `component/StreamingText.kt` | Animated streaming text |
| `component/ChatInputBar.kt` | Message input + send/abort |
| `SessionNavigation.kt` | NavGraph routes |
| `SessionModule.kt` | Hilt DI |

## Verification

1. `./gradlew :feature:session:test` passes
2. ViewModel tests with fake repositories:
   - Session list loads and updates on DB changes
   - Send message triggers streaming flow
   - Permission reply calls API + removes from DB
   - Question reply/reject calls API + removes from DB
   - Abort calls API
3. Compose UI tests:
   - Session list renders items
   - Chat input sends message
   - Permission dialog shows and responds
4. Manual test: connect to real opencode server, send a message, see streaming response
