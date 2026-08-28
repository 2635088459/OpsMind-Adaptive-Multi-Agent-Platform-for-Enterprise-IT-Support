# 10 Failure Handling

## LangSmith 故障

- 对线上 telemetry：fail open，不阻塞业务。
- 对离线 release gate：fail closed，run 标记为 `FAILED` 或 `PARTIAL`，candidate 不能 promotion。
- 本地 PostgreSQL 保存最小 run/report 事实，便于恢复后补链 LangSmith artifact。

## Grader Failure

- Deterministic grader failure：对应 dimension 标记 `GRADER_ERROR`，critical dimension 导致 gate failed。
- LLM Judge failure：质量类 dimension 可标记 `UNSCORED`，但不能影响安全门禁。
- Judge drift：同一 judge bundle 对固定 calibration set 超出阈值时禁用该 bundle。

## Partial Run

Run 可进入 `PARTIAL`，但 release gate 不能 passed。Partial report 必须列出：

- 未执行 case；
- 缺失 score dimension；
- runner error；
- artifact 缺失；
- 可重试建议。

## Poison Event

无法解析或违反 schema 的外部事件进入 poison queue。管理员可查看、修复、重放或忽略，但所有操作必须审计。

## Candidate Rollback

Canary 或 promoted candidate 触发回滚条件时：

1. 07 写入 rollback recommendation。
2. 发布 `improvement.rollback.requested.v1`。
3. Runtime/Config owner 执行回滚。
4. 07 通过后续 trace 验证回滚效果。

