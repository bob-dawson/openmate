# File Management Redesign

## Problem

1. **MIME bug**: Android `guessMime()` returns `text/markdown`, `text/kotlin` etc. Server only handles `text/plain` and `application/x-directory` for file attachments — other MIMEs cause session errors.
2. **Cache management is flat**: `CacheManagerScreen` shows all files in a flat list with no directory navigation or creation.
3. **Redundant attachment code**: `AttachOptionSheet` + `galleryLauncher` + `fileLauncher` + `FilePickerSheet` — 4 components for attachment, all replaceable by the file browser.
4. **Code duplication**: 3 `guessMime()` functions, 2 nearly identical `openFile`/`installApk`/`openWithSystemViewer` implementations.
5. **No download-to-directory**: File browser downloads always go to auto-generated cache path.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Local file root dir | `cacheDir/file_cache/` (unchanged) | No migration, no permission changes |
| MIME strategy | `image/*` preserved, everything else → `text/plain` | Matches TUI (`run.ts:323`) and web UI (`files.ts:62`) |
| Attachment entry point | File browser long-press only | Removes all attachment UI from session input |
| Attachment button | Deleted entirely from ChatInputBar | Redundant — file browser covers all cases |
| Download filename | Original filename only (no full path) | Already works this way |

## Changes by Module

### 1. MIME Fix (`SessionDetailViewModel`)

**File**: `feature/session/.../SessionDetailViewModel.kt`

Change `guessMime()` to:
```kotlin
private fun guessMime(filename: String): String {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "text/plain"
    }
}
```

This matches the TUI behavior: all non-image files are `text/plain`.

### 2. Shared File Utilities (new)

