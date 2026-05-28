# Git Changes 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在会话详情工具栏新增 Git 变更按钮，点击后显示未提交变更文件列表，点击文件可查看 diff。

**Architecture:** Bridge 独立实现 git API（`/api/bridge/git/status` + `/api/bridge/git/diff`），Android 端新增 GitChangesScreen + ViewModel，复用 DiffViewerActivity 查看 diff。

**Tech Stack:** Rust/axum (Bridge), Kotlin/Compose/Hilt (Android)

---

## 文件结构

### Bridge 新建
| 文件 | 职责 |
|------|------|
| `opencode-bridge/src/git/mod.rs` | 模块声明 |
| `opencode-bridge/src/git/operations.rs` | git 命令执行 + porcelain v2 输出解析 |
| `opencode-bridge/src/git/router.rs` | axum handlers (status + diff) |

### Bridge 修改
| 文件 | 改动 |
|------|------|
| `opencode-bridge/src/lib.rs` | 新增 `pub mod git;` |
| `opencode-bridge/src/server.rs` | 注册两条 git 路由 |
| `opencode-bridge/src/error.rs` | 新增 `NotAGitRepo` 变体 |

### Android 新建
| 文件 | 职责 |
|------|------|
| `feature/session/.../component/GitChangesScreen.kt` | 变更文件列表 UI |
| `feature/session/.../GitChangesViewModel.kt` | 状态管理 |

### Android 修改
| 文件 | 改动 |
|------|------|
| `core/network/.../dto/BridgeDto.kt` | 新增 `BridgeGitStatusEntry` DTO |
| `core/network/.../OpencodeApiClient.kt` | 新增 `bridgeGitStatus` / `bridgeGitDiff` |
| `feature/session/.../SessionDetailScreen.kt` | 工具栏新增 Compare 按钮 + 回调参数 |
| `feature/session/.../SessionNavigation.kt` | 新增 GIT_CHANGES 路由 |
| `app/.../diff/DiffViewerViewModel.kt` | toolName="git" 时走 bridgeGitDiff |

---

## Task 1: Bridge — error.rs 新增 NotAGitRepo

**Files:**
- Modify: `D:\openmate\opencode-bridge\src\error.rs:7-85`

- [ ] **Step 1: 在 AppError 枚举中新增变体**

在 `error.rs` 的 `AppError` 枚举中，`NotADirectory` 之后新增：

```rust
    #[error("Not a git repository: {0}")]
    NotAGitRepo(String),
```

- [ ] **Step 2: 在 IntoResponse impl 中新增匹配分支**

在 `into_response` 的 match 中，`NotADirectory` 之后新增：

```rust
            AppError::NotAGitRepo(_) => (StatusCode::NOT_FOUND, self.to_string()),
```

- [ ] **Step 3: 运行测试确认无破坏**

Run: `cd D:\openmate\opencode-bridge && cargo test`
Expected: 所有测试通过

- [ ] **Step 4: Commit**

```bash
git add opencode-bridge/src/error.rs
git commit -m "feat(bridge): add NotAGitRepo error variant"
```

---

## Task 2: Bridge — git/operations.rs 实现 git 命令执行与解析

**Files:**
- Create: `D:\openmate\opencode-bridge\src\git\operations.rs`

- [ ] **Step 1: 创建 operations.rs**

