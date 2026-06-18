# AGENTS.md — Simulator & Docker Test Environment

Android 模拟器 + Docker Bridge 容器的端到端自动化测试环境。Agent 可通过 uiautomator2 操作模拟器 UI，配合 Bridge/opencode 容器完成配对、会话、消息等全流程测试。

## 拓扑

```
┌──────────────────────────────────────────────────────────────┐
│  Windows 宿主机                                                │
│                                                                │
│  ┌──────────────────────┐  10.0.2.2:4097  ┌──────────────────┐ │
│  │ Android Emulator      │◄──────────────►│ Docker Container  │ │
│  │  - OpenMate APK       │                 │  - Bridge :4097   │ │
│  │  - openmate-test AVD  │                 │  - opencode :4096 │ │
│  │  - 720x1280 @320dpi   │                 │  - sqlite3/python3│ │
│  └──────────────────────┘                  └──────────────────┘ │
│       ↑ uiautomator2                           ↑ API / DB 查询  │
│       │                                         │                │
│  测试脚本 (pair.py, session.py, look.py)                         │
└──────────────────────────────────────────────────────────────┘
```

## 环境要求

- Docker Desktop 运行中
- Android SDK：`$env:LOCALAPPDATA\Android\Sdk`
- AVD `openmate-test` 已创建（pixel_7, API 36.1, 720x1280@320dpi）
- OpenMate APK 已安装到模拟器
- Python 依赖：`pip install uiautomator2`

## 快速启动

```powershell
# 1. 启动 Bridge 容器
docker compose -f D:\openmate\docker\docker-compose.yml up -d

# 2. 启动模拟器
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
Start-Process "$sdk\emulator\emulator.exe" -ArgumentList '-avd','openmate-test','-no-snapshot-save','-no-audio','-gpu','swiftshader_indirect'

# 3. 等待就绪（约 60-90 秒）
# 反复执行直到看到 emulator-5554	device
adb devices

# 4. 启动 OpenMate
adb shell am start -n com.openmate/.app.MainActivity

# 5. 配对
python D:\openmate\simulator\pair.py

# 6. 创建会话并发消息
python D:\openmate\simulator\session.py
```

## 关闭

```powershell
adb -s emulator-5554 emu kill
docker compose -f D:\openmate\docker\docker-compose.yml down
```

## Docker 容器

- **Dockerfile**: `D:\openmate\docker\Dockerfile`（debian:bookworm-slim + opencode-ai + bridge + sqlite3 + python3）
- **Compose**: `D:\openmate\docker\docker-compose.yml`（端口 4097, `restart: unless-stopped`）
- **容器名**: `openmate-bridge`
- **Bridge 端口**: 4097（对外映射）
- **opencode 端口**: 4096（仅容器内部）

### 容器内操作

```powershell
# 进入容器 shell（需在真实终端，PowerShell 的 docker exec -it 不兼容）
docker exec -it openmate-bridge bash

# 容器内查询 opencode API
curl -s http://127.0.0.1:4096/api/session
curl -s http://127.0.0.1:4096/api/model

# 容器内查询 opencode 事件 DB
sqlite3 /root/.local/share/opencode/opencode.db "SELECT seq, type FROM event ORDER BY seq DESC LIMIT 10"

# 容器内查询 Bridge 配对状态
sqlite3 /root/.openmate/bridge.db "SELECT * FROM paired_devices"

# Bridge 日志
cat /root/.openmate/bridge.log

# opencode 日志
cat /root/.local/share/opencode/log/opencode.log

# 重启容器（保留数据：restart 不 recreate，只有 down+up 会 recreate）
docker restart openmate-bridge

# 重建容器（数据会丢失！需重新配对）
docker compose -f D:\openmate\docker\docker-compose.yml down
docker compose -f D:\openmate\docker\docker-compose.yml up -d
```

### 重要：数据持久性

- `docker restart` — 进程重启，SQLite 数据保留（`paired_devices`、opencode 事件等不丢失）
- `docker compose down` + `up -d` — 容器 recreate，所有数据丢失，需重新配对
- 当前 compose 未挂载 volume，数据仅在容器文件系统中
- 电脑休眠恢复 → Docker `restart: unless-stopped` 只做 restart，数据不丢

## 脚本说明

### `pair.py` — 自动配对

完整配对流程：Add Instance → Test Connection → Save → Approve PIN → Confirm。

```powershell
python pair.py                                            # 默认参数
python pair.py --name MyBridge --host 10.0.2.2 --port 4097  # 自定义
python pair.py --container openmate-bridge                    # 指定容器
```

前置条件：模拟器运行中，OpenMate app 已安装，Bridge 容器运行中。

### `session.py` — 创建会话 + 发消息

在已配对的实例上创建新会话（含工作目录选择）并发送消息。

```powershell
python session.py                                           # 默认
python session.py --title "My Session" --message "Explain recursion"
python session.py --directory /root/workspace               # 自定义目录
python session.py --wait 30                                  # 更长等待时间
```

