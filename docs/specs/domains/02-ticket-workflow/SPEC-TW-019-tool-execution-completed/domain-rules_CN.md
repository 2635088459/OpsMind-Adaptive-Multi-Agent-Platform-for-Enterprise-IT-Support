# SPEC-TW-019 — 领域规则

| 当前状态 | 目标状态 | Transition ID | Reason Code |
|---|---|---|---|
| `EXECUTING` | `VERIFYING` | `SM-021` | `TOOL_EXECUTION_COMPLETED` |

不变量：

- event 必须来自 trusted Tool Gateway；
- `toolExecutionId` 与当前 execution attempt 一致；
- `workflowId`、`actionId`、`authorizationReference` 必须匹配；
- 成功结果只能启动 verification；
- result payload 不得包含 secret 或完整工具日志。