```rust
use std::path::Path;
use std::process::Command;

use serde::Serialize;

use crate::error::AppError;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GitStatusEntry {
    pub path: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub old_path: Option<String>,
}

pub fn git_status(dir: &Path) -> Result<Vec<GitStatusEntry>, AppError> {
    let output = Command::new("git")
        .args(["status", "--porcelain=v2", "--relative"])
        .current_dir(dir)
        .output()
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to run git: {}", e)))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        if stderr.contains("not a git repository") || stderr.contains("not in a git repo") {
            return Err(AppError::NotAGitRepo(dir.display().to_string()));
        }
        return Err(AppError::Internal(anyhow::anyhow!("git status failed: {}", stderr)));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    Ok(parse_porcelain_v2(&stdout))
}

fn parse_porcelain_v2(output: &str) -> Vec<GitStatusEntry> {
    let mut entries = Vec::new();
    for line in output.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if let Some(rest) = line.strip_prefix("1 ") {
            if let Some(entry) = parse_v2_ordinary(rest) {
                entries.push(entry);
            }
        } else if let Some(rest) = line.strip_prefix("2 ") {
            if let Some(entry) = parse_v2_renamed(rest) {
                entries.push(entry);
            }
        } else if let Some(rest) = line.strip_prefix("u ") {
            if let Some(entry) = parse_v2_unmerged(rest) {
                entries.push(entry);
            }
        } else if let Some(rest) = line.strip_prefix("? ") {
            entries.push(GitStatusEntry {
                path: rest.to_string(),
                status: "untracked".to_string(),
                old_path: None,
            });
        }
    }
    entries
}

fn parse_v2_ordinary(rest: &str) -> Option<GitStatusEntry> {
    let parts: Vec<&str> = rest.split_whitespace().collect();
    if parts.len() < 9 {
        return None;
    }
    let xy = parts.get(1)?;
    let x = xy.chars().next()?;
    let y = xy.chars().nth(1)?;
    let path = parts.get(8)?;
    let status = match (x, y) {
        ('.', 'M') => "modified",
        ('M', '.') | ('M', 'M') => "modified",
        ('A', _) => "added",
        ('D', _) | ('.', 'D') => "deleted",
        _ => "modified",
    };
    Some(GitStatusEntry {
        path: path.to_string(),
        status: status.to_string(),
        old_path: None,
    })
}

fn parse_v2_renamed(rest: &str) -> Option<GitStatusEntry> {
    let parts: Vec<&str> = rest.split_whitespace().collect();
    if parts.len() < 10 {
        return None;
    }
    let path = parts.get(9)?;
    let old_path = parts.get(8)?;
    Some(GitStatusEntry {
        path: path.to_string(),
        status: "renamed".to_string(),
        old_path: Some(old_path.to_string()),
    })
}

fn parse_v2_unmerged(rest: &str) -> Option<GitStatusEntry> {
    let parts: Vec<&str> = rest.split_whitespace().collect();
    if parts.len() < 10 {
        return None;
    }
    let path = parts.get(9)?;
    Some(GitStatusEntry {
        path: path.to_string(),
        status: "unmerged".to_string(),
        old_path: None,
    })
}

pub fn git_diff(file: &Path) -> Result<String, AppError> {
    let file_str = file.display().to_string();
    let parent = file.parent().unwrap_or(Path::new("."));

    let repo_root_output = Command::new("git")
        .args(["rev-parse", "--show-toplevel"])
        .current_dir(parent)
        .output()
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to run git rev-parse: {}", e)))?;

    if !repo_root_output.status.success() {
        return Err(AppError::NotAGitRepo(parent.display().to_string()));
    }

    let repo_root = String::from_utf8_lossy(&repo_root_output.stdout).trim().to_string();

    let output = Command::new("git")
        .args(["diff", "HEAD", "--", &file_str])
        .current_dir(&repo_root)
        .output()
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to run git diff: {}", e)))?;

    let diff = String::from_utf8_lossy(&output.stdout).to_string();

    if diff.trim().is_empty() {
        let staged_output = Command::new("git")
            .args(["diff", "--cached", "--", &file_str])
            .current_dir(&repo_root)
            .output()
            .map_err(|e| AppError::Internal(anyhow::anyhow!("Failed to run git diff --cached: {}", e)))?;

        return Ok(String::from_utf8_lossy(&staged_output.stdout).to_string());
    }

    Ok(diff)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_porcelain_v2_modified() {
        let input = "1 .M N... 100644 100644 100644 abc123 def456 file.rs\n";
        let entries = parse_porcelain_v2(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, "file.rs");
        assert_eq!(entries[0].status, "modified");
    }

    #[test]
    fn test_parse_porcelain_v2_added() {
        let input = "1 A. N... 000000 100644 100644 000000 abc123 new.rs\n";
        let entries = parse_porcelain_v2(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, "new.rs");
        assert_eq!(entries[0].status, "added");
    }

    #[test]
    fn test_parse_porcelain_v2_untracked() {
        let input = "? untracked.txt\n";
        let entries = parse_porcelain_v2(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, "untracked.txt");
        assert_eq!(entries[0].status, "untracked");
    }

    #[test]
    fn test_parse_porcelain_v2_renamed() {
        let input = "2 R. N... 100644 100644 100644 abc123 def456 R100 old.rs\tnew.rs\n";
        let entries = parse_porcelain_v2(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, "new.rs");
        assert_eq!(entries[0].status, "renamed");
        assert_eq!(entries[0].old_path, Some("old.rs".to_string()));
    }

    #[test]
    fn test_parse_porcelain_v2_empty() {
        let entries = parse_porcelain_v2("");
        assert!(entries.is_empty());
    }

    #[test]
    fn test_parse_porcelain_v2_deleted() {
        let input = "1 .D N... 100644 100644 000000 abc123 000000 gone.rs\n";
        let entries = parse_porcelain_v2(input);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, "gone.rs");
        assert_eq!(entries[0].status, "deleted");
    }
}
```

