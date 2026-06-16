---
name: release
description: 发布 OpenMate 新版本。当用户提到"发布"、"release"、"打版本"、"版本发布"、"新版本"时触发。更新版本号与 CHANGELOG，打 git 标签并推送，由 GitHub Actions 自动构建全平台产物并创建 Release。Release 成功后更新 version.json 并清除 CDN 缓存。
---

# OpenMate 发布流程

发布 = 更新版本号 + 编写 CHANGELOG + 打 tag 并 push + CI 构建 + 更新 version.json + 清 CDN 缓存。

**关键约束**：version.json 必须在 GitHub Release 创建且产物上传完成之后再更新并推送，否则用户触发更新时会下载到尚不存在的产物。

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
   - `git add opencode-bridge/Cargo.toml opencode-bridge/Cargo.lock android/app/build.gradle.kts CHANGELOG.md`
   - `git commit -m "release: v{版本号}"`

6. **打 tag 并推送（触发 CI）**
   - `git tag v{版本号}`
   - `git push origin main`
   - `git push origin v{版本号}` —— push 该 tag 即触发 `release.yml`
   - 如遇网络问题，确认 git 已配置代理：`git config http.proxy` / `git config https.proxy`

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

9. **更新 version.json（Release 成功后）**
   - **必须在 GitHub Release 创建且产物上传完成之后执行此步骤**
   - 编辑 `D:\openmate\version.json`，将 android 和 bridge 的 version、tag、releasedAt 更新为新版本
   - 示例：
     ```json
     {
       "android": {"version": "0.2.0", "tag": "v0.2.0", "releasedAt": "2026-06-16"},
       "bridge": {"version": "0.2.0", "tag": "v0.2.0", "releasedAt": "2026-06-16"}
     }
     ```
   - `git add version.json && git commit -m "chore: update version.json to v{版本号}"`
   - `git push origin main`

10. **清除 jsDelivr CDN 缓存**
    - `Invoke-RestMethod -Uri "https://purge.jsdelivr.net/gh/bob-dawson/openmate@main/version.json"`
    - 确认返回状态为 `finished`
    - 这确保客户端立即能通过 jsDelivr CDN 获取到新版 version.json

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
- `version.json` 在每次发布后手动更新，仅更新有变更的模块

## version.json 与自动更新机制

- `version.json` 位于仓库根目录，按模块记录最新发布版本
- 客户端通过 jsDelivr CDN（主）和 raw.githubusercontent.com（备）读取该文件判断是否有更新
- jsDelivr CDN 默认缓存约 12 小时，因此每次更新 version.json 后必须手动 purge
- raw.githubusercontent.com 无缓存，几乎实时，作为 fallback 兜底

## 注意事项

- 构建**完全由 GitHub Actions 负责**，无需本地 Rust/Android/WSL 编译环境
- tag `v*` 是 `release.yml` 的唯一触发条件；push 普通 commit 到 main 不会触发发布
- GitHub Release 的 release notes 由 `generate_release_notes: true` 自动生成（基于 commits/PRs）；项目根目录 `CHANGELOG.md` 是手动维护的详细中文变更日志，两者并存
- Android keystore 在 `D:\openmate\android\release.keystore`，密码在 `local.properties`，这两个文件已在 `.gitignore` 中，不要提交
- GitHub Actions 的 secrets（`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_PASSWORD`）需在仓库 Settings → Secrets 中配置，用于 CI 签名 APK
- **严禁在 Release 创建前更新 version.json**，否则用户会看到有新版本但下载失败
