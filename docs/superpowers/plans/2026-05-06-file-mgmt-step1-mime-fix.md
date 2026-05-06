# Step 1: MIME Fix — Simplify Attachment MIME Types

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the bug where non-plain-text MIME types (e.g. `text/markdown`) cause session errors by aligning with TUI behavior: all non-image files get `text/plain`.

**Architecture:** Change `guessMime()` in `SessionDetailViewModel` to return `text/plain` for all non-image files. This is a standalone fix with no dependencies.

**Tech Stack:** Kotlin

---

### Task 1.1: Simplify guessMime in SessionDetailViewModel

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\SessionDetailViewModel.kt:532-552`

- [ ] **Step 1: Replace guessMime implementation**

Replace the existing `guessMime` method (lines 532-553) with:

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

- [ ] **Step 2: Verify no other callers of this private method**

This is a `private` method in `SessionDetailViewModel`, only called by:
- `attachFile()` (line 522)
- `uploadAndAttach()` (line 175)

Both are correct with the simplified mapping.

- [ ] **Step 3: Build check**

Run: `cd D:\openmate\android && .\gradlew.bat :feature:session:compileDebugKotlin --no-daemon 2>&1 | Select-String -Pattern "^e:"`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add feature/session/src/main/java/com/openmate/feature/session/SessionDetailViewModel.kt
git commit -m "fix: simplify attachment MIME to text/plain for non-image files"
```
