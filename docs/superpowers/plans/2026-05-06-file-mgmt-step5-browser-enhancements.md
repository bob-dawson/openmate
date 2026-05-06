# Step 5: WorkspaceBrowser — Add Attach & Download-to Actions

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add "Attach to session" and "Download to..." long-press menu items to the file browser. The "Attach" option requires passing an `onAttach` callback through navigation from SessionDetailScreen.

**Architecture:** `WorkspaceBrowserScreen` gets an optional `onAttach: ((String, String) -> Unit)?` parameter. When non-null, it shows an "Attach" menu item. "Download to..." opens `DirectoryPickerDialog` and downloads to the chosen directory. Navigation passes the callback via a wrapper lambda.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3

---

### Task 5.1: Add onAttach parameter and "Attach" menu item to WorkspaceBrowserScreen

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\component\WorkspaceBrowserScreen.kt`

- [ ] **Step 1: Add onAttach parameter to function signature**

Change the `WorkspaceBrowserScreen` composable signature (line 123-127):

```kotlin
@Composable
fun WorkspaceBrowserScreen(
    initialDirectory: String,
    onBack: () -> Unit,
    onAttach: ((path: String, filename: String) -> Unit)? = null,
    viewModel: WorkspaceBrowserViewModel = hiltViewModel(),
) {
```

- [ ] **Step 2: Add "Attach to session" menu item**

In the context menu Popup block (around line 938-977), add a new menu item AFTER the Download/Download & Open items, BEFORE the Rename item. Add it for files only (not directories):

```kotlin
                    if (!menuEntry.isDirectory && onAttach != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_to_session)) },
                            onClick = {
                                contextMenu = contextMenu.copy(expanded = false)
                                onAttach(fullPath, menuEntry.name)
                            },
                        )
                    }
```

---

### Task 5.2: Add "Download to..." menu item with DirectoryPickerDialog

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\component\WorkspaceBrowserScreen.kt`
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\WorkspaceBrowserViewModel.kt`

- [ ] **Step 1: Add state variable for download-to picker**

Add alongside other state variables (around line 155-160):

```kotlin
    var showDownloadToPicker by remember { mutableStateOf<Pair<String, String>?>(null) }
```

- [ ] **Step 2: Add "Download to..." menu item**

In the context menu, AFTER the "Download & Open" item and BEFORE the "Attach" item (for non-directory files):

```kotlin
                    if (!menuEntry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.download_to)) },
                            onClick = {
                                contextMenu = contextMenu.copy(expanded = false)
                                showDownloadToPicker = Pair(fullPath, menuEntry.name)
                            },
                        )
                    }
```

- [ ] **Step 3: Add DirectoryPickerDialog for download-to**

Add after the existing dialog blocks (near the end of the composable, before the closing brace):

```kotlin
    if (showDownloadToPicker != null) {
        val (dlPath, dlFilename) = showDownloadToPicker!!
        val rootDir = remember {
            val dir = File(context.cacheDir, "file_cache")
            dir.mkdirs()
            dir
        }
        DirectoryPickerDialog(
            rootDir = rootDir,
            onDismiss = { showDownloadToPicker = null },
            onSelect = { targetDir ->
                showDownloadToPicker = null
                val destFile = File(targetDir, dlFilename)
                scope.launch(Dispatchers.IO) {
                    try {
                        viewModel.apiClient.bridgeDownloadFile(dlPath, destFile) { _, _, _ -> }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "Download failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }
```

- [ ] **Step 4: Add missing import**

Add at top:
```kotlin
import com.openmate.core.ui.component.DirectoryPickerDialog
```

- [ ] **Step 5: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

---

### Task 5.3: Add string resources for new menu items

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\res\values\strings.xml`
- Modify: `D:\openmate\android\feature\session\src\main\res\values-zh\strings.xml`

- [ ] **Step 1: Add English strings**

In `values/strings.xml`, before `</resources>`:

```xml
    <string name="attach_to_session">Attach to session</string>
    <string name="download_to">Download to…</string>
    <string name="local_files">Local Files</string>
```

- [ ] **Step 2: Add Chinese strings**

In `values-zh/strings.xml`, before `</resources>`:

```xml
    <string name="attach_to_session">附加到会话</string>
    <string name="download_to">下载到…</string>
    <string name="local_files">本地文件</string>
```

- [ ] **Step 3: Commit**

```bash
git add feature/session/src/main/java/com/openmate/feature/session/component/WorkspaceBrowserScreen.kt
git add feature/session/src/main/res/values/strings.xml
git add feature/session/src/main/res/values-zh/strings.xml
git commit -m "feat: add Attach to session and Download to actions in file browser"
```