- [ ] **Step 2: 运行单元测试**

Run: `cd D:\openmate\opencode-bridge && cargo test git::operations`
Expected: 6 个测试通过

- [ ] **Step 3: Commit**

```bash
git add opencode-bridge/src/git/operations.rs
git commit -m "feat(bridge): add git operations with porcelain v2 parser"
```

---

## Task 3: Bridge — git/router.rs 实现 API handlers

**Files:**
- Create: `D:\openmate\opencode-bridge\src\git\router.rs`

- [ ] **Step 1: 创建 router.rs**

```rust
use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde::Deserialize;

use crate::error::AppError;
use crate::fs::path_guard::PathGuard;
use crate::git::operations;
use crate::state::AppState;

#[derive(Debug, Deserialize)]
pub struct PathQuery {
    pub path: String,
}

#[derive(Debug, Deserialize)]
pub struct FileQuery {
    pub file: String,
}

pub async fn status(
    State(state): State<AppState>,
    Query(query): Query<PathQuery>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&query.path)
        .map_err(AppError::PathNotAllowed)?;

    let entries = operations::git_status(&validated_path)?;
    Ok(Json(entries))
}

pub async fn diff(
    State(state): State<AppState>,
    Query(query): Query<FileQuery>,
) -> Result<impl IntoResponse, AppError> {
    let guard = PathGuard::from_config(&state.config);
    let validated_path = guard
        .validate(&query.file)
        .map_err(AppError::PathNotAllowed)?;

    let diff_text = operations::git_diff(&validated_path)?;
    Ok(axum::response::Response::builder()
        .status(StatusCode::OK)
        .header("content-type", "text/plain; charset=utf-8")
        .body(axum::body::Body::from(diff_text))
        .unwrap()
        .into_response())
}
```

- [ ] **Step 2: Commit**

```bash
git add opencode-bridge/src/git/router.rs
git commit -m "feat(bridge): add git API handlers (status + diff)"
```

---

## Task 4: Bridge — git/mod.rs + lib.rs + server.rs 注册模块与路由

**Files:**
- Create: `D:\openmate\opencode-bridge\src\git\mod.rs`
- Modify: `D:\openmate\opencode-bridge\src\lib.rs:1-18`
- Modify: `D:\openmate\opencode-bridge\src\server.rs:1-15` (imports) and `server.rs:81-137` (routes)

- [ ] **Step 1: 创建 git/mod.rs**

```rust
pub mod operations;
pub mod router;
```

- [ ] **Step 2: 在 lib.rs 新增模块声明**

在 `pub mod fs;` 之后新增：

```rust
pub mod git;
```

- [ ] **Step 3: 在 server.rs 新增 import**

在 `use crate::fs;` 之后新增：

```rust
use crate::git;
```

- [ ] **Step 4: 在 server.rs 注册路由**

在 `.route("/api/bridge/fs/rename", post(fs::router::rename))` 之后新增：

```rust
        .route("/api/bridge/git/status", get(git::router::status))
        .route("/api/bridge/git/diff", get(git::router::diff))
```

- [ ] **Step 5: 编译验证**

Run: `cd D:\openmate\opencode-bridge && cargo build`
Expected: 编译成功

- [ ] **Step 6: 运行全部测试**

Run: `cd D:\openmate\opencode-bridge && cargo test`
Expected: 所有测试通过

- [ ] **Step 7: Commit**

```bash
git add opencode-bridge/src/git/mod.rs opencode-bridge/src/lib.rs opencode-bridge/src/server.rs
git commit -m "feat(bridge): register git module and routes"
```

---

## Task 5: Android — BridgeGitStatusEntry DTO

**Files:**
- Modify: `D:\openmate\android\core\network\src\main\java\com\openmate\core\network\dto\BridgeDto.kt:166`

- [ ] **Step 1: 在 BridgeDto.kt 末尾新增 DTO**

在文件末尾追加：

```kotlin

@Serializable
data class BridgeGitStatusEntry(
    val path: String = "",
    val status: String = "",
    val oldPath: String? = null,
)
```

