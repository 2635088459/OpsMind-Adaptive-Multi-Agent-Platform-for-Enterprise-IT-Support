# Support Console — 数据模型

> **Document ID:** LLD-SC-007
> **Domain:** `10-support-console`
> **状态:** Draft

---

## 1. 本 domain 同样不拥有后端 Schema

与 09 号 domain 同一原则——所有业务事实都在 02/05/06/07 号 domain 自己的数据库里，support-console 只有客户端展示态。

## 2. 本地存储模型

### 2.1 内存态（Zustand，不持久化）
```text
selectedTicketId: string | null
queuePollingState: "LOADING" | "LIVE_POLLING" | "DEGRADED"
ticketDetailState: "UNSELECTED" | "LOADING_DETAIL" | "READY" | "PARTIAL"
```

### 2.2 sessionStorage — 坐席的界面偏好（非跨会话持久化）
```text
key: pref:selectedQueueId      // 上次查看的队列，刷新页面后恢复
key: pref:sortOrder            // 队列排序方式
```

刻意用 `sessionStorage` 而非 `localStorage`：坐席界面偏好只在当次工作会话内保持，不需要跨天持久化（与 09 号 domain 的草稿场景性质不同，那边必须跨会话持久化，这里不需要）。

### 2.3 TanStack Query 缓存（服务端数据，非本地权威副本）
`QueueView`、`TicketDetailView`、`ApprovalRequestView` 全部通过 TanStack Query 管理，遵循与 09 号 domain 同样的原则：前端缓存只是展示优化，从不作为权威数据源。

## 3. 与 09 号 domain 共享的类型来源

`packages/api-contracts` 里 `TicketStatusView`/`RiskLevel`/`TicketStatus` 等类型两个 domain 共用一份定义（见 `01-domain-model` §4），本文档不重复声明。
