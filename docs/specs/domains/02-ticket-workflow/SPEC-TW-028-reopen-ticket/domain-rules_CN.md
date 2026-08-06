# SPEC-TW-028 领域规则

- 允许起始状态：`RESOLVED or CLOSED`。
- 目标效果：`REOPENED`。
- command actor：requester or authorized support actor。
- Ticket mutation 必须通过 state machine guard，不允许 controller 直接改状态。
- command 必须记录 correlationId、causationId、idempotencyKey、actorId、reasonCode 和 free-text reason。
- outbox event 与 aggregate mutation 在同一事务提交。
- reopen 必须保留上一轮 evidence，并创建新的 work cycle 后再回到 IN_PROGRESS。