- [ ] **Step 2: Commit**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/dto/BridgeDto.kt
git commit -m "feat(android): add BridgeGitStatusEntry DTO"
```

---

## Task 6: Android — OpencodeApiClient 新增 bridgeGitStatus / bridgeGitDiff

**Files:**
- Modify: `D:\openmate\android\core\network\src\main\java\com\openmate\core\network\OpencodeApiClient.kt:437-440`

- [ ] **Step 1: 新增 import**

在 BridgeDto 相关 import 区域新增：

```kotlin
import com.openmate.core.network.dto.BridgeGitStatusEntry
```

- [ ] **Step 2: 在 bridgeSearch 方法之后新增两个方法**

```kotlin
    suspend fun bridgeGitStatus(path: String): List<BridgeGitStatusEntry> {
        return getList("/api/bridge/git/status", mapOf("path" to path))
    }

    suspend fun bridgeGitDiff(file: String): String {
        val url = buildUrl("/api/bridge/git/diff", mapOf("file" to file))
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw ServerUnavailableException("HTTP ${response.code}")
        }
        return response.body?.string() ?: ""
    }
```

- [ ] **Step 3: Commit**

```bash
git add android/core/network/src/main/java/com/openmate/core/network/OpencodeApiClient.kt
git commit -m "feat(android): add bridgeGitStatus and bridgeGitDiff API methods"
```

---

## Task 7: Android — GitChangesViewModel

**Files:**
- Create: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\GitChangesViewModel.kt`

- [ ] **Step 1: 创建 GitChangesViewModel.kt**

```kotlin
package com.openmate.feature.session

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.BridgeGitStatusEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GitChangesState(
    val loading: Boolean = true,
    val files: List<BridgeGitStatusEntry>? = null,
    val error: String? = null,
    val isNotGitRepo: Boolean = false,
)

@HiltViewModel
class GitChangesViewModel @Inject constructor(
    private val apiClient: OpencodeApiClient,
) : ViewModel() {
    private val TAG = "GitChangesVM"

    private val _state = MutableStateFlow(GitChangesState())
    val state: StateFlow<GitChangesState> = _state.asStateFlow()

    fun loadStatus(directory: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entries = apiClient.bridgeGitStatus(directory)
                _state.value = GitChangesState(loading = false, files = entries)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load git status", e)
                val msg = e.message ?: "Unknown error"
                val notGit = msg.contains("404") || msg.contains("not a git repository", ignoreCase = true)
                _state.value = GitChangesState(
                    loading = false,
                    error = if (notGit) null else msg,
                    isNotGitRepo = notGit,
                )
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/GitChangesViewModel.kt
git commit -m "feat(android): add GitChangesViewModel"
```

---

## Task 8: Android — GitChangesScreen UI

