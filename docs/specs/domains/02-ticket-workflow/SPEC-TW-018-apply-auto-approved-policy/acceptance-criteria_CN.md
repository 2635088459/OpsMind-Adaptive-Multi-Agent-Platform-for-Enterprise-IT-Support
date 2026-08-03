# SPEC-TW-018 — 验收标准

- 低风险 action 可由显式 policy decision 自动批准。
- policy decision 必须绑定 Ticket、workflow、action、risk context。
- Ticket 保持 `IN_PROGRESS`。
- 保存 authorization reference。
- duplicate 幂等。
- stale/wrong producer/schema invalid 分类正确。
- 未匹配 policy 不得静默批准。
