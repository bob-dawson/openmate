# Settings Page Cleanup & Cache Manager Design

## Summary

Remove unused notification and auto-allow rule settings. Replace DB-based cache clearing with filesystem-based cache management. Add a new Cache Manager screen for browsing, opening, sharing, deleting, and renaming cached files — all based on actual filesystem state, independent of the CacheDatabase.

## 1. Settings Page Changes

### Remove
- **Notifications section** (4 toggles: `notify_permissions`, `notify_questions`, `notify_complete`, `notify_errors`)
- **Auto Allow Rules section** (3 toggles: `auto_allow_read`, `auto_allow_grep`, `auto_allow_bash`)
- All related `SharedPreferences` keys, StateFlows, and setter methods in `SettingsViewModel`

### Replace Cache & Storage section
Old: single row showing DB-computed cache size + "Clear" button (deletes via `fileCacheRepo.clearAllCache()`)

New: **Cache section** with two rows:
1. **File Cache** — subtitle shows filesystem-computed cache size (`file_cache/` directory walk), trailing "Clear" button that deletes the entire `file_cache/` directory contents
2. **Manage** — subtitle shows file count, clickable row that navigates to `CacheManagerScreen`

### SettingsViewModel changes
- Remove: `fileCacheRepo` dependency, all notify/auto-allow StateFlows and setters
- Add: `cacheSize` computed by walking `file_cache/` directory (not DB query), `cacheFileCount`
- `clearCache()`: delete all contents under `file_cache/` directory (recursively), then refresh size
- `refreshCacheSize()`: walk `file_cache/` to compute total bytes and file count

## 2. Cache Manager Screen (`CacheManagerScreen`)

### Navigation
- Route: `SessionRoutes.CACHE_MANAGER`
- Added to `sessionScreens()` in `SessionNavigation.kt`
- `WorkspaceListScreen` passes `onNavigateToCacheManager` to `SettingsContent`

### Data Source
**Entirely filesystem-based.** Walk `cache/file_cache/` directory structure:
```
file_cache/
  <hash1>/file_a.txt
  <hash2>/file_b.apk
```

Flatten into a list of `CacheFileInfo`:
```kotlin
data class CacheFileInfo(
    val file: File,
    val name: String,
    val size: Long,
    val lastModified: Long,
)
```

Obtained by iterating `file_cache/` subdirectories and their children. No DB reads.

### UI Layout
Similar to `WorkspaceBrowserScreen` file list style:
- TopAppBar with title "Cache" and back button
- Sort controls (Name / Size / Modified, ascending/descending)
- LazyColumn of file items, each showing: icon (by extension), filename, size, modified time
- Empty state when no cached files

### Click Behavior
- **Regular files**: Open with system viewer via `FileProvider` + `Intent.ACTION_VIEW` (reuse `WorkspaceBrowserViewModel.openWithSystemViewer` logic)
- **APK files**: Special handling — request install permission if needed, then install (reuse `WorkspaceBrowserViewModel.installApk` logic)

### Long-Press Context Menu
A dropdown menu on long-press with three options:
1. **Share** — `Intent.ACTION_SEND` via `FileProvider` (system share sheet)
2. **Delete** — Delete the file; if parent directory becomes empty, delete it too
3. **Rename** — Show `AlertDialog` with text field; rename the file on disk; refresh list

### CacheManagerViewModel
Located in `feature:settings` module (same as SettingsViewModel).

```kotlin
@HiltViewModel
class CacheManagerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    private val cacheDir get() = File(appContext.cacheDir, "file_cache")

    val cacheFiles: StateFlow<List<CacheFileInfo>>
    val sortField: MutableStateFlow<SortField>  // NAME, SIZE, MODIFIED
    val sortAscending: MutableStateFlow<Boolean>

    fun refresh()
    fun openFile(info: CacheFileInfo)
    fun installApk(info: CacheFileInfo)
    fun shareFile(info: CacheFileInfo)
    fun deleteFile(info: CacheFileInfo)
    fun renameFile(info: CacheFileInfo, newName: String)
}
```

File opening/APK install/share logic extracted from `WorkspaceBrowserViewModel` into shared utilities (or duplicated simply since the logic is small).

## 3. Download Cache Changes (WorkspaceBrowserViewModel)

### Current behavior
1. `checkCache(remotePath)` → query DB for cached entry
2. Validate: entry exists AND local file exists AND fileSize/modifiedTime match
3. If valid, use cached file; otherwise download + save to DB

### New behavior (no DB)
1. Compute expected local path: `file_cache/<remotePath.hashCode()>/<sanitized_filename>`
2. Check if file exists on disk AND file size > 0
3. If exists, use it; otherwise download
4. After download, **do NOT** call `cacheRepo.saveCachedFile()` — just write the file to disk

Remove `cacheRepo` dependency from `WorkspaceBrowserViewModel`. Remove `checkCache()` method.

### Cache validity simplification
No more `fileSize`/`modifiedTime` validation against DB. If the file exists on disk at the expected path and has content (size > 0), it's considered valid. If the remote file changes (same path, different content), the user would need to clear cache to get a fresh copy — this is acceptable for a cache.

## 4. Cleanup: Remove CacheDatabase Infrastructure

After both settings and browser no longer use the DB:

- **Delete** `CacheDatabase`, `CachedFileEntity`, `CachedFileDao`, `CachedFile` domain model, `FileCacheRepository` interface, `FileCacheRepositoryImpl`
- **Remove** `CacheDatabase` provider from `DatabaseModule`
- **Remove** `file_cache` Room database file from device storage (handled by uninstall/reinstall, no migration needed)

## 5. Files to Modify

| File | Action |
|------|--------|
| `feature/settings/SettingsViewModel.kt` | Remove notify/auto-allow fields; replace cache logic with filesystem |
| `feature/session/WorkspaceListScreen.kt` | Remove notifications & auto-allow UI sections; update cache section; add Manage row + navigation callback |
| `feature/session/SessionNavigation.kt` | Add `CACHE_MANAGER` route |
| `feature/session/WorkspaceBrowserViewModel.kt` | Remove `cacheRepo`, `checkCache()`; simplify download to filesystem-only |
| `feature/session/component/WorkspaceBrowserScreen.kt` | Update `openBinaryFile` to use filesystem cache check instead of `checkCache()` |
| `feature/settings/CacheManagerViewModel.kt` | **New** — filesystem-based cache management |
| `feature/settings/CacheManagerScreen.kt` | **New** — cache browsing UI |
| `core/domain/model/CachedFile.kt` | **Delete** |
| `core/domain/repository/FileCacheRepository.kt` | **Delete** |
| `core/data/FileCacheRepositoryImpl.kt` | **Delete** |
| `core/database/CacheDatabase.kt` | **Delete** |
| `core/database/entity/CachedFileEntity.kt` | **Delete** |
| `core/database/dao/CachedFileDao.kt` | **Delete** |
| `core/database/DatabaseModule.kt` | Remove `provideCacheDatabase()` |

## 6. String Resources

New strings needed in `feature/settings`:
- `cache_manager` → "Cache Manager"
- `manage` → "Manage"
- `share` → "Share"
- `rename` → "Rename"
- `rename_file` → "Rename File"
- `enter_new_name` → "Enter new name"
- `delete_file_confirm` → "Delete this file?"
- `no_cached_files` → "No cached files"
- `file_count` → "{count} files"
