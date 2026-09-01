# Support Console — 可观测性与审计

> **Document ID:** LLD-SC-012
> **Domain:** `10-support-console`
> **状态:** Draft

---

## 1. 前端角色与 09 号 domain 一致

同样只做 OpenTelemetry trace 透传的起点（`traceparent` header），不接触 LangSmith，不自己产生 span。详见 `09-employee-portal` 的 `12-observability-and-audit`，原则完全一致，不重复展开。

## 2. 本 domain 特有：自己就是"可观测性页面"的消费界面

09 号 domain 只是可观测性数据的产生源之一；10 号 domain 额外承担**展示**可观测性/评测数据的职责（视觉稿里的"可观测性·评测"页）。这不改变 §1 的边界——展示层仍然只是外链/只读聚合，不代表 support-console 自己变成了一个可观测性系统。

## 3. 坐席操作本身的审计

坐席在控制台里做的每一个操作（triage/assign/grant/deny）都会在对应后端 domain 产生真实审计记录（`02-ticket-workflow`/`06-policy-approval-governance` 已经真实实现）。前端职责与 09 号 domain 相同：确保每个请求携带真实 `actorId`（JWT `sub`）和 `correlationId`，不缺失不伪造。

## 4. 需要监控的前端专属指标（供未来仪表盘参考）

```text
队列轮询的平均延迟与失败率
AiLogEntry 三路聚合的部分失败频率（衡量后端各 domain 的可用性对坐席体验的实际影响）
版本冲突（VERSION_CONFLICT）触发频率（衡量真实的多坐席协作冲突有多常见，指导是否需要提前做 phase 2 的实时推送）
```
