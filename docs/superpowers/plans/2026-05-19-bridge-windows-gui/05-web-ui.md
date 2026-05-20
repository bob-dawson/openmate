# 步骤 05: Web UI — 管理页面静态 HTML

> 依赖: 步骤 04（Management APIs）
> 产出: `src/ui/` 目录（HTML/JS 文件），修改 `server.rs`

## 背景

管理页面是纯静态 HTML+JS，通过 `include_str!` 编译期嵌入二进制。UI 布局参照 `docs/bridge-windows-gui-mockup.html`。

## 文件结构

```
src/ui/
├── mod.rs           # 路由注册 + include_str! 嵌入
├── index.html       # 主页面（概览 + 扫码连接 + 日志 + 设置，SPA 切换）
└── download.html    # 扫码落地页（移动端友好，独立页面）
```

## 实现步骤

### Step 1: 创建 `src/ui/mod.rs`

```rust
use axum::response::Html;
use axum::routing::get;
use axum::Router;
use crate::state::AppState;

static INDEX_HTML: &str = include_str!("index.html");
static DOWNLOAD_HTML: &str = include_str!("download.html");

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/ui/", get(index))
        .route("/ui/index.html", get(index))
        .route("/ui/download", get(download))
}

async fn index() -> Html<&'static str> {
    Html(INDEX_HTML)
}

async fn download() -> Html<&'static str> {
    Html(DOWNLOAD_HTML)
}
```

### Step 2: 创建 `src/ui/index.html` — 主管理页面

基于 mockup (`docs/bridge-windows-gui-mockup.html`) 的完整 HTML。

**关键 JavaScript API 调用**:

```javascript
// 概览数据
async function loadOverview() {
    const [status, devices, pendingPairs, version] = await Promise.all([
        fetch('/api/bridge/status').then(r => r.json()),
        fetch('/api/bridge/devices').then(r => r.json()),
        // pending pairs: 需要新增 API 或从 status 中获取
        fetch('/api/bridge/opencode/version').then(r => r.json()),
    ]);
    // 渲染状态卡片、设备列表、配对请求
}

// 批准配对
async function approvePair(pin) {
    await fetch('/api/bridge/pair/approve', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({pin}),
    });
    loadOverview();
}

// 删除设备
async function deleteDevice(deviceId) {
    if (!confirm('确认删除该设备？删除后需重新配对。')) return;
    await fetch(`/api/bridge/devices/${deviceId}`, {method: 'DELETE'});
    loadOverview();
}

// 改名
async function renameDevice(deviceId) {
    const name = prompt('输入设备名称:');
    if (!name) return;
    await fetch(`/api/bridge/devices/${deviceId}/name`, {
        method: 'PUT',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({name}),
    });
    loadOverview();
}

// 升级 opencode
async function upgradeOpencode() {
    await fetch('/api/bridge/opencode/upgrade', {method: 'POST'});
    alert('升级已开始');
}

// QR 码加载
async function loadQR() {
    const ifaces = await fetch('/api/bridge/network/interfaces').then(r => r.json());
    // 填充网卡下拉
    // 默认选第一个，加载 QR 码
    updateQR(ifaces[0].ip);
}

function updateQR(ip) {
    document.getElementById('qr-img').src = `/api/bridge/qrcode?ip=${ip}`;
    document.getElementById('qr-url').textContent = `http://${ip}:${window.location.port}/ui/download`;
}

// 日志 SSE
function connectLogStream() {
    const evtSource = new EventSource('/api/bridge/logs/stream');
    evtSource.onmessage = (e) => {
        const entry = JSON.parse(e.data);
        appendLogLine(entry);
    };
}

// 日志查询（首次加载 + 搜索/过滤）
async function loadLogs(level, search) {
    const params = new URLSearchParams();
    if (level) params.set('level', level);
    if (search) params.set('search', search);
    const logs = await fetch(`/api/bridge/logs?${params}`).then(r => r.json());
    renderLogs(logs);
}

// 自启动
async function loadAutostart() {
    const status = await fetch('/api/bridge/autostart').then(r => r.json());
    document.getElementById('autostart-toggle').classList.toggle('on', status.enabled);
}

