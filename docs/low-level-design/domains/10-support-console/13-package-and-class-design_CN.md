# Support Console — 包结构与组件设计

> **Document ID:** LLD-SC-013
> **Domain:** `10-support-console`
> **状态:** Draft
> **技术基线:** React 19 + Vite 8.x + TypeScript（与 09 号 domain 共享同一套技术栈，冻结）

---

## 1. 目录结构

```text
apps/support-console/
├── src/
│   ├── app/
│   │   ├── router.tsx            # 分诊队列 / 待审批 / 我负责的 / 仪表盘 / 可观测性 各路由
│   │   └── providers.tsx
│   ├── features/
│   │   ├── queue/                # 队列列表、筛选、轮询
│   │   ├── ticket-detail/        # AiLogEntry 三路聚合、审批卡片
│   │   │   ├── components/
│   │   │   └── hooks/            # useAiLog（并发聚合三个请求）
│   │   ├── approvals/            # 待审批收件箱视图
│   │   ├── observability/        # Trace 瀑布图预览 + 评测对比表
│   │   └── auth/
│   ├── components/                # 共享展示层（shadcn/ui），含 SeverityChip、SlaCountdown 等
│   ├── api/
│   │   └── generated/             # 复用 packages/api-contracts，与 employee-portal 同一份生成产物
│   └── stores/
└── tests/
```

## 2. `useAiLog` —— 本 domain 最核心的一个 hook

对应 `05-api-contracts` §3 的聚合策略：

```text
useAiLog(ticketId)
  → 并发调用 fetchTicketTimeline / fetchRelatedToolRequests / fetchGovernanceAuditRecords
  → 各自独立的 loading/error 状态（不是合并成一个大的 loading/error）
  → 成功的部分立即合并展示，失败的部分标注具体缺失了哪一路（03-state-machine 的 PARTIAL 态在这里落地）
```

这个 hook 单独拎出来说明，是因为它是本 domain 与 09 号 domain 架构差异最大的地方——09 号 domain 的 hooks 大多是单一请求的简单封装，这里天然需要处理"多请求独立降级"的复杂度。

## 3. 严重程度/状态的展示态映射，集中一处

对应 BI-SC-004（展示态永远来自后端字段，不做前端二次判定）：

```text
components/SeverityChip.tsx    // priority → 颜色/图标的唯一映射表，不允许各 feature 各自写一份映射逻辑
components/SlaCountdown.tsx    // 展示后端给出的 slaDeadline，本地只做"还剩多久"的纯展示计算，不改变判定结果
```

## 4. 与 employee-portal 共享的部分

```text
packages/api-contracts    → 共享后端生成类型（两个 app 唯一的真实共享代码）
```

刻意**不**共享 UI 组件库之外的业务组件——`ProposedActionCard`（09 号）和 `ApprovalCard`（10 号）虽然概念上都是"审批相关"，但交互语境完全不同（员工看到的是"我的请求"，坐席看到的是"要不要批准别人的请求"），强行抽象共享组件会让两边的需求变化互相牵制，不值得。
