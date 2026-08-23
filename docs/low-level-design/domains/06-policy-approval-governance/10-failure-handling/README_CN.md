# 10 Failure Handling

## Policy Evaluation Failure

如果规则解析失败、policy version 不存在或 evaluator 异常：

- 返回 `EVALUATION_FAILED`；
- 写 audit；
- 不默认 allow；
- 高风险入口应 fail closed。

## Approval Timeout

审批超过 `expiresAt`：

- Expiry worker 进入 `EXPIRED`；
- 发布 `approval.expired.v1`；
- 下游自行决定 retry、人工介入或取消。

## Poison Decision

以下情况进入 poison：

- 同一 request 多次产生 evaluator crash；
- approval payload 与 source linkage 不一致；
- outbox 持续发布失败；
- policy rule 与 schema 不兼容。

## Degraded Policy Mode

当 policy evaluator 不可用：

- 高风险 mutation fail closed；
- 低风险只读可以使用最近已发布 policy cache；
- 必须打 `degraded=true` audit/metric；
- 不允许生成缺少 policy version 的 decision。

## 恢复

服务启动时：

1. replay pending outbox；
2. 扫描过期 approval；
3. 检查 policy version consistency；
4. 重新调度 poison review；
5. 恢复 evaluator cache。

