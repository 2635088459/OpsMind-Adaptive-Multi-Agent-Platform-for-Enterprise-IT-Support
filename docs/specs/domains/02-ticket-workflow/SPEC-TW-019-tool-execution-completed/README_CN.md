# SPEC-TW-019 — Tool Execution Completed（工具执行成功）

## 1. 目标

消费 trusted `tool.execution.completed.v1`，验证执行结果匹配当前 Ticket、workflow、action、authorization reference 和 toolExecutionId，并将 Ticket 从 `EXECUTING` 推进到 `VERIFYING`。

Tool success 只代表操作已完成，不代表问题已解决。Resolve 必须等待 Phase 07 Verification。

## 2. 范围

包含：

- Tool Gateway event consumer；
- producer/schema 校验；
- `EXECUTING -> VERIFYING`；
- tool result reference；
- verification seed；
- duplicate/stale 分类；
- timeline、audit、status history、outbox。

不包含：

- Tool Gateway 实际调用；
- 凭证获取；
- Verification 执行；
- Resolve。

## 3. 核心规则

- Ticket 必须为 `EXECUTING`；
- event 必须匹配当前 action；
- `toolExecutionId` 必须唯一；
- success 不得直接进入 `RESOLVED`；
- duplicate event replay 不得再次创建 verification attempt；
- event 早于授权或来自旧 workflow 必须 stale/DLQ。

## 4. 文件索引

本目录包含中英文设计、OpenAPI、AsyncAPI、HTTP 示例和参考 migration。
