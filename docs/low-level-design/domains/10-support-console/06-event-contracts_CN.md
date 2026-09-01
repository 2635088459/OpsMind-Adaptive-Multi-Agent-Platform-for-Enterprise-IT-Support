# Support Console — 事件契约

> **Document ID:** LLD-SC-006
> **Domain:** `10-support-console`
> **状态:** Draft

---

## 1. 本 domain 不发布事件，MVP 阶段也不消费实时流

与 09 号 domain 不同，support-console 的 MVP 阶段**完全依赖 REST 轮询**（`05-api-contracts` §4），不接入任何 SSE/RabbitMQ。这不是遗漏——是刻意的范围裁剪：坐席协作场景对"数据新鲜度"的容忍度比员工实时对话高得多（15-30 秒延迟完全可接受），优先把 UC-SC-01~06 的核心体验做扎实，实时推送留给明确的 phase 2+ non-goal。

## 2. 未来（phase 2+）预期的实时流形状

```text
event: queue.ticket-added
event: queue.ticket-updated
data: {"queueId","ticketId","status","priority"}
```

语义上对应 `02-ticket-workflow` 内部队列成员变化，具体契约届时由该 domain 新增能力时正式定义，本文档只做前瞻性占位，不当作已决定的设计。

## 3. 与 09 号 domain 事件契约的关系

两个 domain 未来如果都需要实时能力，`02-ticket-workflow` 应该只维护**一套**推送机制（很可能是同一个 SSE 网关按不同订阅粒度分发：单工单 vs 整个队列），而不是分别为两个前端 app 各建一套。这是留给 `02-ticket-workflow` 自己 roadmap 决定的实现细节，本文档只标注"两个消费方，同一个能力"这个约束，避免未来重复建设。
