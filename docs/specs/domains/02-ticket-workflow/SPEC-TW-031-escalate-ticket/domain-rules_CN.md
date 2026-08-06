# SPEC-TW-031 领域规则

- 允许起始状态：`mutable non-terminal states`。
- 目标效果：`ESCALATED`。
- command actor：support actor, policy worker, or failure handler。
- Ticket mutation 必须通过 state machine guard，不允许 controller 直接改状态。
- command 必须记录 correlationId、causationId、idempotencyKey、actorId、reasonCode 和 free-text reason。
- outbox event 与 aggregate mutation 在同一事务提交。
- escalation 会冻结自动推进，直到明确 resume 或 cancel。
