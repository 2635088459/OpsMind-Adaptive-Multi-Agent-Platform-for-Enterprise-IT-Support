# Employee Portal — 数据模型

> **Document ID:** LLD-EP-007
> **Domain:** `09-employee-portal`
> **状态:** Draft

---

## 1. 本 domain 不拥有任何后端 Schema

不像 02-08 号 domain 各自拥有 `ticket.*`/`agent.*`/`memory.*` 这样的 Postgres schema（见 shared baseline §7），employee-portal **没有服务端数据库**。这一篇描述的是浏览器本地存储的模型，属于前端工程实现细节，不进入平台的 schema-ownership 表。

## 2. 本地存储模型

### 2.1 IndexedDB — 会话缓存
```text
table: conversation_cache
  conversationId: string (PK)
  ticketId: string | null
  messages: Message[]          // 见 01-domain-model，序列化存储
  lastSyncedAt: datetime
```
用途：离线/弱网时仍能看到最近的对话历史；重新联网后以服务端数据为准做增量同步，**从不**以本地缓存覆盖服务端状态（呼应 BI-EP-005）。

### 2.2 localStorage — 草稿与偏好
```text
key: draft:{conversationId}      → 未发送的文本 + 待发送附件引用（BI-EP-006 依赖这个）
key: pref:theme                  → 浅色/深色/跟随系统
key: pref:lastActiveConversation → 用于 UC-EP-06 的"恢复上次会话"
```

### 2.3 内存态（Zustand store，不持久化）
```text
turnState: TurnState              // 见 03-state-machine §3.1
attachmentUploads: Map<id, UploadState>
ticketPanelConnectionState: "CONNECTING" | "LIVE" | "RECONNECTING" | "STALE"
```

## 3. 与后端数据的映射关系（只读投影，非本地权威副本）

| 前端类型 | 来源 domain | 拉取方式 |
|---|---|---|
| `TicketStatusView` | 02-ticket-workflow | REST 初始拉取 + SSE 增量更新 |
| `UserSession` | 01-user-access-authentication | OIDC 会话（cookie），不落地到 IndexedDB |
| `ProposedAction`/`EscalationNotice` | 03-agent-runtime-orchestration（待建） | 对话轮次响应内联返回，不单独拉取 |

## 4. 数据保留与清理

- `conversation_cache` 按 conversationId 保留最近 20 条活跃/已升级会话，超出后按最后活跃时间淘汰最旧的（纯客户端 LRU，不涉及后端）。
- 员工登出时清空 `draft:*` 之外的所有本地缓存（草稿保留，方便同一浏览器下次登录恢复——但注意这是同一账号自己的草稿，不同用户之间必须完全隔离，实现时按 `subject` 加前缀存储，避免同设备多账号串号）。
