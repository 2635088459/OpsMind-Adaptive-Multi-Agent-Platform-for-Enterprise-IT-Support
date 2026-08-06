# SPEC-TW-027 领域规则

- 允许起始状态：`RESOLVED`。
- 目标效果：`CLOSED`。
- command actor：scheduler policy worker。
- Ticket mutation 必须通过 state machine guard，不允许 controller 直接改状态。
- command 必须记录 correlationId、causationId、idempotencyKey、actorId、reasonCode 和 free-text reason。
- outbox event 与 aggregate mutation 在同一事务提交。
- scheduler 信号只是提示，service 必须在锁内重新计算 eligibility。
