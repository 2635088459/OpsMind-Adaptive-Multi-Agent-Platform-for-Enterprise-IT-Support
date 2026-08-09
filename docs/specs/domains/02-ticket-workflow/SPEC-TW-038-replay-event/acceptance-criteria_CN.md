# SPEC-TW-038 验收标准

## 功能验收

- 给定符合前置条件的 recovery command，系统生成受控恢复结果并记录 `ticket.event-replay-recorded.v1`。
- 给定 duplicate command，系统返回首次结果，不重复执行副作用。
- 给定 stale 或非法状态，系统拒绝并保留可观测 decision。

## 安全与审计

- actor、reason、correlationId、causationId 必填或可追踪；
- 拒绝路径不发布成功事件；
- audit payload 不含 secret、token 或高基数字段。

## 回归验收

- Phase 01～09 golden path 不被破坏；
- outbox、idempotency、audit 和状态机 guard 仍保持原子边界。
