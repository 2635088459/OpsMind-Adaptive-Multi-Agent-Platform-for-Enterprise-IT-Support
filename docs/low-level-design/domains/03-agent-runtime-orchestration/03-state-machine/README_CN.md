# 03 State Machine

## Workflow State

Agent Workflow state 描述 Runtime 编排状态，不描述 Ticket 生命周期。

状态集合：

- `CREATED`：实例已创建，尚未启动。
- `STARTING`：启动事务中，准备 planner 和初始 task。
- `RUNNING`：存在可执行或执行中的 Agent Task。
- `WAITING_FOR_APPROVAL`：等待 approval domain 事件。
- `WAITING_FOR_TOOL`：等待 Tool Gateway result 事件。
- `WAITING_FOR_VERIFICATION`：等待 verification domain 事件。
- `WAITING_FOR_INPUT`：等待用户或人工补充信息。
- `PAUSING`：pause command 已接受，正在冻结 worker。
- `PAUSED`：无新 task 可 claim，running task result 会按 generation 检查处理。
- `RESUMING`：resume command 已接受，正在恢复 runnable task。
- `COMPLETED`：Runtime 自动化完成。
- `FAILED`：Runtime 无法自动恢复。
- `CANCELLED`：Ticket cycle 已取消或 Workflow 被显式终止。

## Workflow 迁移

常见迁移：

- `CREATED -> STARTING -> RUNNING`
- `RUNNING -> WAITING_FOR_TOOL`
- `RUNNING -> WAITING_FOR_APPROVAL`
- `RUNNING -> WAITING_FOR_VERIFICATION`
- `RUNNING -> PAUSING -> PAUSED`
- `PAUSED -> RESUMING -> RUNNING`
- `WAITING_* -> RUNNING`
- `RUNNING -> COMPLETED`
- 任意非终态 `-> FAILED`
- 任意非终态 `-> CANCELLED`

所有迁移必须记录 state transition audit，并发布必要 outbox 事件。

## Agent Task State

状态集合：

- `PENDING`：已创建，等待依赖。
- `READY`：依赖满足，可被 claim。
- `CLAIMED`：worker 已 claim，尚未开始执行。
- `RUNNING`：worker 正在执行。
- `WAITING_TOOL`：该 task 已发起 Tool Request。
- `WAITING_EXTERNAL`：等待 approval/verification/input。
- `COMPLETED`：成功完成。
- `FAILED_RETRYABLE`：可重试失败。
- `FAILED_FINAL`：不可重试失败。
- `CANCELLED`：被 workflow pause/cancel/terminal 终止。
- `STALE`：claim generation 过期或 workflow version 不匹配。

## Checkpoint Type

- `STARTED`：Workflow 启动后稳定点。
- `BEFORE_TASK`：Task 执行前。
- `AFTER_TASK`：Task 完成后。
- `BEFORE_TOOL_REQUEST`：发起工具副作用前。
- `WAITING_EXTERNAL`：等待外部事件前。
- `PAUSED`：进入暂停后。
- `RECOVERY`：崩溃恢复重建后。
- `COMPLETED`：终态摘要。

## 外部事件唤醒

- `approval.granted` 唤醒 `WAITING_FOR_APPROVAL`。
- `tool.completed` 唤醒 `WAITING_FOR_TOOL`。
- `verification.completed` 唤醒 `WAITING_FOR_VERIFICATION`。
- 唤醒时必须校验 correlation id、workflow state、pending request id 和 processed-event 去重。
