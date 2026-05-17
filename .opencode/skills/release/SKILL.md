---
name: release
description: 发布 OpenMate 新版本。当用户提到"发布"、"release"、"打版本"、"版本发布"、"新版本"时触发。构建 Bridge (Windows) + Android APK，生成 CHANGELOG，复制安装说明，打 git 标签。
---

# OpenMate 发布流程

执行 `D:\openmate\scripts\release.ps1` 完成发布。

## 步骤

1. **确认版本号**
   - 读取当前版本：从 `D:\openmate\opencode-bridge\Cargo.toml` 的 `version` 字段
   - 提示用户确认或输入新版本号（如 `0.2.0`）
   - 如果用户指定了新版本号，脚本自动更新以下文件：
     - `D:\openmate\opencode-bridge\Cargo.toml` 的 `version`
     - `D:\openmate\android\app\build.gradle.kts` 的 `versionName`，并将 `versionCode` +1

2. **确认 git 工作区干净**
   - 运行 `git status --short`
   - 如果有未提交的变更，先提示用户是否提交
   - 不要自动提交，等用户确认

3. **执行发布脚本**
   ```powershell
   powershell -File D:\openmate\scripts\release.ps1 -SkipLinux
   ```
   - 默认跳过 Linux 交叉编译（需要额外配置交叉编译工具链）
   - 如果需要 Linux 构建，去掉 `-SkipLinux`

4. **验证产出**
   - 检查 `D:\openmate\release\{version}\` 目录内容
   - 应包含：`openmate.exe`、`OpenMate-{version}.apk`、`bridge.toml`、`CHANGELOG.md`、`INSTALL.md`
   - 检查 CHANGELOG.md 中文是否正常（非乱码）

5. **脚本自动处理**
   - Bridge 和 Android 全部编译成功后，自动 commit 版本号变更并打 git 标签
   - 编译失败则不提交不打标签
   - commit 消息格式：`release: v{版本号}`
   - 标签格式：`v{版本号}`

6. **Push**
   - 提示用户执行 `git push origin v{版本号}`

## 脚本参数

| 参数 | 说明 |
|------|------|
| `-Version "0.2.0"` | 指定版本号 |
| `-SkipBridge` | 跳过 Bridge 构建 |
| `-SkipAndroid` | 跳过 Android 构建 |
| `-SkipLinux` | 跳过 Linux 交叉编译（默认跳过） |
| `-SkipTag` | 不打 git 标签 |
| `-DryRun` | 预览模式，不实际执行 |

## 版本号规则

- Bridge 和 Android 版本号保持一致
- 版本号来源以 `Cargo.toml` 为准
- git 标签格式：`v{版本号}`（如 `v0.1.0`）
- CHANGELOG 只包含上一个 tag 到当前 HEAD 的变更

## 注意事项

- Bridge keystore 在 `D:\openmate\android\release.keystore`，密码在 `local.properties`
- 这两个文件已在 `.gitignore` 中，不要提交
- 发布脚本会自动处理 PowerShell 的 native 命令 stderr 编码问题
- Android 需要 `--no-daemon` 避免 Windows 上 Gradle daemon 挂起
