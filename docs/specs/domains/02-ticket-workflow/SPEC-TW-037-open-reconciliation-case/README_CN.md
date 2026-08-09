# SPEC-TW-037 — Open Reconciliation Case（打开对账案例）

## 1. 目标

为 unknown result、跨服务冲突、stale result 或数据不一致打开可审计 reconciliation case。

## 2. 范围

包含：

- `/internal/v1/tickets/{ticketId}/reconciliation-cases`；
- recovery command、case/attempt/audit 记录；
- 幂等、版本、状态机 guard；
- `ticket.reconciliation-case-opened.v1`。

不包含：

- 新增 Ticket 主 happy path；
- 绕过 Phase 01～09 的状态机和安全规则；
- 静默修改历史事件。

## 3. 核心规则

- reconciliation case 是恢复入口，不得直接修复业务状态。
- command 必须记录 actor、reason、correlationId、causationId；
- duplicate command 必须幂等；
- recovery action 必须可审计、可回放、可解释。
