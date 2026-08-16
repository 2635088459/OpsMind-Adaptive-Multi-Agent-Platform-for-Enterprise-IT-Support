# SPEC-MK-017 Acceptance Criteria

## 验收标准

- 实现 `Hybrid Retrieval Engine` 的最小闭环能力。
- 覆盖 LLD 映射：`04-use-cases, 05-api-contracts, 07-data-model`。
- 所有新增写路径有幂等键、唯一键或 optimistic version。
- 所有返回给 Runtime/Agent 的 evidence 都有 provenance。
- 不直接修改 Ticket state 或 Workflow state。
- 单元测试、应用测试、契约测试计划均可落地。

## 完成定义

- 代码、migration、API/event contract、测试、traceability-entry 均完成。
- 与 02/03 相关的契约存在正向、重复投递、非法 payload 测试。
