# OpenMate Android MVP — Implementation Plan

## Overview

OpenMate is a native Android client for opencode. Phase 1 connects directly to opencode server over Tailscale LAN. Phase 2 (deferred) adds Bridge Agent + Cloud Relay.

**Phase 1 scope**: Connect to a running `opencode serve` instance on the local network (Tailscale), authenticate with password, browse sessions, send messages (streaming), respond to permission/question requests.

**Not in scope**: File browsing, diff viewer, terminal, workspace management, Bridge Agent, Cloud Relay, GitHub OAuth, FCM push.

## Architecture

- **Kotlin + Jetpack Compose** + MVVM + Hilt + Room + OkHttp
- **Multi-module**: `app`, `core/domain`, `core/data`, `core/database`, `core/network`, `core/ui`, `core/common`, `feature/instance`, `feature/session`, `feature/settings`
- **Material 3 dark theme** based on opencode color palette
- **Per-instance SQLite** via Room (one DB per server connection profile)
- **SSE** via OkHttp for real-time event streaming
- **No mocking libraries** — use fakes/test doubles in tests
- **Google Truth** for test assertions
- **TDD**: write tests first, then implement

## Phase 1 Key API Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/session` | GET | List sessions (query: `directory`, `limit`, `start`, `search`) |
| `/session/{id}` | GET | Get session detail |
| `/session/{id}/message` | GET | Paginated messages (query: `limit`, `before` cursor) |
| `/session/{id}/message` | POST | Send message (streaming SSE response) |
| `/session/{id}/prompt_async` | POST | Send message async |
| `/session/{id}/abort` | POST | Abort current operation |
| `/session` | POST | Create session |
| `/session/{id}` | DELETE | Delete session |
| `/global/event` | GET | SSE stream for real-time updates |
| `/global/health` | GET | Health check |
| `/permission/{id}/reply` | POST | Respond to permission (body: `{reply, message?}`) |
| `/permission` | GET | List pending permissions |
| `/question/{id}/reply` | POST | Answer question (body: `{answers}`) |
| `/question/{id}/reject` | POST | Reject question |

**Auth**: If `OPENCODE_SERVER_PASSWORD` is set on server, all requests need `Authorization: Basic base64(:password)` header. If not set, no auth required.

**SSE format**: Standard SSE with `data:` fields only (no `event:` or `id:`). Each `data:` line is JSON: `{directory, project?, workspace?, payload: {type, properties}}`.

**Payload types for MVP**: `session.created`, `session.updated`, `session.deleted`, `session.status`, `message.updated`, `message.removed`, `message.part.updated`, `message.part.delta`, `message.part.removed`, `permission.asked`, `permission.replied`, `question.asked`, `question.replied`, `question.rejected`, `server.connected`, `server.heartbeat`.

## Module Dependency Graph

```
app
 ├── feature/instance
 ├── feature/session
 ├── feature/settings
 ├── core/ui
 └── core/data
      ├── core/domain
      ├── core/network
      ├── core/database
      └── core/common
```

## Task Index

| Task | Description | Depends On | File |
|------|-------------|------------|------|
| 1 | Scaffolding & Build Config | — | task-1-scaffolding.md |
| 2 | Core Domain Models & Repository Interfaces | 1 | task-2-domain.md |
| 3 | Core Network Layer | 1, 2 | task-3-network.md |
| 4 | Core Database Layer | 1, 2 | task-4-database.md |
| 5 | Core Data Layer (Repository Implementations) | 2, 3, 4 | task-5-data.md |
| 6 | Core UI + Common | 1 | task-6-ui-common.md |
| 7 | Feature: Instance (Connection Management) | 5, 6 | task-7-instance.md |
| 8 | Feature: Session (Chat + Permissions/Questions) | 5, 6 | task-8-session.md |
| 9 | Feature: Settings + App Navigation + Integration | 5, 6, 7, 8 | task-9-settings-integration.md |

## Testing Strategy

- Every module has `src/test/` and `src/androidTest/`
- Unit tests: JVM-based, use fakes for dependencies
- Integration tests: Android instrumented, use in-memory Room DB
- No mocking frameworks — hand-written fakes implementing repository interfaces
- TDD: write failing test → implement → verify pass