前置条件：已完成配对（pair.py），模拟器和 Bridge 运行中。

### `look.py` — 屏幕检查器

通用 UI 状态检查工具。输出 texts、content-descs、EditText 字段信息，截图保存到 `screens/`。

```powershell
python look.py                  # 当前屏幕
python look.py --label my_test  # 自定义截图标签
```

**重要**：`look.py` 是稳定工具脚本，不要覆盖或修改为临时脚本。

## 编写自动化测试脚本

### uiautomator2 基础

```python
import uiautomator2 as u2
d = u2.connect('emulator-5554')
```

### UI 元素定位策略

代码库未使用 `Modifier.testTag()`，定位策略优先级：

1. **contentDescription**（最可靠）→ `d(description="Add Instance")`
2. **按钮文本** → `d(text="Save")`
3. **className** → `d(className="android.widget.EditText")`（需 instance index 区分）

### API 36 输入法约束

- **禁止使用** `send_keys()` / `clear_text()` — API 36 上 InputManager.getInstance() 签名变更会崩溃
- **必须使用** `set_text()` — Accessibility ACTION_SET_TEXT，绕过 InputManager
- **关闭键盘**：点击输入法的 `d(description="Done")`，不能用 BACK 键（会退出表单页）

### 通用模式

```python
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from look import look

def dismiss_keyboard(d):
    if d(description="Done").exists:
        d(description="Done").click()
        time.sleep(0.5)

def wait_for_app(d, timeout=10):
    start = time.time()
    while time.time() - start < timeout:
        if d.app_current()["package"] == "com.openmate":
            return True
        time.sleep(1)
    return False

# 状态守卫：检查当前页面是否符合预期
def is_on_instance_list(d):
    return d(description="Add Instance").exists or d(description="Scan QR Code").exists

def is_on_instance_detail(d):
    return d(description="New Session").exists

# 截图 + 打印 UI 元素
_, texts, descs = look(d, "step_label")

# 输入文本
fields = d(className="android.widget.EditText")
fields[0].set_text("value")
dismiss_keyboard(d)
```

### 保存按钮特殊处理

Compose Button 的文本定位不可靠（可能被父布局遮挡）。已知坐标：

```python
# Save 按钮（Add Instance 表单）
d.click(360, 1036)
```

### 容器端操作

```python
import subprocess

def run(cmd, **kwargs):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kwargs)

# 批准配对 PIN
result = run(["docker", "exec", "openmate-bridge", "/usr/local/bin/openmate", "approve", pin])

# 查询 Bridge DB
result = run(["docker", "exec", "openmate-bridge", "sqlite3", "/root/.openmate/bridge.db",
              "SELECT count(*) FROM paired_devices"])
```

### 组合脚本示例

```python
"""
测试场景：配对 → 创建会话 → 发消息 → 检查响应 → 验证数据库
"""
import uiautomator2 as u2, time, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from pair import pair
from session import session
from look import look
import subprocess

d = u2.connect('emulator-5554')

# Step 1: 配对
if not pair(d, "Test-Bridge", "10.0.2.2", "4097", "openmate-bridge"):
    sys.exit(1)

# Step 2: 创建会话并发消息
if not session(d, "Test-Bridge", "E2E Test", "/root/workspace", "What is 2+2?", 15):
    sys.exit(1)

# Step 3: 检查模拟器 UI 上是否有 assistant 回复
_, texts, _ = look(d, "result")
has_response = any("4" in t for t in texts)
print(f"AI response present: {has_response}")

# Step 4: 检查容器端 opencode DB
result = subprocess.run(
    ["docker", "exec", "openmate-bridge", "sqlite3",
     "/root/.local/share/opencode/opencode.db",
     "SELECT count(*) FROM event WHERE type LIKE '%step.ended%'"],
    capture_output=True, text=True
)
event_count = int(result.stdout.strip())
print(f"Step-ended events in DB: {event_count}")
```

## 完整 UI 元素参考

详见 [`UI_REFERENCE.md`](UI_REFERENCE.md)，包含：
- 所有页面导航路由
- 各页面 UI 元素（text、contentDescription、className）
- OutlinedTextField 标签索引
- contentDescription 完整索引
- 按钮文本完整索引

## 完整模拟器操作参考

详见 [`README.md`](README.md)，包含：
- AVD 创建与分辨率调整
- 模拟器启动/停止/窗口位置调整
- APK 安装与启动
- 网络连通性（10.0.2.2）
- 完整一键启动/关闭流程

## 已知限制

- **相机不可用**：模拟器无相机，QR 扫码配对不可行，必须手动输入 Bridge 地址
- **Compose Button 定位**：文本选择器对 Compose Button 不可靠，已知坐标 `d.click(360, 1036)`
- **模型选择**：Model Picker 的搜索按 provider 分组，直接搜索模型名可能显示 "No models found"
- **配额耗尽测试**：需等模型配额自然耗尽或添加有配额限制的 provider
- **冷启动慢**：`-no-snapshot-load` 每次全新状态，启动约 60-90 秒
