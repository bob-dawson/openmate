# Step 2: Extract Shared File Utilities

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract duplicated `guessMime()` (3 copies) and file opening logic (2 copies) into shared modules in `core/common`.

**Architecture:** Two new files in `core/common`: `FileMime.kt` for MIME utilities, `FileOpener.kt` for file open/share/install operations. Then update existing consumers.

**Tech Stack:** Kotlin, Android FileProvider, Intent system

---

### Task 2.1: Create FileMime.kt

**Files:**
- Create: `D:\openmate\android\core\common\src\main\java\com\openmate\core\common\FileMime.kt`

- [ ] **Step 1: Create the shared MIME utility**

The file at `core/common/.../FileMime.kt`:

```kotlin
package com.openmate.core.common

fun guessMimeForAttachment(filename: String): String {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "text/plain"
    }
}

fun guessMimeForOpening(ext: String): String {
    return when (ext.lowercase()) {
        "txt", "log", "cfg", "conf", "ini", "properties", "env", "gitignore", "dockerignore" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "ts" -> "application/typescript"
        "md", "markdown", "mdx" -> "text/markdown"
        "yaml", "yml" -> "application/yaml"
        "toml" -> "application/toml"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "tar" -> "application/x-tar"
        "gz", "tgz" -> "application/gzip"
        "bz2" -> "application/x-bzip2"
        "xz" -> "application/x-xz"
        "7z" -> "application/x-7z-compressed"
        "rar" -> "application/vnd.rar"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }
}
```

- [ ] **Step 2: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :core:common:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

---

### Task 2.2: Create FileOpener.kt

**Files:**
- Create: `D:\openmate\android\core\common\src\main\java\com\openmate\core\common\FileOpener.kt`

- [ ] **Step 1: Extract file opening logic**

Create the file based on the duplicated code in `WorkspaceBrowserViewModel` (lines 149-189) and `CacheManagerViewModel` (lines 127-189):

```kotlin
package com.openmate.core.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object FileOpener {

    fun openWithSystemViewer(context: Context, file: File, filename: String) {
        val ext = filename.substringAfterLast('.', "")
        val mime = guessMimeForOpening(ext)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, filename)
        context.startActivity(chooser)
    }

    fun shareFile(context: Context, file: File, filename: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, filename)
        context.startActivity(chooser)
    }

    fun installApk(context: Context, file: File, filename: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                (context as? android.app.Activity)?.startActivityForResult(intent, 1234)
                return
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
```

- [ ] **Step 2: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :core:common:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

---

### Task 2.3: Update WorkspaceBrowserViewModel to use shared utilities

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\WorkspaceBrowserViewModel.kt`

- [ ] **Step 1: Add import and replace usages**

Add import at top:
```kotlin
import com.openmate.core.common.FileOpener
import com.openmate.core.common.guessMimeForOpening
```

Replace `openWithSystemViewer` method body (around line 165-183):
```kotlin
    fun openWithSystemViewer(file: File, filename: String) {
        FileOpener.openWithSystemViewer(appContext, file, filename)
    }
```

Replace `installApk` method body (around line 131-148):
```kotlin
    fun installApk(file: File, filename: String) {
        FileOpener.installApk(appContext, file, filename)
    }
```

Note: `pendingApkFile`/`pendingApkName`/`retryPendingApkInstall` should remain in the ViewModel since they manage UI state. Only `installApk`'s actual install logic is delegated.

- [ ] **Step 2: Remove private guessMime function**

Delete the private top-level `guessMime(ext: String)` function at the bottom of the file (lines ~190-219). The only caller was `openWithSystemViewer` which now delegates to `FileOpener`.

- [ ] **Step 3: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

---

### Task 2.4: Update SessionDetailViewModel to use shared guessMime

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\SessionDetailViewModel.kt`

- [ ] **Step 1: Add import and replace usage**

Add import:
```kotlin
import com.openmate.core.common.guessMimeForAttachment
```

Replace `guessMime(filename)` calls at lines 175 and 522 with `guessMimeForAttachment(filename)`.

Delete the private `guessMime` method (now lines ~532-541 after Step 1 changes).

- [ ] **Step 2: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

---

### Task 2.5: Update CacheManagerViewModel to use shared utilities

**Files:**
- Modify: `D:\openmate\android\feature\settings\src\main\java\com\openmate\feature\settings\CacheManagerViewModel.kt`

- [ ] **Step 1: Add imports and replace usages**

Add imports:
```kotlin
import com.openmate.core.common.FileOpener
import com.openmate.core.common.guessMimeForOpening
```

Replace `openFile(info)` and `shareFile(info)` method bodies to delegate to `FileOpener`:
```kotlin
    fun openFile(info: CacheFileInfo) {
        val ext = info.name.substringAfterLast('.', "")
        if (ext == "apk") {
            pendingApkFile = info.file
            pendingApkName = info.name
            FileOpener.installApk(appContext, info.file, info.name)
        } else {
            FileOpener.openWithSystemViewer(appContext, info.file, info.name)
        }
    }

    fun shareFile(info: CacheFileInfo) {
        FileOpener.shareFile(appContext, info.file, info.name)
    }
```

Delete the private top-level `guessMime` function at bottom of file (lines ~221-250).

- [ ] **Step 2: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:settings:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

- [ ] **Step 3: Commit all shared utility work**

```bash
git add core/common/src/main/java/com/openmate/core/common/FileMime.kt
git add core/common/src/main/java/com/openmate/core/common/FileOpener.kt
git add feature/session/src/main/java/com/openmate/feature/session/WorkspaceBrowserViewModel.kt
git add feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt
git add feature/settings/src/main/java/com/openmate/feature/settings/CacheManagerViewModel.kt
git commit -m "refactor: extract shared FileMime and FileOpener utilities"
```
