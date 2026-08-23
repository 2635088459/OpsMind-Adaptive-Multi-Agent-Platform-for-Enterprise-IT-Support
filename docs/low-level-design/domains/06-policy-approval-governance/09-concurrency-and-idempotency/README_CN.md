# 09 Concurrency And Idempotency

## 幂等键

- Policy evaluation：`decisionKey + inputHash`。
- Approval request：`sourceDomain + sourceRequestId + requestKey`。
- Approval command：`approvalRequestId + commandIdempotencyKey`。
- Event consumer：`eventId + consumerName`。

## 并发审批

多个审批人同时操作同一个 request 时：

- 第一个提交 final decision 的事务成功；
- 后续请求返回已有 final decision；
- 如果 payload 冲突，返回 conflict，并写 audit。

## Policy Version Race

Policy evaluation 必须绑定开始评估时选择的 effective policy version。评估过程中即使新 version 发布，本次 decision 仍使用原版本。

## 重复 Decision

相同 input hash 返回已有 decision。不同 input hash 但同 decisionKey 返回 conflict，避免下游用同一业务键覆盖不同事实。

## Approval Event 重复投递

下游重复收到 `approval.granted.v1` 时，必须按 event id 和 `approvalRequestId + sourceRequestId + requestHash` 去重。

