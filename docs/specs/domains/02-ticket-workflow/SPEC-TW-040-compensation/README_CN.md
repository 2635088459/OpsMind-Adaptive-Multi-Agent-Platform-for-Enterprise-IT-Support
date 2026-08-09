# SPEC-TW-040 — Compensation（补偿动作）

## 1. 目标

执行受控补偿动作，使 Ticket 与外部副作用或工作流状态重新一致。

## 2. 范围

包含：

- `/internal/v1/tickets/{ticketId}/compensations`；
- recovery command、case/attempt/audit 记录；
- 幂等、版本、状态机 guard；
- `ticket.compensation-executed.v1`。

不包含：

- 新增 Ticket 主 happy path；
- 绕过 Phase 01～09 的状态机和安全规则；
- 静默修改历史事件。

## 3. 核心规则

- compensation 必须选择已定义 action，不允许任意 SQL/任意状态修改。
- command 必须记录 actor、reason、correlationId、causationId；
- duplicate command 必须幂等；
- recovery action 必须可审计、可回放、可解释。