async function toggleAutostart(enabled) {
    await fetch('/api/bridge/autostart', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({enabled}),
    });
}

// 重置密钥
async function resetKey() {
    if (!confirm('确认重置密钥？所有设备需要重新配对。')) return;
    // 需要新增 API 或调用 CLI
    await fetch('/api/bridge/reset-key', {method: 'POST'});
    alert('密钥已重置');
}
```

> 注：完整 HTML 文件直接从 `docs/bridge-windows-gui-mockup.html` 演化而来，替换 mock 数据为 JS API 调用。文件较大（~500 行 HTML+CSS+JS），此处不重复列出，实施时基于 mockup 改造。

### Step 3: 创建 `src/ui/download.html` — 扫码落地页

移动端友好，用于手机扫码后显示 Bridge 地址 + APK 下载。

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>OpenMate</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body { font-family: -apple-system, "Segoe UI", sans-serif; background: #0f172a; color: #e2e8f0; min-height: 100vh; display: flex; align-items: center; justify-content: center; }
    .card { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 32px; max-width: 400px; width: 90%; text-align: center; }
    h1 { font-size: 20px; color: #60a5fa; margin-bottom: 24px; }
    .section { margin-bottom: 20px; }
    .label { font-size: 12px; color: #64748b; margin-bottom: 6px; }
    .url { font-family: monospace; background: #0f172a; padding: 10px; border-radius: 6px; font-size: 13px; color: #60a5fa; word-break: break-all; margin: 8px 0; }
    .btn { display: inline-block; padding: 10px 24px; background: #3b82f6; color: white; border: none; border-radius: 8px; font-size: 14px; text-decoration: none; }
    .btn:hover { background: #2563eb; }
    ol { text-align: left; font-size: 13px; line-height: 2; color: #94a3b8; padding-left: 20px; margin-top: 16px; }
  </style>
</head>
<body>
  <div class="card">
    <h1>OpenMate Bridge</h1>
    <div class="section">
      <div class="label">Bridge 地址</div>
      <div class="url" id="bridge-url"></div>
      <button class="btn" onclick="copyUrl()" style="font-size:12px;padding:6px 16px;margin-top:4px">复制</button>
    </div>
    <div class="section">
      <div class="label">下载 Android 客户端</div>
      <a class="btn" id="apk-link" href="/download/openmate.apk">下载 APK</a>
    </div>
    <div class="section">
      <div class="label">连接步骤</div>
      <ol>
        <li>下载并安装 OpenMate App</li>
        <li>打开 App，输入上方 Bridge 地址</li>
        <li>在电脑管理页面批准配对 PIN</li>
      </ol>
    </div>
  </div>
  <script>
    const port = window.location.port || (window.location.protocol === 'https:' ? '443' : '80');
    const url = `http://${window.location.hostname}:${port}`;
    document.getElementById('bridge-url').textContent = url;
    function copyUrl() {
      navigator.clipboard.writeText(url);
    }
  </script>
</body>
</html>
```

### Step 4: 修改 `src/server.rs` — 注册 UI 路由

```rust
// 在 Router 构建中添加（在 fallback 之前）
.merge(crate::ui::routes())
```

### Step 5: 修改 `src/lib.rs`

```rust
pub mod ui;  // 新增
```

### Step 6: 修改 `src/auth/middleware.rs`

确保 `/ui/` 前缀路径走 localhost 检查：

```rust
// 在 auth_middleware 中，对 /ui/ 开头的路径做 localhost 检查
if path.starts_with("/ui/") {
    if let Some(addr) = addr {
        if addr.ip().is_loopback() {
            return next.run(req).await;
        }
    }
    return (StatusCode::FORBIDDEN, "Forbidden").into_response();
}
```

> `/ui/download` 是公开的，需要特殊处理。

### Step 7: 验证

```powershell
cargo build
# 启动 Bridge，浏览器访问 http://localhost:4097/ui/
# 手机扫码访问 http://<ip>:4097/ui/download
```

### Step 8: 提交

```
feat(bridge): add web management UI with overview, QR, logs, settings pages
```