**Files:**
- Create: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\component\GitChangesScreen.kt`

- [ ] **Step 1: 创建 GitChangesScreen.kt**

```kotlin
package com.openmate.feature.session.component

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.app.diff.DiffViewerActivity
import com.openmate.core.network.dto.BridgeGitStatusEntry
import com.openmate.feature.session.GitChangesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitChangesScreen(
    directory: String,
    onBack: () -> Unit,
    viewModel: GitChangesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(directory) {
        viewModel.loadStatus(directory)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Git Changes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.isNotGitRepo -> {
                    Text(
                        text = "此目录不在 Git 仓库中",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.error != null -> {
                    Text(
                        text = state.error!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.files != null && state.files.isEmpty() -> {
                    Text(
                        text = "没有未提交的变更",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                state.files != null -> {
                    FileList(
                        files = state.files!!,
                        directory = directory,
                        context = context,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileList(
    files: List<BridgeGitStatusEntry>,
    directory: String,
    context: android.content.Context,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items(files, key = { it.path }) { entry ->
            val color = statusColor(entry.status)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = DiffViewerActivity.intent(
                            context = context,
                            sessionId = "",
                            messageId = "",
                            toolName = "git",
                            filePath = entry.path,
                            directory = directory,
                        )
                        context.startActivity(intent)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = statusIcon(entry.status),
                    contentDescription = entry.status,
                    tint = color,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun statusColor(status: String): Color {
    return when (status) {
        "modified" -> Color(0xFFFFA500)
        "added" -> Color(0xFF4CAF50)
        "deleted" -> Color(0xFFF44336)
        "untracked" -> Color(0xFF42A5F5)
        "renamed" -> Color(0xFF9C27B0)
        "unmerged" -> Color(0xFFFF5722)
        else -> MaterialTheme.colorScheme.onSurface
    }
}

private fun statusIcon(status: String) = when (status) {
    "added" -> Icons.Default.Add
    "deleted" -> Icons.Default.Close
    "modified" -> Icons.Default.Edit
    else -> Icons.Default.Edit
}
```

- [ ] **Step 2: Commit**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/component/GitChangesScreen.kt
git commit -m "feat(android): add GitChangesScreen UI"
```

---

## Task 9: Android — SessionNavigation 新增路由

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\SessionNavigation.kt`

- [ ] **Step 1: 新增 import**

在现有 import 区域新增：

```kotlin
import com.openmate.feature.session.component.GitChangesScreen
```

- [ ] **Step 2: 在 SessionRoutes object 中新增常量**

在 `const val LOCAL_FILE_MANAGER = "local_file_manager"` 之后新增：

```kotlin
    const val GIT_CHANGES = "git_changes"
```

- [ ] **Step 3: 在 sessionScreens 函数中新增 composable 路由**

在 `composable(SessionRoutes.LOCAL_FILE_MANAGER)` 块之后新增：

```kotlin
    composable(
        route = "${SessionRoutes.GIT_CHANGES}/{directory}",
        arguments = listOf(navArgument("directory") { type = NavType.StringType }),
    ) { backStackEntry ->
        val encoded = backStackEntry.arguments?.getString("directory") ?: return@composable
        val directory = URLDecoder.decode(encoded, "UTF-8")
        GitChangesScreen(
            directory = directory,
            onBack = { navController.popBackStack() },
        )
    }
```

- [ ] **Step 4: 在 SESSION_DETAIL 和 SUBTASK_DETAIL composable 中新增 onNavigateToGitChanges 回调**

在 `SessionDetailScreen` 的 `SESSION_DETAIL` composable 中，`onNavigateToBrowser` 之后新增：

```kotlin
            onNavigateToGitChanges = { directory ->
                val encoded = URLEncoder.encode(directory, "UTF-8")
                navController.navigate("${SessionRoutes.GIT_CHANGES}/$encoded")
            },
```

同样在 `SUBTASK_DETAIL` composable 中做相同新增。

- [ ] **Step 5: Commit**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/SessionNavigation.kt
git commit -m "feat(android): add GIT_CHANGES route and navigation"
```

---

## Task 10: Android — SessionDetailScreen 新增工具栏按钮与回调

**Files:**
- Modify: `D:\openmate\android\feature\session\src\main\java\com\openmate\feature\session\SessionDetailScreen.kt:142` (callback param) and `:930` (button)

- [ ] **Step 1: 新增 import**

在 import 区域新增：

```kotlin
import androidx.compose.material.icons.filled.Compare
```

- [ ] **Step 2: 在 SessionDetailScreen 函数签名新增回调参数**

在 `onNavigateToBrowser: (directory: String) -> Unit = {},` 之后新增：

```kotlin
    onNavigateToGitChanges: (directory: String) -> Unit = {},
```

- [ ] **Step 3: 在工具栏 FolderOpen 按钮之后新增 Compare 按钮**

在 `Icons.Default.FolderOpen` 的 `IconButton` 闭合 `}` 之后（约 L930），新增：

```kotlin
                IconButton(
                    onClick = { onNavigateToGitChanges(viewModel.getWorkingDirectory()) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Compare,
                        contentDescription = "Git Changes",
                        modifier = Modifier.size(24.dp),
                    )
                }
```

- [ ] **Step 4: Commit**

```bash
git add android/feature/session/src/main/java/com/openmate/feature/session/SessionDetailScreen.kt
git commit -m "feat(android): add Git Changes button to session toolbar"
```

---

## Task 11: Android — DiffViewerViewModel 支持 git diff 场景

**Files:**
- Modify: `D:\openmate\android\app\src\main\java\com\openmate\app\diff\DiffViewerScreen.kt:318-332`

- [ ] **Step 1: 在 DiffViewerViewModel.loadDiff 中新增 git 分支**

将 `loadDiff` 方法改为：

```kotlin
    fun loadDiff(sessionId: String, messageId: String, toolName: String, targetFilePath: String?, directory: String) {
        currentDirectory = directory
        viewModelScope.launch {
            try {
                if (toolName == "git") {
                    val filePath = targetFilePath ?: ""
                    val fullPath = if (filePath.startsWith("/") || (filePath.length >= 3 && filePath[1] == ':')) {
                        filePath
                    } else {
                        "${directory.replace('\\', '/')}/$filePath"
                    }
                    val rawDiff = apiClient.bridgeGitDiff(fullPath)
                    val files = parseRawDiff(rawDiff)
                    if (files.isEmpty()) {
                        state.value = DiffViewState(loading = false, isEmpty = true)
                    } else {
                        state.value = DiffViewState(loading = false, files = files)
                    }
                } else {
                    val files = sessionMessageRepository.fetchDiffFiles(sessionId, messageId, toolName, targetFilePath)
                    if (files.isEmpty()) {
                        state.value = DiffViewState(loading = false, isEmpty = true)
                    } else {
                        state.value = DiffViewState(loading = false, files = files)
                    }
                }
            } catch (e: Exception) {
                state.value = DiffViewState(loading = false, error = e.message ?: "Failed to load diff")
            }
        }
    }

    private fun parseRawDiff(rawDiff: String): List<DiffFile> {
        if (rawDiff.isBlank()) return emptyList()
        val files = mutableListOf<DiffFile>()
        val lines = rawDiff.lines()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("diff --git")) {
                val headerStart = i
                var filePath = ""
                while (i < lines.size && !lines[i].startsWith("@@")) {
                    if (lines[i].startsWith("+++ b/")) {
                        filePath = lines[i].removePrefix("+++ b/")
                    } else if (lines[i].startsWith("--- a/")) {
                        if (lines[i] == "--- /dev/null" && filePath.isEmpty()) {
                            // new file, path from +++ line
                        }
                    }
                    i++
                }
                if (filePath.isEmpty() && headerStart + 1 < lines.size) {
                    val diffLine = lines[headerStart]
                    val parts = diffLine.split(" ")
                    if (parts.size >= 4) filePath = parts[3].removePrefix("b/")
                }
                val hunks = mutableListOf<DiffHunk>()
                while (i < lines.size) {
                    if (lines[i].startsWith("@@")) {
                        val hunkHeader = lines[i]
                        val hunkLines = mutableListOf<String>()
                        i++
                        while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("diff --git")) {
                            hunkLines.add(lines[i])
                            i++
                        }
                        hunks.add(DiffHunk(header = hunkHeader, lines = hunkLines))
                    } else if (lines[i].startsWith("diff --git")) {
                        break
                    } else {
                        i++
                    }
                }
                files.add(DiffFile(path = filePath, hunks = hunks))
            } else {
                i++
            }
        }
        return files
    }
```

- [ ] **Step 2: 确认 DiffFile 和 DiffHunk 数据类存在**

检查 `DiffViewerScreen.kt` 中是否已有 `DiffFile` 和 `DiffHunk` 数据类定义。如果没有，在 `DiffViewState` 之前新增：

```kotlin
data class DiffHunk(
    val header: String,
    val lines: List<String>,
)

data class DiffFile(
    val path: String,
    val hunks: List<DiffHunk>,
)
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/openmate/app/diff/DiffViewerScreen.kt
git commit -m "feat(android): support git diff in DiffViewerViewModel"
```

---

## Task 12: 集成验证

- [ ] **Step 1: Bridge 编译 + 测试**

Run: `cd D:\openmate\opencode-bridge && cargo test`
Expected: 所有测试通过

- [ ] **Step 2: Android 编译**

Run: `Invoke-RestMethod -Uri "http://localhost:5099/api/gradle/run" -Method Post -ContentType "application/json" -Body '{"args":[":app:assembleDebug"],"cwd":"D:\\openmate"}'`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Bridge 本地部署测试**

```powershell
python D:\openmate\scripts\update-bridge.ps1
```

- [ ] **Step 4: 手动验证 Bridge git API**

```powershell
# 获取 token
$token = (Get-Content D:\openmate\scripts\.bridge_token).Trim()

# 测试 git status
curl -s http://127.0.0.1:4097/api/bridge/git/status?path=D:/openmate -H "Authorization: Bearer $token"

# 测试 git diff（取上面返回的某个文件路径）
curl -s "http://127.0.0.1:4097/api/bridge/git/diff?file=D:/openmate/android/core/network/src/main/java/com/openmate/core/network/dto/BridgeDto.kt" -H "Authorization: Bearer $token"
```

Expected: status 返回 JSON 数组，diff 返回纯文本 unified diff

- [ ] **Step 5: Commit（如有修复）**

如有编译或测试修复，提交修复。
