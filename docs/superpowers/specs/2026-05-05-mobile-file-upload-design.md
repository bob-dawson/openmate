# 手机本地文件上传设计

## 背景

当前 OpenMate Android 只能附加服务端文件（通过 Bridge 文件浏览器选取）。用户需要从手机本地发送照片、视频、文档到对话中，类似微信聊天发图片。

## 核心流程

```
用户点击"+" → 选择"相册"或"文件" → 系统选取器(多选) → 
读取 ContentResolver → 上传到 Bridge → attachFile() → 发送时复用现有附件机制
```

## Bridge 端改动

### 新增接口：`PUT /api/bridge/fs/upload`

```
PUT /api/bridge/fs/upload?path=<server_path>&createDirs=true
Content-Type: application/octet-stream
Body: <raw binary data>
```

- `path`: 服务端目标文件绝对路径
- `createDirs`: 是否自动创建父目录（默认 true）
- 请求体为原始二进制数据，服务端直接写入文件
- 同名文件覆盖
- 响应：`{"success": true}`

### 实现要点

在 `router.rs` 新增 `upload` handler：
- 复用 `PathGuard::validate` 校验路径
- 从 `Query` 参数获取 `path` 和 `createDirs`
- 从请求体读取 bytes（`axum::body::Bytes`），直接 `std::fs::write`
- 大小限制：服务端配置 `body_limit`（默认 30MB，留余量给 base64 等编码场景）

### 路由注册

```rust
.put("/api/bridge/fs/upload", fs::router::upload)
```

## Android 端改动

### 1. ChatInputBar 加"+"按钮

在输入框左侧加一个 `IconButton`，图标 `Icons.Default.Add`，点击回调 `onAttach`。

### 2. 附件类型选择 BottomSheet

新组件 `AttachOptionSheet`：
- "从相册选取" — 图标 `Icons.Default.PhotoLibrary`
- "从文件选取" — 图标 `Icons.Default.InsertDriveFile`
- "服务端文件" — 图标 `Icons.Default.Folder`（现有 FilePickerSheet）

### 3. 系统选取器

使用 Android Activity Result API：
- 相册：`PickMultipleVisualMedia(maxItems = 9)` — 支持多选照片/视频
- 文件：`OpenDocument` + `Intent.EXTRA_ALLOW_MULTIPLE` — 支持多选任意文件

在 `SessionDetailScreen` 中用 `rememberLauncherForActivityResult` 注册 launcher。

### 4. 上传逻辑

在 `SessionDetailViewModel` 新增：

```kotlin
fun uploadAndAttach(uris: List<Uri>, contentResolver: ContentResolver) {
    viewModelScope.launch(Dispatchers.IO) {
        _isUploading.value = true
        for (uri in uris) {
            try {
                val (filename, bytes) = readFileFromUri(uri, contentResolver)
                if (bytes.size > 20 * 1024 * 1024) {
                    _errorMessage.value = "$filename 超过 20MB 限制"
                    continue
                }
                val serverPath = "${currentDirectory}/.openmate/upload/$filename"
                apiClient.bridgeUploadFile(serverPath, bytes, createDirs = true)
                _attachedFiles.value = _attachedFiles.value + FileAttachment(serverPath, filename, guessMime(filename))
            } catch (e: Exception) {
                _errorMessage.value = "上传失败: ${e.message}"
            }
        }
        _isUploading.value = false
        draftSessionID?.let { saveDraft(it, _inputText.value, _attachedFiles.value) }
    }
}
```

### 5. OpencodeApiClient 新增方法

```kotlin
suspend fun bridgeUploadFile(path: String, bytes: ByteArray, createDirs: Boolean = true) {
    val params = mutableMapOf("path" to path, "createDirs" to createDirs.toString())
    val url = buildUrl("/api/bridge/fs/upload", params)
    val body = bytes.toRequestBody("application/octet-stream".toMediaType())
    val request = Request.Builder().url(url).put(body).build()
    val response = client.newCall(request).execute()
    if (!response.isSuccessful) throw Exception("Upload failed: HTTP ${response.code}")
}
```

### 6. 文件名获取

从 `ContentResolver` 查询 `OpenableColumns.DISPLAY_NAME`，如果获取不到则用 URI last path segment + 扩展名推断。

### 7. 上传状态

ViewModel 新增 `_isUploading: MutableStateFlow<Boolean>`，UI 显示上传中进度条（与 streaming progress 同位置）。

## 大小限制

- 客户端：20MB，超过则跳过并提示
- Bridge 服务端：body_limit 30MB（略大于客户端限制，留余量）

## 上传目录

统一上传到 `<项目目录>/.openmate/upload/`，同名文件覆盖。

## 附件发送

上传成功后复用现有 `attachFile(path, filename)` → `sendMessage()` 流程，无需改动 sendPrompt API。
