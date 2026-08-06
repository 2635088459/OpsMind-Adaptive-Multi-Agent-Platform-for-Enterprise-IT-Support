# SPEC-TW-030 领域规则

- 允许起始状态：`mutable non-terminal states`。
- 目标效果：`same lifecycle state`。
- command actor：support lead, router, or assignment policy。
- Ticket mutation 必须通过 state machine guard，不允许 controller 直接改状态。
- command 必须记录 correlationId、causationId、idempotencyKey、actorId、reasonCode 和 free-text reason。
- outbox event 与 aggregate mutation 在同一事务提交。
- assignment 是 ownership mutation，必须有独立 audit version，不能改写 resolution evidence。
