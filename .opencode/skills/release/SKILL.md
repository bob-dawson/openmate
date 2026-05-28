---
name: release
description: 发布 OpenMate 新版本。当用户提到"发布"、"release"、"打版本"、"版本发布"、"新版本"时触发。构建 Bridge (Windows + Linux via WSL) + Android APK，生成 CHANGELOG，复制安装说明，打 git 标签。
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

3. **编写 CHANGELOG**
     - 读取项目根目录 `CHANGELOG.md`，了解现有格式（中文，只描述 Bridge 和 Android 变更，其他组件不描述）
     - 运行 `git log v{上一个tag}..HEAD --oneline --no-merges` 获取本次变更
     - **筛选原则**：只记录用户已发布版本中遇到的问题修复；开发过程中发现并修复的 bug（用户从未遇到过）不写入
     - 人工精炼变更条目：将技术性 commit 归纳为用户可读的中文描述，按"新功能"/"问题修复"分类
     - 每条以 `**关键词**：` 开头，简洁说明功能或修复内容
     - 不描述尚未正式支持的平台（如 macOS）
     - 在 `CHANGELOG.md` 顶部（`# Changelog` 标题之后）插入新版本条目，保留所有历史版本
    - 格式模板：
      ```
      ## {版本号}

      Released: {yyyy-MM-dd}

      ### 新功能

      - **功能名**：描述

      ### 问题修复

      - 修复描述
      ```
    - 将 `CHANGELOG.md` 暂存到 git（`git add CHANGELOG.md`），随版本号一起提交

4. **执行发布脚本**
    ```powershell
    powershell -File D:\openmate\scripts\release.ps1
    ```
    - 默认包含 Linux 构建（通过 WSL Ubuntu-24.04 编译）
    - 如果不需要 Linux 版本，加 `-SkipLinux`

5. **验证产出**
    - 检查 `D:\openmate\release\{version}\` 目录内容
    - 应包含：`openmate.exe`、`openmate-linux-x86_64`、`OpenMate-{version}.apk`、`CHANGELOG.md`、`INSTALL.md`
    - 检查发布目录的 CHANGELOG.md 内容是否与项目根目录一致
    - 脚本会自动生成 commit 级别的 CHANGELOG，但发布目录的版本应被项目根目录的 `CHANGELOG.md` 覆盖

6. **脚本自动处理**
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
| `-SkipLinux` | 跳过 Linux 构建（默认包含，通过 WSL 编译） |
| `-SkipTag` | 不打 git 标签 |
| `-DryRun` | 预览模式，不实际执行 |

## 版本号规则

- Bridge 和 Android 版本号保持一致
- 版本号来源以 `Cargo.toml` 为准
- git 标签格式：`v{版本号}`（如 `v0.1.0`）
- CHANGELOG 只包含上一个 tag 到当前 HEAD 的变更
- CHANGELOG.md 存放在项目根目录，持续累积所有版本，发布时复制到发布目录

## 注意事项

- Bridge keystore 在 `D:\openmate\android\release.keystore`，密码在 `local.properties`
- 这两个文件已在 `.gitignore` 中，不要提交
- 发布脚本会自动处理 PowerShell 的 native 命令 stderr 编码问题
- Android 需要 `--no-daemon` 避免 Windows 上 Gradle daemon 挂起
- Linux 构建通过 `wsl -d Ubuntu-24.04` 调用 WSL 中的 `cargo build --release`，产出 Linux 原生 ELF binary
- WSL 需要已安装 Rust 工具链（`~/.cargo/env`）