**New file**: `core/common/.../util/FileMime.kt`
- `fun guessMimeForOpening(ext: String): String` — full MIME mapping for opening files with system viewer (30+ extensions, keeps `application/pdf` etc.)
- `fun guessMimeForAttachment(filename: String): String` — simplified: image/* or text/plain

**New file**: `core/common/.../util/FileOpener.kt`
- `fun openWithSystemViewer(context, file, filename)` — FileProvider + Intent.ACTION_VIEW
- `fun installApk(context, file, filename, activity)` — APK install with permission handling
- `fun shareFile(context, file, filename)` — Intent.ACTION_SEND

Extracted from duplicated code in `WorkspaceBrowserViewModel` and `CacheManagerViewModel`.

### 3. Local File Manager (replaces Cache Manager)

#### 3a. ViewModel: `CacheManagerViewModel` → `LocalFileManagerViewModel`

**File**: `feature/settings/.../LocalFileManagerViewModel.kt` (rename)

Key changes:
- `listDir(dir: File)` → returns `List<LocalFileEntry>` (files + directories in given dir)
- `createDirectory(parent: File, name: String)`
- `moveFile(source: File, targetDir: File)`
- `currentDir: StateFlow<File>` — current navigation directory
- `navigateUp()`, `navigateInto(name: String)`
- Keep existing: open, share, rename, delete

Root: `cacheDir/file_cache/`

#### 3b. Screen: `CacheManagerScreen` → `LocalFileManagerScreen`

**File**: `feature/settings/.../LocalFileManagerScreen.kt` (rename)

UI changes:
- Title bar: show current relative path (e.g. `file_cache/` or `file_cache/subdir/`), back button navigates up
- Title bar action: "+" button → create directory dialog
- File list: directories shown first (with folder icon), clickable to navigate in
- Files shown with size/date
- Long-press context menu: Open, Share, Rename, Delete, **Move to...**
- "Move to..." → opens `DirectoryPickerDialog`

#### 3c. Directory Picker Dialog (new shared component)

**New file**: `core/ui/.../component/DirectoryPickerDialog.kt`

- Reusable composable
- Shows directory tree starting from `file_cache/`
- Navigate into/out of directories
- "New folder" button to create directory inline
- "Select here" confirm button
- Returns selected `File` directory

Used by:
1. LocalFileManager "Move to..."
2. WorkspaceBrowser "Download to..."

### 4. File Browser Enhancements

**File**: `feature/session/.../component/WorkspaceBrowserScreen.kt`

#### Long-press menu additions:

**"Attach to session"**:
- Only shown when opened from a session context (pass `sessionID` parameter)
- Calls same logic as current `FilePickerSheet.onSelect`: creates `FileAttachment(path, filename, mime)` and adds to `SessionDetailViewModel._attachedFiles`
- Need callback mechanism: `onAttach: (path: String, filename: String) -> Unit` parameter

**"Download to..."**:
- Opens `DirectoryPickerDialog`
- Downloads file to selected local directory using `bridgeDownloadFile()`
- Shows download progress overlay (reuse existing `DownloadOverlay`)

#### Download filename:
Already uses original filename. No change needed — `computeLocalPath` is only used for auto-cache downloads, the "Download to..." feature will save directly to user-chosen dir with original filename.

### 5. Remove Redundant Attachment Code

**Delete**:
- `AttachOptionSheet.kt` — 3-option bottom sheet (Gallery / Files / Server)
- `FilePickerSheet.kt` — server file picker bottom sheet
- `SessionDetailScreen.kt`: remove `galleryLauncher`, `fileLauncher`, `showAttachSheet`, `showFilePicker` state, and the attachment button (+) from `ChatInputBar`

**Modify**:
- `SessionDetailScreen.kt`: pass `onAttach` callback to `WorkspaceBrowserScreen` when navigating from session detail
- `WorkspaceBrowserScreen.kt`: accept optional `onAttach` callback; show "Attach to session" in context menu when callback is non-null
- Navigation: `WorkspaceBrowserScreen` gets optional `onAttach` parameter

### 6. Navigation & Naming Updates

| Old | New |
|-----|-----|
| `CacheManagerScreen` | `LocalFileManagerScreen` |
| `CacheManagerViewModel` | `LocalFileManagerViewModel` |
| Route `CACHE_MANAGER` | Route `LOCAL_FILE_MANAGER` |
| All UI strings "缓存管理" | "本地文件管理" |
| Settings cache section | Settings local files section |

### 7. Settings / WorkspaceList Updates

**Files**: `SettingsScreen.kt`, `WorkspaceListScreen.kt`

- Rename all references from "缓存" to "本地文件"
- "管理" row → navigates to `LocalFileManagerScreen`
- "删除" button → clears all files in `file_cache/` (logic unchanged, just label update)

## File Change Summary

| File | Action |
|------|--------|
| `core/common/.../util/FileMime.kt` | **NEW** — shared MIME utilities |
| `core/common/.../util/FileOpener.kt` | **NEW** — shared file open/share/install |
| `core/ui/.../component/DirectoryPickerDialog.kt` | **NEW** — reusable directory picker |
| `feature/session/.../SessionDetailViewModel.kt` | **MODIFY** — simplify `guessMime()` |
| `feature/session/.../SessionDetailScreen.kt` | **MODIFY** — remove attachment UI, pass onAttach to browser |
| `feature/session/.../WorkspaceBrowserScreen.kt` | **MODIFY** — add "Attach" + "Download to..." menu items |
| `feature/session/.../WorkspaceBrowserViewModel.kt` | **MODIFY** — use shared FileOpener, add download-to-dir |
| `feature/session/.../component/AttachOptionSheet.kt` | **DELETE** |
| `feature/session/.../component/FilePickerSheet.kt` | **DELETE** |
| `feature/session/.../SessionNavigation.kt` | **MODIFY** — rename route, add onAttach param |
| `feature/settings/.../CacheManagerViewModel.kt` | **RENAME** → `LocalFileManagerViewModel.kt` + rewrite |
| `feature/settings/.../CacheManagerScreen.kt` | **RENAME** → `LocalFileManagerScreen.kt` + rewrite |
| `feature/settings/.../SettingsViewModel.kt` | **MODIFY** — rename labels |
| `feature/settings/.../SettingsScreen.kt` | **MODIFY** — UI label updates |
| `feature/session/.../WorkspaceListScreen.kt` | **MODIFY** — UI label updates |

## Data Flow After Changes

### File Attachment (new flow)
```
SessionDetail → menu "Browse files" → WorkspaceBrowserScreen(onAttach callback)
  → browse/find file → long press → "Attach to session"
  → onAttach(path, filename) → SessionDetailViewModel.attachFile()
  → file appears as chip above input → send message
```

### Download to Local Directory (new flow)
```
WorkspaceBrowserScreen → long press file → "Download to..."
  → DirectoryPickerDialog (local file_cache tree)
  → select target directory
  → bridgeDownloadFile() to target/dir/filename
  → progress overlay
```

### Local File Management (new flow)
```
Settings/WorkspaceList → "本地文件管理" → LocalFileManagerScreen
  → directory navigation (tap to enter, back to go up)
  → "+" to create directory
  → long press file: Open / Share / Rename / Delete / Move to...
  → "Move to..." → DirectoryPickerDialog → move file
```

## Implementation Order

1. **MIME fix** — standalone bug fix, no dependencies
2. **Shared utilities** — extract FileMime + FileOpener, update existing consumers
3. **Local File Manager** — rewrite CacheManager → LocalFileManager
4. **Directory Picker Dialog** — new shared component
5. **File browser enhancements** — add "Attach" + "Download to..." menu items
6. **Remove redundant attachment code** — delete AttachOptionSheet, FilePickerSheet, attachment button
7. **Navigation & naming cleanup** — route rename, label updates
