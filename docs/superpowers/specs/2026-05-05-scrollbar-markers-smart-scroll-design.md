# Scrollbar Markers + Smart Auto-Scroll Design

## Problem

In chat sessions, user messages are short and rare while agent replies are long and frequent. When a user sends multiple queued messages and returns later, it's hard to locate their own messages to check processing status. The current UI has no navigation aid — only manual scrolling through dense agent content.

Auto-scroll currently forces the view to the bottom on every new message, which is disruptive when reading older content.

## Solution

Two features working together:

1. **Scrollbar markers** — visual indicators on a custom scrollbar showing where user messages and the latest message are
2. **Smart auto-scroll** — only auto-scroll when the user is at the exact bottom; stop following when they scroll up even slightly

## Design Details

### Scrollbar with Markers

A custom `ScrollbarWithMarkers` component replaces the default LazyColumn scrollbar. It draws alongside the chat LazyColumn on the right edge.

**Marker types:**

| Marker | Color | Shape | Meaning |
|--------|-------|-------|---------|
| User message | Blue (`colorScheme.primary`) | Small circle (4dp) | Position of a user message in the list |
| Latest message | Orange (`colorScheme.tertiary`) | Short horizontal line (8dp wide, 2dp tall) | The newest message the user hasn't seen |

**Interaction:** Click/tap on any marker → `LazyListState.scrollToItem(correspondingIndex)` to jump to that message.

**Layout:** The scrollbar is a narrow strip (12dp wide) to the right of the LazyColumn. The drag handle is the standard scrollbar thumb. Markers are drawn on top of the scrollbar track.

**Position calculation:** Each marker's Y position = `(itemIndex / totalItems) * scrollbarHeight`. This uses LazyListState layoutInfo to get first/last visible items and total item count. For accuracy, we can use the accumulated scroll offset ratio instead of simple index ratio to handle items with variable heights.

### Smart Auto-Scroll

**Rule:** Auto-scroll to bottom only when `lastVisibleItemIndex == totalItemCount - 1` (exact bottom, zero tolerance).

**Behavior matrix:**

| User position | New message arrives | Action |
|---------------|---------------------|--------|
| Exact bottom (last item fully/partially visible) | Any | Scroll to new bottom |
| Not at bottom | Any | Don't scroll; show orange "latest" marker on scrollbar |

**Implementation:** In `SessionDetailScreen`/`SessionDetailViewModel`, before calling `scrollToItem(lastIndex)` on message list update, check `lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == totalItems - 1`. Only scroll if true.

### Data Requirements

The `SessionDetailViewModel` (or the composable) needs:

- `userMessageIndices: List<Int>` — indices in the message LazyColumn that are user messages
- `totalItemCount: Int` — total number of items in the LazyColumn
- These can be derived from the existing `messages: List<Message>` flow by filtering `role == USER` and mapping to their list indices

### Scrollbar Position Accuracy

Since messages have variable heights (agent messages can be very tall), simple `index/total` ratio is inaccurate. Better approach:

- Use `LazyListState.layoutInfo` to get `viewportHeight` and `totalItemsCount`
- Track `scrollOffset` via `LazyListState.firstVisibleItemScrollOffset + LazyListState.firstVisibleItemIndex`
- For each user message index, estimate its position using accumulated offsets or pre-computed heights
- Approximation is acceptable — markers just need to be "in the right neighborhood"

A simpler acceptable approach: after each scroll/layout change, iterate `visibleItemsInfo` to get their offsets relative to the viewport. For non-visible items, extrapolate from average item height. This gives ~80% accuracy which is sufficient for markers.

## Files to Modify/Create

| File | Change |
|------|--------|
| `core/ui/component/ScrollbarWithMarkers.kt` | **New** — custom scrollbar composable with marker drawing |
| `feature/session/SessionDetailScreen.kt` | Replace default scrollbar with `ScrollbarWithMarkers`; add smart auto-scroll logic; pass `userMessageIndices` |
| `feature/session/SessionDetailViewModel.kt` | Expose `userMessageIndices` derived from messages list |

## Out of Scope

- Collapsible agent messages
- Minimap-style preview
- Keyboard shortcuts for navigation
- Search within messages (already exists via session_tool.py on server side)