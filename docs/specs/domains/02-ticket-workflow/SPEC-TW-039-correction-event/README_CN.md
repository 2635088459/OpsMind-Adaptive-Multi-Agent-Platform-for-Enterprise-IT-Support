# SPEC-TW-039 — Correction Event（修正事件）

## 1. 目标

通过显式 correction event 修正错误事实，同时保留原始历史和审计链。

## 2. 范围

包含：

- `/internal/v1/tickets/{ticketId}/correction-events`；
- recovery command、case/attempt/audit 记录；
- 幂等、版本、状态机 guard；
- `ticket.correction-event-published.v1`。

不包含：

- 新增 Ticket 主 happy path；
- 绕过 Phase 01～09 的状态机和安全规则；
- 静默修改历史事件。

## 3. 核心规则

- correction event 不得删除或改写原始事件。
- command 必须记录 actor、reason、correlationId、causationId；
- duplicate command 必须幂等；
- recovery action 必须可审计、可回放、可解释。
