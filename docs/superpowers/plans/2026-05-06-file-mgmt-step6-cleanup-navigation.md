# Step 6: Remove Redundant Attachment Code & Update Navigation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete `AttachOptionSheet`, `FilePickerSheet`, and the attachment button from `ChatInputBar`. Pass `onAttach` callback from `SessionDetailScreen` through navigation to `WorkspaceBrowserScreen`. Update settings labels from "Cache" to "Local Files".

**Architecture:** Remove all attachment-related UI from session detail. The "Browse files" menu item now passes the `onAttach` callback. `ChatInputBar` removes the attach button entirely. Navigation wires the callback.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3

---

### Task 6.1: Remove attachment button from ChatInputBar

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\component\ChatInputBar.kt`

- [ ] **Step 1: Remove onAttach parameter and Add button**

Rewrite `ChatInputBar` to remove the `onAttach` param and the `Add` icon button:

```kotlin
@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isUploading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(TopBarBackground)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    stringResource(R.string.input_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
            shape = RoundedCornerShape(4.dp),
        )
        IconButton(
            onClick = onSend,
            enabled = text.isNotBlank() && !isUploading,
            modifier = Modifier.height(40.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.content_desc_send),
                tint = if (text.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
```

Remove unused imports: `Icons.Default.Add`, `IconButton`, `IconButtonDefaults` (still used by Send button).

- [ ] **Step 2: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

---

### Task 6.2: Clean up SessionDetailScreen

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\SessionDetailScreen.kt`

- [ ] **Step 1: Remove attachment-related imports**

Delete these imports:
```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.openmate.feature.session.component.AttachOptionSheet
import com.openmate.feature.session.component.FilePickerSheet
```

- [ ] **Step 2: Remove galleryLauncher and fileLauncher**

Delete lines 113-123:
```kotlin
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadAndAttach(uris, context.contentResolver)
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadAndAttach(uris, context.contentResolver)
    }
```

- [ ] **Step 3: Remove showAttachSheet and showFilePicker state**

Delete `var showFilePicker by remember { mutableStateOf(false) }` (line 131)
Delete `var showAttachSheet by remember { mutableStateOf(false) }` (line 133)

- [ ] **Step 4: Remove onAttach from ChatInputBar call**

Change (line 431-437):
```kotlin
            ChatInputBar(
                text = inputText,
                onTextChange = { viewModel.updateInput(it) },
                onSend = { viewModel.sendMessage(sessionID) },
                onAttach = { showAttachSheet = true },
                isUploading = isUploading,
            )
```
To:
```kotlin
            ChatInputBar(
                text = inputText,
                onTextChange = { viewModel.updateInput(it) },
                onSend = { viewModel.sendMessage(sessionID) },
                isUploading = isUploading,
            )
```

- [ ] **Step 5: Remove FilePickerSheet and AttachOptionSheet composables**

Delete lines 552-579:
```kotlin
    if (showFilePicker) { ... }
    if (showAttachSheet) { ... }
```

- [ ] **Step 6: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

---

### Task 6.3: Delete AttachOptionSheet and FilePickerSheet

**Files:**
- Delete: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\component\AttachOptionSheet.kt`
- Delete: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\component\FilePickerSheet.kt`

- [ ] **Step 1: Delete both files**

```bash
Remove-Item "D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\component\AttachOptionSheet.kt"
Remove-Item "D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\component\FilePickerSheet.kt"
```

- [ ] **Step 2: Verify no remaining imports**

Search for `AttachOptionSheet` and `FilePickerSheet` references across the codebase. There should be none.

- [ ] **Step 3: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

---

### Task 6.4: Wire onAttach through navigation

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\SessionNavigation.kt`
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\SessionDetailScreen.kt`

- [ ] **Step 1: Change onNavigateToBrowser to include onAttach**

In `SessionDetailScreen.kt`, change the function signature:

```kotlin
fun SessionDetailScreen(
    sessionID: String,
    onBack: () -> Unit,
    onNavigateToSubtask: (subtaskSessionID: String, title: String) -> Unit = { _, _ -> },
    onNavigateToBrowser: (directory: String, onAttach: (String, String) -> Unit) -> Unit = { _, _ -> },
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
```

Update the "Browse files" menu item to pass the attach callback:
```kotlin
                             DropdownMenuItem(
                                 text = { Text(stringResource(R.string.browse_files)) },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToBrowser(viewModel.getWorkingDirectory()) { path, filename ->
                                        viewModel.attachFile(path, filename)
                                    }
                                },
                            )
```

- [ ] **Step 2: Update navigation in SessionNavigation.kt**

Update the SESSION_DETAIL composable to pass `onAttach` through to browser:

```kotlin
    composable(
        route = "${SessionRoutes.SESSION_DETAIL}/{sessionID}",
        arguments = listOf(navArgument("sessionID") { type = NavType.StringType }),
    ) { backStackEntry ->
        val sessionID = backStackEntry.arguments?.getString("sessionID") ?: return@composable
        SessionDetailScreen(
            sessionID = sessionID,
            onNavigateToSubtask = { subtaskSessionID, title ->
                navController.navigate("${SessionRoutes.SUBTASK_DETAIL}/$subtaskSessionID?title=${URLEncoder.encode(title, "UTF-8")}")
            },
            onNavigateToBrowser = { directory, onAttach ->
                val encoded = URLEncoder.encode(directory, "UTF-8")
                navController.navigate("${SessionRoutes.WORKSPACE_BROWSER}/$encoded")
            },
            onBack = { navController.popBackStack() },
        )
    }
```

Note: The `onAttach` callback cannot be passed through standard navigation arguments. Two approaches:

**Approach A (Simple — recommended):** Use a shared `SavedStateHandle` or a simple singleton holder. Add a temporary storage in the ViewModel:

In `SessionDetailViewModel`, add:
```kotlin
    var pendingAttachCallback: ((String, String) -> Unit)? = null
```

The `onNavigateToBrowser` lambda sets this callback. Then in navigation, `WorkspaceBrowserScreen` reads it from the same ViewModel scope.

**Approach B:** Use a shared `AttachmentState` object:

```kotlin
// In SessionDetailViewModel.kt (companion or top-level)
object AttachmentBridge {
    var onAttach: ((String, String) -> Unit)? = null
}
```

Set before navigation, read in `WorkspaceBrowserScreen`, clear after use.

Use Approach B. In `SessionDetailScreen`:
```kotlin
onNavigateToBrowser(viewModel.getWorkingDirectory()) { path, filename ->
    viewModel.attachFile(path, filename)
}
```

Where `onNavigateToBrowser` now stores the callback:
```kotlin
onNavigateToBrowser = { directory, onAttach ->
    AttachmentBridge.onAttach = onAttach
    val encoded = URLEncoder.encode(directory, "UTF-8")
    navController.navigate("${SessionRoutes.WORKSPACE_BROWSER}/$encoded")
},
```

In `WorkspaceBrowserScreen`, read it:
```kotlin
val attachCallback = AttachmentBridge.onAttach
```

And clear when leaving:
```kotlin
DisposableEffect(Unit) {
    onDispose {
        AttachmentBridge.onAttach = null
    }
}
```

- [ ] **Step 3: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

---

### Task 6.5: Update Settings/WorkspaceList labels

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\WorkspaceListScreen.kt`
- Modify: `D:\openmate\android\feature\session\src\main\res\values\strings.xml`
- Modify: `D:\openmate\android\feature\session\src\main\res\values-zh\strings.xml`

- [ ] **Step 1: Update strings.xml**

In `values/strings.xml`, change:
```xml
    <string name="cache">Local Files</string>
```

In `values-zh/strings.xml`, change:
```xml
    <string name="cache">本地文件</string>
```

Note: The `cache` string key is kept to avoid renaming all references, only the display text changes.

- [ ] **Step 2: Update SettingsContent parameter name**

In `WorkspaceListScreen.kt`, rename `onNavigateToCacheManager` → `onNavigateToLocalFileManager` in `WorkspaceListScreen` function parameters and the `SettingsContent` composable.

- [ ] **Step 3: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

---

### Task 6.6: Remove uploadAndAttach from SessionDetailViewModel

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\SessionDetailViewModel.kt`

- [ ] **Step 1: Delete uploadAndAttach and resolveFilename methods**

Delete the `uploadAndAttach` method (lines ~161-183) and `resolveFilename` method (lines ~185-195). These are no longer called from anywhere after removing gallery/file launchers.

- [ ] **Step 2: Delete related imports**

Remove imports for `OpenableColumns`, `ContentResolver` if no longer used.

- [ ] **Step 3: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "refactor: remove redundant attachment UI, wire attach through file browser"
```
