# SPEC-TW-026 领域规则

- 允许起始状态：`RESOLVED`。
- 目标效果：`CLOSED`。
- command actor：employee or authorized support actor。
- Ticket mutation 必须通过 state machine guard，不允许 controller 直接改状态。
- command 必须记录 correlationId、causationId、idempotencyKey、actorId、reasonCode 和 free-text reason。
- outbox event 与 aggregate mutation 在同一事务提交。
- 确认必须引用当前 resolution cycle，不能关闭 stale 或已被 supersede 的 evidence。
