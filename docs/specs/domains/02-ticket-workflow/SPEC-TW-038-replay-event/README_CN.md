# SPEC-TW-038 — Replay Event（事件重放）

## 1. 目标

安全重放 outbox、consumer inbox 或 DLQ 消息，并保持幂等与顺序保护。

## 2. 范围

包含：

- `/internal/v1/tickets/events/replay`；
- recovery command、case/attempt/audit 记录；
- 幂等、版本、状态机 guard；
- `ticket.event-replay-recorded.v1`。

不包含：

- 新增 Ticket 主 happy path；
- 绕过 Phase 01～09 的状态机和安全规则；
- 静默修改历史事件。

## 3. 核心规则

- replay 必须以 original event id 和 replay attempt id 双重幂等。
- command 必须记录 actor、reason、correlationId、causationId；
- duplicate command 必须幂等；
- recovery action 必须可审计、可回放、可解释。
