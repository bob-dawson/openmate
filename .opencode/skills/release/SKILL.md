---
name: release
description: 发布 OpenMate 新版本。当用户提到"发布"、"release"、"打版本"、"版本发布"、"新版本"时触发。更新版本号与 CHANGELOG，打 git 标签并推送，由 GitHub Actions 自动构建全平台产物并创建 Release。
---

# OpenMate 发布流程

发布 = 更新版本号 + 编写 CHANGELOG + 打 tag 并 push。构建与产物上传全部由 GitHub Actions（`.github/workflows/release.yml`）在收到 `v*` tag 时自动完成，**无需本地编译**。

## 步骤

1. **确认版本号**
   - 读取当前版本：`D:\openmate\opencode-bridge\Cargo.toml` 的 `version` 字段
   - 提示用户确认或输入新版本号（如 `0.2.0`）
   - Bridge 和 Android 版本号保持一致

2. **确认 git 工作区干净**
   - 运行 `git status --short`
   - 如有未提交的变更，先提示用户处理，不要自动提交无关变更

3. **更新版本号**
   - `D:\openmate\opencode-bridge\Cargo.toml` 的 `version`
   - `D:\openmate\android\app\build.gradle.kts` 的 `versionName`，并将 `versionCode` +1

4. **编写 CHANGELOG**
   - 读取项目根目录 `CHANGELOG.md`，了解现有格式（中文，只描述 Bridge 和 Android 变更，其他组件不描述）
   - 运行 `git log v{上一个tag}..HEAD --oneline --no-merges` 获取本次变更
   - **筛选原则**：只记录用户已发布版本中遇到的问题修复；开发过程中发现并修复的 bug（用户从未遇到过）不写入
   - 人工精炼变更条目：将技术性 commit 归纳为用户可读的中文描述，按"新功能"/"问题修复"分类
   - 每条以 `**关键词**：` 开头，简洁说明功能或修复内容
   - 不描述尚未正式测试支持的平台（如 macOS）
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

5. **提交版本号与 CHANGELOG**
   - `git add opencode-bridge/Cargo.toml android/app/build.gradle.kts CHANGELOG.md`
   - `git commit -m "release: v{版本号}"`

6. **打 tag 并推送（触发 CI）**
   - `git tag v{版本号}`
   - `git push origin main`
   - `git push origin v{版本号}` —— push 该 tag 即触发 `release.yml`

7. **监控 CI 构建**
   - `gh run watch $(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId') --exit-status`
   - 确认 6 个构建 job 全部成功：
     - Android APK
     - Bridge (Windows)
     - Bridge (Linux x86_64, musl 静态)
     - Bridge (Linux arm64, musl 静态)
     - Bridge (macOS, Apple Silicon)
     - Relay Gateway (Linux)
   - 全部成功后，`release` job 会自动创建 GitHub Release 并上传所有产物（`generate_release_notes: true`）
   - 如有 job 失败，不打断：报告失败原因，修复后重新打 tag（需先删除旧 tag）

8. **验证 Release**
   - `gh release view v{版本号}` 确认 Release 已创建且产物完整
   - 核对产物清单（见下）

## CI 产物清单

`release` job 自动上传到 GitHub Release 的文件：

| 文件 | 说明 |
|------|------|
| `OpenMate-{version}.apk` | Android 客户端 |
| `openmate.exe` | Bridge, Windows |
| `openmate-linux-x86_64` | Bridge, Linux x86_64（musl 静态，兼容所有发行版） |
| `openmate-linux-arm64` | Bridge, Linux arm64（musl 静态） |
| `openmate-darwin-arm64` | Bridge, macOS Apple Silicon |
| `relay-gateway-linux-x86_64` | Relay Gateway, Linux |

Linux 二进制为 musl 静态链接，不依赖目标系统的 glibc/openssl，可在任意 Linux 发行版上运行。

## 版本号规则

- Bridge 和 Android 版本号保持一致
- 版本号来源以 `Cargo.toml` 为准
- git 标签格式：`v{版本号}`（如 `v0.2.0`）
- commit 消息格式：`release: v{版本号}`
- `versionCode` 每次发布递增 1，`versionName` 与 `Cargo.toml` 的 `version` 一致

## 注意事项

- 构建**完全由 GitHub Actions 负责**，无需本地 Rust/Android/WSL 编译环境
- tag `v*` 是 `release.yml` 的唯一触发条件；push 普通 commit 到 main 不会触发发布
- GitHub Release 的 release notes 由 `generate_release_notes: true` 自动生成（基于 commits/PRs）；项目根目录 `CHANGELOG.md` 是手动维护的详细中文变更日志，两者并存
- Android keystore 在 `D:\openmate\android\release.keystore`，密码在 `local.properties`，这两个文件已在 `.gitignore` 中，不要提交
- GitHub Actions 的 secrets（`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_PASSWORD`）需在仓库 Settings → Secrets 中配置，用于 CI 签名 APK
