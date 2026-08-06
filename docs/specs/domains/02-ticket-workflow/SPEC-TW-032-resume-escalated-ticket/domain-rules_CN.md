# SPEC-TW-032 领域规则

- 允许起始状态：`ESCALATED`。
- 目标效果：`IN_PROGRESS`。
- command actor：support lead or escalation owner。
- Ticket mutation 必须通过 state machine guard，不允许 controller 直接改状态。
- command 必须记录 correlationId、causationId、idempotencyKey、actorId、reasonCode 和 free-text reason。
- outbox event 与 aggregate mutation 在同一事务提交。
- resume 必须选择下一 owner/queue，不能丢弃 escalation resolution notes。
