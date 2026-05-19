# C.02: Android 远程配对流程

> 目标：用户通过网关远程配对 Bridge，无需局域网连接。Bridge 展示配对码，Android 输入完成配对。

## Files

- 需确认：配对相关 UI 和 ViewModel 文件路径

## Steps

- [ ] **Step 1: Bridge 侧远程配对入口**

Bridge 连接网关后，通过 CLI 或 Web UI 展示配对入口：

```
Bridge 启动 → 连网关 → 进入等待配对状态
用户执行: openmate pair-remote
→ Bridge 请求网关生成配对码
→ 网关返回 6 位配对码 + 有效期
→ Bridge 展示配对码
```

网关新增 API：

```
POST /api/gateway/pair/init
→ { "instance_id": "inst_xxx" }
← { "pair_code": "ABC123", "expires_in": 600 }
```

```
POST /api/gateway/pair/confirm
→ { "pair_code": "ABC123", "token": "..." }
← { "success": true }
```

- [ ] **Step 2: Android 配对 UI**

在添加实例页面，增加"远程配对"选项：

1. 用户输入网关地址（如 gw.openmate.dev）
2. 输入 Bridge 显示的 6 位配对码
3. Android 用配对码向网关验证
4. 成功后获取 token + instance_id
5. 保存为 REMOTE 类型实例

- [ ] **Step 3: 编译验证**

Run: `.\gradlew.bat assembleDebug --no-daemon 2>&1 | Select-String -Pattern "^e:|BUILD"`

- [ ] **Step 4: 提交**

```bash
git add android/ opencode-bridge/ server/relay-gateway/
git commit -m "feat: implement remote pairing flow for gateway"
```
