# SPEC-ARO-038 — Test Plan

目标：支撑 `发起会话即建单`。

- 在真实 docker-compose 栈上（`agent-runtime-service` 和 `ticket-workflow-service` 一起跑）做集成测试，断言两条真实记录都出现。
- 针对调用 `02-ticket-workflow` 真实 `POST /api/v1/tickets` 的外呼形状做契约测试。
- 失败测试：`ticket-workflow-service` 不可用时，本端点干净失败，不留下孤立/半成品的 `workflow_instances` 记录。
- 幂等测试：并发重复提交同一个 `Idempotency-Key`，只产生一张工单和一个 workflow instance。
