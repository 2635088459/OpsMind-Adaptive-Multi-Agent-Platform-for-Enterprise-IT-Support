# SPEC-TW-041 — Data Integrity Repair（数据完整性修复）

## 1. 目标

扫描并修复受控数据完整性问题，例如 projection 缺失、history/audit/outbox 不一致。

## 2. 范围

包含：

- `/internal/v1/tickets/integrity-repairs`；
- recovery command、case/attempt/audit 记录；
- 幂等、版本、状态机 guard；
- `ticket.integrity-repair-applied.v1`。

不包含：

- 新增 Ticket 主 happy path；
- 绕过 Phase 01～09 的状态机和安全规则；
- 静默修改历史事件。

## 3. 核心规则

- repair 必须先产生 scan finding 和 repair plan，再执行受控修复。
- command 必须记录 actor、reason、correlationId、causationId；
- duplicate command 必须幂等；
- recovery action 必须可审计、可回放、可解释。
