# SPEC-TW-029 领域规则

- 允许起始状态：`non-terminal mutable states`。
- 目标效果：`CANCELLED`。
- command actor：requester or authorized support actor。
- Ticket mutation 必须通过 state machine guard，不允许 controller 直接改状态。
- command 必须记录 correlationId、causationId、idempotencyKey、actorId、reasonCode 和 free-text reason。
- outbox event 与 aggregate mutation 在同一事务提交。
- cancel 是 terminal state，后续 close、reopen、assign、escalate、resume 都必须拒绝。
